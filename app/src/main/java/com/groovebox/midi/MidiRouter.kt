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
        if (message.size < 3) return
        val status = message[0].toInt() and 0xFF
        val msgType = status and 0xF0
        val midiChan = (status and 0x0F) + 1
        val data1 = message[1].toInt() and 0x7F
        val data2 = message[2].toInt() and 0x7F
        
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
            val trackIdx = state.selectedTrackIndex
            val track = state.tracks.getOrNull(trackIdx)
            if (track != null) {
                val listensOnAll = track.midiInChannel == 17
                val listensOnChan = track.midiInChannel == midiChan
                
                if (listensOnAll || listensOnChan) {
                    var triggeredNote = data1

                    // EMP16 Bank A Pad Remapping (ONLY in Bank 0)
                    if (state.currentSequencerBank == 0) {
                        val padIdx = when (data1) {
                            in 60..63 -> data1 - 60
                            in 56..59 -> (data1 - 56) + 4
                            in 52..55 -> (data1 - 52) + 8
                            in 48..51 -> (data1 - 48) + 12
                            else -> -1
                        }
                        if (padIdx != -1) {
                            triggeredNote = if (track.engineType == com.groovebox.EngineType.FM_DRUM || track.engineType == com.groovebox.EngineType.ANALOG_DRUM) {
                                60 + (padIdx % 8)
                            } else {
                                val scaleNotes = com.groovebox.ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, 24)
                                scaleNotes.getOrElse(padIdx) { data1 }
                            }
                        }
                    } else {
                        // In Bank B or C, we ignore notes from the pads (they will send CC instead)
                        // This prevents the "high pitched notes" when sequencing.
                        if (data1 in 41..95) return 
                    }
                    
                    if (msgType == 0x90 && data2 > 0) {
                        nativeLib.triggerNote(trackIdx, triggeredNote, data2)
                        onCommand(MidiCommand.NoteTriggered(triggeredNote, data2))
                    } else if (msgType == 0x80 || (msgType == 0x90 && data2 == 0)) {
                        nativeLib.releaseNote(trackIdx, triggeredNote)
                        onCommand(MidiCommand.NoteTriggered(triggeredNote, 0))
                    }
                    return
                }
            }
        }

        if (msgType == 0xB0) { // Control Change (CC)
            handleCC(data1, data2, state)
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
