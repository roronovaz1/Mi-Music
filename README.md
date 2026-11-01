# Dot Music

YouTube Music + Nothing-style music player for Android (original design, not affiliated with Google or Nothing).

## Build (GitHub Actions)
1. Create a new GitHub repo, upload everything from this folder (keep the `.github` folder).
2. Actions tab -> "Build APK" -> run (or just push). Download `DotMusic-APK` artifact, install `app-debug.apk`.

## Sources
- YouTube / SoundCloud / Bandcamp via NewPipe Extractor (personal use; may need an extractor version bump when YouTube changes).
- Audius (free, legal).
- Songs on your phone.

## Import
Library -> IMPORT, or share a link to the app, or paste a link in Search. Spotify/Deezer/YouTube/SoundCloud/Apple Music links,
or paste a list of song names (one per line). Spotify import needs no API key.

## If the build fails
Open `app/build.gradle.kts` and change `newPipeVersion` to the newest tag from
https://github.com/TeamNewPipe/NewPipeExtractor/releases. Send the error log to Claude for a fix.
