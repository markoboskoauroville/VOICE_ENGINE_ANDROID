#!/usr/bin/env python3
"""voiceout: WHERE THE VOICE SPEAKS. This Mac and every phone with Voice Engine (Mantra) for Android, each with its speaker.

Marko, 14.11.2026: "I want to see all the Voice Engine available ... next to each engine, there will be a speaker icon.
If I enable that speaker icon, you are speaking to that engine from this computer. Also in the list is local computer
... so I can also disable the local computer not to be used in the speaking of anything."

  voiceout list [--json]        the outputs, their speaker (ON/OFF) and whether they answer now
  voiceout scan [--json]        look for phones on adb with Voice Engine installed, add new ones (speaker OFF), then list
  voiceout on  <id>             speaker on  (id: mac, pixel7, nothing, emulator, or any id from the list)
  voiceout off <id>             speaker off
  voiceout only <id>            that speaker on, every other off

The state is ~/.voice/outputs.json:  {"mac": true, "phones": [{"id", "name", "addrs": [adb serials], "on"}]}
It replaces ~/.voice/route ("phone <addr>" = Mac off, that phone on), which is read once and moved to ~/.voice/retired.
Readers: langsay (claude-code/tools), phonesay (this folder), voiced.py POST /speak (MANTRA_VOICE), Voice Engine (Mantra) v46.
Only TEXT travels to a phone (voice-text-only.md); each phone speaks it with its own Speechify voice and subtitle.
"""
import json, os, subprocess, sys, threading

HOME = os.path.expanduser("~")
FILE = HOME + "/.voice/outputs.json"
ROUTE = HOME + "/.voice/route"
ADB = HOME + "/Library/Android/sdk/platform-tools/adb"
PKG = "community.auroville.voiceengine"
HERE = os.path.dirname(os.path.realpath(__file__))
PHONESAY = HERE + "/phonesay"

# the phones known by their model (one phone can answer on USB and on Tailscale; both addresses are kept)
KNOWN = {
    "Pixel_7": ("pixel7", "PIXEL 7"),
    "A142": ("nothing", "NOTHING PHONE"),
}
DEFAULT = {"mac": True, "phones": [
    {"id": "pixel7", "name": "PIXEL 7", "addrs": ["100.70.23.53:5555"], "on": False},
    {"id": "nothing", "name": "NOTHING PHONE", "addrs": ["100.67.149.40:5555"], "on": False},
]}


def load():
    s = None
    try:
        s = json.load(open(FILE))
    except Exception:
        pass
    if not isinstance(s, dict):
        s = json.loads(json.dumps(DEFAULT))
        if os.path.exists(ROUTE):                       # the old single route: "phone <addr>" = Mac off, that phone on
            parts = open(ROUTE).read().split()
            if parts[:1] == ["phone"] and len(parts) > 1:
                s["mac"] = False
                hit = next((p for p in s["phones"] if parts[1] in p["addrs"]), None)
                if hit: hit["on"] = True
                else: s["phones"].append({"id": parts[1], "name": parts[1], "addrs": [parts[1]], "on": True})
            os.makedirs(HOME + "/.voice/retired", exist_ok=True)
            os.replace(ROUTE, HOME + "/.voice/retired/route")
        save(s)
    s.setdefault("mac", True); s.setdefault("phones", [])
    return s


def save(s):
    os.makedirs(os.path.dirname(FILE), exist_ok=True)
    tmp = FILE + ".tmp"
    json.dump(s, open(tmp, "w"), indent=2)
    os.replace(tmp, FILE)


def adb(*a, timeout=8):
    try:
        return subprocess.run([ADB, *a], capture_output=True, text=True, timeout=timeout).stdout
    except Exception:
        return ""


def devices():
    """{serial: model} for every adb device in the 'device' state."""
    out = {}
    for line in adb("devices", "-l").splitlines()[1:]:
        f = line.split()
        if len(f) >= 2 and f[1] == "device":
            model = next((x[6:] for x in f if x.startswith("model:")), "")
            out[f[0]] = model
    return out


def has_engine(serial):
    return PKG in adb("-s", serial, "shell", "pm", "list", "packages", PKG)


def scan(s=None):
    """Add phones with Voice Engine installed (speaker OFF), learn new addresses; returns (state, {id: reachable})."""
    s = s or load()
    up = {}
    devs = devices()
    for serial, model in devs.items():
        if not has_engine(serial):
            continue
        if serial.startswith("emulator-"):
            pid, name = "emulator", "PIXEL 7 EMULATOR"
        else:
            pid, name = KNOWN.get(model, (model.lower() or serial, (model or serial).replace("_", " ").upper()))
        p = next((x for x in s["phones"] if x["id"] == pid), None)
        if p is None:
            p = {"id": pid, "name": name, "addrs": [], "on": False}
            s["phones"].append(p)
        if serial not in p["addrs"] and not serial.startswith("127.0.0.1:"):   # a passing local forward is never kept
            p["addrs"].insert(0, serial)                 # the address that answered last goes first
        up[pid] = True
    for p in s["phones"]:
        up.setdefault(p["id"], any(a in devs for a in p["addrs"]))
    save(s)
    return s, up


def find(s, oid):
    if oid in ("mac", "local", "this-mac"): return "mac"
    return next((p for p in s["phones"] if p["id"] == oid or oid in p["addrs"] or p["name"].lower() == oid.lower()), None)


def set_on(oid, on, only=False):
    s = load()
    hit = find(s, oid)
    if hit is None: sys.exit(f"voiceout: no output '{oid}' (voiceout list)")
    if only:
        s["mac"] = False
        for p in s["phones"]: p["on"] = False
        on = True
    if hit == "mac": s["mac"] = on
    else: hit["on"] = on
    save(s)
    return s


def targets():
    """(the phones with their speaker on, the Mac's speaker on?)"""
    s = load()
    return [p for p in s["phones"] if p.get("on")], bool(s.get("mac", True))


def say_on_phones(phones, pieces, frm=None):
    """pieces [(lang, text)]: each phone gets them in order, the phones at the same time. Returns {id: ok}."""
    res = {}
    def one(p):
        ok = True
        for lang, text in pieces:
            cmd = [sys.executable, PHONESAY, "--no-mac", "--phone", ",".join(p["addrs"])]
            if frm: cmd += ["--from", frm]
            r = subprocess.run(cmd + [text, lang], capture_output=True, text=True, timeout=90)
            ok = ok and r.returncode == 0
        res[p["id"]] = ok
    ts = [threading.Thread(target=one, args=(p,)) for p in phones]
    for t in ts: t.start()
    for t in ts: t.join()
    return res


def show(s, up, as_json):
    rows = [{"id": "mac", "name": "THIS MAC", "on": s["mac"], "up": True, "addrs": []}]
    rows += [{"id": p["id"], "name": p["name"], "on": p["on"], "up": up.get(p["id"], False), "addrs": p["addrs"]} for p in s["phones"]]
    if as_json:
        print(json.dumps(rows)); return
    for r in rows:
        print(f"{'ON ' if r['on'] else 'off'}  {r['id']:<10} {r['name']:<18} {'answers' if r['up'] else 'not reached'}  {' '.join(r['addrs'])}")


def main():
    a = sys.argv[1:]
    j = "--json" in a
    a = [x for x in a if x != "--json"]
    cmd = a[0] if a else "list"
    if cmd == "list":
        s = load(); d = devices()
        show(s, {p["id"]: any(x in d for x in p["addrs"]) for p in s["phones"]}, j)
    elif cmd == "scan":
        s, up = scan(); show(s, up, j)
    elif cmd in ("on", "off", "only") and len(a) > 1:
        s = set_on(a[1], cmd != "off", only=cmd == "only")
        d = devices(); show(s, {p["id"]: any(x in d for x in p["addrs"]) for p in s["phones"]}, j)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
