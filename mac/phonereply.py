#!/usr/bin/env python3
"""phonereply.py: the Mac's end of talking back to Claude Code sessions by voice (Voice Engine Android v16).

Only TEXT travels. The phone (Voice Engine) POSTs {"to","to_id","text","at"} here when Marko answered by voice
(REPLY button, Groq whisper-large-v3 on the phone). This relay delivers it to the Claude Code session that spoke:

  1. appends the line to ~/.voice-replies/<session>.jsonl   (always; the session can watch it, see below)
  2. when exactly one live session matches `to_id` / `to`, types "[voice reply from Marko] <text>" into its iTerm tab
     with ~/Developer/claude-code/tools/other_sessions.py type <sid> (the way sessions on this Mac message each other)

  phonereply.py serve [--port 8875]     listen on 127.0.0.1 and the Tailscale address (X-Mantra-Token needed)
  phonereply.py token                   print where the token file is (never the token)

Watching: a session arms ONE Monitor on its own file:
  tail -n0 -F ~/.voice-replies/<session-id>.jsonl
Marko, 13.11.2026: "a talking and listening kind of thing ... back-and-forth communication through voice."
"""
import json, os, secrets, socket, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
DIR = os.path.join(HOME, ".voice-replies")
TOKEN_FILE = os.path.join(DIR, "token")
OTHER = os.path.join(HOME, "Developer/claude-code/tools/other_sessions.py")
PORT = 8875


def token():
    os.makedirs(DIR, exist_ok=True)
    if not os.path.exists(TOKEN_FILE):
        with open(os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT, 0o600), "w") as f:
            f.write(secrets.token_urlsafe(24) + "\n")
    return open(TOKEN_FILE).read().strip()


def live_sessions():
    try:
        return json.loads(subprocess.run([sys.executable, OTHER, "json"], capture_output=True, text=True, timeout=20).stdout)
    except Exception:
        return []


def resolve(to_id, to):
    """The one live session meant, or None. to_id is the session id; `to` its name (full, base, or a part)."""
    ss = live_sessions()
    if to_id:
        for s in ss:
            if s.get("sid") == to_id or s.get("sid", "").startswith(to_id):
                return s
    t = (to or "").strip().lower()
    if not t:
        return None
    for key in ("name", "base"):
        hit = [s for s in ss if (s.get(key) or "").lower() == t]
        if len(hit) == 1:
            return hit[0]
    hit = [s for s in ss if t in (s.get("name") or "").lower()]
    if len(hit) == 1:
        return hit[0]
    # the tab's suffix changes ("(MPA)" -> "(Voice)", "(Failed)"): match on the base name before " (" too (13.11.2026)
    import re as _re
    tb = _re.sub(r"\s*\(.*\)\s*$", "", t)
    hit = [s for s in ss if _re.sub(r"\s*\(.*\)\s*$", "", (s.get("name") or "").lower()) == tb]
    return hit[0] if len(hit) == 1 else None


SEEN = set()
SEEN_LOCK = threading.Lock()


def seen_before(mid):
    """Each reply is delivered exactly once: the phone gives every reply an id; a repeat (a retry after a slow answer) is answered 200 and ignored."""
    if not mid:
        return False
    with SEEN_LOCK:
        if not SEEN:
            try:
                SEEN.update(open(os.path.join(DIR, "seen-ids")).read().split())
            except Exception:
                pass
        if mid in SEEN:
            return True
        SEEN.add(mid)
        os.makedirs(DIR, exist_ok=True)
        with open(os.path.join(DIR, "seen-ids"), "a") as f:
            f.write(mid + "\n")
    return False


def deliver(to_id, to, text, at, mid=""):
    if seen_before(mid):
        return {"ok": True, "duplicate": True}
    s = resolve(to_id, to)
    key = (s or {}).get("sid") or to_id or "".join(c if c.isalnum() or c in "-_" else "_" for c in (to or "unknown"))[:60] or "unknown"
    os.makedirs(DIR, exist_ok=True)
    rec = {"to": to, "to_id": key, "text": text, "at": at or time.strftime("%Y-%m-%dT%H:%M:%S%z"), "from": "Marko, by voice"}
    with open(os.path.join(DIR, key + ".jsonl"), "a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    if s:   # typing takes seconds: answer the phone first, type in the background
        threading.Thread(target=lambda: subprocess.run([sys.executable, OTHER, "type", s["sid"], f"[voice reply from Marko] {text}"],
                                                       capture_output=True, text=True, timeout=30), daemon=True).start()
    return {"ok": True, "session": (s or {}).get("name", ""), "file": key + ".jsonl", "typed": bool(s)}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        if self.path == "/status":
            return self._send(200, {"app": "phonereply", "port": PORT})
        self._send(404, {"error": "no such path"})

    def do_POST(self):
        if self.path != "/reply":
            return self._send(404, {"error": "no such path"})
        if self.headers.get("X-Mantra-Token", "") != token() and not own_device(self.client_address[0]):
            return self._send(401, {"error": "not one of Marko's devices on Tailscale, and no token"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            j = json.loads(self.rfile.read(min(n, 65536)) or b"{}")
        except Exception:
            return self._send(400, {"error": "json"})
        text = str(j.get("text", "")).strip()
        if not text:
            return self._send(400, {"error": "no text"})
        self._send(200, deliver(str(j.get("to_id", "")), str(j.get("to", "")), text, str(j.get("at", "")), str(j.get("id", ""))))



_OWN = {}
def own_device(ip):
    """Marko, 13.11.2026: "All the phones are online. Please use the tail scale". Tailscale is the trust boundary:
    a caller on the tailnet whose machine belongs to his login needs no token (cached per IP)."""
    if not ip.startswith("100."):
        return False
    if ip in _OWN:
        return _OWN[ip]
    ok = False
    for exe in ("/usr/local/bin/tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale", "tailscale"):
        try:
            out = subprocess.run([exe, "whois", "--json", ip], capture_output=True, text=True, timeout=5).stdout
            ok = "marko.bosko@" in json.loads(out).get("UserProfile", {}).get("LoginName", "")
            break
        except Exception:
            continue
    _OWN[ip] = ok
    return ok

def tailscale_ip():
    for exe in ("/usr/local/bin/tailscale", "tailscale"):
        try:
            return subprocess.run([exe, "ip", "-4"], capture_output=True, text=True, timeout=5).stdout.split()[0]
        except Exception:
            continue
    return ""


def serve(port):
    global PORT
    PORT = port
    token()
    servers = []
    for host in ("127.0.0.1", tailscale_ip()):
        if not host:
            continue
        try:
            servers.append(ThreadingHTTPServer((host, port), H))
        except OSError as e:
            print(f"phonereply: {host}:{port} not opened ({e})", file=sys.stderr)
    if not servers:
        sys.exit("phonereply: nothing to listen on")
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print("phonereply: listening on " + ", ".join(f"{s.server_address[0]}:{port}" for s in servers), flush=True)
    servers[0].serve_forever()


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["serve"]:
        serve(int(a[a.index("--port") + 1]) if "--port" in a else PORT)
    elif a[:1] == ["token"]:
        token(); print(TOKEN_FILE)
    else:
        print(__doc__)
