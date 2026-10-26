package com.groovebox.midi

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for the MIDI clock estimator. No MIDI stack involved: the
 * estimator takes explicit nanosecond timestamps, so we can simulate a
 * perfectly jittered master.
 */
class MidiClockInTest {

    private fun feed(
        clock: MidiClockIn,
        bpm: Double,
        ticks: Int,
        startNanos: Long = 1_000_000_000L,
        jitterNanos: Long = 0L
    ): Float? {
        val period = 60_000_000_000.0 / (bpm * 24.0)
        var last: Float? = null
        for (i in 0 until ticks) {
            val wobble = if (jitterNanos == 0L) 0L else {
                // Deterministic +/- jitter so the test is reproducible.
                if (i % 2 == 0) jitterNanos else -jitterNanos
            }
            val now = startNanos + (period * i).toLong() + wobble
            val bpmEstimate = clock.onTick(now)
            if (bpmEstimate != null) last = bpmEstimate
        }
        return last
    }

    @Test
    fun estimatesCommonTempos() {
        for (target in listOf(90.0, 120.0, 128.0, 174.0)) {
            val clock = MidiClockIn()
            val estimate = feed(clock, target, ticks = 80)
            assertTrue(estimate != null, "no estimate for $target BPM")
            assertTrue(
                abs(estimate - target) < 0.2,
                "expected ~$target BPM, got $estimate"
            )
        }
    }

    @Test
    fun toleratesRealWorldJitter() {
        val clock = MidiClockIn()
        // +/- 1.5 ms is typical for a USB MIDI interface.
        val estimate = feed(clock, 120.0, ticks = 160, jitterNanos = 1_500_000L)
        assertTrue(estimate != null)
        assertTrue(abs(estimate - 120.0) < 1.0, "jitter estimate was $estimate")
    }

    @Test
    fun startsSilentUntilTheWindowSettles() {
        val clock = MidiClockIn()
        val period = 60_000_000_000.0 / (120.0 * 24.0)
        var reports = 0
        for (i in 0 until 15) {
            if (clock.onTick(1_000_000_000L + (period * i).toLong()) != null) reports++
        }
        assertEquals(0, reports, "should not report before the window has 16 samples")
    }

    @Test
    fun songPositionIsAppliedToTheNextStartOnly() {
        val clock = MidiClockIn()
        clock.onSongPosition(32)
        assertEquals(32, clock.onStart())
        assertNull(clock.onStart())
    }

    @Test
    fun songPositionAlsoAppliesToContinue() {
        val clock = MidiClockIn()
        clock.onSongPosition(64)
        assertEquals(64, clock.onContinue())
        assertNull(clock.onContinue())
    }

    @Test
    fun gapRestartsTheWindow() {
        val clock = MidiClockIn()
        feed(clock, 120.0, ticks = 40)
        // A 2-second gap must invalidate the old average rather than drag it down.
        val estimateAfterGap = clock.onTick(10_000_000_000L)
        assertNull(estimateAfterGap)
    }
}

class MidiClockOutTest {

    @Test
    fun sendsStartAndStopAroundTheTransport() {
        val out = MidiClockOut()
        val sent = mutableListOf<Byte>()
        out.attachSender { message -> sent.add(message[0]) }

        out.sync(isRunning = false, tempo = 120f, clockEnabled = true, sendTransport = true)
        out.sync(isRunning = true, tempo = 120f, clockEnabled = true, sendTransport = true)
        assertTrue(sent.contains(0xFA.toByte()), "expected Start, got $sent")

        out.sync(isRunning = false, tempo = 120f, clockEnabled = true, sendTransport = true)
        assertTrue(sent.contains(0xFC.toByte()), "expected Stop, got $sent")

        out.close()
    }

    @Test
    fun noTransportWhenDisabled() {
        val out = MidiClockOut()
        val sent = mutableListOf<Byte>()
        out.attachSender { message -> sent.add(message[0]) }

        out.sync(isRunning = false, tempo = 120f, clockEnabled = true, sendTransport = false)
        out.sync(isRunning = true, tempo = 120f, clockEnabled = true, sendTransport = false)
        out.sync(isRunning = false, tempo = 120f, clockEnabled = true, sendTransport = false)

        assertTrue(sent.none { it == 0xFA.toByte() || it == 0xFC.toByte() }, "unexpected transport: $sent")
        out.close()
    }

    @Test
    fun disablingClockMidPlaySendsStop() {
        val out = MidiClockOut()
        val sent = mutableListOf<Byte>()
        out.attachSender { message -> sent.add(message[0]) }

        out.sync(isRunning = true, tempo = 120f, clockEnabled = true, sendTransport = true)
        out.sync(isRunning = true, tempo = 120f, clockEnabled = false, sendTransport = true)

        assertTrue(sent.contains(0xFC.toByte()), "expected Stop on clock disable, got $sent")
        out.close()
    }

    @Test
    fun emitsPulsesWhileRunning() {
        val out = MidiClockOut()
        val sent = java.util.Collections.synchronizedList(mutableListOf<Byte>())
        out.attachSender { message -> sent.add(message[0]) }

        out.sync(isRunning = true, tempo = 240f, clockEnabled = true, sendTransport = false)
        Thread.sleep(120)
        out.sync(isRunning = false, tempo = 240f, clockEnabled = true, sendTransport = false)
        out.close()

        // 240 BPM = 96 pulses/second; 120 ms should give a handful even on a
        // loaded CI machine. Assert only a conservative lower bound.
        val pulses = synchronized(sent) { sent.count { it == 0xF8.toByte() } }
        assertTrue(pulses >= 2, "expected clock pulses, got $pulses")
    }
}

