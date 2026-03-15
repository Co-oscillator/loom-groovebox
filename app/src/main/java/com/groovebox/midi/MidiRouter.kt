package com.groovebox.midi

import com.groovebox.NativeLib
import android.util.Log

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

    fun setMidiSender(sender: (ByteArray) -> Unit) {
        midiSender = sender
    }

    fun processMidiMessage(message: ByteArray, state: com.groovebox.GrooveboxState) {
        if (message.isEmpty()) return
        val status = message[0].toInt() and 0xFF
        val msgType = status and 0xF0
        val midiChan = (status and 0x0F) + 1
        val data1 = if (message.size > 1) message[1].toInt() and 0x7F else 0
        val data2 = if (message.size > 2) message[2].toInt() and 0x7F else 0
        
        // Log all incoming messages at ERROR level for max visibility
        val hex = message.joinToString(" ") { String.format("%02X", it) }
        Log.e("MidiRouter", "@@@ IN: $hex (Type: $msgType, Chan: $midiChan, D1: $data1, D2: $data2)")

        // --- ECHO MODE (Device Test) ---
        if (state.echoModeActive) {
             if (msgType == 0x90 && data2 > 0) { // Note On > 0
                  // Echo back on SAME channel with MAX velocity
                  val echoData = byteArrayOf(status.toByte(), data1.toByte(), 127.toByte())
                  midiSender?.invoke(echoData)
             }
        }

        // Note On/Off: Only target the currently selected track
        if (msgType == 0x90 || msgType == 0x80) {
        // Note On/Off: Check all tracks for channel matches
        if (msgType == 0x90 || msgType == 0x80) {
            state.tracks.forEachIndexed { i, track ->
                val listensOnAll = track.midiInChannel == 17
                val listensOnChan = track.midiInChannel == midiChan
                
                // "All" channels only triggers if this is the selected track
                // specific channel triggers regardless of selection
                val shouldTrigger = (listensOnAll && i == state.selectedTrackIndex) || listensOnChan
                
                if (shouldTrigger) {
                    var triggeredNote = data1

                    // EMP16 Bank A Pad Remapping (ONLY in Bank 0)
                    // Apply remapping only if this is the selected track and we are in Bank 0
                    // For background tracks (fixed channel), we likely want standard chromatic mapping
                    // But if the user plays the pads on channel 1, they expect the remapping.
                    // We'll apply it if it's the selected track OR if it's a drum engine.
                    
                    val isDrum = track.engineType == com.groovebox.EngineType.FM_DRUM || track.engineType == com.groovebox.EngineType.ANALOG_DRUM
                    
                    if (state.currentSequencerBank == 0 && i == state.selectedTrackIndex) {
                        val padIdx = when (data1) {
                            in 60..63 -> data1 - 60
                            in 56..59 -> (data1 - 56) + 4
                            in 52..55 -> (data1 - 52) + 8
                            in 48..51 -> (data1 - 48) + 12
                            else -> -1
                        }
                        if (padIdx != -1) {
                            triggeredNote = if (isDrum) {
                                60 + (padIdx % 8)
                            } else {
                                val scaleNotes = com.groovebox.ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, 24)
                                scaleNotes.getOrElse(padIdx) { data1 }
                            }
                        }
                    } else if (state.currentSequencerBank != 0 && i == state.selectedTrackIndex && data1 in 41..95) {
                         // Ignore potentially conflicting pad ranges for selected track in other banks
                         return@forEachIndexed
                    }
                    
                    if (msgType == 0x90 && data2 > 0) {
                        nativeLib.triggerNote(i, triggeredNote, data2)
                        if (i == state.selectedTrackIndex) {
                             onCommand(MidiCommand.NoteTriggered(triggeredNote, data2))
                        }
                    } else if (msgType == 0x80 || (msgType == 0x90 && data2 == 0)) {
                        nativeLib.releaseNote(i, triggeredNote)
                        if (i == state.selectedTrackIndex) {
                             onCommand(MidiCommand.NoteTriggered(triggeredNote, 0))
                        }
                    }
                }
            }
            return
        }
        }

        if (msgType == 0xB0) { // Control Change (CC)
            handleCC(data1, data2, state)
        }

        // --- AFTERTOUCH (Channel Pressure 0xD0) → Y-axis Modulation ---
        if (msgType == 0xD0) {
            val pressure = data1 / 127.0f  // 0xD0 is 2-byte: [status, pressure]
            val trackIdx = state.selectedTrackIndex
            val track = state.tracks.getOrNull(trackIdx) ?: return
            val modParamId = track.padModTargetId

            // Route to the same target as pad Y-axis
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
            // Sequencing CCs (Pads 17-48)
            in 24..55 -> {
                if (value > 0) {
                   onCommand(MidiCommand.StepToggle(ccNumber - 24))
                }
                return
            }
            // Faders 1-4 (Strips 1-4)
            in 12..15 -> {
                val stripIdx = ccNumber - 12
                onCommand(MidiCommand.ParameterChange(state.selectedTrackIndex, -100 - stripIdx, normalizedValue)) // Special ID for UI sync
                applyRouting(stripIdx, normalizedValue, state)
            }
            // Knobs 1-4
            in 70..73 -> {
                val knobIdx = ccNumber - 70
                onCommand(MidiCommand.ParameterChange(state.selectedTrackIndex, -200 - knobIdx, normalizedValue)) // Special ID for UI sync
                applyRouting(knobIdx + 4, normalizedValue, state)
            }
            // Track Volumes 1-8
            in 74..81 -> {
                val trackIdx = ccNumber - 74
                nativeLib.setTrackVolume(trackIdx, normalizedValue)
                onCommand(MidiCommand.TrackVolume(trackIdx, normalizedValue))
            }
            // Transport
            59 -> onCommand(MidiCommand.Transport("PLAY"))
            60 -> onCommand(MidiCommand.Transport("RECORD"))
            61 -> onCommand(MidiCommand.Transport("STOP"))
            // Next Track (Trigger on every toggle event to support latching buttons)
            62 -> onCommand(MidiCommand.NextTrack)
            // MIDI Learn Toggle
            63 -> onCommand(MidiCommand.ToggleMidiLearn)
            
            // Legacy/Generic mappings (CC 10-17)
            in 10..11 -> { // 10 and 11 only now as 12-15 are overridden
                 val trackIdx = ccNumber - 10
                 nativeLib.setTrackVolume(trackIdx, normalizedValue)
                 onCommand(MidiCommand.TrackVolume(trackIdx, normalizedValue))
            }
        }

        // EMP16 Bank Buttons A, B, C, D (Assuming CC 20-23)
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
                // Target is a Macro
                val macroIdx = it.targetId
                nativeLib.setMacroValue(macroIdx, value)
                onCommand(MidiCommand.MacroValue(macroIdx, value))
            }
        }
    }
}
