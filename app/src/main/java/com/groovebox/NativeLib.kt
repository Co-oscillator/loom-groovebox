package com.groovebox

class NativeLib {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    external fun init()
    external fun start()
    external fun stop()
    external fun setTrackVolume(trackIndex: Int, volume: Float)
    external fun setEngineType(trackIndex: Int, type: Int)
    external fun setTempo(bpm: Float)
    external fun setPatternLength(length: Int)
    external fun setPlaying(playing: Boolean)
    external fun triggerNote(trackIndex: Int, note: Int, velocity: Int)
    external fun releaseNote(trackIndex: Int, note: Int)
    external fun setStep(trackIndex: Int, stepIndex: Int, active: Boolean, notes: IntArray, velocity: Float, ratchet: Int, punch: Boolean, probability: Float, gate: Float, isSkipped: Boolean)
    external fun setSequencerConfig(trackIndex: Int, numPages: Int, stepsPerPage: Int)
    external fun setRouting(destTrack: Int, sourceTrack: Int, source: Int, dest: Int, amount: Float, destParamId: Int = -1)
    external fun setParameter(trackIndex: Int, parameterId: Int, value: Float)
    external fun setParameterPreview(trackIndex: Int, parameterId: Int, value: Float)
    external fun setSwing(swing: Float)
    external fun setPlaybackDirection(trackIndex: Int, direction: Int) // 0: Forward, 1: Backward
    external fun setIsRandomOrder(trackIndex: Int, isRandom: Boolean)
    external fun setIsJumpMode(trackIndex: Int, isJump: Boolean)
    external fun setSelectedFmDrumInstrument(trackIndex: Int, drumIndex: Int)
    external fun jumpToStep(stepIndex: Int)
    external fun setParameterLock(trackIndex: Int, stepIndex: Int, parameterId: Int, value: Float)
    external fun clearParameterLocks(trackIndex: Int, stepIndex: Int)
    external fun loadFmPreset(trackIndex: Int, presetId: Int)
    external fun setIsRecording(isRecording: Boolean)
    external fun setResampling(isResampling: Boolean)
    external fun loadSample(trackIndex: Int, path: String)
    external fun loadWavetable(trackIndex: Int, path: String)
    external fun loadDefaultWavetable(trackIndex: Int)
    external fun loadSoundFont(trackIndex: Int, path: String)
    external fun setSoundFontPreset(trackIndex: Int, presetIndex: Int)
    external fun getSoundFontPresetCount(trackIndex: Int): Int
    external fun getSoundFontPresetName(trackIndex: Int, presetIndex: Int): String
    external fun setSoundFontMapping(trackIndex: Int, knobIndex: Int, paramId: Int)
    external fun saveSample(trackIndex: Int, path: String)
    external fun restorePresets()
    external fun restoreTrackPreset(trackIndex: Int)
    external fun trimSample(trackIndex: Int)
    external fun getCurrentStep(trackIndex: Int, drumIndex: Int = -1): Int
    external fun getStepActive(trackIndex: Int, stepIndex: Int, drumIndex: Int = -1): Boolean
    external fun setArpConfig(trackIndex: Int, mode: Int, octaves: Int, inversion: Int, isLatched: Boolean, isMutated: Boolean, rhythms: Array<BooleanArray>, sequence: IntArray)
    external fun setChordProgConfig(trackIndex: Int, enabled: Boolean, mood: Int, complexity: Int)
    external fun setScaleConfig(rootNote: Int, intervals: IntArray)
    external fun getGranularPlayheads(trackIndex: Int): FloatArray
    external fun startRecordingSample(trackIndex: Int)
    external fun stopRecordingSample(trackIndex: Int)
    external fun getWaveform(trackIndex: Int): FloatArray?
    external fun setSlices(trackIndex: Int, starts: IntArray, ends: IntArray)
    external fun resetSampler(trackIndex: Int)
    external fun getSlicePoints(trackIndex: Int): FloatArray
    external fun clearSequencer(trackIndex: Int)
    external fun setMasterVolume(volume: Float)
    external fun panic()
    external fun getActiveNoteMask(trackIndex: Int): Int
    external fun getCpuLoad(): Float

    // Routing / Macro Controls
    external fun setGenericLfoParam(lfoIndex: Int, paramId: Int, value: Float)
    external fun setMacroValue(macroIndex: Int, value: Float)
    external fun setMacroSource(macroIndex: Int, sourceType: Int, sourceIndex: Int)
    external fun setFxChain(sourceFx: Int, destFx: Int)
    external fun fetchMidiEvents(): IntArray
    
    // Persistence
    external fun setAppDataDir(path: String)
    external fun loadAppState()
    external fun getLastSamplePath(trackIndex: Int): String

    // Audio Export
    external fun exportAudio(numRepeats: Int, path: String)
    external fun setArpRate(trackIndex: Int, rate: Float, divisionMode: Int)
    external fun setClockMultiplier(trackIndex: Int, multiplier: Float)
    external fun setFilterMode(trackIndex: Int, mode: Int) // 0=LP, 1=HP, 2=BP
    
    external fun getAllTrackParameters(trackIndex: Int): FloatArray
    external fun getAllStepActiveStates(trackIndex: Int): BooleanArray
    external fun getRecordedSampleData(trackIndex: Int, targetSampleRate: Float): FloatArray?
    external fun setInputDevice(deviceId: Int)
    external fun setRecordingLocked(locked: Boolean)
    external fun setTrackActive(trackIndex: Int, active: Boolean)
    external fun setTrackPan(trackIndex: Int, pan: Float)
}
