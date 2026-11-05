# AudiRead

An Android app for reading an ebook and listening to its audiobook — separately or at the same time.

## Features

**Library**
- Grid library with covers, progress bars and format badges (ebook / audiobook)
- "Continue" row, search, filters (Ebooks / Audiobooks / Ebook + audio) and sorting
- Open any book to choose **Listen**, **Read**, or **Read + Listen**
- Add ebook files, audiobook files, an audiobook folder, or **scan a whole library folder** — ebooks and audiobooks with matching titles are paired automatically
- Link or replace the audiobook / ebook of an existing book at any time

**Ebook formats**
| Format | Notes |
|---|---|
| EPUB 2 / 3 (incl. kepub) | Full spine, nav/NCX table of contents, cover, internal links |
| PDF | Native renderer, page swipe, double-tap zoom, night-mode inversion |
| MOBI / AZW / AZW3 / PRC | DRM-free files (PalmDOC compression), images, page-break chapters |
| FB2 / FB2.ZIP | Sections, images, notes, cover |
| CBZ | Comic pages with zoom |
| TXT / Markdown / HTML | Automatic chapter detection for text files |

**Reader**
- Paginated (tap or swipe) or continuous-scroll layout
- Themes: Light, Sepia, Dusk, true Black (OLED), plus font, size, line spacing, margins and justification
- Table of contents, whole-book slider, chapter jumps, immersive full screen

**Audiobooks**
- One long file (M4B/M4A/MP3…) or many part files; folders with CD1/CD2 sub-folders work too
- Embedded chapters are read from M4B/MP4 (QuickTime chapter track and Nero `chpl`) and MP3 (ID3v2 `CHAP`); otherwise each file is a chapter
- Formats: MP3, M4B, M4A, AAC, MP4, OGG, Opus, FLAC, WAV, AMR, MKA, WebM
- Chapter skip, configurable rewind / fast-forward, speed 0.5–3.5×, skip silence, sleep timer (incl. end of chapter, with fade-out)
- Background playback with media notification; headphone / Bluetooth buttons skip chapters

**Read + Listen**
- The page follows the narration automatically
- Turning pages yourself pauses following and offers **Back to audio** or **Play from here**
- Sync is estimated from the book length and refined automatically wherever audio chapter titles match the table of contents
- For exact sync, pin a point (sync menu → *Pin: narrator is at the top of this page*); pins are kept per book and can be managed or cleared

**Exact sync with the desktop app**
- [`desktop/audiread-sync`](desktop/audiread-sync) is a small Linux app that listens to the audiobook with local Whisper speech recognition and matches it to the ebook word by word
- It saves a `.audiread-sync.json`; import it with **⋮ → Import sync file** for a sync point every few seconds through the whole book

**Android Auto**
- Browse *Continue listening* and *All audiobooks*, resume where you left off, chapter skip and ±seconds buttons on the car screen
- Reading and listening positions are saved continuously and resume from either side

## Installing

Every push builds a debug-signed APK with GitHub Actions. Download it from the latest **build-N** pre-release on the Releases page (or from the run's artifacts).

> **Android Auto and sideloaded apps:** Android Auto only lists media apps installed from Google Play unless you enable
> *Unknown sources*: open Android Auto settings → tap *Version* repeatedly to unlock developer settings → ⋮ → *Developer settings* → enable **Unknown sources**.

## Building

Requires JDK 17 and the Android SDK (API 35).

```
./gradlew assembleDebug
```

## Project layout

```
app/src/main/java/com/brenyd/audiread/
  data/       models, JSON library store, settings
  importer/   EPUB, MOBI, FB2, text, PDF/CBZ importers; audiobook import + chapter extraction; folder scan
  playback/   Media3 MediaLibraryService (notification + Android Auto), chapter-aware player, UI connection
  sync/       text ↔ audio position mapping and title matching
  ui/         Compose screens: library, book detail, player, reader (WebView pagination, PDF, comics)
```
