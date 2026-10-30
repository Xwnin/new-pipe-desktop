# NewPipe for Windows

A native Windows 11 app that works like the NewPipe Android app (the v0.29.1 APK in the parent folder was the
reference): lightweight YouTube / SoundCloud / Bandcamp / media.ccc.de / PeerTube client with no ads and no
Google account, plus **tempo and pitch control** that is done locally (time-stretching) instead of using
YouTube's own playback speed.

Run `NewPipe.exe` (in `..\NewPipe-Windows`). Nothing needs to be installed. Settings, history and subscriptions are
stored in `%APPDATA%\NewPipe`.

## What it does

| NewPipe feature | In this app |
|---|---|
| Trending / kiosks, search with suggestions and filters | yes (kiosk chooser on the first page, filter chips on results) |
| Pasting a YouTube / SoundCloud link into the search box | opens the video / channel / playlist directly |
| Video page: uploader, likes, description, related videos, comments | yes |
| Playback speed dialog: **tempo, pitch, step size, "Unhook"** | yes (speed button in the player, or `,` and `.` keys) |
| Queue, shuffle, repeat, autoplay of related videos | yes |
| Background (audio only) and popup (floating, always-on-top) player | `Background` / `Popup` buttons |
| Quality selection, subtitles, fullscreen, resume position | yes |
| Subscriptions, "What's new" feed, channel pages | yes |
| Local playlists, bookmarked playlists, watch & search history | yes |
| Downloads (video, audio; HD streams are merged without re-encoding) | yes |
| Subscription export / import (NewPipe's own JSON, YouTube Takeout CSV) | Settings page |
| Light / Dark / Black themes | Settings page |

### Tempo and pitch

* **Unhook checked (default):** tempo and pitch are independent. Changing the tempo keeps the voice natural.
* **Unhook unchecked:** pitch follows tempo (classic "tape" speed-up).
* Range 0.10x - 4.00x tempo, 25% - 400% pitch, step size 1% - 100%. Presets 0.5x ... 3x.
* "Remember tempo and pitch" keeps your setting for the next video and the next launch.

### Keyboard shortcuts

`Space` play/pause, `Left/Right` seek 5 s, `J/L` seek 10 s, `,` / `.` tempo down / up by the step size,
`Up/Down` volume, `M` mute, `F` fullscreen (`Esc` leaves it), `N` / `P` next / previous, `Ctrl+L` focus search.

## How it is built

* **NewPipeExtractor** (the library NewPipe itself uses) does all searching and stream extraction.
* **libmpv** plays the audio/video, embedded in the window; its audio filters provide tempo (scaletempo2) and
  independent pitch (rubberband).
* **ffmpeg** (stream copy only) joins separately downloaded video + audio for HD downloads.
* Java 24 + Swing + FlatLaf for the interface, packaged with `jpackage`.

## Rebuilding

1. Install JDK 24 and put `libmpv-2.dll` (mpv-dev package) and `ffmpeg.exe` in `native\` (from
   github.com/shinchiro/mpv-winbuild-cmake releases).
2. `powershell -ExecutionPolicy Bypass -File build-windows.ps1`
3. The app is created in `build\dist\NewPipe\`.

For development: `gradlew run`.

## Known differences from the Android app

* No notifications for new videos and no database backup/restore (only subscriptions can be moved).
* "Fast-forward during silence" is not implemented.
* YouTube changes its site often; if search or playback stops working, the NewPipeExtractor version in
  `build.gradle` needs updating (the same thing the Android app needs).
* The executable is not code-signed, so Windows SmartScreen may warn the first time you start it.
