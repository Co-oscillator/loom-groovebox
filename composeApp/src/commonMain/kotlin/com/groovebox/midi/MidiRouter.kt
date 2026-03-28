package com.groovebox.midi

import com.groovebox.NativeLib

sealed class MidiCommand {
    data class BankChange(val bank: Int) : MidiCommand()
    data class TrackVolume(val trackIdx: Int, val volume: Float) : MidiCommand()
    data class ParameterChange(val trackIdx: Int, val parameterId: Int, val value: Float) : MidiCommand()
    data class Transport(val action: String) : MidiCommand() // "PLAY", "STOP", "RECORD"
    object NextTrack : MidiCommand()
    object PreviousTrack : MidiCommand()
    data class SelectTrack(val trackIdx: Int) : MidiCommand()
    object ToggleMidiLearn : MidiCommand()
    data class MidiLearnSelect(val stripIdx: Int) : MidiCommand()
    data class MacroValue(val macroIdx: Int, val value: Float) : MidiCommand()
    data class NoteTriggered(val note: Int, val velocity: Int) : MidiCommand()
    data class StepToggle(val stepIdx: Int) : MidiCommand()
}

class MidiRouter(private val nativeLib: NativeLib, private val onCommand: (MidiCommand) -> Unit) {
    
    private var midiSender: ((ByteArray) -> Unit)? = null
    
    // Track active notes per track: InputNote -> TriggeredNote (remapped)
    // This ensures that when a Note Off comes, we release the SAME note we triggered,
    // even if the Root/Scale/Bank has changed in the meantime.
    private val trackActiveNotes = Array(8) { mutableMapOf<Int, Int>() }

    fun setMidiSender(sender: (ByteArray) -> Unit) {
        midiSender = sender
    }

    private fun log(msg: String) {
        // Platform-agnostic logging (Println or could be replaced by a proper logger)
        println("MidiRouter: $msg")
    }

    fun processMidiMessage(message: ByteArray, state: com.groovebox.GrooveboxState) {
        if (message.isEmpty()) return
        
        var i = 0
        while (i < message.size) {
            val status = message[i].toInt() and 0xFF
            val msgType = status and 0xF0
            val midiChan = (status and 0x0F) + 1
            
            // Determine message length
            val msgLen = when (status and 0xF0) {
                0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 3
                0xC0, 0xD0 -> 2
                0xF0 -> {
                    // SysEx: Find F7 or end of buffer
                    var sysExLen = 1
                    while (i + sysExLen < message.size && (message[i + sysExLen].toInt() and 0xFF) != 0xF7) {
                        sysExLen++
                    }
                    if (i + sysExLen < message.size) sysExLen++ // Include F7
                    sysExLen
                }
                else -> 1 // System Real-Time or Unknown
            }
            
            val remaining = message.size - i
            val actualLen = if (msgLen <= remaining) msgLen else remaining
            val currentMsg = message.copyOfRange(i, i + actualLen)
            
            processSingleMessage(currentMsg, state)
            i += actualLen
        }
    }

    private fun processSingleMessage(message: ByteArray, state: com.groovebox.GrooveboxState) {
        val status = message[0].toInt() and 0xFF
        val msgType = status and 0xF0
        val midiChan = (status and 0x0F) + 1
        val data1 = if (message.size > 1) message[1].toInt() and 0x7F else 0
        val data2 = if (message.size > 2) message[2].toInt() and 0x7F else 0
        
        // --- ECHO MODE (Device Test) ---
        if (state.echoModeActive) {
             if (msgType == 0x90 && data2 > 0) { // Note On > 0
                  // Echo back on SAME channel with MAX velocity
                  val echoData = byteArrayOf(status.toByte(), data1.toByte(), 127.toByte())
                  midiSender?.invoke(echoData)
             }
        }

        // Note On/Off: Check all tracks for channel matches
        if (msgType == 0x90 || msgType == 0x80) {
            state.tracks.forEachIndexed { trackIdx, track ->
                // --- MIDI TRIGGER LOGIC ---
                var shouldTrigger = (track.midiInChannel == 17 && trackIdx == state.selectedTrackIndex) || 
                                   (track.midiInChannel in 1..16 && track.midiInChannel == midiChan)

                // STUCK NOTE SAFETY
                if (!shouldTrigger && (msgType == 0x80 || (msgType == 0x90 && data2 == 0))) {
                    if (trackActiveNotes[trackIdx].containsKey(data1)) {
                         shouldTrigger = true
                    }
                }
                
                if (shouldTrigger && track.midiInChannel == 17 && trackIdx != state.selectedTrackIndex && data1 in 24..83) {
                    shouldTrigger = false
                }

                if (shouldTrigger && (msgType == 0x90 || (msgType == 0x99))) {
                    val isLowVelocity = data2 in 1..16
                    val isUiRange = data1 in 24..83
                    val isChannel10 = (status and 0x0F) == 9
                    if (isUiRange && (isLowVelocity || isChannel10)) shouldTrigger = false
                }

                if (shouldTrigger) {
                    var triggeredNote = data1

                    // REMAPPING BYPASS FOR EXTERNAL KEYBOARDS:
                    // Only apply scale remapping if the user is in a Grid mode AND 
                    // the velocity is high (UI pads send 127 usually, while keyboards vary).
                    // More importantly: avoid remapping if it's an Omni track unless specifically using UI range.
                    val isDrum = track.engineType == com.groovebox.EngineType.FM_DRUM || track.engineType == com.groovebox.EngineType.ANALOG_DRUM
                    
                    if (state.currentSequencerBank == 0 && trackIdx == state.selectedTrackIndex) {
                        val padIdx = when (state.gridMode) {
                            com.groovebox.GridMode.GRID_4X4 -> {
                                when (data1) {
                                    in 60..63 -> data1 - 60
                                    in 56..59 -> (data1 - 56) + 4
                                    in 52..55 -> (data1 - 52) + 8
                                    in 48..51 -> (data1 - 48) + 12
                                    else -> -1
                                }
                            }
                            com.groovebox.GridMode.GRID_6X6 -> if (data1 in 48..83) data1 - 48 else -1
                            else -> -1
                        }

                        // FIXED: Remapping priority - only apply if we have a valid padIdx 
                        // AND we believe it's intended for pad control (e.g. EMP16 on specific channel).
                        // For general keyboards (N25), we stay CHROMATIC but keep the OCTAVE shift.
                        if (padIdx != -1 && (midiChan == 10 || midiChan == 16)) {
                            triggeredNote = if (isDrum) {
                                60 + (padIdx % 16) 
                            } else {
                                val scaleNotes = com.groovebox.ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, 48)
                                scaleNotes.getOrElse(padIdx) { data1 }
                            }
                        } else {
                            val octaveOffset = state.rootNote - 48
                            triggeredNote = (data1 + octaveOffset).coerceIn(0, 127)
                        }
                    }
                    
                    if (msgType == 0x90 && data2 > 0) {
                        nativeLib.triggerNote(trackIdx, triggeredNote, data2)
                        trackActiveNotes[trackIdx][data1] = triggeredNote
                        if (trackIdx == state.selectedTrackIndex) onCommand(MidiCommand.NoteTriggered(triggeredNote, data2))
                    } else if (msgType == 0x80 || (msgType == 0x90 && data2 == 0)) {
                        val activeNote = trackActiveNotes[trackIdx].remove(data1) ?: triggeredNote
                        nativeLib.releaseNote(trackIdx, activeNote)
                        if (trackIdx == state.selectedTrackIndex) onCommand(MidiCommand.NoteTriggered(activeNote, 0))
                    }
                }
            }
            return
        }

        if (msgType == 0xB0) {
            handleCC(data1, data2, state)
        }

        if (msgType == 0xE0) {
            val pbValue = (data1 or (data2 shl 7))
            val normalizedPB = (pbValue - 8192) / 8192.0f
            nativeLib.setPitchBend(state.selectedTrackIndex, normalizedPB)
        }

        if (msgType == 0xC0) {
            // Donner N25: Program + Keys 0-9 usually send C0 00-09
            // Shift mapping to match keyboard labels: Key '1' (C0 01) -> Track 1 (Index 0)
            log("Program Change: $data1 (Mapped to ${data1 - 1})")
            if (data1 in 1..8) {
                onCommand(MidiCommand.SelectTrack(data1 - 1))
            } else if (data1 == 0) {
                // Potential fallback: map Key '0' to Track 1 or 8?
                // For now, just log it.
            }
        }

        if (msgType == 0xD0) {
            val pressure = data1 / 127.0f
            val trackIdx = state.selectedTrackIndex
            val track = state.tracks.getOrNull(trackIdx) ?: return
            val modParamId = track.padModTargetId
            if (modParamId >= 2000) nativeLib.setMacroValue(modParamId - 2000, pressure)
            else nativeLib.setParameter(trackIdx, modParamId, pressure)
            nativeLib.setPadMod(trackIdx, pressure)
        }
    }

    private fun handleCC(ccNumber: Int, value: Int, state: com.groovebox.GrooveboxState) {
        val normalizedValue = value / 127.0f

        // MIDI LEARN AUTO-SELECT
        if (state.midiLearnActive && state.midiLearnStep == 1) {
            when (ccNumber) {
                in 12..15 -> onCommand(MidiCommand.MidiLearnSelect(ccNumber - 12))
                in 70..73 -> onCommand(MidiCommand.MidiLearnSelect(ccNumber - 70 + 4))
            }
        }

        // Hardcoded EMP16 Mappings
        when (ccNumber) {
            in 24..55 -> {
                if (value > 0) {
                   onCommand(MidiCommand.StepToggle(ccNumber - 24))
                }
                return
            }
            in 12..15 -> {
                val stripIdx = ccNumber - 12
                onCommand(MidiCommand.ParameterChange(state.selectedTrackIndex, -100 - stripIdx, normalizedValue))
                applyRouting(stripIdx, normalizedValue, state)
            }
            in 70..73 -> {
                val knobIdx = ccNumber - 70
                onCommand(MidiCommand.ParameterChange(state.selectedTrackIndex, -200 - knobIdx, normalizedValue))
                applyRouting(knobIdx + 4, normalizedValue, state)
            }
            in 74..81 -> {
                val trackIdx = ccNumber - 74
                nativeLib.setTrackVolume(trackIdx, normalizedValue)
                onCommand(MidiCommand.TrackVolume(trackIdx, normalizedValue))
            }
            59 -> onCommand(MidiCommand.Transport("PLAY"))
            60 -> onCommand(MidiCommand.Transport("RECORD"))
            61 -> onCommand(MidiCommand.Transport("STOP"))
            62 -> onCommand(MidiCommand.NextTrack)
            63 -> onCommand(MidiCommand.ToggleMidiLearn)
            
            // All Sound Off (Donner N25 panic)
            120 -> nativeLib.panic()

            in 10..11 -> {
                 val trackIdx = ccNumber - 10
                 nativeLib.setTrackVolume(trackIdx, normalizedValue)
                 onCommand(MidiCommand.TrackVolume(trackIdx, normalizedValue))
            }
        }

        if (ccNumber in 20..23 && value > 0) {
            onCommand(MidiCommand.BankChange(ccNumber - 20))
        }
    }

    private fun applyRouting(stripIdx: Int, value: Float, state: com.groovebox.GrooveboxState) {
        val routing = if (stripIdx < 4) {
            state.stripRoutings.getOrNull(stripIdx)
        } else {
            state.knobRoutings.getOrNull(stripIdx - 4)
        }

        routing?.let {
            if (it.targetType == 1) { // Track Parameter
                nativeLib.setParameter(state.selectedTrackIndex, it.targetId, value)
            } else if (it.targetType == 2) { // Global FX
                nativeLib.setParameter(0, it.targetId, value)
            } else if (it.targetType == 3) {
                val macroIdx = it.targetId
                nativeLib.setMacroValue(macroIdx, value)
                onCommand(MidiCommand.MacroValue(macroIdx, value))
            }
        }
    }
}
