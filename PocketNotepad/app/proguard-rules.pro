# This custom editor is inflated by its XML class name.
-keep class dev.pocket.notepad.ui.NotepadEditText {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Preserve Nothing Glyph Developer Kit & AIDL service interfaces in release builds.
-keep class com.nothing.ketchum.** { *; }
-keep class com.nothing.thirdparty.** { *; }
-keep interface com.nothing.thirdparty.** { *; }
-dontwarn com.nothing.**
