package dev.pocket.notepad.data

import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.Rope
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction

object TextCodec {
    fun read(input: InputStream, rejectPdf: Boolean = true, checkpoint: () -> Unit = {}): Rope {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = InputStreamReader(input, decoder)
        val buffer = CharArray(Rope.LEAF_SIZE)
        var root: Rope = Rope.Empty
        while (true) {
            checkpoint()
            val count = reader.read(buffer)
            if (count < 0) break
            require(root.length + count <= Limits.MAX_CHARACTERS) { Limits.SIZE_MESSAGE }
            require((0 until count).none { buffer[it] == 0.toChar() }) {
                "This looks like a binary or UTF-16 file. Open a UTF-8 text file instead."
            }
            root = Rope.concat(root, Rope.of(buffer.concatToString(0, count)))
        }
        require(!rejectPdf || root.length < 5 || root.subSequence(0, 5).toString() != "%PDF-") {
            "PDF import is not supported. Open the original text file."
        }
        return root
    }
    fun write(root: Rope, output: OutputStream, checkpoint: () -> Unit = {}) {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // Close completes encoder validation, including a trailing high surrogate.
        OutputStreamWriter(output, encoder).use { root.writeTo(it, checkpoint) }
    }
}
