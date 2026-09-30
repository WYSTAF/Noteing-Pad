package dev.pocket.notepad.ui

import android.view.Choreographer

/**
 * On-device scroll telemetry. A single Choreographer callback self-reposts
 * while armed and classifies each frame's interval against the 60/90/120 Hz
 * grids: frames that land 1.6x past a nominal step count as dropped/janked.
 * The ⋮ menu renders the running window, so "the scroll is stiff" becomes a
 * readable number the user can A/B against each Scroll tuning dial.
 */
class FrameHealth {
    @Volatile var fps = 0; private set          // avg rate over the last window
    @Volatile var longFrames = 0; private set   // dropped-frame count, same window
    private var armed = false
    private var lastNanos = 0L
    private var nominalStepNanos = 16_666_666L   // best guess; adapts on first frames
    private val stamps = LongArray(64)
    private var stampCount = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!armed) return
            val previous = lastNanos
            lastNanos = frameTimeNanos
            if (previous != 0L) {
                val delta = frameTimeNanos - previous
                if (stampCount == 0) {
                    // Snap the nominal step to the display's steady rhythm:
                    // nearest standard rate to the first observed interval.
                    val millis = delta / 1_000_000.0
                    nominalStepNanos = when {
                        millis < 9.0 -> 8_333_333L      // ~120 Hz
                        millis < 13.0 -> 11_111_111L    // ~90 Hz
                        millis < 20.0 -> 16_666_666L    // ~60 Hz
                        else -> 33_333_333L             // ~30 Hz idle
                    }
                }
                if (stampCount < stamps.size) { stamps[stampCount++] = delta }
                else {
                    System.arraycopy(stamps, 1, stamps, 0, stamps.size - 1)
                    stamps[stamps.size - 1] = delta
                }
                publish()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun publish() {
        if (stampCount == 0) return
        var sum = 0L
        var longs = 0
        for (i in 0 until stampCount) {
            sum += stamps[i]
            // A frame that skipped at least one whole refresh step.
            if (stamps[i] > nominalStepNanos * 1.6) longs++
        }
        fps = (stampCount * 1_000_000_000.0 / sum).toInt().coerceAtMost(240)
        longFrames = longs
    }

    /** Arm while a gesture/scroll is live; disarm shortly after it settles. */
    val isArmed: Boolean get() = armed
    fun setArmed(active: Boolean) {
        if (active == armed) return
        armed = active
        if (active) {
            lastNanos = 0L
            stampCount = 0
            longFrames = 0
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }
}
