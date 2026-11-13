# Momentary updates

- 7.11.2026, v1. Marko: "build one for this Android phone APK which you can control from Claude Code here on the phone.
  It has subtitles and all other things ... so i have same functionality on mac and on the phone by displaying
  subtitles, and i have then a stop, i have disable subtitle thing Etc."
  **Status: done, v1 installed and tested on the phone** (subtitle over every app, lit word in time, HIDE, STOP,
  notification, settings, `talk sub`).
- 7.11.2026. Marko: "yes, fix MA Reader's Flask". **Status: done.** Flask was present; the server was not running.
  Started with Termux's python; Croatian through Edge and English through Speechify, both via MA Reader, verified.
- 7.11.2026. Marko: "please close the project, write takeovers and skills". **Status: done** (this TAKEOVER, LESSONS,
  the skill, the manifest).
- 10.11.2026. Marko: "Also there is some bug when you talk to me through my Nothing phone, audio files get open. I have audio engine with subtitles in this phone and this should be used." **Status: done (v3, release Latest, tested on the Nothing)** (phonesay opened the mp3 in MX Player; the engine only drew subtitles for `talk`; v3 gets /play so the engine plays the sound itself).
- 13.11.2026. Marko: "Please, forget this, that other application send me audio. No audio anymore. update my speaking engine so the other computer send only text and API from speechify is talking. please fix voice engine right now in the github. install it in this Termux environment, please" **Status: v4 written and pushed.** POST /say {text, voice?}: the engine calls Speechify itself (its own key, picked from a file, tested at once, Keystore-encrypted; voice beatrice_32 by default), plays it with the v3 player and draws the subtitle with Speechify's word timings. /play stays for old callers; nothing new sends audio.
