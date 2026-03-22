package com.groovebox.midi

import com.groovebox.StepState
import com.groovebox.TrackState
import java.io.InputStream

/**
 * A lightweight Standard MIDI File (SMF) parser for extracting sequence data.
 */
object MidiParser {

    fun parseMidiToTrackState(inputStream: InputStream, targetTrack: TrackState): TrackState? {
        try {
            val bytes = inputStream.readBytes()
            if (bytes.size < 14) return null
            
            // Check MThd header
            if (bytes[0].toInt() != 'M'.toInt() || bytes[1].toInt() != 'T'.toInt() || 
                bytes[2].toInt() != 'h'.toInt() || bytes[3].toInt() != 'd'.toInt()) return null
            
            val headerLength = readInt32(bytes, 4)
            val format = readInt16(bytes, 8)
            val numTracks = readInt16(bytes, 10)
            val division = readInt16(bytes, 12) // PPQN
            
            if (division <= 0) return null
            
            var offset = 8 + headerLength
            var trackOffset = offset
            
            // Scan for the first MTrk that contains actual Note On events
            var trackFound = false
            while (trackOffset < bytes.size - 8) {
                if (bytes[trackOffset].toInt() == 'M'.toInt() && bytes[trackOffset+1].toInt() == 'T'.toInt() && 
                    bytes[trackOffset+2].toInt() == 'r'.toInt() && bytes[trackOffset+3].toInt() == 'k'.toInt()) {
                    
                    val trackLen = readInt32(bytes, trackOffset + 4)
                    if (hasNoteEvents(bytes, trackOffset + 8, trackOffset + 8 + trackLen)) {
                        offset = trackOffset
                        trackFound = true
                        break
                    }
                    trackOffset += 8 + trackLen
                } else {
                    trackOffset++
                }
            }
            
            if (!trackFound) return null
            
            val trackLength = readInt32(bytes, offset + 4)
            val trackDataStart = offset + 8
            val trackDataEnd = trackDataStart + trackLength
            
            var currentOffset = trackDataStart
            var currentTick = 0L
            var maxStepEncountered = 0
            
            val activeNotes = mutableMapOf<Int, Long>() // Note number -> Start Tick
            val activeVelocities = mutableMapOf<Int, Int>()
            
            val melodicSteps = Array(64) { mutableListOf<MidiNoteEvent>() }
            val drumSteps = Array(16) { Array(64) { mutableListOf<MidiNoteEvent>() } } // 16 lanes
            
            var lastStatus = 0
            
            while (currentOffset < trackDataEnd && currentOffset < bytes.size) {
                // Read Delta-Time
                val (deltaTime, vlqLen) = readVLQ(bytes, currentOffset)
                currentOffset += vlqLen
                currentTick += deltaTime
                
                var status = bytes[currentOffset].toInt() and 0xFF
                if (status < 0x80) {
                    status = lastStatus // Running status
                } else {
                    currentOffset++
                    lastStatus = status
                }
                
                val eventType = status and 0xF0
                
                when (eventType) {
                    0x80 -> { // Note Off
                        val note = bytes[currentOffset++].toInt() and 0x7F
                        val vel = bytes[currentOffset++].toInt() and 0x7F
                        val step = processNoteOff(note, currentTick, activeNotes, activeVelocities, division, melodicSteps, drumSteps)
                        if (step != -1) maxStepEncountered = maxOf(maxStepEncountered, step)
                    }
                    0x90 -> { // Note On
                        val note = bytes[currentOffset++].toInt() and 0x7F
                        val vel = bytes[currentOffset++].toInt() and 0x7F
                        if (vel > 0) {
                            activeNotes[note] = currentTick
                            activeVelocities[note] = vel
                        } else {
                            val step = processNoteOff(note, currentTick, activeNotes, activeVelocities, division, melodicSteps, drumSteps)
                            if (step != -1) maxStepEncountered = maxOf(maxStepEncountered, step)
                        }
                    }
                    0xA0 -> currentOffset += 2 // Polyphonic Key Pressure
                    0xB0 -> currentOffset += 2 // Control Change
                    0xC0 -> currentOffset += 1 // Program Change
                    0xD0 -> currentOffset += 1 // Channel Pressure
                    0xE0 -> currentOffset += 2 // Pitch Bend
                    0xF0 -> { // System Exclusive or Meta
                        if (status == 0xFF) {
                            val type = bytes[currentOffset++].toInt() and 0xFF
                            val (len, lLen) = readVLQ(bytes, currentOffset)
                            currentOffset += lLen + len
                        } else if (status == 0xF0 || status == 0xF7) {
                            val (len, lLen) = readVLQ(bytes, currentOffset)
                            currentOffset += lLen + len
                        }
                    }
                }
            }
            
            // Map our array of events to StepState
            val finalMelodicSteps = List(64) { i ->
                val events = melodicSteps[i]
                if (events.isEmpty()) {
                    StepState()
                } else {
                    StepState(
                        active = true,
                        notes = events.map { it.note },
                        velocity = events.maxOf { it.velocity },
                        gate = events.maxOf { it.gate }
                    )
                }
            }

            val finalDrumSteps = List(16) { drumIdx ->
                List(64) { stepIdx ->
                    val events = drumSteps[drumIdx][stepIdx]
                    if (events.isEmpty()) {
                        StepState()
                    } else {
                        StepState(
                            active = true,
                            notes = listOf(60 + drumIdx),
                            velocity = events.maxOf { it.velocity },
                            gate = events.maxOf { it.gate }
                        )
                    }
                }
            }
            
            // Dynamically set page count based on max note position
            val numPages = ((maxStepEncountered + 16) / 16).coerceIn(1, 4)
            
            return targetTrack.copy(
                steps = finalMelodicSteps, 
                drumSteps = finalDrumSteps,
                numPages = numPages,
                stepsPerPage = 16,
                patternLength = numPages * 16
            )

            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }


    private data class MidiNoteEvent(val note: Int, val velocity: Float, val gate: Float)

    private fun hasNoteEvents(bytes: ByteArray, start: Int, end: Int): Boolean {
        var current = start
        var lastStatus = 0
        while (current < end && current < bytes.size) {
            val (_, vlqLen) = readVLQ(bytes, current)
            current += vlqLen
            if (current >= bytes.size) break
            
            var status = bytes[current].toInt() and 0xFF
            if (status < 0x80) {
                status = lastStatus
            } else {
                current++
                lastStatus = status
            }
            
            val eventType = status and 0xF0
            if (eventType == 0x90) {
                if (current + 1 < bytes.size) {
                    val vel = bytes[current + 1].toInt() and 0x7F
                    if (vel > 0) return true
                }
            }
            
            // Skip data bytes
            when (eventType) {
                0x80, 0x90, 0xA0, 0xB0, 0xE0 -> current += 2
                0xC0, 0xD0 -> current += 1
                0xF0 -> {
                    if (status == 0xFF) {
                        current++ // skip type
                        val (len, lLen) = readVLQ(bytes, current)
                        current += lLen + len
                    } else {
                        val (len, lLen) = readVLQ(bytes, current)
                        current += lLen + len
                    }
                }
            }
        }
        return false
    }


    private fun processNoteOff(
        note: Int, 
        endTick: Long, 
        activeNotes: MutableMap<Int, Long>, 
        activeVelocities: MutableMap<Int, Int>,
        division: Int,
        melodicSteps: Array<MutableList<MidiNoteEvent>>,
        drumSteps: Array<Array<MutableList<MidiNoteEvent>>>
    ): Int {
        val startTick = activeNotes.remove(note) ?: return -1
        val vel = activeVelocities.remove(note) ?: 100
        
        val startStep = (startTick * 4 / division).toInt()
        if (startStep >= 64) return -1
        
        val ticksPerStep = division / 4.0
        val durationInSteps = (endTick - startTick) / ticksPerStep
        
        val event = MidiNoteEvent(
            note = note,
            velocity = vel / 127.0f,
            gate = durationInSteps.toFloat().coerceIn(0.1f, 8.0f)
        )

        // Melodic mapping (all notes)
        melodicSteps[startStep].add(event)

        // Drum mapping (General MIDI / Common maps to 8 lanes)
        val drumLane = when (note) {
            35, 36 -> 0 // Kick
            38, 40 -> 1 // Snare
            41, 43 -> 2 // Toms
            42, 44 -> 3 // Closed HH / Pedal
            46 -> 4 // Open HH
            49, 51 -> 5 // Cymbal / Ride
            37, 39, 56 -> 6 // Percussion (Side Stick, Clap, Cowbell)
            else -> if (note in 60..67) note - 60 else -1 // Fallback to Loom's own 60-67 mapping
        }

        if (drumLane in 0..15) {
            drumSteps[drumLane][startStep].add(event)
        }
        
        return startStep
    }


    private fun readInt16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset+1].toInt() and 0xFF)
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or 
               ((bytes[offset+1].toInt() and 0xFF) shl 16) or 
               ((bytes[offset+2].toInt() and 0xFF) shl 8) or 
               (bytes[offset+3].toInt() and 0xFF)
    }

    private fun readVLQ(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        var len = 0
        var i = offset
        do {
            val b = bytes[i++].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            len++
        } while ((b and 0x80) != 0 && len < 4)
        return value to len
    }
}
