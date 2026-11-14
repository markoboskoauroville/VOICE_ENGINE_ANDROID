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

## v2, 8.11.2026: PAUSE on the subtitle, rule from VOICE_ENGINE_UNIVERSAL
Marko: "there is a stop, there is a hide, but there is no pause". PAUSE / CONTINUE is the first key on the subtitle (contentDescription Pause / Continue); a press sets the talker's pending `toggle`, flips the key at once, and PauseSync (1.5 s guard) then follows `paused` from /pos. Reading.kt and PauseSync.kt come from the public repo VOICE_ENGINE_UNIVERSAL (git submodule `universal/`, gradle srcDir, workflow checkout with submodules). Change the rule there, `git submodule update --remote` here.

## v3, 10.11.2026: phonesay opened files in MX Player
phonesay pushed an mp3 and fired a VIEW intent at MX Player (the engine only drew for `talk`). adb shell cannot reach Termux (RUN_COMMAND needs a permission shell lacks), so the engine got its own player: /play takes the mp3 in the POST. Test quiet: media volume 3 of 16, restore 13 after. A flag like --help was spoken as text: phonesay now rejects any leading dash.

## v4, 13.11.2026: text in, the engine speaks
Marko: "No audio anymore ... the other computer sends only text and the API from Speechify is talking." Shipping mp3s over adb (v3 /play) put a second voice pipeline on every caller and a robot voice (espeak) when the caller had no Speechify. The engine now owns the voice: one key, one voice (Beatrice), the same on every caller. A key enters only from a file and is tested when picked.

## v6, 13.11.2026: the Mac and the phone drifted
The Mac got the hard shadow in v37 (8.11.2026) and the phone never did; Marko noticed. RULE: every subtitle look change in VOICE_ENGINE (Mac) is made in VOICE_ENGINE_ANDROID in the same session (and the other way round); TAKEOVER lists both. On Android a shadow radius of 0 means NO shadow: use 0.01 for a hard one.
- v7/v8 failed to compile 13.11.2026: inside apply{} on a TextView, `text = ...` assigned the function's own `text` parameter (val). Write `this.text` inside apply when a parameter has the same name.

## v16, 13.11.2026: REPLY
- BSD sed needs `sed -i ''`; the first push built v15 again because the version bump silently failed. Check `grep appVersion` before pushing.
- Recording from an overlay tap needs a foreground service of type microphone (ReplyService) and RECORD_AUDIO granted first; startForeground throws SecurityException otherwise.
- Plain http to the Mac needs usesCleartextTraffic (targetSdk 35 blocks it). The emulator reaches the Mac at 10.0.2.2.
- The sender gives the phone the relay address and token inside the message, so the phone needs no setting and the token travels only as text.

## 13.11.2026: the phones did not sound like the Mac (Beatrice)
- Marko: "The Beatrice sounds here beautiful, but on the phone something is wrong." Cause: the app asked Speechify with model `simba-multilingual` first (then `simba-english`), while the Mac (langsay, CCMC speechify.py) asks `simba-3.2`, the model beatrice_32 is made for. Different model, different voice. The playback path was already right (MediaPlayer, USAGE_MEDIA / CONTENT_TYPE_SPEECH, no Equalizer or LoudnessEnhancer), so nothing there was changed.
- Measured, one sentence (the same 105 characters, voice beatrice_32, mp3 128 kbit/s, 24 kHz mono, two requests only): simba-3.2 lasts 6.77 s, RMS 0.104 (-19.6 dBFS), peak 0.562; simba-multilingual lasts 6.58 s (3 % faster), RMS 0.081 (-21.8 dBFS, 2.2 dB quieter), peak 0.541. Band RMS 0-300 Hz 0.059 vs 0.050, 300-1000 Hz 0.055 vs 0.039, 1-4 kHz 0.031 vs 0.020: the multilingual render has 2-4 dB less body and presence, which is the thinner voice Marko heard on the phones.
- The Mac also levels what it plays (voiced.py Reader: speech RMS to -20 dBFS, true peak -3 dBFS, resampled once). That is the Mac's player, not the voice; the phone plays the mp3 as sent. Not copied (a LoudnessEnhancer is processing; the phone has its volume key).
- Fix: model order is now simba-3.2, simba-multilingual, simba-english; the later ones only when Speechify answers an error other than a key problem.

## 14.11.2026
- phonesay fell back to `langsay`, and langsay routed to phonesay: an endless loop if the phone was down. The fallback now
  sets LANGSAY_LOCAL=1 and only runs while the Mac's speaker is on.
- `adb devices` can show a passing 127.0.0.1:<port> forward for a phone; voiceout never stores those addresses.
