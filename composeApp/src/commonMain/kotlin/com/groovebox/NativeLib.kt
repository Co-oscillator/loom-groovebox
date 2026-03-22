package com.groovebox

expect class NativeLib() {
    fun init()
    fun start()
    fun stop()
    fun setTrackVolume(trackIndex: Int, volume: Float)
    fun setEngineType(trackIndex: Int, type: Int)
    fun setTempo(bpm: Float)
    fun setPatternLength(trackIndex: Int, length: Int)
    fun setPlaying(playing: Boolean)
    fun triggerNote(trackIndex: Int, note: Int, velocity: Int)
    fun releaseNote(trackIndex: Int, note: Int)
    fun setStep(trackIndex: Int, stepIndex: Int, active: Boolean, notes: IntArray, velocity: Float, ratchet: Int, punch: Boolean, probability: Float, gate: Float, isSkipped: Boolean, subStepOffset: Float = 0.0f, noteOffsets: FloatArray? = null, noteVelocities: FloatArray? = null)
    fun setSequencerConfig(trackIndex: Int, numPages: Int, stepsPerPage: Int)
    fun setRouting(destTrack: Int, sourceTrack: Int, source: Int, dest: Int, amount: Float, destParamId: Int = -1)
    fun setParameter(trackIndex: Int, parameterId: Int, value: Float)
    fun setParameterPreview(trackIndex: Int, parameterId: Int, value: Float)
    fun setSwing(swing: Float)
    fun setTrackHumanize(trackIndex: Int, amount: Float)
    fun setPlaybackDirection(trackIndex: Int, direction: Int)
    fun setIsRandomOrder(trackIndex: Int, isRandom: Boolean)
    fun setIsJumpMode(trackIndex: Int, isJump: Boolean)
    fun setSelectedFmDrumInstrument(trackIndex: Int, drumIndex: Int)
    fun jumpToStep(stepIndex: Int)
    fun setParameterLock(trackIndex: Int, stepIndex: Int, parameterId: Int, value: Float)
    fun clearParameterLocks(trackIndex: Int, stepIndex: Int)
    fun loadFmPreset(trackIndex: Int, presetId: Int)
    fun setIsRecording(isRecording: Boolean)
    fun setResampling(isResampling: Boolean)
    fun setRecordingSource(source: Int)
    fun loadSample(trackIndex: Int, path: String)
    fun loadWavetable(trackIndex: Int, path: String)
    fun loadDefaultWavetable(trackIndex: Int)
    fun loadSoundFont(trackIndex: Int, path: String)
    fun setSoundFontPreset(trackIndex: Int, presetIndex: Int)
    fun getSoundFontPresetCount(trackIndex: Int): Int
    fun getSoundFontPresetName(trackIndex: Int, presetIndex: Int): String
    fun setSoundFontMapping(trackIndex: Int, knobIndex: Int, paramId: Int)
    fun saveSample(trackIndex: Int, path: String)
    fun restorePresets()
    fun restoreTrackPreset(trackIndex: Int)
    fun saveTrackPreset(trackIndex: Int)
    fun saveTrackPresetToPath(trackIndex: Int, path: String)
    fun trimSample(trackIndex: Int)
    fun getCurrentStep(trackIndex: Int, drumIndex: Int = -1): Int
    fun getStepActive(trackIndex: Int, stepIndex: Int, drumIndex: Int = -1): Boolean
    fun getStepNotes(trackIndex: Int, stepIndex: Int, drumIndex: Int = -1): IntArray
    fun getStepVelocity(trackIndex: Int, stepIndex: Int, drumIndex: Int = -1): Float
    fun getStepSubStep(trackIndex: Int, stepIndex: Int, drumIndex: Int = -1): Float
    fun setArpConfig(trackIndex: Int, mode: Int, octaves: Int, inversion: Int, isLatched: Boolean, isMutated: Boolean, rhythms: Array<BooleanArray>, sequence: IntArray, gateLengths: FloatArray, probability: Float, weird: Float)
    fun setChordProgConfig(trackIndex: Int, enabled: Boolean, mood: Int, complexity: Int)
    fun setScaleConfig(rootNote: Int, intervals: IntArray)
    fun getGranularPlayheads(trackIndex: Int): FloatArray
    fun startRecordingSample(trackIndex: Int)
    fun stopRecordingSample(trackIndex: Int)
    fun getWaveform(trackIndex: Int): FloatArray?
    fun setSlices(trackIndex: Int, starts: IntArray, ends: IntArray)
    fun resetSampler(trackIndex: Int)
    fun getSlicePoints(trackIndex: Int): FloatArray
    fun clearSequencer(trackIndex: Int)
    fun setMasterVolume(volume: Float)
    fun setTrackTranspose(trackIndex: Int, semitones: Int)
    fun panic()
    fun getActiveNoteMask(trackIndex: Int): Long
    fun setPitchBend(trackIndex: Int, value: Float)
    fun setPadMod(trackIndex: Int, value: Float)
    fun getCpuLoad(): Float
    fun setGenericLfoParam(lfoIndex: Int, paramId: Int, value: Float)
    fun setMacroValue(macroIndex: Int, value: Float)
    fun setMacroSource(macroIndex: Int, sourceType: Int, sourceIndex: Int, sourceTrackIndex: Int = -1)
    fun setFxChain(sourceFx: Int, destFx: Int)
    fun fetchMidiEvents(): IntArray
    fun fetchEngineEvents(): IntArray
    fun setAppDataDir(path: String)
    fun loadAppState()
    fun getLastSamplePath(trackIndex: Int): String
    fun exportAudio(numRepeats: Int, path: String)
    fun setArpRate(trackIndex: Int, rate: Float, divisionMode: Int)
    fun setArpStrum(trackIndex: Int, strum: Float)
    fun setClockMultiplier(trackIndex: Int, multiplier: Float)
    fun setFilterMode(trackIndex: Int, mode: Int)
    fun getAllTrackParameters(trackIndex: Int): FloatArray
    fun getAllStepActiveStates(trackIndex: Int): BooleanArray
    fun getRecordedSampleData(trackIndex: Int, targetSampleRate: Float): FloatArray?
    fun setInputDevice(deviceId: Int)
    fun setRecordingLocked(locked: Boolean)
    fun setTrackActive(trackIndex: Int, active: Boolean)
    fun setTrackPan(trackIndex: Int, pan: Float)
    fun setTrackMute(trackIndex: Int, muted: Boolean)
    fun setTrackSolo(trackIndex: Int, soloed: Boolean)
    fun getFxSends(trackIndex: Int): FloatArray
    fun getFxMix(trackIndex: Int): FloatArray
    fun getFxChain(): IntArray
    fun setSlicePosition(trackIndex: Int, sliceIndex: Int, position: Float)
    fun setSidechainConfig(trackIndex: Int, drumIndex: Int)
    fun pushSystemAudioSamples(data: FloatArray)
    fun getSampleLength(trackIndex: Int): Long
    fun setChainEnabled(trackIndex: Int, enabled: Boolean)
    fun setChainLength(trackIndex: Int, length: Int)
    fun setChainSlot(trackIndex: Int, slotIndex: Int, laneIndex: Int, steps: Array<StepState>)
    fun getIsPlaying(): Boolean
    fun getIsRecording(): Boolean
    fun getIsRecordingSample(): Boolean
}
