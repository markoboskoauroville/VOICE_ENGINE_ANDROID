# TAKEOVER

**State 7.11.2026, v1**: first build. App (overlay, notification, settings, 127.0.0.1 server), talker feed
(termux-tools), MA Reader `/api/say` word headers (MA_READER_TERMUX_MACOS, both installers, and the phone's
~/.maread-web/server.py).

To continue: change the code, raise `appVersion` in android/gradle.properties, push to main, `gh run watch`, install
the release. The settings keys are the Mac's (VOICE_ENGINE app/App.swift SubtitleSettings); a key added there should
be added in `SubSettings.kt` too.
