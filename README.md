# Noteing Pad

<p align="center">
  <img src=".github/assets/logo.svg" width="160" alt="Noteing Pad logo — black sheet with a red pen-dot" />
</p>

![Platform](https://img.shields.io/badge/android-8.0%2B-3DDC84?logo=android)
![License](https://img.shields.io/badge/license-Apache%202.0-D92C35)
![Nothing](https://img.shields.io/badge/glyph-Nothing%20GDK-111111)

A plain-text notepad for Android with Nothing Glyph integration — built to be a
fast, focused writing surface, not a block editor. Notes stay as real text
files on your device; nothing leaves it.

> Black sheet, red pen-dot — the whole app in one mark.

## Features

- **Write first** — opens straight into the library; one tap to a blank note.
  Plain text only, on purpose: no rendering, no formatting chrome, no nonsense.
- **Autosave + auto-name** — every edit persists to local storage (debounced);
  the first line names the note until you rename it yourself.
- **Real Save As** — export or share as `.txt`, `.md`, or PDF through the
  system file picker. Import any UTF-8 text file the same way.
- **Swipe to trash** — flick a note left in the library; deleted notes rest in
  a trash screen for 30 days before final erase, restorable until then.
- **Nothing Glyph** — on Nothing phones, the Glyph strip mirrors where you are
  in the document, fills like a meter while saving, and blinks on export.
  A master switch lives in the editor menu (hidden on non-Nothing hardware).
- **Editor craft** — line numbers drawn in the same canvas pass as the text
  (they can never lag a scroll frame), quintic-fling scrolling tuned at
  120 Hz, a draggable scroll thumb, and undo/redo with full history.
- **Custom icon set** — every icon is hand-drawn 1024×1024 SVG geometry
  (sources in this repo), ported stroke-exact into Canvas with a single
  #d92c35 accent per mark.

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
reuse — the unit tests pin the parts that matter (rope ops, undo history,
gutter logic, export specs, icon path parsing).

## Building

Requirements: Android Studio, JDK 17, an Android SDK with platform 35.

```bash
cd PocketNotepad
./gradlew assembleDebug        # app-debug.apk
./gradlew testDebugUnitTest    # 25 JVM tests
```

The Nothing Glyph SDK ships in `app/libs/glyph-sdk-2.0.aar`, so no extra
dependency setup is needed. The Glyph paths no-op safely on non-Nothing
devices — the app runs identically everywhere, minus the lights.

## Project layout

```
├── svg/                      # original 1024×1024 icon sources + logo
├── LED Dot-Matrix 400.ttf     # Glyph matrix typeface
└── PocketNotepad/
    ├── app/src/main/java/dev/pocket/notepad/
    │   ├── model/            # rope, edit history, export specs, limits
    │   ├── data/             # repository, SAF, txt/md/pdf exporters
    │   ├── glyph/            # Nothing Glyph SDK bridge
    │   ├── ui/               # Compose screens + the EditText surface
    │   └── MainActivity.kt
    └── app/src/test/         # JVM unit tests
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
