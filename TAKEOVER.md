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
**v8, 13.11.2026: RotationGuard.** Auto-rotate is kept off on the phone: a ContentObserver resets ACCELEROMETER_ROTATION to 0 whenever anything sets it to 1; needs WRITE_SETTINGS (`adb shell appops set community.auroville.voiceengine WRITE_SETTINGS allow`, or the window's ALLOW); /status shows rotation_guard, rotation_caught, rotation_last.
**v11, 13.11.2026: the blob.** After LET ME USE IT a small orange blob stays at the right edge: GIVE THE PHONE BACK TO CLAUDE (tap = the lock returns with the last text; drag = move it). POST /request {text}: while Marko uses the phone, the blob CLAUDE ASKS FOR THE PHONE; tap = the lock comes on. Status lock: off | on | overridden | requested. /unlock removes lock and blob.
**v12, 13.11.2026: the lock is a bar at the top.** No dimming, nothing in the middle: a thin dark bar at the top of the app area (Android keeps its own status bar above every app overlay, so the bar sits right under it), left "CLAUDE · what it does", a gap sized to the camera cutout, right TAKE OVER (= override, then the blob gives it back). The rest of the screen stays visible; touches there are swallowed by an invisible full-screen layer, which /through hides for the caller's own taps.
**v13, 13.11.2026:** /lock {text, seconds}: the bar counts down "m:ss left" every second (tabular digits), "almost done" at 0; another /lock resets it.

**v16, 13.11.2026: talk back (REPLY).** GitHub run 43401752246 green, release v16 Latest. `/say {text, from, from_id, reply:true, relay, token}` speaks and raises a big REPLY button (`Reply.kt`); REPLY records (1.5 s silence, 60 s max, level line, STOP; `ReplyService` holds the microphone), `GroqKeys.kt` transcribes with whisper-large-v3 over a Groq key ring (window: PICK GROQ KEY FILE; gsk_ keys, rotate on 401/403/429), SEND / AGAIN (auto-send 3 s), `Outbox.kt` POSTs `{to,to_id,text,at}` to the relay (the sender's `relay`, then 127.0.0.1:8875 via adb reverse, then 10.0.2.2:8875) and retries every 20 s when unreached. Also `/offer` (button without voice) and `/reply` (= the button); /status has reply, outbox, groq, mic.
Mac side in `mac/`: `phonereply.py serve` (port 8875, 127.0.0.1 + Tailscale, token ~/.voice-replies/token; writes ~/.voice-replies/<session id>.jsonl and types `[voice reply from Marko] <text>` into the one matching live session via other_sessions.py type; a session may instead arm `tail -n0 -F ~/.voice-replies/<id>.jsonl` in a Monitor) and `phonesay [--from me|"<name>"] "text"` (text only; installed copy in ~/.local/bin; replaces the old mp3 /play version). Verified on the Pixel 7 emulator: overlay + REPLY button, tap -> LISTENING with level line and STOP, silence -> "I heard nothing"; relay tested with curl (token, jsonl). NOT verified: Groq transcription and the full delivery (needs Marko's Groq key file picked on the phone), the real microphone, Tailscale route. The spoken word "Reply" is not built (always-on listening).

**v18, 13.11.2026 (replaces the v16 silence rules).** Marko: "It doesn't stop ... I need to say send. Don't cut automatically." REPLY records until SEND (max 10 min, level line + timer), CANCEL discards; no silence cut, no countdown, no spoken-word detection (not reliable without rolling Groq calls). After SEND: Groq on the kept recording, then straight to the session. On a transcription error the take is kept (TRY AGAIN); on a delivery error the text is kept in the Outbox and goes out only when SEND is pressed again (no automatic retry). Each reply has an id; phonereply.py ignores a repeat (the v16 duplicate came from the phone timing out on the slow typing; the relay now answers first and types in the background, phone timeout 25 s). STOP SPEAKING on the REPLY panel and a generation counter so a voice still being fetched never starts after STOP. phonesay tries $NOTHING_ADB, then the Pixel 100.70.23.53:5555. v17 (3.5 s idea) was released and superseded the same day.

**State 13.11.2026: v22** Queue (/say while speaking waits, 0.6 s gap, Speechify fetches ahead, plays in order); /say/next, /say/prev, /say/skip, /say/rate (0.8-1.6, pitch kept, remembered; STOP also clears the queue); subtitle = chunks of at most 4 words (Chunks.kt, port of the Mac's makeChunks), 76 % column, hard shadow on text and keys, three rows (prev pause next skip / minus speed plus / hide stop), margins 16 dp inside bars and cutout. simba-3.2 only. GitHub run 43488176396 green, release v22 Latest; installed on the Pixel (100.70.23.53) and the Nothing (100.67.149.40).

**State 13.11.2026: v23** The whole subtitle block drags up and down only (also from a key after 10 dp; a tap still presses), place kept in ypos for every later subtitle. Run 43489262173 green, release v23 Latest, installed on the Pixel and the Nothing.

## v24-v26 (13.11.2026)
MacServer.kt: engine "Mac (free)" (default) sends text to the Mac's /render over Tailscale (3 s), Speechify only as a counted fallback; REPLY uses the Mac's /hear first, Groq after. `POST /engine {engine, host}`. The voice is a proper player: audio focus (GAIN_TRANSIENT, delayed) + MediaSession; TTT's record (GAIN_TRANSIENT_EXCLUSIVE) pauses it at the word, its release resumes it. `POST /focus-test {seconds}` stands in for TTT's button. Cost Agent gate: none yet, fallbacks only logged.

**State 14.11.2026: voiceout** (mac/voiceout.py): the speakers list (This Mac + every phone with Voice Engine), read by
langsay, phonesay and voiced; phonesay `--no-mac`, several addresses per phone, and no more langsay↔phonesay loop.

**State 14.11.2026: v27** The lock bar and the orange blob never outlive their owner (Marko: "it's persistent on the screen. I cannot get rid of it."). Blob closes after 10 min, the lock after 15 min (each /lock renews), both at once when the HTTP server is closed for two checks (4 s) or the service is destroyed; a small X or a 0.7 s long-press on the blob removes it for good; the service clears any lock/blob at start; the HTTP server loop is now supervised and restarts after 1 s if accept dies or no port was free. Run: see GitHub Actions, release v27 Latest.
