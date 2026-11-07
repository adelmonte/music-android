# music-android

> The Android companion to **[music-tui](https://github.com/adelmonte/music-tui)**, the terminal music player.
> On Kodi: **[music-kodi](https://github.com/adelmonte/music-kodi)**.

Your music, Spotify playlists, internet radio and podcasts in one app with a
bottom navigation bar, built with Material 3 Expressive.

## What it does

- **Explore**: search songs, artists and albums, charts, recommendations from
  what you like, and song radios. Anything you play streams right away and is
  saved as a tagged `.m4a` file.
- **Library**: playlists, liked songs, and every song, album and artist on the
  phone (MediaStore), including the ones the app saved.
- **Radio**: about 50,000 stations from Radio Browser, with live song titles.
- **Podcasts**: search, top charts and subscriptions; episodes resume where you
  stopped, and speed (0.5×–3×) applies only to episodes.
- **Spotify**: import an [Exportify](https://exportify.app) zip or CSVs from
  the Library ⋮ menu, or link a public playlist (paste the link, or share it to
  the app from Spotify) to keep it in step.
- **Themes**: light, dark or system; wallpaper colors or any accent (Phonograph
  yellow by default); four palette styles; pure black; and a now-playing screen
  colored from the cover.

## Install

Download `music-android-<version>.apk` from the
[latest release](https://github.com/adelmonte/music-android/releases/latest)
and open it on the phone (Android 10 or newer), or install it over USB:

    adb install -r music-android-<version>.apk

## Where music goes

Saved songs are ordinary files in the shared Music folder, where any player and
file manager can see them:

    Music/music-android/<Album artist>/[<Year>] <Album>/<NN> <Title>.m4a

The folder name can be changed in Settings. App data (library, playlists,
subscriptions, the queue) is JSON in the app's private storage.

## How it works without the desktop's tools

[music-tui](https://github.com/adelmonte/music-tui) relies on yt-dlp and ffmpeg. Here:

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) finds
  YouTube audio, in place of yt-dlp.
- Media3 ExoPlayer plays everything (files, streams, HLS radio, episodes), in
  place of ffmpeg and rodio, and handles ICY titles and speed.
- Files are downloaded in ranged chunks (so they resume) and tagged by a small
  MP4 tag writer, in place of ffmpeg's remux.
- Metadata and artwork come from Deezer's public API, as in music-tui.

## Building

Needs JDK 17+ and the Android SDK (platform 37) with `sdk.dir` in
`local.properties`.

    ./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
    adb install -r app/build/outputs/apk/release/app-release.apk

The release build is signed with the debug key so it installs directly over
adb. Unit tests: `./gradlew testDebugUnitTest`; network tests run with `LIVE=1`.

## License

GPL-3.0-or-later (see `LICENSE`). The app icon is
[Phonograph](https://github.com/kabouzeid/Phonograph)'s, by Karim Abou Zeid,
also GPL-3.0. Uses NewPipeExtractor (GPL-3.0).
