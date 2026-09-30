# Pocket Notepad

A small, distraction-free Android text editor with a Jetpack Compose / Material 3 shell, MVVM, a native Android text-editing surface, and dependency-free native PDF export.

**Delivery status:** complete application source, resources, Gradle configuration, bootstrap scripts, automated tests, and CI configuration. There is no compiled APK in this archive. The Android build, Kotlin compilation, Android lint, and device tests have **not** been run here: the generation sandbox has no Android SDK, Kotlin compiler, or internet access. Do not mistake source review for a successful Android build or a performance benchmark.

## Open and compile

1. Install Android Studio Meerkat 2024.3.1 or a newer version compatible with Android Gradle Plugin 8.9.2. Install **Android SDK Platform 35** and **Android SDK Build-Tools 35.0.0** through SDK Manager. Use **JDK 17** for Gradle.
2. Extract `PocketNotepad.zip`. Open a terminal in the extracted `PocketNotepad` directory, where `settings.gradle.kts` lives. Run the bootstrap below once **before** importing into Android Studio. The first run needs internet access to fetch the official Gradle wrapper and Gradle distribution.
3. In Android Studio, choose **Open**, select that directory, and let Gradle sync. Studio creates the machine-specific `local.properties`. Select an Android 8.0 / API 26 or newer device and click **Run**.

macOS / Linux:

```sh
chmod +x gradlew
./gradlew --version
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows, from Command Prompt:

```bat
gradlew.bat --version
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Windows, from PowerShell, prefix those commands with `.\`, for example `.\gradlew.bat --version`.

The first script invocation downloads the **official Gradle 8.11.1 wrapper JAR**, verifies its pinned SHA-256, and executes it. The wrapper then downloads the Gradle distribution and verifies the separately pinned distribution SHA-256. No custom or unverifiable wrapper binary is supplied. `curl` or `wget` plus a SHA-256 utility is required on macOS/Linux; Windows uses PowerShell. Set `JAVA_HOME` to an installed JDK 17 if `java` is not already JDK 17 on your path.

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Install it through Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The release build enables R8 and resource shrinking; use Studio's **Generate Signed App Bundle / APK** workflow with your own signing key for distribution. No signing secrets are included.

For command-line builds, Android Studio must already have created `local.properties`, or `ANDROID_HOME` must identify your installed SDK. Dependencies come from Google Maven, Maven Central, and the Gradle Plugin Portal. The app itself requests **no internet or storage permissions**.

## Engineering decisions, in order

### 1. Keep the editable UI out of a full-String state loop

The screens and controls are Compose. The text surface is a framework `EditText` hosted by `AndroidView`, not a giant value-based Compose `TextField`. This deliberate interop choice keeps Android's mature IME, selection, accessibility, composing-span handling, and mutable `Editable` buffer, while avoiding a full-document `String` in Compose state on every keystroke.

`NotepadViewModel` owns an immutable AVL rope. Each leaf owns at most 4,096 UTF-16 units. Editing changes only the affected path and bounded leaves. The editor's `TextWatcher` copies **only inserted text**, not the entire document. Normal typing does not convert the whole document to `String`. Toolbar/context-menu paste prepares the new rope on `Dispatchers.Default`; the existing document is changed only after preparation succeeds.

An immutable root is an O(1) snapshot reference. Export and recovery workers can read it while the UI subsequently edits another root, without reading a live `Editable` from a worker thread. Counts live in tree metadata, so displaying counts does not trigger whole-document scans or regex token arrays.

The native UI necessarily has its own editable text buffer. This is not a zero-copy editor. Bulk insertion, native text measurement, view re-creation, and IME operations still run on the main thread. Initial attachment uses `GetChars` to copy directly from the rope without an intermediate whole-document String. The supplied engine is not a fully virtualized gigabyte-file editor.

### 2. Make memory limits explicit instead of promising unlimited speed

The default document cap is **2,097,152 UTF-16 units** (roughly 4 MiB of raw UTF-16 before object/layout overhead). Oversized Open/Paste/input is rejected, never silently truncated. This is a product guardrail, not a measured performance guarantee. An individual line of millions of characters, unusual font fallback, selection across the whole document, or a low-memory device can still produce pauses below the cap. Android's clipboard/IPC and IME can impose smaller independent limits.

The application undo history keeps at most 200 input transactions and conservatively budgets 4,194,304 changed UTF-16 units. History stores shared roots and deltas, not one whole String per key. Native undo is disabled with `android:allowUndo="false"`, avoiding a second competing history. An individual largest supported replacement is retained as one undoable action. Node overhead and shared live/saved roots are additional memory, so the history budget is not an exact heap cap. New/Open reset history; Clear and toolbar Paste are undoable.

This app has no syntax highlighter, Markdown parser, full-text search index, or heavyweight editor/PDF library. Those choices keep the editing path small. Release APK size and frame timings must be measured on a real build; no invented size/FPS claims are supplied.

### 3. Use SAF, never invent filesystem paths

`ACTION_OPEN_DOCUMENT` opens a provider-backed URI; all reads use `ContentResolver`. `ACTION_CREATE_DOCUMENT` requests a new document using the exact requested display name and the best-known MIME type. Unknown extensions use `application/octet-stream`. The user chooses any **SAF-exposed writable location** including local Downloads, supported SD cards, and installed document providers. Android's protected directories, such as restricted `Android/data` and `Android/obb`, are not bypassed.

All extensions other than `.pdf` contain plain UTF-8 text, including `.txt`, `.md`, `.py`, `.js`, `.json`, `.html`, `.css`, `.csv`, `.env`, and user-defined extensions. Changing an extension does not execute code or transform content into that language's syntax. UTF-8 BOM, CRLF/LF, tabs, and trailing newlines are preserved by text export. Opening malformed UTF-8, binary NUL data, oversized input, or a PDF signature produces an error and leaves the previous document untouched. UTF-16/legacy encoding import and PDF-to-text import are intentionally unsupported.

The system picker may append a suffix for duplicate names or adjust an extension according to its provider. The app reads the actual display name after writing and warns if it differs, rather than falsely promising the requested name was preserved. SAF cannot guarantee arbitrary names across every provider. Save As creates a **new copy**; it does not silently overwrite the source file. No permanent URI grants are retained, because neither subsequent in-place saving nor background rereading of external files is needed. Recovery uses app-private storage instead.

### 4. Generate PDFs without a giant layout or a third-party SDK

`PdfExporter` uses `android.graphics.pdf.PdfDocument` and bounded `StaticLayout` windows. A4 pages are 595 x 842 points, with 40-point horizontal margins, a 10-point monospace body, filename header, and page footer. Long lines wrap; paragraphs paginate; Unicode shaping and bidirectional layout are delegated to Android's native text engine. Tab stops expand to four UTF-16 columns for the PDF presentation; CRLF/CR are normalized only for rendering.

Each layout window contains about 8,192 UTF-16 units. Its last incomplete visual line is carried into the next window, avoiding lost text at boundaries and avoiding a full-document `StaticLayout`. Complete visual lines are clipped and drawn into each page's content area. Pages are always finished, and the PDF is always closed, including failure/cancellation paths. The PDF is rendered on `Dispatchers.Default` and written on `Dispatchers.IO`.

**Important:** `PdfDocument` retains native page data until `writeTo`. It is not a streaming PDF writer. Export therefore has an explicit **250-page cap**. Large jobs that exceed it fail before opening the destination picker; users can export text or split the document. Very long bidirectional paragraphs can have a changed base direction at a layout-window boundary, and system font availability determines glyph coverage. This is text PDF export, not an archival, tagged/accessibility, font-embedded, or PDF/A renderer. Emoji/color glyph appearance and complex scripts require device testing.

Exports are staged in app-private storage **before** the destination picker is opened. A rendering failure cannot create an empty destination. The completed file is then copied in 32 KiB chunks to the chosen URI. A write failure can still leave a partial destination, because SAF does not provide a universal atomic replace operation. The app reports this and keeps editor text intact; it does not silently delete a provider-returned URI. Staging requires enough additional internal disk space for the output.

### 5. Keep lifecycle, process recovery, and export intent separate

The ViewModel contains no Activity, View, or live native Editable. Activity rotation retains document roots, app undo history, theme state, and selection in the ViewModel. The native view is rebound on recreation. Scroll position is not explicitly persisted; recreation prioritizes bringing the restored caret into view.

Recovery writes a private `AtomicFile` after a 700 ms text-idle debounce, and requests a flush on Activity stop. Writes run on IO, are serialized with a mutex, and use monotonically increasing generations to prevent older snapshots from replacing newer ones. The document is **never** put in `rememberSaveable` or `SavedStateHandle`, avoiding Binder transaction-size failures. Text and selection recover after ordinary process recreation; undo history does not.

No Android app can guarantee that a final asynchronous write completes before a sudden kill or power loss. The last debounce window or an in-flight write can be lost. A private recovery copy is **not** an external save; the dirty marker remains until a text copy is successfully exported. PDF export does not mark the editable text as saved. Failed recovery writes are reported. There is one private recovery document, not a multi-document vault.

Small pending-export metadata lives in `SavedStateHandle`; the complete staged file stays on private disk while the picker is open. Activity Result registration uses stable unconditional call order. If the process is recreated during picking, a completed staged file can still be published without reconstructing a PDF from possibly changed text. A recreated session conservatively does not clear the document's dirty state. Abandoned staged files are cleaned on the next startup, excluding a recoverable pending export.

There is no network permission, analytics, account, background sync, or broad storage access. Android backup is disabled for the app. Private drafts are not application-level encrypted and are deleted on uninstall; treat device unlock/security as the protection boundary. The IME is asked not to personalize learning, but this cannot guarantee behavior of every installed keyboard.

## Interface and behavior

- Minimal paper/sage palette, a monospace editing surface, and no cards or decorative dashboard UI.
- System/light/dark mode and optional logical line numbers in the settings menu; settings persist.
- New, Open, Paste, Clear, Undo, Redo, counts, and Save As. A horizontally scrollable action row keeps controls usable on narrow screens.
- Native soft wrapping, scrolling, selection, cut/copy, IME composing support, and accessibility text semantics. The gutter only draws visible logical line numbers and does not number soft-wrap continuations.
- Character count means **Unicode code points**, not grapheme clusters; word count means **whitespace-separated runs**. Source/editor offsets use UTF-16 units. Logical line numbers count LF boundaries, including CRLF, not standalone legacy CR separators.
- New/Open prompt before discarding externally unsaved changes. Clear has its own confirmation and is undoable. Choosing Save first saves a copy without automatically continuing the destructive action; invoke New/Open again afterward.
- Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+V, Ctrl+Z, Ctrl+Shift+Z, and Ctrl+Y are handled while the editor has focus. Hardware Tab inserts a tab; Shift+Tab keeps normal focus navigation.
- Clipboard Paste reads the first plain-text item only. Clipboard URI/image coercion is not performed behind the user's back.

## Tests and release gate

Run JVM tests and static Android checks:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Run PDF instrumentation tests on a connected API 26+ device/emulator:

```sh
./gradlew connectedDebugAndroidTest
```

Tests cover randomized rope edits, AVL balance, immutable snapshots, chunk boundaries, Unicode surrogate boundaries, range copying, count metadata, history branching/eviction, UTF-8 round trips, malformed input, size limits, filename validation, and opening/paginating generated PDFs with Android's `PdfRenderer`.

`TESTING.md` contains the device acceptance/performance matrix. `.github/workflows/android.yml` is an optional CI workflow for your repository; it has not been run during this delivery and does not publish or deploy anything by itself.

`tools/verify_rope_model.py`, if run with Python 3, checks an independent executable model of the AVL algorithms. Passing that model is **not** the same as executing the supplied Kotlin/JUnit tests. See `VERIFICATION.md` for exactly what was checked in the source-generation environment.

## Repackage or copy source

The archive already has the correct Android Studio directory layout. To re-zip it, zip the top-level `PocketNotepad` folder, excluding `.gradle`, `.idea`, `local.properties`, and all `build` directories. With Python 3, from its parent directory:

```sh
python -m zipfile -c PocketNotepad.zip PocketNotepad
```

For a source-only re-zip, first remove or exclude generated build/cache folders. No machine-specific SDK paths or signing material belong in a shared source archive. The separate `PocketNotepad-All-Source.md` companion contains the complete text of every project source/configuration/documentation file, labeled by relative path. It is a copy/paste fallback, not an additional dependency.

## API references

- SAF and provider limitations: https://developer.android.com/training/data-storage/shared/documents-files
- Native PDF lifecycle: https://developer.android.com/reference/android/graphics/pdf/PdfDocument
- Compose/View interoperability: https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose
- EditText attributes and native editing: https://developer.android.com/reference/android/widget/EditText
- Gradle wrapper/distribution checksums: https://gradle.org/release-checksums/

Project code is provided under the MIT license in `LICENSE`. AndroidX, Kotlin, coroutines, Android platform, and downloaded build tools retain their respective upstream licenses.
