package dev.pocket.notepad.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphPatternsTest {

    @Test
    fun allModelsHaveValidChannelSizesAndIndices() {
        for (model in NothingModel.entries) {
            if (model == NothingModel.UNKNOWN) continue
            val scroll0 = GlyphPatterns.scrollFrame(model, 0)
            val scroll50 = GlyphPatterns.scrollFrame(model, 50)
            val scroll100 = GlyphPatterns.scrollFrame(model, 100)
            val save50 = GlyphPatterns.saveFrame(model, 50)
            val share50 = GlyphPatterns.shareDrainFrame(model, 50)
            val back = GlyphPatterns.backPulseFrame(model)
            val newNote = GlyphPatterns.newNotePulseFrame(model)
            val del = GlyphPatterns.deleteBlinkFrame(model)
            val doneSave = GlyphPatterns.doneFlashFrame(model, isShare = false)
            val doneShare = GlyphPatterns.doneFlashFrame(model, isShare = true)

            assertEquals("${model.displayName} scroll0 size", model.channelCount, scroll0.size)
            assertEquals("${model.displayName} scroll50 size", model.channelCount, scroll50.size)
            assertEquals("${model.displayName} scroll100 size", model.channelCount, scroll100.size)
            assertEquals("${model.displayName} save50 size", model.channelCount, save50.size)
            assertEquals("${model.displayName} share50 size", model.channelCount, share50.size)
            assertEquals("${model.displayName} back size", model.channelCount, back.size)
            assertEquals("${model.displayName} newNote size", model.channelCount, newNote.size)
            assertEquals("${model.displayName} del size", model.channelCount, del.size)
            assertEquals("${model.displayName} doneSave size", model.channelCount, doneSave.size)
            assertEquals("${model.displayName} doneShare size", model.channelCount, doneShare.size)

            assertTrue("${model.displayName} scroll 0% should be dark", scroll0.all { it == 0 })
            assertTrue("${model.displayName} scroll 100% must light LEDs", scroll100.any { it > 0 })
            assertTrue("${model.displayName} back pulse must light LEDs", back.any { it > 0 })
            assertTrue("${model.displayName} new note pulse must light LEDs", newNote.any { it > 0 })
            assertTrue("${model.displayName} delete blink must light LEDs", del.any { it > 0 })
        }
    }

    @Test
    fun phone2aScrollsTopToBottomAndSaveFillsBottomToTop() {
        // Phone 2a: C_24 (23) is TOP, C_1 (0) is BOTTOM
        val scrollQuarter = GlyphPatterns.scrollFrame(NothingModel.PHONE_2A, 25)
        // Top channels (23 down to 18) lit; bottom channels (0..17) off
        assertEquals(GlyphPatterns.MAX_LIGHT, scrollQuarter[23])
        assertEquals(0, scrollQuarter[0])

        val saveQuarter = GlyphPatterns.saveFrame(NothingModel.PHONE_2A, 25)
        // Save fills bottom-to-top: bottom channels (0..5) lit; top channels off
        assertEquals(GlyphPatterns.MAX_LIGHT, saveQuarter[0])
        assertEquals(0, saveQuarter[23])

        // Upload/share drains top-to-bottom (at 25% uploaded, 75% remains at bottom, top 25% drained)
        val shareQuarter = GlyphPatterns.shareDrainFrame(NothingModel.PHONE_2A, 25)
        assertEquals(GlyphPatterns.MAX_LIGHT, shareQuarter[0])
        assertEquals(0, shareQuarter[23])
    }

    @Test
    fun phone4aProMatchesMatrixBeforePhone4a() {
        val detected = NothingModel.detect(
            model = "A069P",
            brand = "Nothing",
            manufacturer = "Nothing",
            sdkIs20111 = false, sdkIs22111 = false, sdkIs23111 = false,
            sdkIs23113 = false, sdkIs24111 = false, sdkIs23112 = false,
            sdkIs25111 = true, // Note: A069P.contains("A069") in Nothing's Common.is25111!
            sdkIs25111p = true,
            sdkIs25131 = false
        )
        assertEquals(NothingModel.PHONE_4A_PRO_MATRIX, detected)
        assertEquals(169, detected.channelCount)
    }

    @Test
    fun matrixDevicesProduceExact2dGridSizes() {
        assertEquals(625, NothingModel.PHONE_3_MATRIX.channelCount)
        assertEquals(169, NothingModel.PHONE_4A_PRO_MATRIX.channelCount)
        val phone3Cross = GlyphPatterns.deleteBlinkFrame(NothingModel.PHONE_3_MATRIX)
        assertEquals(625, phone3Cross.size)
        // Center pixel of 25x25 (12, 12) must be lit for X
        assertEquals(GlyphPatterns.MAX_LIGHT, phone3Cross[12 * 25 + 12])
    }

    @Test
    fun directionalSnapsLightExactlyOneHalf() {
        for (model in NothingModel.entries) {
            if (model == NothingModel.UNKNOWN || model.isMatrix) continue
            val left = GlyphPatterns.snapFrame(model, GlyphPatterns.SnapSide.LEFT)
            val right = GlyphPatterns.snapFrame(model, GlyphPatterns.SnapSide.RIGHT)
            assertEquals(model.channelCount, left.size)
            assertEquals(model.channelCount, right.size)
            // The two halves are disjoint and each lights its own channels.
            for (i in left.indices) {
                val l = left[i] > 0
                val r = right[i] > 0
                assertTrue("channel $i lit by both halves", !(l && r))
            }
            assertTrue("left snap must light LEDs", left.any { it > 0 })
            assertTrue("right snap must light LEDs", right.any { it > 0 })
            // Left half of a 2a is C_1..C_12, right half C_13..C_24.
            if (model == NothingModel.PHONE_2A) {
                assertEquals(GlyphPatterns.MED_LIGHT, left[0])
                assertEquals(0, left[12])
                assertEquals(0, right[0])
                assertEquals(GlyphPatterns.MED_LIGHT, right[12])
            }
        }
        // Matrix: bespoke left/right chevron icons, not column thirds.
        val left = GlyphPatterns.snapFrame(NothingModel.PHONE_3_MATRIX, GlyphPatterns.SnapSide.LEFT)
        val right = GlyphPatterns.snapFrame(NothingModel.PHONE_3_MATRIX, GlyphPatterns.SnapSide.RIGHT)
        assertEquals(625, left.size)
        assertEquals(625, right.size)
        assertTrue(left.any { it > 0 })
        assertTrue(right.any { it > 0 })
        assertFalse(left.contentEquals(right))
        assertEquals(0, left[0]) // outside the circular mask
        assertEquals(0, right[0])
    }

    @Test
    fun cometTravelsDownWhenDownloadingAndUpWhenUploading() {
        val model = NothingModel.PHONE_2A
        val top = GlyphPatterns.topToBottomIndices(model).first()
        val bottom = GlyphPatterns.topToBottomIndices(model).last()
        assertEquals(GlyphPatterns.MAX_LIGHT, GlyphPatterns.cometFrame(model, 0, fromTop = true)[top])
        assertEquals(GlyphPatterns.MAX_LIGHT, GlyphPatterns.cometFrame(model, 100, fromTop = true)[bottom])
        assertEquals(GlyphPatterns.MAX_LIGHT, GlyphPatterns.cometFrame(model, 0, fromTop = false)[bottom])
        assertEquals(GlyphPatterns.MAX_LIGHT, GlyphPatterns.cometFrame(model, 100, fromTop = false)[top])
        // Matrix comet stays inside the 2D grid and lights a row.
        val matrixComet = GlyphPatterns.cometFrame(NothingModel.PHONE_3_MATRIX, 50, fromTop = true)
        assertEquals(625, matrixComet.size)
        assertTrue(matrixComet.any { it > 0 })
    }

    @Test
    fun downloadFillsTopDownAndImpactsLightTheEdges() {
        val model = NothingModel.PHONE_2A
        val half = GlyphPatterns.downloadFillFrame(model, 50)
        assertEquals(GlyphPatterns.MAX_LIGHT, half[23]) // top of arc lit
        assertEquals(0, half[0])                        // bottom still dark
        val bottomImpact = GlyphPatterns.impactFrame(model, atTop = false)
        assertEquals(GlyphPatterns.MAX_LIGHT, bottomImpact[0])
        val topImpact = GlyphPatterns.impactFrame(model, atTop = true)
        assertEquals(GlyphPatterns.MAX_LIGHT, topImpact[23])
        val matrixImpact = GlyphPatterns.impactFrame(NothingModel.PHONE_4A_PRO_MATRIX, atTop = true)
        assertEquals(169, matrixImpact.size)
        assertTrue(matrixImpact.any { it > 0 })
    }

    @Test
    fun importStreamPhasesStayInBoundsAndKeepMoving() {
        for (model in NothingModel.entries) {
            if (model == NothingModel.UNKNOWN) continue
            val heads = HashSet<Int>()
            for (phase in 0 until 6) {
                val frame = GlyphPatterns.importStreamFrame(model, phase)
                assertEquals(model.channelCount, frame.size)
                assertTrue("phase $phase must light something", frame.any { it > 0 })
                val head = frame.indexOf(GlyphPatterns.MAX_LIGHT)
                assertTrue("phase $phase must keep a bright head", head >= 0)
                heads += head
            }
            assertTrue("${model.displayName} stream must move", heads.size >= 2)
        }
    }

    @Test
    fun fallOrderIsACompletePermutationForEveryLinearModel() {
        // The full-body cascade must touch every LED exactly once, so meters and
        // comets sweep the whole phone instead of a single strip.
        for (model in NothingModel.entries) {
            if (model == NothingModel.UNKNOWN || model.isMatrix) continue
            val order = GlyphPatterns.topToBottomIndices(model)
            assertEquals(model.displayName, model.channelCount, order.size)
            assertEquals(model.displayName, (0 until model.channelCount).toList(), order.toList().sorted())
            assertTrue("${model.displayName} left snap empty", GlyphPatterns.leftIndices(model).isNotEmpty())
            assertTrue("${model.displayName} right snap empty", GlyphPatterns.rightIndices(model).isNotEmpty())
            // Left/right are spatial partitions: no channel belongs to both.
            val left = GlyphPatterns.leftIndices(model).toSet()
            val right = GlyphPatterns.rightIndices(model).toSet()
            assertTrue("${model.displayName} left/right overlap", (left intersect right).isEmpty())
            assertTrue("${model.displayName} out-of-range channel",
                (left + right).all { it in 0 until model.channelCount })
        }
    }

    @Test
    fun matrixDiskFillSweepsTheWholeCircularDisplay() {
        for (model in listOf(NothingModel.PHONE_3_MATRIX, NothingModel.PHONE_4A_PRO_MATRIX)) {
            val size = model.matrixSize
            val downloadStart = GlyphPatterns.matrixDiskFillFrame(size, 5, fromTop = true)
            val downloadDone = GlyphPatterns.matrixDiskFillFrame(size, 100, fromTop = true)
            val uploadStart = GlyphPatterns.matrixDiskFillFrame(size, 5, fromTop = false)
            assertEquals(model.channelCount, downloadStart.size)
            assertEquals(model.channelCount, downloadDone.size)
            // Download: top edge is the bright leading row.
            assertEquals(GlyphPatterns.MAX_LIGHT, downloadStart[size / 2])
            // Upload: bottom edge is the bright leading row.
            assertEquals(GlyphPatterns.MAX_LIGHT, uploadStart[(size - 1) * size + size / 2])
            // The full disk fills (not just a sparse center gauge), but pixels
            // outside the circular mask stay dark.
            assertTrue(downloadDone.count { it > 0 } > size * size / 2)
            assertEquals(0, downloadDone[0])
        }
    }

    @Test
    fun bespokeMatrixIconsAreValidOnBothMatrixSizes() {
        for (size in listOf(25, 13)) {
            val frames = ArrayList<IntArray>().apply {
                for (p in listOf(0, 50, 100)) {
                    add(GlyphPatterns.matrixFileFrame(size, p, goingDown = true))
                    add(GlyphPatterns.matrixFileFrame(size, p, goingDown = false))
                }
                for (phase in 0 until 6) add(GlyphPatterns.matrixSaveFrame(size, phase, 6))
                for (phase in 0 until 5) add(GlyphPatterns.matrixSendFrame(size, phase, 5))
                for (phase in 0 until 6) add(GlyphPatterns.matrixImportFrame(size, phase, 6))
                for (p in listOf(0, 50, 100)) add(GlyphPatterns.matrixCheckFrame(size, p))
                add(GlyphPatterns.matrixArrowFrame(size, GlyphPatterns.MatrixDirection.LEFT))
                add(GlyphPatterns.matrixArrowFrame(size, GlyphPatterns.MatrixDirection.RIGHT))
                add(GlyphPatterns.matrixArrowFrame(size, GlyphPatterns.MatrixDirection.LEFT, double = true))
                add(GlyphPatterns.matrixArrowFrame(size, GlyphPatterns.MatrixDirection.RIGHT, double = true))
            }
            frames.forEachIndexed { index, frame ->
                assertEquals("size $size frame $index", size * size, frame.size)
                assertTrue("size $size frame $index must light pixels", frame.any { it > 0 })
                assertEquals("size $size frame $index circular mask", 0, frame[0])
            }
            assertFalse(GlyphPatterns.matrixFileFrame(size, 50, true)
                .contentEquals(GlyphPatterns.matrixFileFrame(size, 50, false)))
            assertFalse(GlyphPatterns.matrixArrowFrame(size, GlyphPatterns.MatrixDirection.LEFT)
                .contentEquals(GlyphPatterns.matrixArrowFrame(size, GlyphPatterns.MatrixDirection.RIGHT)))
            var previous = -1
            for (p in listOf(0, 25, 50, 75, 100)) {
                val lit = GlyphPatterns.matrixCheckFrame(size, p).count { it > 0 }
                assertTrue("check must draw forward", lit >= previous)
                previous = lit
            }
        }
    }
}
