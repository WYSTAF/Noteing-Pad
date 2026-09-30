package dev.pocket.notepad.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RopeTest {
    @Test fun randomEditsMatchAStringAndRemainBalanced() {
        val random = Random(782)
        var expected = "a".repeat(20_000) + "\nFirst paragraph\n"
        var rope = Rope.of(expected)
        repeat(5_000) {
            val start = random.nextInt(expected.length + 1)
            val end = minOf(expected.length, start + random.nextInt(128))
            val alphabet = "abc  \t\r\né中"
            val inserted = buildString {
                repeat(random.nextInt(180)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val oldSnapshot = rope
            val oldExpected = expected
            rope = rope.replace(start, end, Rope.of(inserted))
            expected = expected.replaceRange(start, end, inserted)
            assertEquals(expected, rope.toString())
            assertEquals(oldExpected, oldSnapshot.toString())
            assertEquals(expected.codePointCount(0, expected.length), rope.characters)
            assertEquals(expected.count { it == '\n' }, rope.newlines)
            var words = 0
            var previousWord = false
            expected.forEach {
                val word = !it.isWhitespace()
                if (word && !previousWord) words++
                previousWord = word
            }
            assertEquals(words, rope.words)
            val position = random.nextInt(expected.length + 1)
            assertEquals(expected.take(position).count { it == '\n' }, rope.newlinesBefore(position))
            assertBalanced(rope)
        }
    }
    @Test fun surrogatePairAcrossLeavesCountsAsOneCharacter() {
        val emoji = String(Character.toChars(0x1F680))
        val text = "a".repeat(4095) + emoji + "z"
        val rope = Rope.of(text)
        assertEquals(4097, rope.characters)
        assertEquals(text, rope.toString())
        assertEquals(emoji, rope.subSequence(4095, 4097).toString())
        val split = rope.split(4096)
        assertEquals(rope.characters, Rope.concat(split.first, split.second).characters)
    }
    @Test fun copyRangesAndReaderUseUtf16Offsets() {
        val text = "0123456789\n".repeat(900)
        val rope = Rope.of(text)
        val destination = CharArray(8000)
        rope.copyTo(destination, 4, 3000, 9000)
        assertEquals(text.substring(3000, 9000), destination.concatToString(4, 6004))
        assertEquals(text, rope.reader().use { it.readText() })
    }
    @Test fun emptyAndWhitespaceCounts() {
        assertEquals(0, Rope.Empty.words)
        assertEquals(0, Rope.of(" \n\t").words)
        assertEquals(2, Rope.concat(Rope.of("hello "), Rope.of("world")).words)
        assertEquals(1, Rope.concat(Rope.of("hel"), Rope.of("lo")).words)
    }
    private fun assertBalanced(rope: Rope) {
        when (rope) {
            Rope.Empty -> assertEquals(0, rope.length)
            is Rope.Leaf -> assertTrue(rope.length in 1..Rope.LEAF_SIZE)
            is Rope.Branch -> {
                assertTrue(abs(rope.left.height - rope.right.height) <= 1)
                assertEquals(rope.left.length + rope.right.length, rope.length)
                assertBalanced(rope.left)
                assertBalanced(rope.right)
            }
        }
    }
}
