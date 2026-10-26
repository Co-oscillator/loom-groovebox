package com.groovebox.midi

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * MIDI clock **output**.
 *
 * Emits 24 pulses per quarter note (PPQN) on a dedicated thread, derived from
 * an absolute-time anchor so the pulse stream never drifts even if a wake-up
 * is late.  Tempo changes re-anchor without producing a burst or a gap.
 *
 * Transport messages are sent from [sync] (UI thread) on state transitions so
 * Start/Stop line up with the user's gesture rather than the next poll:
 *
 *   0xFA Start   - transport went from stopped to playing (or clock was just enabled)
 *   0xFC Stop    - transport stopped, or clock-out was switched off mid-play
 *   0xF8 Clock   - 24 per quarter note while playing
 *
 * System real-time bytes are channel-less, so per-device channel remapping in
 * [MidiManager] leaves them untouched; mute/disable still applies.
 */
class MidiClockOut {
    @Volatile
    private var sender: ((ByteArray) -> Unit)? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var transportEnabled = true

    @Volatile
    private var running = false

    @Volatile
    private var bpm = 120f

    @Volatile
    private var closed = false

    private var thread: Thread? = null

    fun attachSender(newSender: (ByteArray) -> Unit) {
        sender = newSender
    }

    fun detachSender() {
        sender = null
    }

    /**
     * Reconcile the generator with the current transport / tempo / settings.
     * Safe to call from any thread; cheap enough to call on every state change.
     *
     * [clockEnabled] should already account for whether any output port exists.
     */
    fun sync(isRunning: Boolean, tempo: Float, clockEnabled: Boolean, sendTransport: Boolean) {
        val wasRunning = running
        val wasEnabled = enabled
        val wasTransport = transportEnabled

        enabled = clockEnabled
        transportEnabled = sendTransport
        bpm = tempo.coerceIn(MIN_BPM, MAX_BPM)

        if (clockEnabled && (isRunning && (!wasRunning || !wasEnabled))) {
            if (sendTransport) sendNow(START)
            ensureThread()
        } else if (!clockEnabled && wasEnabled && wasRunning && wasTransport) {
            // Clock-out was switched off while playing: tell the slaves to stop.
            sendNow(STOP)
        } else if (clockEnabled && !isRunning && wasRunning && wasTransport) {
            sendNow(STOP)
        }

        running = isRunning
    }

    /** Called once when the MIDI manager goes away. */
    fun close() {
        closed = true
        enabled = false
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun sendNow(status: Int) {
        sender?.invoke(byteArrayOf(status.toByte()))
    }

    private fun ensureThread() {
        if (thread != null) return
        val worker = Thread({ loop() }, "LoomMidiClockOut").apply { isDaemon = true }
        thread = worker
        worker.start()
    }

    private fun loop() {
        var anchorNanos = 0L
        var sentPulses = 0L
        var lastBpm = 0f
        var wasRunning = false

        while (!closed) {
            val now = System.nanoTime()
            val out = sender
            val active = enabled && running && out != null

            if (!active) {
                if (wasRunning) {
                    sentPulses = 0L
                    lastBpm = 0f
                }
                wasRunning = false
                try {
                    Thread.sleep(IDLE_SLEEP_MS)
                } catch (e: InterruptedException) {
                    if (closed) return
                }
                continue
            }

            val periodNanos = (60_000_000_000.0 / (bpm * 24.0))
                .toLong()
                .coerceAtLeast(1_000_000L)

            if (!wasRunning) {
                anchorNanos = now
                sentPulses = 0L
                lastBpm = bpm
            } else if (bpm != lastBpm) {
                // Re-anchor on the last pulse so the phase stays continuous.
                anchorNanos = now - sentPulses * periodNanos
                lastBpm = bpm
            }

            val duePulses = ((now - anchorNanos) / periodNanos) + 1
            var catchUp = 0
            while (sentPulses < duePulses && catchUp < MAX_CATCH_UP) {
                out?.invoke(CLOCK)
                sentPulses++
                catchUp++
            }

            wasRunning = true

            val nextDue = anchorNanos + sentPulses * periodNanos
            val waitNanos = nextDue - System.nanoTime()
            val sleepMs = ((waitNanos - 500_000L) / 1_000_000L).coerceIn(0L, MAX_SLEEP_MS)
            try {
                if (sleepMs > 0L) Thread.sleep(sleepMs) else Thread.yield()
            } catch (e: InterruptedException) {
                if (closed) return
            }
        }
    }

    companion object {
        const val PPQN = 24
        private const val MIN_BPM = 20f
        private const val MAX_BPM = 300f
        private const val IDLE_SLEEP_MS = 50L
        private const val MAX_SLEEP_MS = 20L
        private const val MAX_CATCH_UP = 8

        private val CLOCK = byteArrayOf(0xF8.toByte())
        private const val START = 0xFA
        private const val CONTINUE = 0xFB
        private const val STOP = 0xFC
    }
}

/**
 * MIDI clock **input** estimator.
 *
 * Estimates the incoming tempo from a rolling window of 0xF8 intervals and
 * remembers Song Position Pointer so a following Start/Continue can jump the
 * sequencer to the right step.  All methods are pure (no I/O), which keeps the
 * maths testable without a MIDI stack.
 */
class MidiClockIn {
    private val intervals = DoubleArray(WINDOW)
    private var intervalIndex = 0
    private var intervalCount = 0
    private var intervalSum = 0.0

    private var lastTickNanos = 0L
    private var lastReportedBpm = 0f
    private var pendingStartStep: Int? = null

    /** Feed one 0xF8. Returns a new tempo estimate once the window has settled. */
    fun onTick(nowNanos: Long = System.nanoTime()): Float? {
        val previous = lastTickNanos
        lastTickNanos = nowNanos

        if (previous == 0L) return null

        val delta = nowNanos - previous
        if (delta <= 0L || delta > MAX_INTERVAL_NANOS) {
            // A gap means the clock stopped or hiccupped: start a fresh window.
            resetWindow()
            return null
        }

        pushInterval(delta.toDouble())
        if (intervalCount < MIN_SAMPLES) return null

        val average = intervalSum / intervalCount
        if (average <= 0.0) return null

        val bpm = (60_000_000_000.0 / (average * PPQN))
            .coerceIn(MIN_BPM.toDouble(), MAX_BPM.toDouble())
            .toFloat()

        // Round to 0.1 BPM so tiny jitter does not churn the UI/state.
        val rounded = (bpm * 10f).roundToInt() / 10f
        if (lastReportedBpm != 0f && abs(rounded - lastReportedBpm) < REPORT_THRESHOLD) {
            return null
        }
        lastReportedBpm = rounded
        return rounded
    }

    /** 0xFA. Returns the step to jump to, or null to start from the top. */
    fun onStart(): Int? {
        resetTiming()
        val step = pendingStartStep
        pendingStartStep = null
        return step
    }

    /** 0xFB. Returns a jump target only when an SPP preceded the Continue. */
    fun onContinue(): Int? {
        resetTiming()
        val step = pendingStartStep
        pendingStartStep = null
        return step
    }

    /** 0xFC. */
    fun onStop() {
        resetTiming()
        pendingStartStep = null
    }

    /**
     * 0xF2 Song Position Pointer, in 16th notes. Loom's sequencer advances one
     * step per 16th, so the value maps directly onto a step index.
     */
    fun onSongPosition(position16ths: Int) {
        pendingStartStep = position16ths.coerceAtLeast(0)
    }

    private fun resetTiming() {
        lastTickNanos = 0L
        lastReportedBpm = 0f
        resetWindow()
    }

    private fun resetWindow() {
        intervalIndex = 0
        intervalCount = 0
        intervalSum = 0.0
    }

    private fun pushInterval(value: Double) {
        if (intervalCount == WINDOW) {
            intervalSum -= intervals[intervalIndex]
        } else {
            intervalCount++
        }
        intervals[intervalIndex] = value
        intervalSum += value
        intervalIndex = (intervalIndex + 1) % WINDOW
    }

    companion object {
        const val PPQN = 24
        private const val WINDOW = 48
        private const val MIN_SAMPLES = 16
        private const val REPORT_THRESHOLD = 0.2f
        private const val MIN_BPM = 20f
        private const val MAX_BPM = 300f
        private const val MAX_INTERVAL_NANOS = 1_000_000_000L
    }
}
