package dev.pocket.notepad.glyph

import org.junit.Assert.assertEquals
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
}
