---
name: voice-engine-android
description: Voice Engine (Mantra) for Android, the subtitle over every app while `talk` speaks on Marko's phone. Use for anything about the phone's subtitles, `talk sub`, the overlay, HIDE/STOP, subtitle settings on the phone, VOICE_ENGINE_ANDROID builds, or MA Reader word timings.
---

# Voice Engine (Mantra) for Android

The Mac's VOICE_ENGINE subtitle, on the phone. Repo `markoboskoauroville/VOICE_ENGINE_ANDROID` (private), package
`community.auroville.voiceengine`. Details: README.md, TAKEOVER.md, LESSONS.md in the repo.

## Rules (Marko's)

- **Same as the Mac**: same settings keys, same words on the rows, same behaviour (HIDE = subtitle goes, voice reads on;
  STOP = the reading ends; a tap never hides it; a drag moves it and the place is kept).
- **APKs are built only on GitHub.** Never build locally. Push, the workflow releases `v<N>`, install the release.
- **Test a finished build once**, cheaply (`talk sub test`, one `talk say`, a burst of screencaps), not endless rounds.
- Replies are spoken; keep the report short.

## How it works

`talk` (termux-tools talker.py, mpv in PRoot) -> HTTP 127.0.0.1:8720 (8720-8735): `POST /start {id,text,words}`,
`POST /pos {id,pos,paused,dur}` 5x/s returns `{"cmd": ""|"stop"|"toggle"}`, `POST /end`. Words `{s,e,t,d}`: code-point
offsets, start, absolute end, seconds. Sources, in order: MA Reader `/api/say` with `"words": true` (headers X-Words,
X-Text, base64) -> Speechify speech marks (when talk calls Speechify itself) -> clip length spread over the words.
The clock (Reading.kt): predicted, eased to each sample, snapped when >0.35 s off; LEAD 0.05 s; last word ends 0.12 s
after its end.

## Do

| Task | Command |
|---|---|
| Is it alive | `talk sub status` (reading, shown, overlay, settings) |
| See it | `talk sub test [text]` |
| Settings | `talk sub set size=3.4 position=bottom word=#FFD60A linger=0` |
| On/off/stop | `talk sub on` / `off` / `hide` / `stop` / `toggle` |
| Release | raise `appVersion` in android/gradle.properties, push main, `gh run watch` |
| Install | `gh release download --repo markoboskoauroville/VOICE_ENGINE_ANDROID --pattern '*.apk' -D $TMPDIR/ve --clobber && adb -s 127.0.0.1:5555 install -r $TMPDIR/ve/voice-engine-v*.apk && adb shell appops set community.auroville.voiceengine SYSTEM_ALERT_WINDOW allow` |
| MA Reader up? | `curl -s -o /dev/null -w '%{http_code}' 127.0.0.1:$(cat ~/.maread-web/port.txt)/` (200) |
| Start MA Reader | `cd ~/.maread-web && setsid nohup /data/data/com.termux/files/usr/bin/python3 server.py >server.log 2>&1 &` |
| Which engine spoke | `grep spoke ~/.talker/*.log \| tail -1` |

## Mistakes already made

- Believed `server.log`'s `No module named 'flask'`: it was from a PRoot-python start; Termux's python has Flask. Check
  the port, not the log.
- Judged the highlight broken from one screenshot; use a burst.
- The talker change lives in two places: termux-tools `talker/talker.py` (repo) and `~/.talker/talker.py` (installed).
  Change both.
- The signing key is never in the repo; it is `~/.voiceengine-signing/` + repo secrets.

## Run log

- 7.11.2026: v1 built (CI green first push), installed, tested; MA Reader started again (was just not running).

## From the Mac: phonesay (v3, 10.11.2026)
`phonesay "text" [hr|en]` renders with langsay --out and POSTs it to the app's /play over adb forward; the engine plays it with subtitles. Never open an audio file in a player app for Marko. Needs v3 on the phone. Test quiet (media volume 3, restore after).
