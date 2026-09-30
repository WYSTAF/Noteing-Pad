# Device acceptance and performance gate

These checks are supplied for you to execute. They have not been represented as passing.

## Build gate

1. Bootstrap Gradle, then run `testDebugUnitTest lintDebug assembleDebug` with JDK 17 and SDK 35.
2. Run `connectedDebugAndroidTest` on API 26 and API 35 devices/emulators.
3. Generate a minified signed release APK and repeat core editing/export smoke tests. Record actual APK/download size rather than guessing it.

## Editor and IME matrix

Test a 320dp-width phone, a regular phone, a tablet, landscape, split-screen, 200% font scaling, TalkBack, system/light/dark themes, and at least two keyboards. Verify navigation/IME insets on API 35.

Use English, Persian/Arabic, CJK, combining accents, emoji including ZWJ sequences, and mixed-direction paragraphs. Check selection drag, select all, cut/copy, composing edits, undo during composition, redo, toolbar Paste, context-menu Paste, keyboard shortcuts, and hardware Tab.

Turn line numbering on and off with soft-wrapped paragraphs. Check the first/last visible lines and the empty final line after a trailing LF. Confirm that TalkBack reads the editor rather than each decorative line number.

## Large-text matrix

Use 100 Ki, 500 Ki, 1 Mi, and 2 Mi UTF-16-unit documents, both many short lines and one pathological unbroken line. Paste at the start, middle, end, and over a large selection. Include an oversized paste and file to verify whole-operation rejection, with the original text intact.

Measure input-to-render time, frame timing, main-thread stalls, peak Java/native heap, and GC with Android Studio Profiler/Perfetto. Record device model, Android version, keyboard, build variant, text shape, and character count with every result. Profile a release build on a lower-memory physical device, not just a debug emulator.

The 2 Mi-unit cap is not evidence of smooth performance at that size. If your supported devices miss your frame-time or paste-latency requirements, lower `Limits.MAX_CHARACTERS` or replace the native surface with a purpose-built virtualized editor. Background IO alone cannot remove main-thread text layout costs.

## Data integrity and lifecycle

Test BOM/no-BOM, LF, CRLF, tabs, empty text, trailing newlines, invalid UTF-8, binary NUL, unsupported UTF-16, and a file that grows beyond the cap while being read. Text exports should preserve valid UTF-8 bytes for unchanged documents.

Rotate with a large document and an active selection. Leave the app, wait for the recovery write, terminate its process, and reopen. Test process recreation while the picker is open, while preparing a PDF, and during publication. Do not expect undo history after process death or durability of edits made immediately before a sudden kill.

Test dirty prompts for New/Open, canceling Open, Open failure, Clear/Undo, PDF export remaining dirty, text Save As clearing dirty, and returning to an older revision using Undo. Save first deliberately does not auto-run a previously requested New/Open action.

## SAF providers and failure cases

Test local Downloads, a removable card where available, and two installed document providers. Test `.txt`, `.md`, `.py`, `.js`, `.json`, `.html`, `.css`, `.csv`, `.pdf`, `.env`, extensionless names, Unicode names, and a custom extension. Check duplicate-name and extension adjustments are reported with the actual provider name.

Cancel both pickers. Deny provider access. Fill internal storage to test staging/recovery failure, then fill the destination to test copy failure. Verify no silent overwrite of the source, no automatic deletion of a provider URI, and no false saved-state transition after failure. A failed publication may leave a partial destination that the user must remove.

## PDFs

Test an empty document, many blank lines, CRLF/CR, tabs, long words, a paragraph longer than the layout window, surrogate pairs on a window boundary, mixed RTL/LTR, and unsupported glyphs. Verify page count, text selection/copy in an external PDF reader, printable margins, header/footer separation, and no repeated/dropped visual lines at page/window transitions.

Generate exactly the maximum allowed page count, then exceed it. Confirm failure occurs before destination picking and internal temporary output is removed. Profile native memory because `PdfDocument` retains page data until writing. The output is not PDF/A or tagged PDF.
