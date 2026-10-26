package dev.pocket.notepad.glyph

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixUtils
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Nothing Glyph integration — official GDK (com.nothing.ketchum, vendored at
 * app/libs/glyph-sdk-2.0.aar).
 *
 * SPECIALIZED HARDWARE GROUPS:
 *   1. Phone (1) [A063 / 20111], Phone (2) [A065 / 22111]
 *      -> Classic multi-zone interface (Phone 1: 16 zones, Phone 2: 33 zones with C1 16-seg arc)
 *   2. Phone (2a / 2a+) [A142/A142P / 23111/23113], Phone (3a / 3a Pro) [A059 / 24111]
 *      -> 2a circular C-ring (24 addressable segments C_1..C_24 around camera + B ring + A slash)
 *         and 3a 20-segment arc
 *   3. Phone (4a) [A069 / 25111], Phone (4b) [A009P / 25131]
 *      -> Sleek compact multi-segment indicator strip (A_1..A_6 / A_1..A_4)
 *   4. Phone (3) [A024 / 23112], Phone (4a Pro) [A069P / 25111p]
 *      -> High-density Glyph LED Matrix displays
 */
object GlyphSupport {

    private const val TAG = "GlyphSupport"
    const val MAX_LIGHT = 4000
    const val MED_LIGHT = 2000
    const val LOW_LIGHT = 1000
    const val OFF = 0

    enum class Hardware { INTERFACE, MATRIX, UNKNOWN }
    enum class ModelGroup { PHONE_1_2, PHONE_2A_3A, PHONE_4A_4B, MATRIX, GENERIC }

    @Volatile var hardware: Hardware = Hardware.UNKNOWN
        private set

    @Volatile var modelGroup: ModelGroup = ModelGroup.GENERIC
        private set

    @Volatile var targetDeviceCode: String = ""
        private set

    @Volatile var enabled: Boolean = true
        private set

    @Volatile var available: Boolean = false
        private set

    val availableFlow = MutableStateFlow(false)

    private var glyphManager: GlyphManager? = null
    private var matrixManager: GlyphMatrixManager? = null
    private var sessionOpen = false
    private var initialised = false
    private var totalChannels: Int = 26

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) turnOff()
    }

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        try {
            val m = (Build.MODEL ?: "").uppercase()
            val b = (Build.BRAND ?: "").uppercase()
            val f = (Build.MANUFACTURER ?: "").uppercase()
            val isNothing = b.contains("NOTHING") || f.contains("NOTHING") || m.contains("NOTHING") || m.contains("A0") || m.contains("A1")

            when {
                // Group 4: Matrix devices
                Common.is23112() || m.contains("A024") || (isNothing && m.contains("PHONE (3)")) -> {
                    hardware = Hardware.MATRIX
                    modelGroup = ModelGroup.MATRIX
                    targetDeviceCode = Glyph.DEVICE_23112
                }
                Common.is25111p() || m.contains("A069P") || (isNothing && m.contains("4A PRO")) -> {
                    hardware = Hardware.MATRIX
                    modelGroup = ModelGroup.MATRIX
                    targetDeviceCode = Glyph.DEVICE_25111p
                }

                // Group 2: Phone (2a / 2a Plus) and (3a / 3a Pro)
                Common.is23111() || m.contains("A142") || (isNothing && m.contains("2A") && !m.contains("2A+")) -> {
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_2A_3A
                    targetDeviceCode = Glyph.DEVICE_23111 // "A142"
                    totalChannels = 26
                }
                Common.is23113() || m.contains("A142P") || (isNothing && (m.contains("2A+") || m.contains("2A PLUS"))) -> {
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_2A_3A
                    targetDeviceCode = Glyph.DEVICE_23113 // "A142P"
                    totalChannels = 26
                }
                Common.is24111() || m.contains("A059") || (isNothing && m.contains("3A")) -> {
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_2A_3A
                    targetDeviceCode = Glyph.DEVICE_24111 // "A059"
                    totalChannels = 36
                }

                // Group 1: Phone (1) and Phone (2)
                Common.is20111() || m.contains("A063") || (isNothing && (m.contains("PHONE (1)") || m == "PHONE 1")) -> {
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_1_2
                    targetDeviceCode = Glyph.DEVICE_20111 // "A063"
                    totalChannels = 16
                }
                Common.is22111() || m.contains("A065") || m.contains("AIN065") || (isNothing && (m.contains("PHONE (2)") || m == "PHONE 2")) -> {
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_1_2
                    targetDeviceCode = Glyph.DEVICE_22111 // "A065"
                    totalChannels = 33
                }

                // Group 3: Phone (4a) and Phone (4b)
                Common.is25111() || m.contains("A069") || (isNothing && m.contains("4A")) -> {
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_4A_4B
                    targetDeviceCode = Glyph.DEVICE_25111 // "A069"
                    totalChannels = 6
                }
                Common.is25131() || m.contains("A009P") || (isNothing && m.contains("4B")) -> {
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_4A_4B
                    targetDeviceCode = Glyph.DEVICE_25131 // "A009P"
                    totalChannels = 4
                }

                isNothing -> {
                    // Fallback for general Nothing devices (treat as 2a circular interface)
                    hardware = Hardware.INTERFACE
                    modelGroup = ModelGroup.PHONE_2A_3A
                    targetDeviceCode = Glyph.DEVICE_23111
                    totalChannels = 26
                }
                else -> {
                    hardware = Hardware.UNKNOWN
                    modelGroup = ModelGroup.GENERIC
                }
            }

            if (hardware == Hardware.UNKNOWN) return
            val app = context.applicationContext

            if (hardware == Hardware.INTERFACE) {
                val manager = GlyphManager.getInstance(app)
                glyphManager = manager
                manager.init(object : GlyphManager.Callback {
                    override fun onServiceConnected(name: android.content.ComponentName?) {
                        Log.i(TAG, "glyph service connected for $targetDeviceCode")
                        var reg = if (targetDeviceCode.isNotEmpty()) {
                            runCatching { manager.register(targetDeviceCode) }.getOrDefault(false)
                        } else false
                        if (!reg) {
                            reg = runCatching { manager.register() }.getOrDefault(false)
                        }
                        Log.i(TAG, "glyph registration result=$reg")
                        available = reg
                        availableFlow.value = reg
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        Log.w(TAG, "glyph service disconnected")
                        available = false
                        availableFlow.value = false
                        sessionOpen = false
                    }
                })
            } else {
                val manager = GlyphMatrixManager.getInstance(app)
                matrixManager = manager
                manager.init(object : GlyphMatrixManager.Callback {
                    override fun onServiceConnected(name: android.content.ComponentName?) {
                        Log.i(TAG, "matrix service connected for $targetDeviceCode")
                        val reg = if (targetDeviceCode.isNotEmpty()) {
                            runCatching { manager.register(targetDeviceCode) }.getOrDefault(false)
                        } else {
                            runCatching { manager.register(android.os.Build.MODEL) }.getOrDefault(false)
                        }
                        Log.i(TAG, "matrix registration result=$reg")
                        available = reg
                        availableFlow.value = reg
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        Log.w(TAG, "matrix service disconnected")
                        available = false
                        availableFlow.value = false
                    }
                })
            }
            Log.i(TAG, "Glyph initialized: hardware=$hardware, group=$modelGroup, code=$targetDeviceCode, channels=$totalChannels")
        } catch (failure: Throwable) {
            Log.w(TAG, "glyph unavailable: ${failure.message}")
            available = false
            availableFlow.value = false
        }
    }

    // ---- Session Management ---------------------------------------------

    private inline fun session(block: () -> Unit) {
        if (!available || !enabled) return
        try {
            if (hardware == Hardware.INTERFACE && !sessionOpen) {
                glyphManager?.openSession()
                sessionOpen = true
            }
            block()
        } catch (failure: Throwable) {
            Log.w(TAG, "glyph session op: ${failure.message}")
        }
    }

    fun turnOff() {
        runCatching {
            if (sessionOpen) {
                sessionOpen = false
                glyphManager?.closeSession()
            }
            when (hardware) {
                Hardware.INTERFACE -> glyphManager?.turnOff()
                Hardware.MATRIX -> matrixManager?.turnOff()
                Hardware.UNKNOWN -> Unit
            }
        }
    }

    // ---- Specialized Model Indicators ----------------------------------

    /**
     * Interactive Scroll Progress:
     * - Phone (2a / 2a+): The circular 24-segment C-ring lights smoothly from top to bottom
     *   as the user scrolls down, and drains backwards as they scroll up!
     * - Phone (1): Lights the diagonal D1 bar (8 segments) progressively.
     * - Phone (2): Lights the center 16-segment C1 arc progressively.
     * - Phone (3a): Lights the 20-segment C arc progressively.
     * - Phone (4a / 4b): Lights the A strip progressively.
     * - Matrix: Sweeps an LED progress bar across the matrix.
     */
    fun onScrollProgress(percent: Int) {
        if (!available || !enabled) return
        val p = percent.coerceIn(0, 100)

        if (hardware == Hardware.INTERFACE) {
            val frame = IntArray(totalChannels)
            when (modelGroup) {
                ModelGroup.PHONE_2A_3A -> {
                    // For Phone 2a/2a+, channels 0..23 are the 24 circular segments around camera
                    val segCount = if (totalChannels == 36) 20 else 24
                    val startOffset = if (totalChannels == 36) 16 else 0
                    val litCount = (p * segCount / 100).coerceIn(0, segCount)
                    for (i in 0 until litCount) {
                        frame[startOffset + i] = MAX_LIGHT
                    }
                }
                ModelGroup.PHONE_1_2 -> {
                    if (totalChannels == 33) {
                        // Phone (2): C1_1..C1_16 are channels 3..18
                        val litCount = (p * 16 / 100).coerceIn(0, 16)
                        for (i in 0 until litCount) frame[3 + i] = MAX_LIGHT
                    } else {
                        // Phone (1): D1_1..D1_8 are channels 7..14
                        val litCount = (p * 8 / 100).coerceIn(0, 8)
                        for (i in 0 until litCount) frame[7 + i] = MAX_LIGHT
                    }
                }
                ModelGroup.PHONE_4A_4B -> {
                    val count = totalChannels
                    val litCount = (p * count / 100).coerceIn(0, count)
                    for (i in 0 until litCount) frame[i] = MAX_LIGHT
                }
                else -> {
                    val litCount = (p * totalChannels / 100).coerceIn(0, totalChannels)
                    for (i in 0 until litCount) frame[i] = MAX_LIGHT
                }
            }
            session { glyphManager?.setFrameColors(frame) }
        } else if (hardware == Hardware.MATRIX) {
            matrixSweep(p)
        }
    }

    /**
     * Saving note: Lights fill up from 0 to 100% (meter effect).
     */
    fun onSaveProgress(percent: Int) {
        onScrollProgress(percent)
    }

    /**
     * Save completed: All glyphs pulse / celebrate with a full bright flash, then fade off.
     */
    fun onSaveDone() {
        if (!available || !enabled) return
        if (hardware == Hardware.INTERFACE) {
            val full = IntArray(totalChannels) { MAX_LIGHT }
            val blank = IntArray(totalChannels) { OFF }
            session {
                glyphManager?.setFrameColors(full)
                Thread.sleep(120)
                glyphManager?.setFrameColors(blank)
                Thread.sleep(80)
                glyphManager?.setFrameColors(full)
                Thread.sleep(180)
                glyphManager?.setFrameColors(blank)
            }
        } else if (hardware == Hardware.MATRIX) {
            showMatrixText("SAVED")
        }
    }

    /**
     * Sharing / Exporting (Upload): Lights drain in the OPPOSITE direction (top to bottom drain).
     * As upload advances 0% to 100%, the active lights decrease from full down to 0.
     */
    fun onShareProgress(percent: Int) {
        if (!available || !enabled) return
        val p = percent.coerceIn(0, 100)
        val inverted = 100 - p

        if (hardware == Hardware.INTERFACE) {
            val frame = IntArray(totalChannels)
            when (modelGroup) {
                ModelGroup.PHONE_2A_3A -> {
                    val segCount = if (totalChannels == 36) 20 else 24
                    val startOffset = if (totalChannels == 36) 16 else 0
                    val litCount = (inverted * segCount / 100).coerceIn(0, segCount)
                    for (i in 0 until litCount) frame[startOffset + (segCount - 1 - i)] = MAX_LIGHT
                }
                ModelGroup.PHONE_1_2 -> {
                    if (totalChannels == 33) {
                        val litCount = (inverted * 16 / 100).coerceIn(0, 16)
                        for (i in 0 until litCount) frame[3 + (15 - i)] = MAX_LIGHT
                    } else {
                        val litCount = (inverted * 8 / 100).coerceIn(0, 8)
                        for (i in 0 until litCount) frame[7 + (7 - i)] = MAX_LIGHT
                    }
                }
                else -> {
                    val litCount = (inverted * totalChannels / 100).coerceIn(0, totalChannels)
                    for (i in 0 until litCount) frame[totalChannels - 1 - i] = MAX_LIGHT
                }
            }
            session { glyphManager?.setFrameColors(frame) }
        } else if (hardware == Hardware.MATRIX) {
            matrixSweep(inverted)
        }
    }

    fun onShareDone() {
        if (!available || !enabled) return
        if (hardware == Hardware.INTERFACE) {
            val full = IntArray(totalChannels) { MAX_LIGHT }
            val blank = IntArray(totalChannels) { OFF }
            session {
                glyphManager?.setFrameColors(full)
                Thread.sleep(100)
                glyphManager?.setFrameColors(blank)
            }
        } else if (hardware == Hardware.MATRIX) {
            showMatrixText("SHARED")
        }
    }

    /**
     * Delete / Trash interaction: A sharp, crisp double-blink on the main glyphs!
     */
    fun onDelete() {
        if (!available || !enabled) return
        if (hardware == Hardware.INTERFACE) {
            val frame = IntArray(totalChannels)
            // On Phone 2a, light B (camera ring: 24) and A (slash: 25)
            if (modelGroup == ModelGroup.PHONE_2A_3A && totalChannels == 26) {
                frame[24] = MAX_LIGHT
                frame[25] = MAX_LIGHT
            } else {
                for (i in 0 until totalChannels) frame[i] = MAX_LIGHT
            }
            val blank = IntArray(totalChannels) { OFF }
            session {
                glyphManager?.setFrameColors(frame)
                Thread.sleep(80)
                glyphManager?.setFrameColors(blank)
                Thread.sleep(60)
                glyphManager?.setFrameColors(frame)
                Thread.sleep(100)
                glyphManager?.setFrameColors(blank)
            }
        } else if (hardware == Hardware.MATRIX) {
            matrixBlink(2)
        }
    }

    /**
     * Back button interaction: A subtle, rapid tap pulse on the upper glyph.
     */
    fun onBack() {
        if (!available || !enabled) return
        if (hardware == Hardware.INTERFACE) {
            val frame = IntArray(totalChannels)
            if (modelGroup == ModelGroup.PHONE_2A_3A && totalChannels == 26) {
                frame[25] = MED_LIGHT // Channel A (diagonal slash)
            } else {
                frame[0] = MED_LIGHT
            }
            val blank = IntArray(totalChannels) { OFF }
            session {
                glyphManager?.setFrameColors(frame)
                Thread.sleep(60)
                glyphManager?.setFrameColors(blank)
            }
        }
    }

    /**
     * New note creation: A gentle welcome glow on the glyphs.
     */
    fun onNew() {
        if (!available || !enabled) return
        if (hardware == Hardware.INTERFACE) {
            val frame = IntArray(totalChannels)
            if (modelGroup == ModelGroup.PHONE_2A_3A && totalChannels == 26) {
                frame[24] = MED_LIGHT // Channel B (ring)
            } else {
                for (i in 0 until totalChannels) frame[i] = LOW_LIGHT
            }
            val blank = IntArray(totalChannels) { OFF }
            session {
                glyphManager?.setFrameColors(frame)
                Thread.sleep(120)
                glyphManager?.setFrameColors(blank)
            }
        } else if (hardware == Hardware.MATRIX) {
            showMatrixText("NEW")
        }
    }

    /** Generic blink helper for backward compatibility */
    fun blink(colour: Int = MAX_LIGHT, times: Int = 2) {
        onDelete()
    }

    // ---- Glyph MATRIX: pixel frames ------------------------------------

    fun showMatrixText(text: String) {
        if (!available || !enabled || hardware != Hardware.MATRIX) return
        runCatching {
            val bmp = GlyphMatrixUtils.createBitmapWithText(text, 18f, 0xFFFFFFFF.toInt())
            if (bmp == null) return@runCatching
            val width = Common.getDeviceMatrixLength()
            if (width <= 0) return@runCatching
            val frame = GlyphMatrixUtils.convertToGlyphMatrix(bmp, width, 0, 0, 0, 0, false, false)
            matrixManager?.setMatrixFrame(frame)
        }.onFailure { Log.w(TAG, "matrix text: ${it.message}") }
    }

    fun matrixSweep(percent: Int) {
        if (!available || !enabled || hardware != Hardware.MATRIX) return
        runCatching {
            val width = Common.getDeviceMatrixLength()
            if (width <= 0) return@runCatching
            val p = percent.coerceIn(0, 100)
            val lit = (p * width / 100).coerceIn(0, width)
            val frame = IntArray(width) { if (it < lit) MAX_LIGHT else OFF }
            matrixManager?.setMatrixFrame(frame)
        }
    }

    fun onMatrixScroll(percent: Int) = matrixSweep(percent)

    fun matrixBlink(times: Int = 2) {
        if (!available || !enabled || hardware != Hardware.MATRIX) return
        runCatching {
            val width = Common.getDeviceMatrixLength()
            if (width <= 0) return@runCatching
            val on = IntArray(width) { MAX_LIGHT }
            val off = IntArray(width) { OFF }
            repeat(times) {
                matrixManager?.setMatrixFrame(on)
                Thread.sleep(80)
                matrixManager?.setMatrixFrame(off)
                Thread.sleep(60)
            }
        }
    }

    fun dotMatrixTypeface(context: Context): Typeface? =
        runCatching {
            Typeface.createFromAsset(context.assets, "fonts/led_dot_matrix.ttf")
        }.getOrNull()
}
