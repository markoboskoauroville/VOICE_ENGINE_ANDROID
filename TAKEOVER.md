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


## From the former README (9.11.2026)

# Voice Engine (Mantra) for Android

**The Mac's Voice Engine subtitle (VOICE_ENGINE), on the phone.** While the voice reads a reply of Claude Code, the
words being spoken are written over every app like a film's subtitle, the word being said lit in its own colour.
Marko, 7.11.2026: *"I also want the same subtitles overlay here while you're talking ... so i have same functionality on
mac and on the phone by displaying subtitles, and i have then a stop, i have disable subtitle thing Etc."*

## What is on the screen

- **The subtitle**: a band across the whole width, the sentence being spoken (a long one in pieces, never cut short),
  the word being said lit. On its right **HIDE** (captions crossed out: the subtitle goes, the voice reads on) and
  **STOP** (a square: the reading ends). A drag moves it up or down; the place is kept (`ypos`, as on the Mac). A tap
  never hides it.
- **The notification** (the Mac's ▶ ■ SUB in the menu bar): **PAUSE / PLAY**, **STOP** while she reads, and **SUB ON /
  OFF** always.
- **The window** (the Mac window's subtitle rows, same words, same order): SUBTITLES ON / OFF, SHOW A TEST SUBTITLE,
  LETTER SIZE, BOLD, LETTER COLOUR, COLOUR OF THE WORD BEING SPOKEN, MARKER BEHIND THE SPOKEN WORD, SPOKEN WORD LIT
  EARLIER / LATER, SUBTITLE STAYS AFTER THE VOICE STOPS, BACKGROUND (clear ↔ solid), BACKGROUND SHADE (dark ↔ light),
  WHERE ON THE SCREEN (TOP / MIDDLE / BOTTOM / WHERE YOU DRAGGED IT). Every change is shown on the screen at once.

## How it knows what is said

On the Mac the subtitle asks the voice server `GET :8837/speaking`. On the phone the voice is `talk` (termux-tools
`talker/talker.py`, playing with mpv in the PRoot), so the talker tells the app instead, on 127.0.0.1:8720 (the first
free of 8720–8735):

    POST /start {id, text, words}     words [{s, e, t, d}]: character offsets, seconds (MA Reader's tokens)
    POST /pos   {id, pos, paused, dur}   five times a second -> {"cmd": "" | "stop" | "toggle"}
    POST /end   {id}

The word timings come from MA Reader's `/api/say` (`"words": true` -> headers X-Words, X-Text) or, when the talker
speaks to Speechify itself, from Speechify's own speech marks. With none, the clip's length is spread over the words.

## From Claude Code

    talk sub on | off            SUB
    talk sub test [text]         a subtitle without a voice, 8 s
    talk sub hide | stop
    talk sub status              port, state, settings
    talk sub set size=3.4 position=bottom word=#FFD60A linger=0

The same over HTTP: `GET/POST /settings`, `POST /test /hide /stop /toggle /subtitles`, `GET /status`.

## Build and install

Built only by GitHub Actions (`.github/workflows/android.yml`) on every push to main: release `v<N>` (Latest), APK
`voice-engine-v<N>.apk`, signed with the app's permanent key (secrets `KEYSTORE_B64`, `KEYSTORE_PASS`; the key was
made on the phone with openssl and lives in `~/.voiceengine-signing/`). Version: `android/gradle.properties` appVersion.

    gh release download --repo markoboskoauroville/VOICE_ENGINE_ANDROID --pattern '*.apk' -D $TMPDIR/ve --clobber
    adb install -r $TMPDIR/ve/voice-engine-v*.apk
    adb shell appops set community.auroville.voiceengine SYSTEM_ALERT_WINDOW allow

Package `community.auroville.voiceengine`, minSdk 30, Kotlin, no libraries. `Reading.kt` (the clock, which word is
lit, the pieces) imports nothing from Android.

**State 10.11.2026: v3** `POST /play {id,text,audio(base64 mp3)}`: the engine plays the sound with its own MediaPlayer and draws the subtitle (word timings estimated from the clip length; PAUSE/STOP act on the player). `phonesay "text" [hr|en]` on the Mac uses it (adb forward -> 127.0.0.1:8720); it no longer opens an mp3 in MX Player, and refuses unknown options. GitHub run 42746571026 green, release v3 Latest, installed on the Nothing (A142), screenshot showed the subtitle over the launcher, no other app.

**State 13.11.2026: v4, the engine speaks itself (Speechify).** `POST /say {text, voice?, id?}` answers at once; a thread asks Speechify (`Speechify.kt`: /v1/audio/speech, model simba-multilingual then simba-english, voice `beatrice_32` unless set), the mp3 plays through the v3 player, the subtitle uses Speechify's speech marks ({s,e,t,d}). The key: window, VOICE (SPEECHIFY), PICK KEY FILE (any text file; every long token is tested against /v1/voices, "Key 1: works / does not work", the first that works is kept, AES-GCM with an Android Keystore key), RE-TEST, DELETE, SAY A TEST SENTENCE. `/status` now has `speechify`, `voice`, `say_error`. Callers (Claude Code over adb, `talk`, TTT) send text only.
**v5, 13.11.2026: the key ring.** Speechify.saveKeys/keys/ringLabel: all working keys from the picked file, forward-only cursor (pref `cur`), rotation on 401/402/403/429, the first-cut single key (`k`) still read. Window: KEYS n · SPEAKING ON i/n; RE-TEST lists every key.
**Verified 13.11.2026 on the Nothing:** v5 installed over adb (install -r), PICK KEY FILE -> Download/Api/speechify_api.txt -> "KEYS: 21 · SPEAKING ON 1/21 · VOICE BEATRICE_32"; POST /say {text} from the cloud session spoke three sentences in Beatrice with the subtitle (/status reading true, the text moving sentence by sentence, say_error empty).
**v6, 13.11.2026:** the hard subtitle shadow as on the Mac (v37): black, no blur, 7 % of the letter size down-right, min 2 px (Overlay.restyle). Keep the two in sync: any subtitle look change goes into both VOICE_ENGINE and VOICE_ENGINE_ANDROID.
**v7, 13.11.2026: the phone lock** (Lock.kt): /lock {text}, /unlock, /through {ms}, status `lock` off|on|overridden. Not focusable (the keyboard of the app below stays), touches swallowed, LET ME USE IT overrides. Callers: TTT tools/ttt_remote.py lock/unlock and every tap/shot passes through. Rule: MANTRA_MANIFEST modules/phone-lock.md.
