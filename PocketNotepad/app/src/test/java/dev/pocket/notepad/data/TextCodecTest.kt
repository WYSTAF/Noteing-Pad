package dev.pocket.notepad.data

import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.Rope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class TextCodecTest {
    @Test fun utf8RoundTripPreservesBomCrLfTabsAndEmoji() {
        val text = 0xFEFF.toChar() + "x".repeat(4094) + String(Character.toChars(0x1F680)) + "\r\n\tسلام\n"
        val output = ByteArrayOutputStream()
        TextCodec.write(Rope.of(text), output)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertEquals(text, TextCodec.read(ByteArrayInputStream(output.toByteArray())).toString())
    }
    @Test fun rejectsMalformedUtf8() {
        assertThrows(CharacterCodingException::class.java) {
            TextCodec.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }
    @Test fun rejectsBinaryNul() {
        assertThrows(IllegalArgumentException::class.java) {
            TextCodec.read(ByteArrayInputStream(byteArrayOf(65, 0, 66)))
        }
    }
    @Test fun rejectsOversizedFilesWithoutTruncating() {
        assertThrows(IllegalArgumentException::class.java) {
            TextCodec.read(ByteArrayInputStream(ByteArray(Limits.MAX_CHARACTERS + 1) { 65 }))
        }
    }
    @Test fun rejectsUnpairedSurrogateOnExport() {
        assertThrows(CharacterCodingException::class.java) {
            TextCodec.write(Rope.of(0xD800.toChar().toString()), ByteArrayOutputStream())
        }
    }
}
