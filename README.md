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
