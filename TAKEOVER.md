# TAKEOVER

**State 7.11.2026, v1, installed and verified on the Nothing phone (A142, Android 16).** Read the skill first:
`skills/voice-engine-android/SKILL.md`.

## What works (tested on the phone, 7.11.2026)

- The subtitle over every app (home screen tested) while `talk say` speaks; the spoken word lit red, in time.
- HIDE and STOP on the subtitle; `talk sub stop` ended mpv ("stopped from the subtitle" in the talker log).
- Word timings from MA Reader's X-Words header (MA Reader running) and from Speechify speech marks (MA Reader down).
- Notification PAUSE / STOP / SUB; the settings window; `talk sub on|off|test|hide|stop|toggle|status|set`.

## The pieces and where they live

| Piece | Where |
|---|---|
| The app | this repo, `android/app/src/main/java/community/auroville/voiceengine/` (Reading, SubSettings, Overlay, VoiceService, MainActivity, BootReceiver) |
| The feed (`talk` tells the app what is said) | termux-tools `talker/talker.py`: `subtitle_feed`, `sub_command`; copy installed at `~/.talker/talker.py` |
| Word timings from the reader | MA_READER_TERMUX_MACOS, both installers' `/api/say` (`"words": true` -> X-Text, X-Words); installed `~/.maread-web/server.py` |
| The signing key | `~/.voiceengine-signing/` (voiceengine.p12, pass.txt, cert.pem) on the phone, and the repo secrets KEYSTORE_B64 / KEYSTORE_PASS. Never commit it. |
| The Mac original | VOICE_ENGINE `app/App.swift` (SubtitleSettings, the clock) |

## To continue

1. Change the code; raise `appVersion` in `android/gradle.properties`.
2. Push to main; `gh run watch --repo markoboskoauroville/VOICE_ENGINE_ANDROID`.
3. Install the release (README, "Build and install"); `talk sub test` to see it.
A settings key added on the Mac is added to `SubSettings.kt` too (same name, same meaning).

## Open

- MA Reader does not start after a reboot or a Termux kill; `talk` then quietly uses Speechify directly (subtitles still
  work). Start: `cd ~/.maread-web && setsid nohup /data/data/com.termux/files/usr/bin/python3 server.py >server.log 2>&1 &`.
- Not built yet: the Mac's voice picker (`voice` key) has no row here; the phone's voice is chosen by `talk`.

**State 8.11.2026: v2** PAUSE / CONTINUE on the subtitle; Reading + PauseSync from VOICE_ENGINE_UNIVERSAL (submodule). GitHub run 42530569905 green, release v2, installed on the Pixel 7 (cable + Wi-Fi), seen in a screenshot (pause bars, then play triangle with the word held).
