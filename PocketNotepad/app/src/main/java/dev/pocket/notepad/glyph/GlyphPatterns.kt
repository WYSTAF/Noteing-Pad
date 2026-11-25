package dev.pocket.notepad.glyph

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure, deterministic frame builders for every Nothing hardware model group.
 * Kept free of Android framework dependencies so every channel index, bounds
 * check, and 2D matrix bitmap can be verified directly by JVM unit tests.
 */
enum class NothingModel(
    val displayName: String,
    val deviceCode: String,
    val isMatrix: Boolean,
    val channelCount: Int,
    val matrixSize: Int = 0
) {
    PHONE_1("Phone (1)", "A063", false, 15),
    PHONE_2("Phone (2)", "A065", false, 33),
    PHONE_2A("Phone (2a)", "A142", false, 26),
    PHONE_2A_PLUS("Phone (2a) Plus", "A142P", false, 26),
    PHONE_3A("Phone (3a)", "A059", false, 36),
    PHONE_4A("Phone (4a)", "A069", false, 6),
    PHONE_4B("Phone (4b)", "A009P", false, 4),
    PHONE_3_MATRIX("Phone (3) Matrix", "A024", true, 625, 25),
    PHONE_4A_PRO_MATRIX("Phone (4a) Pro Matrix", "A069P", true, 169, 13),
    UNKNOWN("Unsupported", "", false, 0);

    companion object {
        fun detect(
            model: String,
            brand: String,
            manufacturer: String,
            sdkIs20111: Boolean,
            sdkIs22111: Boolean,
            sdkIs23111: Boolean,
            sdkIs23113: Boolean,
            sdkIs24111: Boolean,
            sdkIs23112: Boolean,
            sdkIs25111: Boolean,
            sdkIs25111p: Boolean,
            sdkIs25131: Boolean
        ): NothingModel {
            val m = model.uppercase()
            val b = brand.uppercase()
            val f = manufacturer.uppercase()
            val isNothing = b.contains("NOTHING") || f.contains("NOTHING") || m.contains("NOTHING")

            // Order matters! Check 4a Pro (A069P) BEFORE 4a (A069), and 2a+ BEFORE 2a.
            return when {
                sdkIs23112 || m.contains("A024") || (isNothing && (m.contains("PHONE (3)") || m == "PHONE 3")) ->
                    PHONE_3_MATRIX
                sdkIs25111p || m.contains("A069P") || (isNothing && m.contains("4A PRO")) ->
                    PHONE_4A_PRO_MATRIX
                sdkIs23113 || m.contains("A142P") || (isNothing && (m.contains("2A+") || m.contains("2A PLUS"))) ->
                    PHONE_2A_PLUS
                sdkIs23111 || m.contains("A142") || (isNothing && m.contains("2A")) ->
                    PHONE_2A
                sdkIs24111 || m.contains("A059") || (isNothing && m.contains("3A")) ->
                    PHONE_3A
                sdkIs20111 || m.contains("A063") || (isNothing && (m.contains("PHONE (1)") || m == "PHONE 1")) ->
                    PHONE_1
                sdkIs22111 || m.contains("A065") || m.contains("AIN065") || (isNothing && (m.contains("PHONE (2)") || m == "PHONE 2")) ->
                    PHONE_2
                sdkIs25111 || m.contains("A069") || (isNothing && m.contains("4A")) ->
                    PHONE_4A
                sdkIs25131 || m.contains("A009P") || (isNothing && m.contains("4B")) ->
                    PHONE_4B
                isNothing -> PHONE_2A
                else -> UNKNOWN
            }
        }
    }
}

object GlyphPatterns {
    const val MAX_LIGHT = 4000
    const val MED_LIGHT = 2000
    const val LOW_LIGHT = 900
    const val OFF = 0

    enum class MatrixGlyph { CHECK, UP_ARROW, CROSS_X, BACK_ARROW, PLUS }

    /**
     * Progressive strip fill with fractional smoothing on the leading LED.
     * [indicesFromStart] must be ordered in the desired fill direction
     * (e.g. top-to-bottom for scroll, bottom-to-top for save).
     */
    private fun fillOrdered(
        total: Int,
        indices: IntArray,
        percent: Int,
        maxBrightness: Int = MAX_LIGHT
    ): IntArray {
        val frame = IntArray(total)
        val p = percent.coerceIn(0, 100)
        if (p == 0 || indices.isEmpty()) return frame
        val exact = p / 100f * indices.size
        val full = exact.toInt().coerceAtMost(indices.size)
        for (i in 0 until full) {
            val ch = indices[i]
            if (ch in frame.indices) frame[ch] = maxBrightness
        }
        if (full < indices.size) {
            val frac = exact - full
            if (frac > 0.08f) {
                val ch = indices[full]
                if (ch in frame.indices) frame[ch] = (frac * maxBrightness).roundToInt()
            }
        }
        return frame
    }

    /**
     * Ordered indices from TOP to BOTTOM for each Glyph Interface model,
     * verified against the official Glyph Developer Kit channel map
     * (Nothing-Developer-Programme/Glyph-Developer-Kit README, 2026):
     *
     * Phone (1): A1/B1 top corners, C1-C4 coil ring, D1_8..D1_1 bottom strip
     *            (docs: D1_1 is the bottom, D1_8 the top), E1 bottom dot.
     * Phone (2): A1/A2 camera-left arcs, B1 top-right slash, C1_16..C1_1 big
     *            arc top-left to bottom-right, C2..C6 surround arcs, D1_8..D1_1
     *            bottom strip, E1 bottom dot.
     * Phone (2a): B right bar, C_24..C_1 eye ring top-right to bottom-left,
     *             A mid-left arc last.
     * Phone (3a): B_5..B_1 top-right slash, C_20..C_1 eye ring top-right to
     *             bottom-left, A_1..A_11 right strip top to bottom.
     * Phone (4a)/(4b): single strip, A_1 top to A_6/A_4 bottom.
     *
     * Each list is a permutation of every channel (0 until channelCount), so a
     * meter or comet sweeps the whole phone body, not just one strip.
     */
    fun topToBottomIndices(model: NothingModel): IntArray = when (model) {
        NothingModel.PHONE_1 -> intArrayOf(
            0, 1, 2, 3, 4, 5,                       // A1, B1, C ring
            14, 13, 12, 11, 10, 9, 8, 7,            // D1_8 -> D1_1 (top -> bottom)
            6                                       // E1 bottom dot
        )
        NothingModel.PHONE_2 -> intArrayOf(
            0, 1, 2,                                // A1, A2, B1
            18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, // C1_16 -> C1_1
            19, 20, 23,                             // C2, C3, C6 surround arcs
            21, 22,                                 // C4, C5 bottom arcs
            32, 31, 30, 29, 28, 27, 26, 25,         // D1_8 -> D1_1
            24                                      // E1
        )
        NothingModel.PHONE_2A, NothingModel.PHONE_2A_PLUS -> intArrayOf(
            24,                                     // B right bar
            23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12,
            11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,   // C_24 -> C_1 top-right -> bottom-left
            25                                      // A mid-left arc
        )
        NothingModel.PHONE_3A -> intArrayOf(
            35, 34, 33, 32, 31,                     // B_5 -> B_1 top-right slash
            19, 18, 17, 16, 15, 14, 13, 12, 11, 10,
            9, 8, 7, 6, 5, 4, 3, 2, 1, 0,           // C_20 -> C_1
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30 // A_1 -> A_11 right strip
        )
        NothingModel.PHONE_4A -> intArrayOf(0, 1, 2, 3, 4, 5)       // A_1 top -> A_6 bottom
        NothingModel.PHONE_4B -> intArrayOf(0, 1, 2, 3)             // A_1 top -> A_4 bottom
        else -> IntArray(0)
    }

    /** Scroll progress: fills smoothly from TOP to BOTTOM as user scrolls down. */
    fun scrollFrame(model: NothingModel, percent: Int): IntArray {
        val p = percent.coerceIn(0, 100)
        if (model.isMatrix) return matrixProgressFrame(model.matrixSize, p, fromTop = true)
        val topDown = topToBottomIndices(model)
        val frame = fillOrdered(model.channelCount, topDown, p, MAX_LIGHT)
        // Subtle model-specific anchor LEDs so the arc stays legible in the dark
        when (model) {
            NothingModel.PHONE_1 -> if (p > 0) frame[6] = LOW_LIGHT // E1 anchor dot
            NothingModel.PHONE_2 -> {
                // Mirror proportionally on D1_8..D1_1 (32 downTo 25)
                val dBars = (p / 100f * 8).roundToInt().coerceIn(0, 8)
                for (i in 0 until dBars) frame[32 - i] = MED_LIGHT
            }
            NothingModel.PHONE_3A -> {
                // Mirror subtly along A_1..A_11 (20..30)
                val aBars = (p / 100f * 11).roundToInt().coerceIn(0, 11)
                for (i in 0 until aBars) frame[20 + i] = LOW_LIGHT
            }
            else -> Unit
        }
        return frame
    }

    /** Save meter: fills from BOTTOM to TOP (rising progress meter). */
    fun saveFrame(model: NothingModel, percent: Int): IntArray {
        val p = percent.coerceIn(0, 100)
        if (model.isMatrix) return matrixDiskFillFrame(model.matrixSize, p, fromTop = false)
        val bottomUp = topToBottomIndices(model).reversedArray()
        return fillOrdered(model.channelCount, bottomUp, p, MAX_LIGHT)
    }

    /** Share / Upload meter: drains from TOP to BOTTOM (inverse of Save). */
    fun shareDrainFrame(model: NothingModel, percent: Int): IntArray {
        val p = percent.coerceIn(0, 100)
        val remaining = 100 - p
        if (model.isMatrix) return matrixProgressFrame(model.matrixSize, remaining, fromTop = false)
        // Keep the bottom segments lit as the top empties down toward bottom
        val bottomUp = topToBottomIndices(model).reversedArray()
        return fillOrdered(model.channelCount, bottomUp, remaining, MAX_LIGHT)
    }

    /** Accent/pulse frame for Back button (subtle upper glyph tap). */
    fun backPulseFrame(model: NothingModel): IntArray {
        if (model.isMatrix) return matrixSymbolFrame(model.matrixSize, MatrixGlyph.BACK_ARROW)
        val frame = IntArray(model.channelCount)
        when (model) {
            NothingModel.PHONE_1 -> frame[1] = MED_LIGHT              // B1 slash
            NothingModel.PHONE_2 -> { frame[0] = MED_LIGHT; frame[1] = MED_LIGHT } // A1, A2
            NothingModel.PHONE_2A, NothingModel.PHONE_2A_PLUS -> frame[25] = MED_LIGHT // A slash
            NothingModel.PHONE_3A -> for (ch in 31..35) frame[ch] = MED_LIGHT // B_1..B_5
            NothingModel.PHONE_4A, NothingModel.PHONE_4B -> frame[0] = MED_LIGHT // A_1 top
            else -> Unit
        }
        return frame
    }

    /** Welcome frame for opening / creating a note. */
    fun newNotePulseFrame(model: NothingModel): IntArray {
        if (model.isMatrix) return matrixSymbolFrame(model.matrixSize, MatrixGlyph.PLUS)
        val frame = IntArray(model.channelCount)
        when (model) {
            NothingModel.PHONE_1 -> {
                for (ch in 2..5) frame[ch] = MED_LIGHT // C1..C4
            }
            NothingModel.PHONE_2 -> {
                for (ch in 19..23) frame[ch] = MED_LIGHT // C2..C6
            }
            NothingModel.PHONE_2A, NothingModel.PHONE_2A_PLUS -> {
                frame[24] = MED_LIGHT // B pill
                for (ch in 18..23) frame[ch] = LOW_LIGHT // upper C arc
            }
            NothingModel.PHONE_3A -> {
                for (ch in 20..25) frame[ch] = MED_LIGHT
            }
            NothingModel.PHONE_4A, NothingModel.PHONE_4B -> {
                for (i in frame.indices) frame[i] = MED_LIGHT
            }
            else -> Unit
        }
        return frame
    }

    /** Sharp double-blink accent frame for Delete / Trash. */
    fun deleteBlinkFrame(model: NothingModel): IntArray {
        if (model.isMatrix) return matrixSymbolFrame(model.matrixSize, MatrixGlyph.CROSS_X)
        val frame = IntArray(model.channelCount)
        when (model) {
            NothingModel.PHONE_1 -> {
                frame[0] = MAX_LIGHT; frame[1] = MAX_LIGHT; frame[6] = MAX_LIGHT
                for (ch in 2..5) frame[ch] = MED_LIGHT
            }
            NothingModel.PHONE_2 -> {
                frame[0] = MAX_LIGHT; frame[1] = MAX_LIGHT; frame[2] = MAX_LIGHT; frame[24] = MAX_LIGHT
                for (ch in 19..23) frame[ch] = MAX_LIGHT
            }
            NothingModel.PHONE_2A, NothingModel.PHONE_2A_PLUS -> {
                frame[24] = MAX_LIGHT // B
                frame[25] = MAX_LIGHT // A
                for (ch in 0..23) frame[ch] = MED_LIGHT
            }
            NothingModel.PHONE_3A -> {
                for (ch in 20..35) frame[ch] = MAX_LIGHT
            }
            NothingModel.PHONE_4A, NothingModel.PHONE_4B -> {
                for (i in frame.indices) frame[i] = MAX_LIGHT
            }
            else -> Unit
        }
        return frame
    }

    /** Celebration flash frame after save or share completes. */
    fun doneFlashFrame(model: NothingModel, isShare: Boolean): IntArray {
        if (model.isMatrix) {
            return matrixSymbolFrame(
                model.matrixSize,
                if (isShare) MatrixGlyph.UP_ARROW else MatrixGlyph.CHECK
            )
        }
        val frame = IntArray(model.channelCount)
        when (model) {
            NothingModel.PHONE_1 -> {
                for (ch in 0..6) frame[ch] = MAX_LIGHT
            }
            NothingModel.PHONE_2 -> {
                frame[0] = MAX_LIGHT; frame[1] = MAX_LIGHT; frame[2] = MAX_LIGHT
                for (ch in 19..24) frame[ch] = MAX_LIGHT
            }
            NothingModel.PHONE_2A, NothingModel.PHONE_2A_PLUS -> {
                frame[24] = MAX_LIGHT; frame[25] = MAX_LIGHT
            }
            NothingModel.PHONE_3A -> {
                for (ch in 31..35) frame[ch] = MAX_LIGHT
            }
            NothingModel.PHONE_4A, NothingModel.PHONE_4B -> {
                for (i in frame.indices) frame[i] = MAX_LIGHT
            }
            else -> Unit
        }
        return frame
    }

    // ---- Directional snap language ------------------------------------------

    enum class SnapSide { LEFT, RIGHT }

    /**
     * Spatially-left elements — "enter note" snap and undo tick base.
     * Mapped from the official GDK layout diagrams: on Phone (1) the left side
     * is A1 plus the left half of the C ring; on Phone (2) it is the camera-left
     * arcs (A1/A2) plus the left/bottom-left surround arcs (C2/C3/C4); on (2a)
     * it is the A arc plus the lower-left half of the C ring; on (3a) it is the
     * left half of the C ring. The single-strip (4a)/(4b) glyphs have no left/
     * right elements, so the top half stands in for left.
     */
    fun leftIndices(model: NothingModel): IntArray = when (model) {
        NothingModel.PHONE_1 -> intArrayOf(0, 2, 3)
        NothingModel.PHONE_2 -> intArrayOf(0, 1, 19, 20, 21)
        NothingModel.PHONE_2A, NothingModel.PHONE_2A_PLUS ->
            (0..11).toList().toIntArray() + intArrayOf(25)
        NothingModel.PHONE_3A -> (0..9).toList().toIntArray()
        NothingModel.PHONE_4A -> intArrayOf(0, 1, 2)
        NothingModel.PHONE_4B -> intArrayOf(0, 1)
        else -> IntArray(0)
    }

    /** Spatially-right elements — "back" snap and redo tick base. */
    fun rightIndices(model: NothingModel): IntArray = when (model) {
        NothingModel.PHONE_1 -> intArrayOf(1, 4, 5, 6) + (7..14).toList().toIntArray()
        NothingModel.PHONE_2 ->
            intArrayOf(2) + (3..18).toList().toIntArray() +
                intArrayOf(22, 23, 24) + (25..32).toList().toIntArray()
        NothingModel.PHONE_2A, NothingModel.PHONE_2A_PLUS ->
            (12..23).toList().toIntArray() + intArrayOf(24)
        NothingModel.PHONE_3A ->
            (10..19).toList().toIntArray() + (20..35).toList().toIntArray()
        NothingModel.PHONE_4A -> intArrayOf(3, 4, 5)
        NothingModel.PHONE_4B -> intArrayOf(2, 3)
        else -> IntArray(0)
    }

    /** Instant one-frame snap: one half of the glyph flashes. */
    fun snapFrame(model: NothingModel, side: SnapSide): IntArray {
        if (model.isMatrix) {
            val grid = IntArray(model.channelCount)
            val size = model.matrixSize
            val third = (size / 3).coerceAtLeast(1)
            val xRange = when (side) {
                SnapSide.LEFT -> 0 until third
                SnapSide.RIGHT -> (size - third) until size
            }
            for (y in 0 until size) for (x in xRange) grid[y * size + x] = MED_LIGHT
            return grid
        }
        val frame = IntArray(model.channelCount)
        val indices = if (side == SnapSide.LEFT) leftIndices(model) else rightIndices(model)
        for (ch in indices) if (ch in frame.indices) frame[ch] = MED_LIGHT
        return frame
    }

    /** Falling (download) meter: fills top-to-bottom as the export progresses. */
    fun downloadFillFrame(model: NothingModel, percent: Int): IntArray {
        val p = percent.coerceIn(0, 100)
        if (model.isMatrix) return matrixDiskFillFrame(model.matrixSize, p, fromTop = true)
        return fillOrdered(model.channelCount, topToBottomIndices(model), p, MAX_LIGHT)
    }

    /** Single bright head with a two-step fading trail — the travelling comet. */
    fun cometFrame(model: NothingModel, percent: Int, fromTop: Boolean): IntArray {
        val p = percent.coerceIn(0, 100)
        if (model.isMatrix) return matrixCometFrame(model.matrixSize, p, fromTop)
        val seq = topToBottomIndices(model)
        if (seq.isEmpty()) return IntArray(model.channelCount)
        val ordered = if (fromTop) seq else seq.reversedArray()
        val pos = (p / 100f * (ordered.size - 1)).roundToInt().coerceIn(0, ordered.size - 1)
        val frame = IntArray(model.channelCount)
        frame[ordered[pos]] = MAX_LIGHT
        if (pos >= 1) frame[ordered[pos - 1]] = MED_LIGHT
        if (pos >= 2) frame[ordered[pos - 2]] = LOW_LIGHT
        return frame
    }

    /** Landing impact: the edge row blazes, its neighbour glows. */
    fun impactFrame(model: NothingModel, atTop: Boolean): IntArray {
        if (model.isMatrix) {
            val grid = IntArray(model.channelCount)
            val size = model.matrixSize
            val row = if (atTop) 0 else size - 1
            val neighbor = if (atTop) 1 else size - 2
            val center = (size - 1) / 2f
            val maxR = size / 2f
            fun inCircle(x: Int, y: Int): Boolean {
                val dx = x - center
                val dy = y - center
                return kotlin.math.sqrt(dx * dx + dy * dy) <= maxR + 0.25f
            }
            for (x in 0 until size) {
                if (inCircle(x, row)) grid[row * size + x] = MAX_LIGHT
                if (neighbor in 0 until size && inCircle(x, neighbor)) grid[neighbor * size + x] = MED_LIGHT
            }
            return grid
        }
        val frame = IntArray(model.channelCount)
        val seq = if (atTop) topToBottomIndices(model) else topToBottomIndices(model).reversedArray()
        seq.take(2).forEach { if (it in frame.indices) frame[it] = MAX_LIGHT }
        seq.drop(2).take(2).forEach { if (it in frame.indices) frame[it] = MED_LIGHT }
        return frame
    }

    /** Import stream: two offset comets + a data-flicker tick, phase 0..phaseCount. */
    fun importStreamFrame(model: NothingModel, phase: Int, phaseCount: Int = 6): IntArray {
        if (model.isMatrix) {
            val grid = IntArray(model.channelCount)
            val size = model.matrixSize
            val r1 = (phase * size / phaseCount).coerceIn(0, size - 1)
            val r2 = (r1 + size / 2) % size
            for (x in 0 until size) {
                grid[r1 * size + x] = MAX_LIGHT
                grid[r2 * size + x] = MED_LIGHT
            }
            val flick = ((phase * 53) % size) * size + ((phase * 91) % size)
            if (grid[flick] == 0) grid[flick] = LOW_LIGHT
            return grid
        }
        val frame = IntArray(model.channelCount)
        val seq = topToBottomIndices(model)
        if (seq.isEmpty()) return frame
        val p1 = (phase * seq.size / phaseCount).coerceIn(0, seq.size - 1)
        val p2 = (p1 + seq.size / 2) % seq.size
        frame[seq[p1]] = MAX_LIGHT
        frame[seq[p2]] = MED_LIGHT
        if (p1 >= 1) frame[seq[p1 - 1]] = LOW_LIGHT
        val flick = (phase * 7 + 5) % frame.size
        if (frame[flick] == 0) frame[flick] = LOW_LIGHT
        return frame
    }

    // ---- 2D Glyph Matrix Generators (Phone 3: 25x25, Phone 4a Pro: 13x13) ----

    fun matrixProgressFrame(size: Int, percent: Int, fromTop: Boolean): IntArray {
        if (size <= 0) return IntArray(0)
        val grid = IntArray(size * size)
        val p = percent.coerceIn(0, 100)
        if (p == 0) return grid
        val center = (size - 1) / 2f
        val maxR = size / 2f
        // Circular mask matching the circular Glyph Matrix hardware on Phone (3) & (4a Pro)
        val filledRows = (p / 100f * size).roundToInt().coerceIn(0, size)
        val barHalfW = if (size >= 20) 3 else 2

        for (y in 0 until size) {
            val rowLit = if (fromTop) y < filledRows else y >= (size - filledRows)
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > maxR + 0.25f) continue
                // Subtle outer circular rim indicator
                val onRim = dist >= maxR - 1.15f
                val inCenterGauge = abs(dx) <= barHalfW
                if (inCenterGauge && rowLit) {
                    grid[y * size + x] = MAX_LIGHT
                } else if (inCenterGauge) {
                    grid[y * size + x] = 450 // faint track
                } else if (onRim && rowLit) {
                    grid[y * size + x] = MED_LIGHT
                }
            }
        }
        return grid
    }

    /**
     * Full-disk progressive fill for the Phone (3) 25x25 and Phone (4a) Pro
     * 13x13 Glyph Matrix. Unlike the sparse scroll gauge, the whole circular
     * display lights row-by-row: a blazing leading edge, two medium trail rows,
     * a low-light filled body and a faint unfilled track inside the circle.
     */
    fun matrixDiskFillFrame(size: Int, percent: Int, fromTop: Boolean): IntArray {
        if (size <= 0) return IntArray(0)
        val grid = IntArray(size * size)
        val p = percent.coerceIn(0, 100)
        if (p == 0) return grid
        val center = (size - 1) / 2f
        val maxR = size / 2f
        val filledRows = (p / 100f * size).roundToInt().coerceIn(0, size)
        val edge = if (fromTop) filledRows - 1 else size - filledRows
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                if (kotlin.math.sqrt(dx * dx + dy * dy) > maxR + 0.25f) continue
                val lit = if (fromTop) y < filledRows else y >= size - filledRows
                val distance = if (fromTop) edge - y else y - edge
                grid[y * size + x] = when {
                    lit && y == edge -> MAX_LIGHT
                    lit && distance <= 1 -> MED_LIGHT
                    lit -> LOW_LIGHT
                    else -> 100
                }
            }
        }
        return grid
    }

    /** A comet sweeping across the matrix: one blazing row plus two trail rows. */
    fun matrixCometFrame(size: Int, percent: Int, fromTop: Boolean): IntArray {
        if (size <= 0) return IntArray(0)
        val grid = IntArray(size * size)
        val p = percent.coerceIn(0, 100)
        val row = (p / 100f * (size - 1)).roundToInt().coerceIn(0, size - 1)
        val head = if (fromTop) row else size - 1 - row
        val t1 = if (fromTop) head - 1 else head + 1
        val t2 = if (fromTop) head - 2 else head + 2
        val center = (size - 1) / 2f
        val maxR = size / 2f
        for (y in 0 until size) {
            val value = when (y) {
                head -> MAX_LIGHT
                t1 -> MED_LIGHT
                t2 -> LOW_LIGHT
                else -> 0
            }
            if (value == 0) continue
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                if (kotlin.math.sqrt(dx * dx + dy * dy) <= maxR + 0.25f) grid[y * size + x] = value
            }
        }
        return grid
    }

    fun matrixSymbolFrame(size: Int, glyph: MatrixGlyph): IntArray {
        if (size <= 0) return IntArray(0)
        val grid = IntArray(size * size)
        val c = size / 2
        val span = (size / 3).coerceAtLeast(2)

        fun setPx(x: Int, y: Int, v: Int = MAX_LIGHT) {
            if (x in 0 until size && y in 0 until size) grid[y * size + x] = v
        }
        fun thickPx(x: Int, y: Int) {
            setPx(x, y, MAX_LIGHT)
            if (size >= 18) {
                setPx(x + 1, y, MAX_LIGHT)
                setPx(x, y + 1, MAX_LIGHT)
            }
        }

        when (glyph) {
            MatrixGlyph.CROSS_X -> {
                for (d in -span..span) {
                    thickPx(c + d, c + d)
                    thickPx(c + d, c - d)
                }
            }
            MatrixGlyph.PLUS -> {
                for (d in -span..span) {
                    thickPx(c + d, c)
                    thickPx(c, c + d)
                }
            }
            MatrixGlyph.UP_ARROW -> {
                for (y in (c - span)..(c + span)) thickPx(c, y)
                for (d in 1..span) {
                    thickPx(c - d, c - span + d)
                    thickPx(c + d, c - span + d)
                }
            }
            MatrixGlyph.BACK_ARROW -> {
                for (x in (c - span)..(c + span)) thickPx(x, c)
                for (d in 1..span) {
                    thickPx(c - span + d, c - d)
                    thickPx(c - span + d, c + d)
                }
            }
            MatrixGlyph.CHECK -> {
                val left = c - span
                val midX = c - span / 3
                val midY = c + span / 2
                for (i in 0..(midX - left)) {
                    thickPx(left + i, c + i)
                }
                val rightSteps = (c + span) - midX
                for (i in 0..rightSteps) {
                    thickPx(midX + i, midY - i)
                }
            }
        }
        return grid
    }
}
