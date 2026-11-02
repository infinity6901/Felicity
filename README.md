# Felicity

*_Felicity_ is the third and final app of the three projects I had planned for my own learning, the
first two are [Positional](https://github.com/Hamza417/Positional)
and [Inure App Manager](https://github.com/Hamza417/Inure).*

The development of the app has started and if you've used the first two apps you might want to join
the [Telegram Channel](https://t.me/felicity_music_player) and become the part of the whole initial
development process.

The project will be developed under the codename Felicity, the final name maybe updated in the
future.

## Stats

[![](https://img.shields.io/github/downloads/Hamza417/Felicity/total?color=blue&label=Total%20Downloads%20(GitHub)&logo=github&logoColor=white)](https://tooomm.github.io/github-release-stats/?username=Hamza417&repository=Felicity)
![](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Ftotal%2Fapp.simple.felicity.json&query=%24.total_downloads&logo=fdroid&label=Total%20Downloads%20(F-Droid))
[![](https://img.shields.io/endpoint?url=https://ghloc.vercel.app/api/Hamza417/Felicity/badge?style=flat&logo=kotlin&logoColor=white&label=Total%20Lines&color=indianred)](https://ghloc.vercel.app/Hamza417/Felicity?branch=master)
[![Release](https://img.shields.io/github/v/release/Hamza417/Felicity?color=52be80&label=Current%20Release)](https://github.com/Hamza417/Felicity/releases)
![](https://img.shields.io/github/languages/count/Hamza417/Felicity?color=white&label=Languages)
![](https://img.shields.io/github/license/Hamza417/Felicity?color=red&label=License)
![](https://img.shields.io/badge/Minimum%20SDK-29%20(Android%2010)-839192?logo=android&logoColor=white)
![](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-566573?logo=android&logoColor=white)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/Hamza417/Felicity/build_preview.yml?branch=master&logo=github&logoColor=white&label=build%20(preview)&color=white)](https://github.com/Hamza417/Felicity/actions/workflows/build_preview.yml)
[![Crowdin](https://badges.crowdin.net/felicity/localized.svg)](https://crowdin.com/project/felicity)

## Download

[![](https://img.shields.io/badge/Play%20Store-05acff?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=app.simple.felicity)
[![](https://img.shields.io/github/v/release/Hamza417/Felicity?color=181717&logo=github&label=GitHub%20Release)](https://github.com/Hamza417/Felicity/releases/latest)
[![](https://img.shields.io/f-droid/v/app.simple.felicity?logo=fdroid&logoColor=white&label=F-Droid&color=1976D2)](https://f-droid.org/en/packages/app.simple.felicity/)

## Purchase

[![](https://img.shields.io/badge/License%20Key%20(GumRoad)-Purchase-23a094?logo=gumroad&logoColor=white)](https://hamza417.gumroad.com/l/Felicity/)

Play Store users can buy full version directly from the app itself.

## Features

Felicity is a local-first music player built around a custom native audio engine, with an
increasingly capable layer of online sources (including a YouTube client) and a browser-based
remote control server layered on top. The sections below go deep on each part; the short version
is at the top of each one.

### Custom Audio Engine

Everything downstream of "decode this file" is native C++ (JNI), not the framework's own
`MediaPlayer`/`AudioTrack` pipeline, built specifically to keep the DSP chain and output path as
close to the hardware as possible.

- **Dual Decoder Pipeline:** Audio is decoded via Android's `MediaExtractor`/`MediaCodec` for
  broadly-supported formats, with an FFmpeg-based decoder (via a Media3 FFmpeg extension) as a
  fallback for formats/codecs the platform decoder can't handle — so an obscure or exotic file
  still plays instead of silently failing.
- **Native Output Path:** Audio output goes through hand-written Oboe and AAudio backends
  (`engine/src/main/jni/oboe`, `engine/src/main/jni/aaudio`) rather than the standard Java
  `AudioTrack` API, for lower latency and finer control over the output stream.
- **Custom DSP Chain:** The entire processing chain — equalizer, bass, reverb, stereo widening,
  tape saturation, downmixing — is written in C++ (`engine/src/main/jni/dsp/dsp-engine.cpp`) and
  uses ARM NEON SIMD auto-vectorization, so processing a buffer of samples costs as little CPU as
  possible.
- **10-Band Parametric Equalizer:** Ten adjustable bands, ±15 dB range, with a dedicated PreAmp
  control and support for saving/loading/switching between named presets (own database table,
  own dedicated UI panel).
- **Advanced Spatial Effects:** Stereo widening and tape-style saturation for an "analog" coloring
  on top of the base signal.
- **Gapless Playback:** No silence or re-buffering gap between consecutive tracks.
- **Crossfade:** Optionally blend the end of one track into the start of the next instead of a
  hard cut, with an adjustable overlap length (1–12 seconds). Note: the few seconds of overlap
  play back without the custom EQ/DSP chain applied (the incoming track briefly plays through the
  stock Android audio path before handoff) — a deliberate tradeoff to avoid two ExoPlayer
  instances fighting over the same native DSP pipeline; normal full-effects playback resumes the
  moment the crossfade completes.
- **High-Resolution & Multi-Channel Audio:** FLAC, ALAC, and DSD are supported for
  audiophile-grade playback, along with multichannel formats (5.1/7.1) with native downmixing to
  stereo when the output device doesn't support the source channel layout.
- **Native USB DAC Support:** A hand-written USB Audio Class driver
  (`engine/src/main/jni/usb_driver`) — including its own isochronous USB streaming, UAC parsing,
  and negotiation logic — for output through external USB DACs, independent of whatever level of
  USB audio support the OS itself provides.
- **Milkdrop Visualizer:** A twin-buffer Milkdrop-compatible visualizer, rendered on a GL surface
  at native frame rate, driven by the same native audio pipeline.
- **Realtime Canvas Visualizer:** A separate, lightweight visualizer (lock-free, zero-allocation)
  drawn directly on a `Canvas`, backed by a native PFFFT (FFT) implementation for the frequency
  analysis.

### User Interface

- **Four Player Interfaces:** Default, Faded, Carousel, and Vinyl — distinct visual layouts for
  the now-playing screen, switchable from settings.
- **Dynamic Theming:** The app's color scheme adapts to the currently-playing track's album art.
- **Themes:** Light, Dark, AMOLED black, Material You, and others, on top of the dynamic
  album-art theming.
- **Modern Android UI Conventions:** Predictive back gesture, edge-to-edge layout, and other
  current platform UI conventions rather than a frozen-in-time UI.
- **Custom Animations:** Hand-tuned transitions and interactions throughout, rather than default
  platform animations.
- **Dual Fast Scroll:** Both slide-to-scroll and jump-to-letter fast scrolling, at the same time,
  on long lists.
- **Lyrics:** On-the-fly LRC (synced lyrics) extraction from a file's own embedded tags, with
  online lookup and download from [LRCLIB](https://lrclib.net) when a file has none, plus a
  built-in LRC editor with word-by-word sync support.

### Library Management

- **Realtime Library Updates:** New or removed tracks on the device are picked up automatically,
  without a manual "rescan" step.
- **Full Local Organization:** Browse by songs, albums, artists, genres, years, folders (with a
  folder-hierarchy browser), favorites, most played, most skipped, recently added, and recently
  played.
- **Playlists:** User-created playlists, plus `.m3u` playlist file import/export support.
- **Metadata Editor:** Edit title, artist, album, genre, track/disc numbers, artwork, and lyrics
  directly on a file's tags via a native TagLib bridge (`repository/src/main/jni`) — the same
  writer used internally whenever the app itself needs to tag a file (e.g. a YouTube download,
  see below).
- **Online Metadata Lookup:** Search and pull in metadata (title/artist/album art) from
  MusicBrainz for files with missing or incorrect tags.
- **Bookmarks:** Timestamped bookmarks within a track, for jumping back to a specific moment
  later (an interview clip, a specific verse, etc.) rather than only whole-track favoriting.
- **Selection Mode:** Multi-select across list screens for bulk actions (add to playlist, delete,
  etc.).

### Smart Core

- **True Randomized Shuffle:** A choice of shuffle algorithms (not just "sort by random key"),
  so repeats and streaks behave the way a real shuffle should.
- **Replay Gain:** Volume-leveling support so tracks tagged with differing loudness don't jump in
  volume against each other.
- **Multiple Queues:** More than one saved queue at a time, switchable without losing either
  one's state or position.
- **Always Skip:** Mark specific tracks to be silently skipped whenever they come up in a queue or
  shuffle, without removing them from the library.
- **Sleep Timer:** Stop playback after a set duration, or at the end of the current track, with an
  optional gradual fade-out instead of a hard stop.
- **System-Wide Search:** A search provider integration so the library's songs, albums, and
  artists can be searched from outside the app itself (e.g. a device-wide search surface), not
  only from Felicity's own in-app search.
- **Home Screen Widgets:** Multiple widget styles (compact, minimal, and full) for controlling
  playback and seeing what's playing without opening the app.
  Includes Material You widgets that follow your system's dynamic colors (Android 12+, with a
  matching fallback palette on older versions): a **Lyrics** widget that shows the previous /
  current / next line in sync with the song, a 4×2 **player card**, a 2×2 **album-art tile**, a 3×1 **pill**, and an **Up next** queue widget.
  There are also frosted **Glass** widgets (player card, lyrics, album-art card) drawn over blurred artwork.

### Online Sources

Beyond the local library, Felicity can search and stream from five online sources, accessible from
their own panel(s) in the app. The player, queue, and mini-player all treat an online track the
same as a local one once it's playing — same controls, same notification, same DSP chain.

- **Radio Browser:** Free, community-maintained internet radio station directory — search and
  stream live radio stations directly.
- **Jamendo:** Creative Commons / independent-artist music, streamed via Jamendo's public API
  (requires a free Jamendo client ID, entered once in-app).
- **Audius:** Decentralized, artist-uploaded music via Audius's public API.
- **Internet Archive:** Search and stream from the Internet Archive's audio collections
  (public-domain recordings, live concert archives, old-time radio, and more).
- **YouTube:** A standalone tab (separate from the four sources above — see the note below on
  why) providing:
    - **Sign-in:** A WebView-based YouTube/Google sign-in, so personal library data (playlists,
      likes) becomes available, entirely optional — search works without signing in.
    - **Search:** Search YouTube Music directly from the app.
    - **Your Playlists:** Browse your own YouTube Music library playlists and their tracks.
    - **Playback as a real queue:** Playing a track from search results or a playlist queues the
      rest of that list behind it — next/previous track controls and drag-to-reorder work the
      same as a local playlist queue, resolving each track's stream URL just-in-time right before
      it plays (YouTube's direct stream URLs are short-lived, so nothing is pre-resolved in bulk).
    - **Download, with real metadata:** Download a single track or an entire playlist at once,
      with a live per-track progress indicator. Downloaded files are saved into the device's
      public **Music** folder (not an app-private directory, so any other player/file manager can
      see them too), with title/artist/album art embedded via the same native TagLib writer used
      by the Metadata Editor, plus lyrics looked up from LRCLIB and embedded when found.
    - **How it works:** Streaming and downloading are both backed by
      [yt-dlp](https://github.com/yt-dlp/yt-dlp) (bundled via
      [youtubedl-android](https://github.com/yausername/youtubedl-android), which packages a real
      Python + yt-dlp + ffmpeg build for Android), which is actively maintained against YouTube's
      frequent changes, with a lighter hand-written fallback extractor for when that isn't
      available.
    - **A note on why this is separate:** the other four sources are all either public-domain,
      Creative-Commons, or an openly-licensed API where artists opt in to distribution — none of
      them require working around access controls on someone else's catalogue. YouTube is the
      deliberate exception: it works by calling YouTube's own internal, undocumented API rather
      than an official one, in the same general way tools like
      [NewPipe](https://github.com/TeamNewPipe/NewPipe) and yt-dlp itself do, which sits outside
      YouTube's Terms of Service. It's included because it's genuinely useful, with that tradeoff
      stated plainly rather than glossed over.

### Web-Based Remote Player

Felicity can run a small embedded HTTP server (toggled in Settings) that serves a full browser-based
player UI over the local network — point any device's browser on the same Wi-Fi at the host
device's IP and port to get:

- A YouTube-Music-styled dashboard: browse Songs, Albums, Artists, and Genres from a browser.
- Live "now playing" state, matching the theme (light/dark) and app icon of the host device.
- Playback control from the browser itself.
- A YouTube search interface within the web UI, mirroring the in-app YouTube tab.

This turns any device with a browser — including ones Felicity doesn't have a native build for —
into a remote control / secondary display for the library running on the host device, without
needing a companion app.

This feature list is not exhaustive and only main features are listed.

## Roadmap

- [x] Initial development and setup
- [x] Custom audio engine implementation
- [x] Basic playback controls and UI
- [x] Library management and scanning
- [x] Advanced audio effects and equalizer
- [x] Dynamic theming and custom animations
- [x] Embedded lyrics support
- [x] Realtime audio visualizer
- [x] Milkdrop visualizer support
- [x] Crossfade support
- [x] Multiple Player interface styles
- [x] Playlist support
- [x] LRC Editor
- [x] Word-by-Word LRC support
- [x] m3u playlist support
- [x] Metadata editing support
- [x] Replay gain
- [x] Local server for centralized music access across multiple devices.
- [x] Selection support for library management and playlist creation.
- [x] Reproducible build
- [x] Parametric Equalizer
- [x] Sleep Timer
- [x] Global Search Provider
- [x] More widgets
- [x] Multiple Queue Support
- [x] Bookmarks
- [x] Online sources (Radio Browser, Jamendo, Audius, Internet Archive)
- [x] YouTube integration (sign-in, search, playlists, streaming, downloading)
- [x] Web-based remote player with browser control

##### Niche Features

Features that are planned but will not be a priority.

- [ ] Cue sheet support
- [x] ~Native USB DAC support~
- [x] Oboe
- [x] AAudio

... and more features will be updated here as development progresses.

## Screenshots

|                          |                          |                          |
|--------------------------|--------------------------|--------------------------|
| ![](/screenshots/01.png) | ![](/screenshots/02.png) | ![](/screenshots/03.png) |
| ![](/screenshots/04.png) | ![](/screenshots/05.png) | ![](/screenshots/06.png) |
| ![](/screenshots/07.png) | ![](/screenshots/08.png) | ![](/screenshots/11.png) |
| ![](/screenshots/12.png) | ![](/screenshots/13.png) | ![](/screenshots/14.png) |
| ![](/screenshots/15.png) | ![](/screenshots/16.png) | ![](/screenshots/17.png) |
| ![](/screenshots/18.png) | ![](/screenshots/19.png) | ![](/screenshots/20.png) |
| ![](/screenshots/21.png) | ![](/screenshots/22.png) | ![](/screenshots/23.png) |
| ![](/screenshots/24.png) | ![](/screenshots/25.png) | ![](/screenshots/26.gif) |
| ![](/screenshots/27.png) | ![](/screenshots/28.png) | ![](/screenshots/29.png) |

| Artflow Interface        |
|--------------------------|
| ![](/screenshots/09.png) |
| ![](/screenshots/10.png) |

| Server Webpage                        |
|---------------------------------------|
| ![](/screenshots/server_01_dark.png)  |
| ![](/screenshots/server_01_light.png) |

## Translations

[![Crowdin](https://badges.crowdin.net/felicity/localized.svg)](https://crowdin.com/project/felicity)

Felicity now supports localization. If you want to translate it into your own language(s), you can
do so [here on Crowdin](https://crowdin.com/project/felicity).

[Contributors](https://crowdin.com/project/felicity/members)

## License

**Felicity Music Player** Copyright © 2026 - Hamza Rizwan

**Felicity Music Player** is released as open source software under
the [GNU AGPL v3](https://www.gnu.org/licenses/agpl-3.0.en.html)
license, see the [LICENSE](./LICENSE) file in the project root for the full license text.

## History

Felicity as a whole project is a continuation of my first ever programming
project [Beatz](https://github.com/Hamza417/Beatz) which I
tried to do back then solely for learning and getting used to building.
