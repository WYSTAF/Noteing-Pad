# Build notes

## 2026-09-28 v1.6.0 (code 19): momentum root cause found; new icons; MD insert; line-number k-form

THE SCROLL ANSWER (finally, with a mechanism instead of a multiplier):
v1.5.3's "mirror" wrote physics position into the final base getters by
calling super.startScroll(currX, currY, 0, 0) each frame. startScroll
RESTARTS the animation clock — so every frame reset the fling's own clock to
zero. Momentum could never accumulate: content stopped dead on release
(exactly the user's report: "drag, release, stops immediately"). v1.6.0
restructures: the BASE scroller runs the actual fling and the view's
postOnAnimation loop (its clock untouched), while OverScroller only supplies
the deceleration profile, synced via the base's own computeScrollOffset().
Result: the framework drives frames; the spline shape is native; nothing
restarts the clock. FLING_BOOST is fixed at 1.6 (dials removed per user).
Overscroll: OVER_SCROLL_IF_CONTENT_SCROLLS retained (platform glow; NOT
enabled for bounce, which ScrollView cannot do without a custom
EdgeEffect — noted rather than faked).

Also in v1.6.0:
- Icons: all 13 hand-drawn Canvas glyphs replaced by the user's new 1024px
  SVG set (Noteing Pad Icon_*.svg), geometry ported verbatim, #d92c35
  accents preserved (Canvas, not tinted drawables, so red stays red).
  Added ADD glyph for the new MD button.
- MD button beside PASTE/SHARE → INSERT MARKDOWN palette (heading, bullets,
  numbered, task list, code, quote, table, divider, link). Inserts Markdown
  SOURCE at the caret — the app renders no Markdown by design.
- Line numbers: compact k-form (1..9999 verbatim, then 10k / 10.6k) with
  stable monospace width — the digit-count change per frame was the
  "jiggling".
- Status bar now includes total LINES alongside words/chars.
- RENAME BUG fixed: renameNote persisted only through the 700 ms debounce,
  so rename → immediate Back re-read the OLD title from disk. It now writes
  immediately with a fresh generation and drops the stale pending draft.
- Scroll tuning dials (⋮) removed; frame-health readout kept (diagnostic).

## 2026-09-25 v1.5.3 (code 18): launch-crash fix — Scroller bridge infinite recursion

v1.5.2 crashed on opening. ROOT CAUSE (found by reading the shipped code after
the crash report, not by guessing): NativeMomentumScroller's mirror() calls
super.startScroll(), which internally calls computeScrollOffset() — overridden
to call mirror() again → unbounded recursion → StackOverflowError on the first
scroller activity (opening a note restores the caret). No JVM test executes
Android Scroller code, so 20/20 tests stayed green through a launch-crash:
view-layer regressions are only caught on-device. FIX: mirroring re-entrancy
flag; nested computeScrollOffset returns false immediately (Scroller discards
startScroll's internal result anyway).
Lesson: when overriding a Scroller/Looper-adjacent class, trace the base
class's internal callback paths before shipping. The handle (v1.5.2) was
fine; only the physics bridge was the crash.

## 2026-09-24 v1.5.1 (16) + v1.5.2 (17): dark-mode numbers fix, share-as-file, real handle, native momentum

v1.5.0/1 (codes 15/16):
- Dark mode invisible line numbers: Paint defaults to opaque black AND the
  setter's change-guard (initial field == #999999) rejected dark mode's first
  real color. Explicit init + guard removed.
- SHARE now opens a TXT/MD/PDF dialog and sends a REAL FILE via FileProvider
  (per-share dir keeps clean attachment names; Binder text-size cap gone).
- Keyboard incognito banner: caused by flagNoPersonalizedLearning — removed.
- Frame-health meter: ⋮ shows "LAST SCROLL: N FPS · M JANKY"; 120Hz hint
  default OFF (it thrashes pacing on 60-capped devices).

v1.5.2 (17) — user: "handle not working, scrolling among txts stiff":
- ROOT CAUSE (handle): Android framework scrollbars on TextView/ScrollView
  are indicators only — thumb hit-testing exists solely in the ListView
  family (known decade-old framework gap). The styled thumb could NEVER be
  grabbed. Now: framework scrollbar off; real thumb drawn by
  NotepadEditText, grabbable via 18dp right strip (offset-preserving), tap
  track = jump; invalidateBands() from onScrollChanged keeps it glued.
- Physics bridge: NativeMomentumScroller delegates fling/startScroll to a
  private OverScroller (RecyclerView spline engine) and mirrors position
  into the final base getters via zero-delta startScroll. NOTE: the earlier
  v1.4.4 "bridge impossible" verdict was wrong — final getters block
  delegation, but mirroring through scrollTo/base-state works; what's
  unproven is whether OEM TextView fling routes through mScroller.fling at
  all (AOSP mirror file lacked the region; ScrollabilityCache 404s on that
  ref). DIAL TEST SETTLES IT: cycle ⋮ → Fling momentum 1.0x vs 3.2x; if
  glide distance doesn't change, the seam is dead → abandon physics edits,
  the stiff feel is inherent to EditText re-layout, needs container swap.
- Build note: 0xB0606060 is a Long in Kotlin — needs .toInt() for Paint.color.

## 2026-09-13 v1.4.5 (code 12): fling momentum raised to the max usable

Other AI diagnosed "fling/momentum" — matches: our fling was still decaying
quickly at FLING_BOOST 1.6. Raised to 2.4 (quintic curve + 2.4x velocity =
long Keep-style tail). This is the ONLY scroll mechanism an EditText exposes;
history of this dial: 1.35(janked via hand-roll)→1.5→1.6→2.2→(1.6 restored)→2.4.
Two dead ends confirmed with receipts this round (do NOT retry):
- OverScroller bridge for native momentum: IMPOSSIBLE. Scroller's isFinished/
  getCurrX/getCurrY are `final` (javap android-35) so a subclass cannot
  delegate them; the only OverScroller path is hand-driving per-frame
  scrollTo, which the user rejected as "AWFUL" (v1).
- android:preferredRefreshRate manifest pin: AAPT rejects it on this SDK
  (SDK_old table lacks API-31 attrs). Runtime setRequestedFrameRate(35+) stays.
If 2.4 is still stiff: it is NOT the curve — the remaining suspect is per-frame
number rendering (test: turn Line numbers OFF and scroll; if fluid, cache the
wrap classification). Or trace on-device via USB ADB (no more guessing).

## 2026-09-13 v1.4.4 (code 11, ABANDONED): attempted OverScroller bridge + refresh-rate pin
Both dead-ended above; never shipped (version number reused into 1.4.5 line).

## 2026-09-13 v1.4.3 (code 10): user SVG as adaptive launcher icon

"Noteing Pad Icon.svg" (312x312, 3 charcoal capsules + red dot) converted to
res/drawable/ic_launcher_foreground.xml (rounded rects -> equivalent arcs),
adaptive icon in mipmap-anydpi-v26 with white plate (ic_launcher_plate in
colors.xml — change it to restyle the background) + monochrome layer for
themed launchers. Geometry scaled 0.66x into the 72dp safe zone so capsules
survive circular masking. Old placeholder vector deleted.

## 2026-09-13 v1.4.2 (code 9): EMPIRICAL scroll stack restored (revert of the revert)

v1.4.1 fixed the number double-shift but user still called stock scroll
stiff. Version history as A/B evidence: v1.3.x (boost + quintic +
unbuffered + frame-rate hint) → NO scroll complaints; v1.4.x (stock) →
complaints. Restored the v1.3.x stack wholesale (GlideScroller quintic +
FLING_BOOST 1.6 at the Scroller seam, unbuffered dispatch API 28+,
120 Hz hint API 35+ with wantsHighRate dedupe, cancelGlide on text
change) ON TOP of v1.4.1's correct in-textview number rendering. Header
micro-label trimmed to "NOTEING PAD" (user: no rename reminder needed).
Lesson recorded in memory: this device's user prefers the tuned stack;
stop removing it on principle. If scroll complaints return, the NEXT
step is a device-side Perfetto trace over USB (adb now configured),
not another code guess.

## 2026-09-13 v1.4.1 (code 8): gutter double-shift fix

The v1.4.0 in-textview gutter subtracted scrollY from baselines, but the
framework canvas is ALREADY translated by -scrollY before onDraw (that's how
super.onDraw positions text). Result: numbers drawn at 2x scroll offset —
they showed the wrong lines mid-scroll ("frozen numbers", and the misrailing
band makes the scroll itself read as buggy). One-line fix in
NotepadEditText.onDraw. Also re-enabled the stock vertical scrollbar (the
sibling-gutter hack had disabled it because it double-counted the band width;
that constraint died with the sibling).
If scroll still disappoints after this: device-side ADB diagnosis is the
next step (dumpsys SurfaceFlinger refresh rate during scroll, gfxinfo jank
count, then a Perfetto trace) — no more blind physics edits.

## 2026-09-13 v1.4.0 (code 7): stock scroll + in-textview gutter

User report: scroll bad on 60 Hz (fine at device-mode 120 Hz), frozen gutter
numbers, plus an AI-generated optimization brief (its LazyColumn advice is N/A
to this app's native EditText, but its "find the root cost, don't fake
physics" principle applied).
- REMOVED every custom scroll layer: GlideScroller/quintic interpolator,
  FLING_BOOST, requestUnbufferedDispatch, setRequestedFrameRate, the
  cross-view sibling gutter and all per-frame invalidates. The editor is now
  a stock EditText with stock fling — what Google Keep feels like.
- Line numbers draw inside NotepadEditText.onDraw after super.onDraw: same
  canvas/frame/scrollY (no invalidate-poke window → frozen numbers
  impossible). The number band is padding (16dp ↔ 72dp toggled with the
  setting); selection colors read live from selectionStart/End.
- GutterLogic and its 7 tests unchanged; v1.3.1 (code 6) added the
  live-selection gutter read + GutterLogic unit tests (13→20 total);
  v1.3.0 (code 5) added the quintic/120Hz experiment since reverted.
- Red caret + red active number + all Nothing styling kept (UI brief §12).

## 2026-09-13 v1.2.2 (code 4): blank notes are never saved

User report: New→Back saved an empty note; rapid tapping littered the
library. Fixes in NotepadRepository/NotepadViewModel:
- `saveNote` refuses to CREATE a note whose root is empty (file absent);
  once a note exists, clearing it remains a real persisted edit.
- `newNote()` no longer saves eagerly; the file appears on first content.
- One-time migration (marker file `.empties-purged`, so once per install):
  pre-existing empty notes move to trash (recoverable, tombstoned).

## 2026-09-13 scroll round 3 (v1.2.1+): research + verified approach

User reported custom-fling attempts as "AWFUL"; asked to research properly.
Findings, honestly:

- Web search summary claimed `DynamicLayout.setUseForHighScrollRate` (API 35).
  VERIFIED AGAINST THE LOCAL android-35 AND android-36 SDK JARS: the method
  does NOT exist there (javap dumps). Do not code against it.
- developer.android.com / android.googlesource.com are 403 on this network
  (same geo-block as dl.google.com). AOSP GitHub mirror works.
- Current approach (v1.2.1): ALL hand-rolled fling removed. Two levers, each
  verified in the local platform jar:
  1. `requestUnbufferedDispatch(event)` on ACTION_MOVE (API 28+, guarded) —
     per-POFFER-frame pointer events, drag tracks at full frame rate.
  2. `setScroller(BoostedScroller)` — TextView's fling funnels through the
     Scroller (AOSP: mScroller is only assigned by setScroller); the subclass
     multiplies velocity 2.2x INSIDE the framework's own (optimized) scroll
     loop. If an OEM build routes fling around setScroller, this is inert —
     it can never reintroduce hand-rolled jank.
  Typing cancels the glide via the held scroller's abortAnimation().
- Version stamps: v1.2.0 = code 2, v1.2.1 = code 3; ⋮ menu shows BUILD x.y (n)
  so installed-version confusion is checkable on-device.

## 2026-09-12 trash update: 30-day recycle bin (BUILT — 13/13 tests, 0 lint errors)

- Delete now MOVES the note file to `filesDir/trash/<deletedAtMillis>_<id>.bin`
  (format unchanged → restore is a rename). `Screen.TRASH` lists it with
  days-remaining labels, RESTORE, ERASE-forever (✕), and EMPTY TRASH.
- Expiry: `Limits.TRASH_RETENTION_DAYS = 30`; expired files are swept on every
  library and trash listing (`purgeExpiredLocked`), so purge doesn't depend on
  opening the trash screen.
- Resurrection guard: `trashed` tombstone set in the repository — a debounced
  autosave racing a delete is skipped under `ioMutex`; restore clears it.
- Physical Back from trash returns to the library. New icons: TRASH (can),
  ERASE (✕); DELETE-on-library-rows now means "move to trash" and its
  confirmation says so.

## 2026-09-12 library update: multi-note library + autosave + auto-naming (BUILT — 13/13 tests, 0 lint errors)

Architecture change after the redesign round:
- **Two screens**: `Screen.LIBRARY` (note list, newest first) and
  `Screen.EDITOR`; `EditorScreen` switches on `ui.screen`.
- **Per-note storage**: app-private `filesDir/notes/<16hex>.bin`, AtomicFile,
  header (version, title, updatedAt, selection, preview, char count) + UTF-8
  body. List reads headers only. Legacy `current-draft.bin` migrates into the
  library on first run. `NotepadRepository` lost `open/saveDraft/restoreDraft`.
- **Autosave**: 700ms-debounced per-note save + flush on screen change and
  onStop; the `dirty` flag and all discard dialogs are gone.
- **Auto file name** (menu toggle, default on): title derives from the first
  non-blank line (60 chars); the Save As dialog is prefilled from it and an
  accepted export renames the note. New `Screen`/`NoteMeta` models.
- Share moved to the editor action row; Ctrl+N now creates a note (lossless
  thanks to autosave), Ctrl+O imports a file into a new note.
- Back navigation: back arrow in the editor header only (physical Back still
  exits the app — worth revisiting with BackHandler).

## 2026-09-12 redesign record: Nothing-style + feature work (BUILT — 13/13 tests, 0 lint errors)

Second round of changes, per user feedback. Compiled successfully on
2026-09-12 after system memory freed up; current shipped APK is
`NoteingPad-debug.apk` in this folder.

- App renamed to **"Noteing Pad"** (`strings.xml`).
- **Share button** added (TopBar action row + `NotepadViewModel.share()`):
  flattens the rope off the main thread, sends ACTION_SEND text/plain chooser.
- **Scrolling (3 revisions, final state)**: v1 hand-rolled 1.35x fling
  stuttered (per-frame scrollTo without gesture integration) and was
  removed; the restored v2 (present in NotepadEditText, FLING_FACTOR=1.5)
  cancels the framework fling on fast release, glides via OverScroller,
  and aborts any in-flight glide the instant text changes (typing beats
  scrolling) or a new touch lands. Over-scroll pinned to
  IF_CONTENT_SCROLLS (ALWAYS forces a RenderEffect re-render at edges on
  Android 12+); gutter invalidation skipped when line numbers are hidden;
  scrollbars removed. onTouchEvent carries @SuppressLint
  ("ClickableViewAccessibility"): the override delegates clicks to
  super, which owns caret/click handling — the lint heuristic assumes a
  custom performClick path.
- **Typing performance past ~100 lines**:
  - `bind()` no longer re-applies text/paint colors on every keystroke
    (each forced a full editor repaint); changes are diffed now.
  - Gutter numbering does ONE leaf-scan per frame (walks lines locally)
    instead of one `newlinesBefore` scan per visible line.
  - Editor colors from the theme are cached; `editor.isEnabled` only
    flips on change.
- **Nothing design language** (skill: nothing-design):
  - Monochrome palette: #000/#FFF, #0A0A0A surfaces, #1A1A1A borders,
    #999/#666 secondary text; red #FF0000 only for caret, dirty-square,
    selection blocks.
  - OLED-pure black dark mode; square geometry everywhere (0-radius shapes).
  - Space Grotesk (UI), Space Mono (counts/fields) bundled in res/font;
    Doto available for accents.
  - Red caret, red current-line gutter number, mechanical square toggle,
    filled-square radio in menu, segmented pulsing loader, uppercase
    micro-labels, mono status row, dot-matrix launcher icon.
  - Nothing-styled ExportDialog (square bordered mono filename field,
    segment extension chips).

## Result (first build, 2026-09-10)

- `gradlew testDebugUnitTest lintDebug assembleDebug` → **BUILD SUCCESSFUL**
- Unit tests: 13 run, 0 failures (TextCodec 5, ExportSpec 2, EditHistory 2, Rope 4)
- Lint: 0 errors, 10 warnings (2 were fixed, see below)
- APK: `app-debug.apk` (9.2 MB) — copy in this folder, original at
  `PocketNotepad/app/build/outputs/apk/debug/app-debug.apk`
  (that APK predates the redesign)

## Source changes made (first build; 2 lines, lint error fixes)

Both files now use `android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE`
instead of `Layout.BREAK_STRATEGY_SIMPLE`. Identical constant values (API 23+,
minSdk is 26) — lint's WrongConstant check just requires the canonical
LineBreaker constants. Files changed:

- `PocketNotepad/app/src/main/java/dev/pocket/notepad/ui/NativeEditor.kt`
- `PocketNotepad/app/src/main/java/dev/pocket/notepad/data/PdfExporter.kt`

## Environment used

- JDK: Android Studio's bundled JBR 21 (`C:\Program Files\Android\Android Studio\jbr`)
- SDK: `C:\AndroidTools\SDK_old` (has platforms;android-35 and build-tools;35.0.0)
- Gradle wrapper JAR bootstrapped via `tools/bootstrap-wrapper.ps1` (SHA-256 verified)

## Important: dl.google.com is blocked on this network

Google Maven 404s everything here (verified even for known-good artifacts).
Builds need the mirror init script at
`%TEMP%\pocketnotepad-mirror.init.gradle.kts` (Tencent primary, Huawei fallback):

```
cd "Q:/Vibe Coding/PocketNotepad/PocketNotepad"
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="C:/AndroidTools/SDK_old"
./gradlew --no-daemon \
  --init-script "C:/Users/Etemadi/AppData/Local/Temp/pocketnotepad-mirror.init.gradle.kts" \
  testDebugUnitTest lintDebug assembleDebug
```

If `%TEMP%` was cleaned, recreate the script (see contents in chat history or
rewrite: settingsEvaluated → pluginManagement + dependencyResolutionManagement,
repositories cleared, Tencent mirror `https://mirrors.cloud.tencent.com/nexus/repository/maven-public`
plus mavenCentral added). Mirrors here are flaky — a failed resolution often
just needs a re-run.

## Machine memory ceiling (2026-09-12)

The Kotlin compile of the redesigned app repeatedly died with
"insufficient memory / paging file too small". Diagnosis: browsers +
desktop apps hold ~10.8 GB of commit charge; only ~1 GB of the 24 GB
commit limit stayed free, while the Kotlin compiler needs ~1.5–2 GB.

If a build dies with `hs_err_pid*.log` ("out of physical RAM or swap")
or "daemon disappeared unexpectedly", close memory-heavy apps first
(firefox/chrome tabs are the biggest holders here), then rebuild:

```
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="C:/AndroidTools/SDK_old"
./gradlew --no-daemon \
  --init-script "C:/Users/Etemadi/AppData/Local/Temp/pocketnotepad-mirror.init.gradle.kts" \
  -Pkotlin.compiler.execution.strategy=in-process \
  "-Dorg.gradle.jvmargs=-Xmx1400m -Dfile.encoding=UTF-8" \
  --max-workers=1 testDebugUnitTest lintDebug assembleDebug
```

The durable fix is a larger pagefile (System Properties → Advanced →
Performance → Virtual memory; needs admin + reboot).

## Not yet verified

- `connectedDebugAndroidTest` (needs a device/emulator) — includes the two
  PdfExporter instrumentation tests
- App behavior on a real device (IME, SAF pickers, PDF rendering)
