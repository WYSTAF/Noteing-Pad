# Noteing Pad

<p align="center">
  <img src=".github/assets/logo.svg" width="160" alt="Noteing Pad logo — black sheet with a red pen-dot" />
</p>

<p align="center">
  <a href="https://github.com/WYSTAF/Noteing-Pad/releases/latest"><img src="https://img.shields.io/github/v/release/WYSTAF/Noteing-Pad?color=D92C35&label=release" alt="Latest release" /></a>
  <img src="https://img.shields.io/badge/android-8.0%2B-3DDC84?logo=android" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/license-All%20Rights%20Reserved-D92C35" alt="License" />
  <img src="https://img.shields.io/badge/glyph-Nothing%20GDK-111111" alt="Nothing GDK" />
  <img src="https://img.shields.io/badge/tests-46%20JVM-3DDC84" alt="46 JVM tests" />
</p>

A plain-text notepad for Android with Nothing Glyph integration — developed by Byteus Studio. Built to be a
fast, focused writing surface, not a block editor. Notes stay as real text
files on your device; nothing leaves it.

> Black sheet, red pen-dot — the whole app in one mark.

## What's new in v1.9.4

- **A directional Glyph language.** Export reads as a download (meter falls
  top-to-bottom, lands with an edge impact and a soft full-glyph glow); share
  reads as an upload (rising meter, top-edge impact, send flash); import is a
  six-phase data stream of two offset comets plus a flicker tick.
- **Instant side snaps.** Enter/undo flash the left half of the glyph; back/redo
  flash the right half — single tick for navigation, double tick for undo/redo.
- **Verified channel maps for every Phone series.** Every index cross-checked
  against the official Glyph Developer Kit docs and layout diagrams — meters and
  comets now sweep the whole phone body (all 15/33/26/36/6/4 LEDs), not one strip.
- **Markdown preview upgrades.** Lazy list rendering for long notes, tappable
  links, inline styles inside table cells, and an all-or-nothing numbered-list
  toggle that can't produce `1. 1. 2.` duplicates.
- **Unmistakable switches.** Settings toggles now differ in fill, border and knob
  position: filled track + knob right = ON, hollow track + knob left = OFF.
- **Deep-dive bug fixes.** Linear-time Markdown task offsets, memoized preview
  source, alignment toggle/retarget, newest-trash restore, duplicate-key-safe
  trash list, clean delete exit, Glyph thread-visibility and frame-callback fixes.

## Features

- **Write first** — opens straight into the library; one tap to a blank note.
- **Markdown, without the block editor** — an optional inline toolbar with 17
  actions (bold, italic, strike, H1–H3, bullets, numbered, checklist, quote,
  code, alignment, link, table, divider) and smart caret placement. Live preview
  renders headings, inline styles, fenced code, quotes, tables, dividers and
  **interactive task checkboxes** — with tappable links and lazy scrolling.
- **Autosave + auto-name** — every edit persists to local storage (debounced);
  the first line names the note until you rename it yourself.
- **Real Save As** — export or share as `.txt`, `.md`, or PDF through the
  system file picker. Import any UTF-8 text file the same way. Sharing sends a
  real file (FileProvider), not pasted text.
- **Swipe to trash** — flick a note left in the library with tactile haptic
  feedback; deleted notes rest in a trash screen for 30 days before final erase.
- **Nothing Glyph & Matrix** — model-specific, directional light language for
  Phone (1)/(2), (2a)/(2a+)/(3a)/(4a)/(4b) and the (3)/(4a Pro) Matrix: scroll
  mirror, download/upload/import streams, left/right snaps, undo/redo ticks,
  delete double-blink, and a Glyph master switch.
- **Editor craft** — hardware-cached 120 Hz text scrolling with OverScroller
  momentum, zero-lag synchronized line numbers, draggable scroll thumb, and
  undo/redo with full history. Up to 2 M characters per note.
- **Custom icon set** — every icon is hand-drawn 1024×1024 SVG geometry
  (sources in this repo), ported stroke-exact into Canvas with a single
  #d92c35 accent per mark.
- **Quiet, legible chrome** — monochrome Material 3 theme (System/Light/Dark),
  mechanical switches, and a build stamp in the settings menu.

## The stack

| Layer | Choice |
| --- | --- |
| UI | Jetpack Compose (Material 3) + one framework `EditText` for the text surface |
| Core | Rope-based text buffer with patch-based undo/redo |
| Storage | Local files via repository layer; SAF for import/export |
| Glyph | Nothing GDK (`com.nothing.ketchum`, vendored AAR) |
| Min / target SDK | 26 / 35 |

Large documents (up to 2 M characters) stay smooth because edits are rope
patches, not full-string rewrites, and the editor never rebuilds state it can
reuse — the unit tests pin the parts that matter.

## Testing

46 JVM unit tests cover rope operations, undo history and budgets, Markdown
editing and block parsing, task offsets, alignment toggles, numbered-list
toggling, gutter activation, export specs, UTF-8 codec edges, icon path parsing,
and every Glyph pattern for every model — including invariants that each
top-to-bottom order is a complete permutation of its channels and that
left/right snaps are disjoint and in range. Two instrumented tests open the
exported PDF with `PdfRenderer`.

## Building

Requirements: Android Studio, JDK 17, an Android SDK with platform 35.

```bash
cd PocketNotepad
./gradlew assembleDebug        # app-debug.apk
./gradlew testDebugUnitTest    # 46 JVM tests
```

The Nothing Glyph SDK ships in `app/libs/glyph-sdk-2.0.aar`, so no extra
dependency setup is needed. The Glyph paths no-op safely on non-Nothing
devices — the app runs identically everywhere, minus the lights.

## Install

Download the latest debug-signed APK from
[Releases](https://github.com/WYSTAF/Noteing-Pad/releases/latest)
(`PocketNotepad-debug-<version>.apk`). Android will ask you to allow installs
from your browser/files app the first time. Notes are stored as plain files in
the app's private storage and never leave the device.

## Project layout

```
├── svg/                      # original 1024×1024 icon sources + logo
├── LED Dot-Matrix 400.ttf     # Glyph matrix typeface
└── PocketNotepad/
    ├── app/src/main/java/dev/pocket/notepad/
    │   ├── model/            # rope, edit history, Markdown logic, export specs, limits
    │   ├── data/             # repository, SAF, txt/md/pdf exporters
    │   ├── glyph/            # Nothing Glyph SDK bridge + per-device patterns
    │   ├── ui/               # Compose screens + the EditText surface
    │   └── MainActivity.kt
    ├── app/src/test/         # 46 JVM unit tests
    └── app/src/androidTest/  # PDF exporter instrumented tests
```

## License

All Rights Reserved © 2026 WYSTAF — see [LICENSE](LICENSE).
A Byteus Studio app.
