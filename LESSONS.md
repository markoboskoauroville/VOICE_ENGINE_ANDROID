# LESSONS

1. **The first screenshot showed no lit word; it was the moment, not the code** (7.11.2026). One screencap landed
   between words. A burst of screencaps a few hundred ms apart showed the word lit. Test a timed highlight with a burst.
2. **MA Reader looked broken because of an old log line** (7.11.2026). `server.log` ended in `No module named 'flask'`,
   from a start under PRoot's python. Termux's python 3.14 has Flask; the server just was not running. Check the port
   (`curl 127.0.0.1:8081/` -> 200) before believing a log, and start it with Termux's python.
3. **`talk` falls back to Speechify silently** when MA Reader is down, so the subtitle must not depend on the reader:
   the talker keeps Speechify's speech marks as word timings too.
4. **No python `cryptography` in PRoot for the key**: the PKCS12 signing key was made with `openssl req` +
   `openssl pkcs12 -export`. Keep the key outside the repo (`*.p12` in .gitignore).
5. **Creating the repo and writing secrets is stopped by the auto-mode classifier** until Marko says yes; ask once
   with both actions in one question.
