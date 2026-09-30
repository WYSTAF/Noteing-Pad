package dev.pocket.notepad.glyph

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.GlyphManager
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixUtils

/**
 * Nothing Glyph integration — official GDK (com.nothing.ketchum, vendored at
 * app/libs/glyph-sdk-2.0.aar).
 *
 * HARDWARE MAP (Common.* verified against the shipped AAR):
 *   Phone (1) 20111, Phone (2) 22111, Phone (2a/2a+) 23111/23113,
 *   Phone (3a/3a Pro) 24111  -> GLYPH INTERFACE (LED zones)
 *   Phone (4a / 4a Pro) 25111 / 25111p, Phone (4b) 25131 -> GLYPH MATRIX / BAR
 *
 * The SDK drives a system proxy, so every call is defensive: on other phones,
 * or when the app is not foreground, it throws or refuses. We degrade to a
 * no-op — the Glyph can never take the app down.
 *
 * Three live behaviours, as requested:
 *   save   -> zones fill bottom-to-top (progress fills like a save meter)
 *   share  -> zones drain top-to-bottom (the inverse motion)
 *   scroll -> the lit length mirrors the caret's position in the document
 */
object GlyphSupport {

    private const val TAG = "GlyphSupport"
    const val DEBUG_KEY = "test"      // Nothing's documented key for debug builds
    const val RED = 0xFFFF0000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val OFF = 0x00000000

    enum class Hardware { INTERFACE, MATRIX, UNKNOWN }

    @Volatile var hardware: Hardware = Hardware.UNKNOWN
        private set

    @Volatile var enabled: Boolean = true          // user toggle from the menu
        private set

    @Volatile var available: Boolean = false        // device actually supports it
        private set

    private var glyphManager: GlyphManager? = null
    private var matrixManager: GlyphMatrixManager? = null
    private var sessionOpen = false
    private var initialised = false

    /** Number of addressable zones on this device's interface. */
    private var zones: Int = 24                     // Phone (2a) C1..C24

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) turnOff()
    }

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        try {
            hardware = when {
                Common.is20111() -> Hardware.INTERFACE.also { zones = 16 }
                Common.is22111() -> Hardware.INTERFACE.also { zones = 34 }
                Common.is23111() || Common.is23113() -> Hardware.INTERFACE.also { zones = 26 }
                Common.is24111() -> Hardware.INTERFACE.also { zones = 36 }
                Common.is25111() || Common.is25111p() || Common.is25131() -> Hardware.MATRIX
                else -> Hardware.UNKNOWN
            }
            if (hardware == Hardware.UNKNOWN) return
            val app = context.applicationContext
            if (hardware == Hardware.INTERFACE) {
                val manager = GlyphManager.getInstance(app)
                manager.init(object : GlyphManager.Callback {
                    override fun onServiceConnected(name: android.content.ComponentName?) {
                        Log.i(TAG, "glyph service connected")
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        Log.w(TAG, "glyph service disconnected")
                        available = false
                    }
                })
                glyphManager = manager
                available = runCatching { manager.register(DEBUG_KEY) }.getOrDefault(false)
            } else {
                val manager = GlyphMatrixManager.getInstance(app)
                manager.init(object : GlyphMatrixManager.Callback {
                    override fun onServiceConnected(name: android.content.ComponentName?) {
                        Log.i(TAG, "matrix service connected")
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        Log.w(TAG, "matrix service disconnected")
                        available = false
                    }
                })
                matrixManager = manager
                available = runCatching { manager.register(DEBUG_KEY) }.getOrDefault(false)
            }
            Log.i(TAG, "hardware=$hardware zones=$zones available=$available")
        } catch (failure: Throwable) {
            Log.w(TAG, "glyph unavailable: ${failure.message}")
            available = false
        }
    }

    // ---- session plumbing ------------------------------------------------

    private inline fun session(block: () -> Unit) {
        if (!available || !enabled) return
        var opened = false
        try {
            if (hardware == Hardware.INTERFACE && !sessionOpen) {
                glyphManager?.openSession(); sessionOpen = true; opened = true
            }
            block()
        } catch (failure: Throwable) {
            Log.w(TAG, "glyph op: ${failure.message}")
        } finally {
            if (opened) {
                sessionOpen = false
                runCatching { glyphManager?.closeSession() }
            }
        }
    }

    fun turnOff() {
        runCatching {
            when (hardware) {
                Hardware.INTERFACE -> glyphManager?.turnOff()
                Hardware.MATRIX -> matrixManager?.turnOff()
                Hardware.UNKNOWN -> Unit
            }
        }
    }

    // ---- Glyph INTERFACE: zone painting ---------------------------------

    /**
     * Light the first [lit] zones of the strip. direction=1 fills from the
     * bottom up (save), direction=-1 from the top down (share), matching how
     * each action reads on the hardware.
     */
    private fun paintInterface(lit: Int, colour: Int, fromBottom: Boolean) {
        if (!available || !enabled || hardware != Hardware.INTERFACE) return
        val n = zones
        val count = lit.coerceIn(0, n)
        val frame = IntArray(n) { index ->
            val on = if (fromBottom) index >= n - count else index < count
            if (on) colour else OFF
        }
        session { glyphManager?.setFrameColors(frame) }
    }

    /** Save: strip fills bottom-to-top as the document is written. */
    fun onSaveProgress(percent: Int) =
        paintInterface((percent.coerceIn(0, 100) / 100f * zones).toInt(), RED, fromBottom = true)

    fun onSaveDone() = paintInterface(zones, WHITE, fromBottom = true)

    /** Share: the inverse — the strip drains downward. */
    fun onShareProgress(percent: Int) =
        paintInterface((percent.coerceIn(0, 100) / 100f * zones).toInt(), WHITE, fromBottom = false)

    fun onShareDone() = paintInterface(0, WHITE, fromBottom = false)

    /** Live scroll/caret position: lit length == how far through the note you are. */
    fun onScrollProgress(percent: Int) =
        paintInterface((percent.coerceIn(0, 100) / 100f * zones).toInt(), RED, fromBottom = true)

    /** Short blink, e.g. delete or export finished. */
    fun blink(colour: Int = RED, times: Int = 2) {
        if (!available || !enabled) return
        val frame = IntArray(zones) { colour }
        val blank = IntArray(zones) { OFF }
        session {
            repeat(times) {
                runCatching { glyphManager?.setFrameColors(frame) }
                runCatching { glyphManager?.setFrameColors(blank) }
            }
        }
    }

    // ---- Glyph MATRIX / BAR: pixel frames -------------------------------

    /**
     * Renders text to the matrix using the LED Dot-Matrix typeface and the
     * SDK's own bitmap->matrix converter — this is what makes the matrix show
     * the authentic dot-matrix glyphs rather than plain text.
     */
    fun showMatrixText(text: String) {
        if (!available || !enabled || hardware != Hardware.MATRIX) return
        runCatching {
            val bmp = GlyphMatrixUtils.createBitmapWithText(text, 18f, 0xFFFF0000.toInt())
            if (bmp == null) return@runCatching
            val width = Common.getDeviceMatrixLength()
            if (width <= 0) return@runCatching
            val frame = GlyphMatrixUtils.convertToGlyphMatrix(bmp, width, 0, 0, 0, 0, false, false)
            matrixManager?.setMatrixFrame(frame)
        }.onFailure { Log.w(TAG, "matrix text: ${it.message}") }
    }

    /** Animated sweep used on save / upload: a bar that fills, then clears. */
    fun matrixSweep(percent: Int, colour: Int = RED) {
        if (!available || !enabled || hardware != Hardware.MATRIX) return
        runCatching {
            val width = Common.getDeviceMatrixLength()
            if (width <= 0) return@runCatching
            val p = percent.coerceIn(0, 100)
            val lit = (p / 100f * width).toInt().coerceIn(0, width)
            val frame = IntArray(width) { if (it < lit) colour else OFF }
            matrixManager?.setMatrixFrame(frame)
        }
    }

    /** Matrix mirror of the scroll position. */
    fun onMatrixScroll(percent: Int) = matrixSweep(percent)

    fun matrixBlink(times: Int = 2) {
        if (!available || !enabled || hardware != Hardware.MATRIX) return
        runCatching {
            val width = Common.getDeviceMatrixLength()
            if (width <= 0) return@runCatching
            val on = IntArray(width) { RED }
            val off = IntArray(width) { OFF }
            repeat(times) {
                matrixManager?.setMatrixFrame(on)
                matrixManager?.setMatrixFrame(off)
            }
        }
    }

    /** The bundled LED Dot-Matrix face, for on-screen accents if wanted. */
    fun dotMatrixTypeface(context: Context): Typeface? =
        runCatching {
            Typeface.createFromAsset(context.assets, "fonts/led_dot_matrix.ttf")
        }.getOrNull()
}
