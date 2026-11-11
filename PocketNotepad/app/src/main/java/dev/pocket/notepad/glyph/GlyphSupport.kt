package dev.pocket.notepad.glyph

import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.GlyphManager
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Nothing Glyph & Glyph Matrix engine — official GDK (com.nothing.ketchum,
 * vendored at app/libs/glyph-sdk-2.0.aar).
 *
 * All IPC calls and multi-frame animations execute on a dedicated single-thread
 * executor (never blocking the main UI thread or stalling scroll frames).
 */
object GlyphSupport {

    private const val TAG = "GlyphSupport"
    private const val SCROLL_IDLE_FADE_MS = 1200L

    @Volatile var model: NothingModel = NothingModel.UNKNOWN
        private set

    @Volatile var enabled: Boolean = true
        private set

    @Volatile var available: Boolean = false
        private set

    val availableFlow = MutableStateFlow(false)
    val statusFlow = MutableStateFlow("NOT CHECKED")

    private var appContext: Context? = null
    private var glyphManager: GlyphManager? = null
    private var matrixManager: GlyphMatrixManager? = null
    private var serviceBound = false
    private var sessionOpen = false

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GlyphWorker").apply { isDaemon = true }
    }
    private val idleScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GlyphIdle").apply { isDaemon = true }
    }
    private val animGen = AtomicLong(0L)
    @Volatile private var lastScrollPercent = -1
    @Volatile private var pendingIdleFade: ScheduledFuture<*>? = null

    fun setEnabled(on: Boolean, triggerPulse: Boolean = false) {
        enabled = on
        if (!on) {
            lastScrollPercent = -1
            cancelIdleFade()
            animGen.incrementAndGet()
            turnOff()
        } else {
            val ctx = appContext
            if (ctx != null && !available && model != NothingModel.UNKNOWN) {
                retryRegisterOrBind(ctx)
            }
            if (triggerPulse && available) {
                onTestPulse()
            }
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (serviceBound && available) return

        try {
            val detected = NothingModel.detect(
                model = Build.MODEL ?: "",
                brand = Build.BRAND ?: "",
                manufacturer = Build.MANUFACTURER ?: "",
                sdkIs20111 = Common.is20111(),
                sdkIs22111 = Common.is22111(),
                sdkIs23111 = Common.is23111(),
                sdkIs23113 = Common.is23113(),
                sdkIs24111 = Common.is24111(),
                sdkIs23112 = Common.is23112(),
                sdkIs25111 = Common.is25111(),
                sdkIs25111p = Common.is25111p(),
                sdkIs25131 = Common.is25131()
            )
            model = detected
            if (detected == NothingModel.UNKNOWN) {
                available = false
                availableFlow.value = false
                statusFlow.value = "NO GLYPH HARDWARE"
                return
            }
            if (serviceBound) {
                // Already bound; retry registration if not yet authorized
                retryRegisterOrBind(app)
                return
            }
            serviceBound = true
            statusFlow.value = "${detected.displayName.uppercase()} · CONNECTING"

            if (!detected.isMatrix) {
                val manager = GlyphManager.getInstance(app)
                glyphManager = manager
                manager.init(object : GlyphManager.Callback {
                    override fun onServiceConnected(name: ComponentName?) {
                        Log.i(TAG, "Glyph service connected for ${detected.displayName} (${detected.deviceCode})")
                        worker.execute {
                            authorizeInterface(app, manager, detected)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        Log.w(TAG, "Glyph service disconnected")
                        available = false
                        availableFlow.value = false
                        sessionOpen = false
                        serviceBound = false
                        statusFlow.value = "${detected.displayName.uppercase()} · DISCONNECTED"
                    }
                })
            } else {
                val manager = GlyphMatrixManager.getInstance(app)
                matrixManager = manager
                manager.init(object : GlyphMatrixManager.Callback {
                    override fun onServiceConnected(name: ComponentName?) {
                        Log.i(TAG, "Matrix service connected for ${detected.displayName} (${detected.deviceCode})")
                        worker.execute {
                            authorizeMatrix(app, manager, detected)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        Log.w(TAG, "Matrix service disconnected")
                        available = false
                        availableFlow.value = false
                        serviceBound = false
                        statusFlow.value = "${detected.displayName.uppercase()} · DISCONNECTED"
                    }
                })
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Glyph init error: ${failure.message}")
            available = false
            availableFlow.value = false
            statusFlow.value = "UNAVAILABLE"
        }
    }

    private fun authorizeInterface(app: Context, manager: GlyphManager, m: NothingModel) {
        var reg = false
        if (m.deviceCode.isNotEmpty()) {
            reg = runCatching { manager.register(m.deviceCode) }.getOrDefault(false)
        }
        if (!reg) {
            reg = runCatching { manager.register() }.getOrDefault(false)
        }
        available = reg
        availableFlow.value = reg
        if (reg) {
            runCatching {
                manager.openSession()
                sessionOpen = true
            }
            statusFlow.value = "${m.displayName.uppercase()} · READY"
            Log.i(TAG, "Glyph authorized & session open for ${m.displayName}")
        } else {
            val debugOn = isNothingDebugEnabled(app)
            statusFlow.value = if (debugOn == 0 && Build.VERSION.SDK_INT < 36) {
                "${m.displayName.uppercase()} · ENABLE GLYPH DEBUG"
            } else {
                "${m.displayName.uppercase()} · NOT AUTHORIZED"
            }
            Log.w(TAG, "Glyph register returned false (model=${Build.MODEL}, debug=$debugOn)")
        }
    }

    private fun authorizeMatrix(app: Context, manager: GlyphMatrixManager, m: NothingModel) {
        var reg = false
        if (m.deviceCode.isNotEmpty()) {
            reg = runCatching { manager.register(m.deviceCode) }.getOrDefault(false)
        }
        if (!reg) {
            reg = runCatching { manager.register(Build.MODEL) }.getOrDefault(false)
        }
        available = reg
        availableFlow.value = reg
        if (reg) {
            statusFlow.value = "${m.displayName.uppercase()} · READY"
            Log.i(TAG, "Matrix authorized for ${m.displayName}")
        } else {
            val debugOn = isNothingDebugEnabled(app)
            statusFlow.value = if (debugOn == 0 && Build.VERSION.SDK_INT < 36) {
                "${m.displayName.uppercase()} · ENABLE GLYPH DEBUG"
            } else {
                "${m.displayName.uppercase()} · NOT AUTHORIZED"
            }
            Log.w(TAG, "Matrix register returned false (model=${Build.MODEL}, debug=$debugOn)")
        }
    }

    private fun isNothingDebugEnabled(context: Context): Int =
        runCatching {
            Settings.Global.getInt(context.contentResolver, "nt_glyph_interface_debug_enable", -1)
        }.getOrDefault(-1)

    private fun retryRegisterOrBind(app: Context) {
        if (model == NothingModel.UNKNOWN) return
        if (!serviceBound) {
            init(app)
            return
        }
        worker.execute {
            if (!model.isMatrix) {
                glyphManager?.let { authorizeInterface(app, it, model) }
            } else {
                matrixManager?.let { authorizeMatrix(app, it, model) }
            }
        }
    }

    /** Called from MainActivity.onStart() when app enters foreground. */
    fun onForeground(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (model == NothingModel.UNKNOWN) return
        if (!serviceBound || !available) {
            init(app)
            return
        }
        if (enabled && !model.isMatrix) {
            worker.execute {
                if (available && !sessionOpen) {
                    runCatching {
                        glyphManager?.openSession()
                        sessionOpen = true
                    }
                }
            }
        }
    }

    /** Called from MainActivity.onStop() when app goes to background. */
    fun onBackground() {
        lastScrollPercent = -1
        cancelIdleFade()
        animGen.incrementAndGet()
        if (!available && !sessionOpen) return
        worker.execute {
            turnOffInternal(closeSessionAfter = true)
        }
    }

    // ---- Low-level Frame Delivery (always called on GlyphWorker) --------

    private fun sendFrame(frame: IntArray) {
        if (!available || !enabled || frame.isEmpty()) return
        if (!model.isMatrix) {
            val mgr = glyphManager ?: return
            runCatching {
                if (!sessionOpen) {
                    mgr.openSession()
                    sessionOpen = true
                }
                mgr.setFrameColors(frame)
            }.onFailure {
                // Re-try opening session once if OS reclaimed it while backgrounded
                sessionOpen = false
                runCatching {
                    mgr.openSession()
                    sessionOpen = true
                    mgr.setFrameColors(frame)
                }.onFailure { err ->
                    Log.w(TAG, "Glyph frame failed: ${err.message}")
                }
            }
        } else {
            val mgr = matrixManager ?: return
            runCatching {
                mgr.setAppMatrixFrame(frame)
            }.onFailure {
                runCatching { mgr.setMatrixFrame(frame) }
                    .onFailure { err -> Log.w(TAG, "Matrix frame failed: ${err.message}") }
            }
        }
    }

    private fun turnOffInternal(closeSessionAfter: Boolean) {
        runCatching {
            if (!model.isMatrix) {
                val mgr = glyphManager
                if (mgr != null && available) {
                    if (sessionOpen) {
                        runCatching { mgr.setFrameColors(IntArray(model.channelCount)) }
                    }
                    if (closeSessionAfter && sessionOpen) {
                        sessionOpen = false
                        runCatching { mgr.closeSession() }
                    }
                }
            } else {
                val mgr = matrixManager
                if (mgr != null && available) {
                    runCatching { mgr.setAppMatrixFrame(IntArray(model.channelCount)) }
                    if (closeSessionAfter) {
                        runCatching { mgr.closeAppMatrix() }
                    }
                }
            }
        }
    }

    fun turnOff() {
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        worker.execute {
            if (animGen.get() == gen) {
                turnOffInternal(closeSessionAfter = false)
            }
        }
    }

    // ---- Idle Auto-Fade Helper ------------------------------------------

    private fun cancelIdleFade() {
        pendingIdleFade?.cancel(false)
        pendingIdleFade = null
    }

    private fun scheduleIdleFade(gen: Long) {
        cancelIdleFade()
        pendingIdleFade = idleScheduler.schedule({
            if (animGen.get() == gen) {
                lastScrollPercent = -1
                worker.execute {
                    if (animGen.get() == gen) {
                        turnOffInternal(closeSessionAfter = false)
                    }
                }
            }
        }, SCROLL_IDLE_FADE_MS, TimeUnit.MILLISECONDS)
    }

    // ---- Interactive Behaviours -----------------------------------------

    /**
     * Live document scroll mirror:
     * Lights top-to-bottom as user scrolls down (and drains bottom-to-top when scrolling up),
     * deduplicated per percent so scrolling stays locked at 120 FPS, with clean fade-out after rest.
     */
    fun onScrollProgress(percent: Int) {
        if (!available || !enabled) return
        val p = percent.coerceIn(0, 100)
        if (p == lastScrollPercent) return
        lastScrollPercent = p
        val gen = animGen.incrementAndGet()
        val frame = GlyphPatterns.scrollFrame(model, p)
        worker.execute {
            if (animGen.get() == gen) {
                sendFrame(frame)
            }
        }
        scheduleIdleFade(gen)
    }

    /** Subtle autosave tick while typing — never freezes UI and never wipes active glyphs harshly. */
    fun onAutoSavePulse() {
        if (!available || !enabled) return
        val gen = animGen.incrementAndGet()
        cancelIdleFade()
        val pulse = GlyphPatterns.saveFrame(model, 25)
        val blank = IntArray(model.channelCount)
        worker.execute {
            if (animGen.get() != gen) return@execute
            sendFrame(pulse)
            sleepUninterruptibly(70)
            if (animGen.get() != gen) return@execute
            sendFrame(blank)
        }
    }

    /** Export / explicit Save progress: rising meter from BOTTOM to TOP. */
    fun onSaveProgress(percent: Int) {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val frame = GlyphPatterns.saveFrame(model, percent)
        worker.execute {
            if (animGen.get() == gen) sendFrame(frame)
        }
    }

    /** Explicit Save / Export completed: rising bottom-to-top sweep + confirmation flash. */
    fun onSaveDone() {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val m = model
        val blank = IntArray(m.channelCount)
        worker.execute {
            for (step in intArrayOf(25, 55, 85, 100)) {
                if (animGen.get() != gen) return@execute
                sendFrame(GlyphPatterns.saveFrame(m, step))
                sleepUninterruptibly(45)
            }
            if (animGen.get() != gen) return@execute
            sendFrame(GlyphPatterns.doneFlashFrame(m, isShare = false))
            sleepUninterruptibly(220)
            if (animGen.get() != gen) return@execute
            sendFrame(blank)
        }
    }

    /** Share / Upload progress: draining meter from TOP to BOTTOM (opposite of Save). */
    fun onShareProgress(percent: Int) {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val frame = GlyphPatterns.shareDrainFrame(model, percent)
        worker.execute {
            if (animGen.get() == gen) sendFrame(frame)
        }
    }

    /** Share completed: top-to-bottom drain sweep + crisp upward send flash. */
    fun onShareDone() {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val m = model
        val blank = IntArray(m.channelCount)
        worker.execute {
            for (step in intArrayOf(65, 85, 100)) {
                if (animGen.get() != gen) return@execute
                sendFrame(GlyphPatterns.shareDrainFrame(m, step))
                sleepUninterruptibly(45)
            }
            if (animGen.get() != gen) return@execute
            sendFrame(GlyphPatterns.doneFlashFrame(m, isShare = true))
            sleepUninterruptibly(200)
            if (animGen.get() != gen) return@execute
            sendFrame(blank)
        }
    }

    /** Delete / Trash interaction: crisp double-blink synchronized with vibration. */
    fun onDelete() {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val m = model
        val flash = GlyphPatterns.deleteBlinkFrame(m)
        val blank = IntArray(m.channelCount)
        worker.execute {
            repeat(2) {
                if (animGen.get() != gen) return@execute
                sendFrame(flash)
                sleepUninterruptibly(90)
                if (animGen.get() != gen) return@execute
                sendFrame(blank)
                sleepUninterruptibly(70)
            }
        }
    }

    /** Back navigation: rapid crisp tap pulse on upper glyph / left arrow on Matrix. */
    fun onBack() {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val m = model
        val pulse = GlyphPatterns.backPulseFrame(m)
        val blank = IntArray(m.channelCount)
        worker.execute {
            if (animGen.get() != gen) return@execute
            sendFrame(pulse)
            sleepUninterruptibly(90)
            if (animGen.get() != gen) return@execute
            sendFrame(blank)
        }
    }

    /** New / Open note: gentle welcoming glow on the model's signature glyph. */
    fun onNew() {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val m = model
        val pulse = GlyphPatterns.newNotePulseFrame(m)
        val blank = IntArray(m.channelCount)
        worker.execute {
            if (animGen.get() != gen) return@execute
            sendFrame(pulse)
            sleepUninterruptibly(150)
            if (animGen.get() != gen) return@execute
            sendFrame(blank)
        }
    }

    /** Test pulse when toggling Glyph lights ON in settings: immediate confirmation wave. */
    fun onTestPulse() {
        if (!available || !enabled) return
        lastScrollPercent = -1
        cancelIdleFade()
        val gen = animGen.incrementAndGet()
        val m = model
        val blank = IntArray(m.channelCount)
        worker.execute {
            for (step in intArrayOf(35, 70, 100)) {
                if (animGen.get() != gen) return@execute
                sendFrame(GlyphPatterns.scrollFrame(m, step))
                sleepUninterruptibly(55)
            }
            if (animGen.get() != gen) return@execute
            sendFrame(GlyphPatterns.doneFlashFrame(m, isShare = false))
            sleepUninterruptibly(140)
            if (animGen.get() != gen) return@execute
            sendFrame(blank)
        }
    }

    fun blink() = onDelete()

    private fun sleepUninterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun dotMatrixTypeface(context: Context): Typeface? =
        runCatching {
            Typeface.createFromAsset(context.assets, "fonts/led_dot_matrix.ttf")
        }.getOrNull()
}
