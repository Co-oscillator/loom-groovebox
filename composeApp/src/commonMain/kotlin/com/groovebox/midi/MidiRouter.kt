package com.groovebox.midi

import com.groovebox.NativeLib

sealed class MidiCommand {
    data class BankChange(val bank: Int) : MidiCommand()
    data class TrackVolume(val trackIdx: Int, val volume: Float) : MidiCommand()
    data class ParameterChange(val trackIdx: Int, val parameterId: Int, val value: Float) : MidiCommand()
    data class Transport(val action: String) : MidiCommand() // "PLAY", "STOP", "RECORD"
    object NextTrack : MidiCommand()
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
            state.tracks.forEachIndexed { i, track ->
                // --- MIDI TRIGGER LOGIC ---
                // 1. Omni Mode (17): Only triggers if this is the selected track.
                // 2. Specific Channel (1-16): Triggers even if unselected (Background MIDI).
                var shouldTrigger = (track.midiInChannel == 17 && i == state.selectedTrackIndex) || 
                                   (track.midiInChannel in 1..16 && track.midiInChannel == midiChan)
                
                // GHOST NOTE ISOLATION (UI Pads):
                // Only apply isolation to Omni tracks or selected tracks to prevent UI crosstalk.
                // If a user MANUALLY set a channel (1-16), we bypass isolation to give them full control.
                if (shouldTrigger && track.midiInChannel == 17 && i != state.selectedTrackIndex && data1 in 24..83) {
                    shouldTrigger = false
                }

                // GHOST NOTE FILTER (LED Loopback):
                // 1. Velocity 1-16 on any channel is reserved for LED color updates.
                // 2. Any activity on Channel 10 (0x99) for the UI note range (24-83) is 
                //    almost certainly loopback from EmpledManager background updates.
                if (shouldTrigger && (msgType == 0x90 || msgType == 0x99)) {
                    val isLowVelocity = data2 in 1..16
                    val isUiRange = data1 in 24..83
                    val isChannel10 = (status and 0x0F) == 9
                    
                    if (isUiRange && (isLowVelocity || isChannel10)) {
                        shouldTrigger = false
                    }
                }

                if (shouldTrigger) {
                    var triggeredNote = data1

                    // EMP16 Bank A / 6x6 Remapping (ONLY in Bank 0)
                    val isDrum = track.engineType == com.groovebox.EngineType.FM_DRUM || track.engineType == com.groovebox.EngineType.ANALOG_DRUM
                    
                    if (state.currentSequencerBank == 0 && i == state.selectedTrackIndex) {
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

                        if (padIdx != -1) {
                            triggeredNote = if (isDrum) {
                                60 + (padIdx % 16) 
                            } else {
                                val scaleNotes = com.groovebox.ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, 48)
                                scaleNotes.getOrElse(padIdx) { data1 }
                            }
                        }
                    }
                    
                    if (msgType == 0x90 && data2 > 0) {
                        nativeLib.triggerNote(i, triggeredNote, data2)
                        trackActiveNotes[i][data1] = triggeredNote
                        if (i == state.selectedTrackIndex) {
                             onCommand(MidiCommand.NoteTriggered(triggeredNote, data2))
                        }
                    } else if (msgType == 0x80 || (msgType == 0x90 && data2 == 0)) {
                        val activeNote = trackActiveNotes[i].remove(data1) ?: triggeredNote
                        nativeLib.releaseNote(i, activeNote)
                        if (i == state.selectedTrackIndex) {
                             onCommand(MidiCommand.NoteTriggered(activeNote, 0))
                        }
                    }
                }
            }
            return
        }

        if (msgType == 0xB0) { // Control Change (CC)
            handleCC(data1, data2, state)
        }

        // --- AFTERTOUCH (Channel Pressure 0xD0) → Y-axis Modulation ---
        if (msgType == 0xD0) {
            val pressure = data1 / 127.0f
            val trackIdx = state.selectedTrackIndex
            val track = state.tracks.getOrNull(trackIdx) ?: return
            val modParamId = track.padModTargetId

            if (modParamId >= 2000) {
                nativeLib.setMacroValue(modParamId - 2000, pressure)
            } else {
                nativeLib.setParameter(trackIdx, modParamId, pressure)
            }
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
