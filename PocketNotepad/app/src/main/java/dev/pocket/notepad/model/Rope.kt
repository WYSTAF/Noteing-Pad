package dev.pocket.notepad.model

import java.io.Reader
import java.io.Writer
import kotlin.math.max

/** Immutable AVL rope; leaves own at most 4096 UTF-16 units. Offsets match Editable. */
sealed class Rope : CharSequence {
    abstract val height: Int
    abstract val characters: Int
    abstract val words: Int
    abstract val newlines: Int
    abstract val first: Char?
    abstract val last: Char?

    object Empty : Rope() {
        override val length = 0
        override val height = 0
        override val characters = 0
        override val words = 0
        override val newlines = 0
        override val first: Char? = null
        override val last: Char? = null
        override fun get(index: Int): Char = throw IndexOutOfBoundsException()
    }
    class Leaf internal constructor(val value: String) : Rope() {
        override val length = value.length
        override val height = 1
        override val characters = value.codePointCount(0, value.length)
        override val newlines = value.count { it == '\n' }
        override val first = value.first()
        override val last = value.last()
        override val words: Int = run {
            var count = 0
            var inWord = false
            value.forEach {
                val next = !it.isWhitespace()
                if (next && !inWord) count++
                inWord = next
            }
            count
        }
        override fun get(index: Int): Char = value[index]
    }
    class Branch internal constructor(val left: Rope, val right: Rope) : Rope() {
        override val length = left.length + right.length
        override val height = max(left.height, right.height) + 1
        override val first = left.first
        override val last = right.last
        override val newlines = left.newlines + right.newlines
        override val characters = left.characters + right.characters -
            if (left.last!!.isHighSurrogate() && right.first!!.isLowSurrogate()) 1 else 0
        override val words = left.words + right.words -
            if (!left.last!!.isWhitespace() && !right.first!!.isWhitespace()) 1 else 0
        override fun get(index: Int): Char {
            require(index in 0 until length)
            return if (index < left.length) left[index] else right[index - left.length]
        }
    }
    override fun subSequence(startIndex: Int, endIndex: Int): Rope {
        require(startIndex in 0..endIndex && endIndex <= length)
        if (startIndex == 0 && endIndex == length) return this
        return split(endIndex).first.split(startIndex).second
    }
    fun replace(start: Int, end: Int, inserted: Rope): Rope {
        require(start in 0..end && end <= length)
        val (before, tail) = split(start)
        val after = tail.split(end - start).second
        return concat(concat(before, inserted), after)
    }
    fun split(offset: Int): Pair<Rope, Rope> {
        require(offset in 0..length)
        if (offset == 0) return Empty to this
        if (offset == length) return this to Empty
        return when (this) {
            Empty -> Empty to Empty
            is Leaf -> Leaf(value.substring(0, offset)) to Leaf(value.substring(offset))
            is Branch -> if (offset < left.length) {
                val (a, b) = left.split(offset)
                a to concat(b, right)
            } else {
                val (a, b) = right.split(offset - left.length)
                concat(left, a) to b
            }
        }
    }
    /** O(log n + one leaf), for visible line numbers only. */
    fun newlinesBefore(offset: Int): Int {
        require(offset in 0..length)
        if (offset == length) return newlines
        return when (this) {
            Empty -> 0
            is Leaf -> {
                var count = 0
                for (i in 0 until offset) if (value[i] == '\n') count++
                count
            }
            is Branch -> if (offset <= left.length) left.newlinesBefore(offset)
                else left.newlines + right.newlinesBefore(offset - left.length)
        }
    }
    fun copyTo(destination: CharArray, destinationOffset: Int, start: Int, end: Int) {
        require(start in 0..end && end <= length)
        require(destinationOffset >= 0 && destinationOffset + end - start <= destination.size)
        when (this) {
            Empty -> Unit
            is Leaf -> value.toCharArray(destination, destinationOffset, start, end)
            is Branch -> {
                val boundary = left.length
                if (start < boundary) left.copyTo(destination, destinationOffset, start, minOf(end, boundary))
                if (end > boundary) right.copyTo(destination,
                    destinationOffset + maxOf(0, boundary - start),
                    maxOf(0, start - boundary), end - boundary)
            }
        }
    }
    fun writeTo(writer: Writer, checkpoint: () -> Unit = {}) {
        when (this) {
            Empty -> Unit
            is Leaf -> { checkpoint(); writer.write(value) }
            is Branch -> { left.writeTo(writer, checkpoint); right.writeTo(writer, checkpoint) }
        }
    }
    fun reader(): Reader = object : Reader() {
        private var offset = 0
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off + len <= cbuf.size)
            if (len == 0) return 0
            if (offset == length) return -1
            val size = minOf(len, length - offset)
            copyTo(cbuf, off, offset, offset + size)
            offset += size
            return size
        }
        override fun close() = Unit
    }
    final override fun toString(): String {
        val buffer = CharArray(length)
        copyTo(buffer, 0, 0, length)
        return buffer.concatToString()
    }
    companion object {
        const val LEAF_SIZE = 4096
        fun of(text: CharSequence): Rope {
            if (text.isEmpty()) return Empty
            val leaves = ArrayList<Rope>((text.length + LEAF_SIZE - 1) / LEAF_SIZE)
            var start = 0
            while (start < text.length) {
                val end = minOf(start + LEAF_SIZE, text.length)
                leaves.add(Leaf(text.subSequence(start, end).toString()))
                start = end
            }
            fun build(startIndex: Int, endIndex: Int): Rope {
                if (endIndex - startIndex == 1) return leaves[startIndex]
                val middle = (startIndex + endIndex) / 2
                return Branch(build(startIndex, middle), build(middle, endIndex))
            }
            return build(0, leaves.size)
        }
        fun concat(a: Rope, b: Rope): Rope {
            if (a === Empty) return b
            if (b === Empty) return a
            if (a is Leaf && b is Leaf && a.length + b.length <= LEAF_SIZE) {
                return Leaf(a.value + b.value)
            }
            if (a.height > b.height + 1) {
                a as Branch
                return balance(a.left, concat(a.right, b))
            }
            if (b.height > a.height + 1) {
                b as Branch
                return balance(concat(a, b.left), b.right)
            }
            return Branch(a, b)
        }
        private fun balance(a: Rope, b: Rope): Rope {
            if (a.height > b.height + 1) {
                a as Branch
                return if (a.left.height >= a.right.height) Branch(a.left, Branch(a.right, b))
                else {
                    val middle = a.right as Branch
                    Branch(Branch(a.left, middle.left), Branch(middle.right, b))
                }
            }
            if (b.height > a.height + 1) {
                b as Branch
                return if (b.right.height >= b.left.height) Branch(Branch(a, b.left), b.right)
                else {
                    val middle = b.left as Branch
                    Branch(Branch(a, middle.left), Branch(middle.right, b.right))
                }
            }
            return Branch(a, b)
        }
    }
}
