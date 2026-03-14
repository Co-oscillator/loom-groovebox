#include "AudioEngine.h"
#include <jni.h>

static AudioEngine *engine = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nInit(JNIEnv *env, jobject thiz) {
  if (engine != nullptr) {
    AudioEngine *oldEngine = engine;
    engine = nullptr; // Null out global pointer BEFORE deleting to avoid race
                      // conditions
    delete oldEngine;
  }
  engine = new AudioEngine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nStart(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nStop(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetTempo(JNIEnv *env, jobject thiz, jfloat bpm) {
  if (engine)
    engine->setTempo(bpm);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetPlaying(
    JNIEnv *env, jobject thiz, jboolean playing) {
  if (engine)
    engine->setPlaying(playing);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetStep(
    JNIEnv *env, jobject thiz, jint track_index, jint step_index,
    jboolean active, jintArray notes, jfloat velocity, jint ratchet,
    jboolean punch, jfloat probability, jfloat gate, jboolean is_skipped,
    jfloat sub_step_offset, jfloatArray note_offsets,
    jfloatArray note_velocities) {
  if (engine) {
    std::vector<int> noteVec;
    std::vector<float> offsetVec;
    std::vector<float> velVec;
    bool hasNotes = false;

    if (notes != nullptr) {
      jsize len = env->GetArrayLength(notes);
      if (len > 0) {
        hasNotes = true;
        jint *elems = env->GetIntArrayElements(notes, nullptr);
        int cap = (len > 16) ? 16 : len; // Strict cap
        for (int i = 0; i < cap; ++i)
          noteVec.push_back(elems[i]);
        env->ReleaseIntArrayElements(notes, elems, JNI_ABORT);

        // Parse Offsets
        if (note_offsets != nullptr &&
            env->GetArrayLength(note_offsets) >= cap) {
          jfloat *offElems = env->GetFloatArrayElements(note_offsets, nullptr);
          for (int i = 0; i < cap; ++i)
            offsetVec.push_back(offElems[i]);
          env->ReleaseFloatArrayElements(note_offsets, offElems, JNI_ABORT);
        }

        // Parse Velocities
        if (note_velocities != nullptr &&
            env->GetArrayLength(note_velocities) >= cap) {
          jfloat *velElems =
              env->GetFloatArrayElements(note_velocities, nullptr);
          for (int i = 0; i < cap; ++i)
            velVec.push_back(velElems[i]);
          env->ReleaseFloatArrayElements(note_velocities, velElems, JNI_ABORT);
        }
      }
    }
    // Safety: If no notes are provided, the step CANNOT be active.
    // This prevents "ghost notes" from empty triggers.
    bool safeActive = (active && hasNotes);

    // Deep Sanitization: Clamp all inputs to valid ranges
    float safeVelocity = std::max(0.0f, std::min(1.0f, velocity));
    float safeGate = std::max(0.0f, std::min(8.0f, gate));
    float safeProb = std::max(0.0f, std::min(1.0f, probability));
    int safeRatchet = std::max(1, std::min(16, ratchet));
    float safeSubStep = std::max(0.0f, std::min(1.0f, sub_step_offset));

    // Clamp notes to MIDI range 0-127
    for (int &n : noteVec) {
      if (n < 0)
        n = 0;
      else if (n > 127)
        n = 127;
    }

    engine->setStep(track_index, step_index, safeActive, noteVec, safeVelocity,
                    safeRatchet, punch, safeProb, safeGate, is_skipped,
                    safeSubStep, offsetVec, velVec);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetSequencerConfig(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jint num_pages,
                                                jint steps_per_page) {
  if (engine)
    engine->setSequencerConfig(track_index, num_pages, steps_per_page);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetTrackVolume(
    JNIEnv *env, jobject thiz, jint track_index, jfloat volume) {
  if (engine)
    engine->setTrackVolume(track_index, volume);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetEngineType(
    JNIEnv *env, jobject thiz, jint track_index, jint type) {
  if (engine)
    engine->setEngineType(track_index, type);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nTriggerNote(
    JNIEnv *env, jobject thiz, jint track_index, jint note, jint velocity) {
  if (engine)
    engine->triggerNote(track_index, note, velocity);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nReleaseNote(
    JNIEnv *env, jobject thiz, jint track_index, jint note) {
  if (engine)
    engine->releaseNote(track_index, note);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetArpRate(
    JNIEnv *env, jobject thiz, jint track_index, jfloat rate,
    jint division_mode) {
  if (engine)
    engine->setArpRate(track_index, rate, division_mode);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetArpStrum(
    JNIEnv *env, jobject thiz, jint track_index, jfloat strum) {
  if (engine)
    engine->setArpStrum(track_index, strum);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetParameter(
    JNIEnv *env, jobject thiz, jint track_index, jint parameter_id,
    jfloat value) {
  if (engine)
    engine->setParameter(track_index, parameter_id, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetParameterPreview(JNIEnv *env, jobject thiz,
                                                 jint track_index,
                                                 jint parameter_id,
                                                 jfloat value) {
  if (engine)
    engine->setParameterPreview(track_index, parameter_id, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetRouting(
    JNIEnv *env, jobject thiz, jint dest_track, jint source_track, jint source,
    jint dest, jfloat amount, jint dest_param_id) {
  if (engine)
    engine->setRouting(dest_track, source_track, source, dest, amount,
                       dest_param_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetSwing(JNIEnv *env, jobject thiz, jfloat swing) {
  if (engine)
    engine->setSwing(swing);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetTrackHumanize(
    JNIEnv *env, jobject thiz, jint track_index, jfloat amount) {
  if (engine)
    engine->setTrackHumanize(track_index, amount);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetPlaybackDirection(JNIEnv *env, jobject thiz,
                                                  jint track_index,
                                                  jint direction) {
  if (engine)
    engine->setPlaybackDirection(track_index, direction);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nExportAudio(
    JNIEnv *env, jobject thiz, jint num_repeats, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->renderToWav(num_repeats, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetIsRandomOrder(
    JNIEnv *env, jobject thiz, jint track_index, jboolean is_random) {
  if (engine)
    engine->setIsRandomOrder(track_index, is_random);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetIsJumpMode(
    JNIEnv *env, jobject thiz, jint track_index, jboolean is_jump) {
  if (engine)
    engine->setIsJumpMode(track_index, is_jump);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetParameterLock(
    JNIEnv *env, jobject thiz, jint track_index, jint step_index,
    jint parameter_id, jfloat value) {
  if (engine)
    engine->setParameterLock(track_index, step_index, parameter_id, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nClearParameterLocks(JNIEnv *env, jobject thiz,
                                                 jint track_index,
                                                 jint step_index) {
  if (engine)
    engine->clearParameterLocks(track_index, step_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetIsRecording(
    JNIEnv *env, jobject thiz, jboolean is_recording) {
  if (engine)
    engine->setIsRecording(is_recording);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetResampling(
    JNIEnv *env, jobject thiz, jboolean is_resampling) {
  if (engine)
    engine->setResampling(is_resampling);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetRecordingSource(JNIEnv *env, jobject thiz,
                                                jint source) {
  if (engine)
    engine->setRecordingSource(source);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nPushSystemAudioSamples(JNIEnv *env, jobject thiz,
                                                    jfloatArray data) {
  if (engine && data != nullptr) {
    jsize len = env->GetArrayLength(data);
    jfloat *elems = env->GetFloatArrayElements(data, nullptr);
    if (elems != nullptr) {
      engine->pushSystemAudioSamples(elems, len);
      env->ReleaseFloatArrayElements(data, elems, JNI_ABORT);
    }
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetPatternLength(
    JNIEnv *env, jobject thiz, jint length) {
  if (engine)
    engine->setPatternLength(length);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetSelectedFmDrumInstrument(JNIEnv *env,
                                                         jobject thiz,
                                                         jint track_index,
                                                         jint drum_index) {
  if (engine)
    engine->setSelectedFmDrumInstrument(track_index, drum_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nJumpToStep(
    JNIEnv *env, jobject thiz, jint step_index) {
  if (engine)
    engine->jumpToStep(step_index);
}

extern "C" JNIEXPORT jint JNICALL Java_com_groovebox_NativeLib_nGetCurrentStep(
    JNIEnv *env, jobject thiz, jint track_index, jint drum_index) {
  if (engine)
    return engine->getCurrentStep(track_index, drum_index);
  return 0;
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetArpConfig(
    JNIEnv *env, jobject thiz, jint track_index, jint mode, jint octaves,
    jint inversion, jboolean is_latched, jboolean is_mutated,
    jobjectArray rhythms, jintArray sequence, jfloatArray gate_lengths) {
  if (engine) {
    std::vector<std::vector<bool>> rhythmVecs;
    if (rhythms != nullptr) {
      jsize rows = env->GetArrayLength(rhythms);
      for (int i = 0; i < rows; ++i) {
        jbooleanArray rowArr =
            (jbooleanArray)env->GetObjectArrayElement(rhythms, i);
        std::vector<bool> rowVec;
        if (rowArr) {
          jsize len = env->GetArrayLength(rowArr);
          jboolean *elems = env->GetBooleanArrayElements(rowArr, nullptr);
          for (int j = 0; j < len; ++j)
            rowVec.push_back(elems[j]);
          env->ReleaseBooleanArrayElements(rowArr, elems, JNI_ABORT);
        }
        rhythmVecs.push_back(rowVec);
      }
    }

    std::vector<int> seqVec;
    if (sequence != nullptr) {
      jsize len = env->GetArrayLength(sequence);
      jint *elems = env->GetIntArrayElements(sequence, nullptr);
      for (int i = 0; i < len; ++i)
        seqVec.push_back(elems[i]);
      env->ReleaseIntArrayElements(sequence, elems, JNI_ABORT);
    }

    std::vector<float> gateVec;
    if (gate_lengths != nullptr) {
      jsize len = env->GetArrayLength(gate_lengths);
      jfloat *elems = env->GetFloatArrayElements(gate_lengths, nullptr);
      for (int i = 0; i < len; ++i)
        gateVec.push_back(elems[i]);
      env->ReleaseFloatArrayElements(gate_lengths, elems, JNI_ABORT);
    }

    engine->setArpConfig(track_index, mode, octaves, inversion, is_latched,
                         is_mutated, rhythmVecs, seqVec, gateVec);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nPanic(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->panic();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_nGetGranularPlayheads(JNIEnv *env, jobject thiz,
                                                  jint track_index) {
  const int MAX_GRAINS = 64;
  jfloatArray result = env->NewFloatArray(MAX_GRAINS * 2);
  if (engine) {
    GranularEngine::PlayheadInfo info[MAX_GRAINS];
    // Initialize with -1.0f (inactive) to avoid garbage data
    for (int i = 0; i < MAX_GRAINS; ++i) {
      info[i].pos = -1.0f;
      info[i].vol = 0.0f;
    }

    engine->getGranularPlayheads(track_index, info, MAX_GRAINS);

    jfloat buffer[MAX_GRAINS * 2];
    for (int i = 0; i < MAX_GRAINS; ++i) {
      buffer[i * 2] = info[i].pos;
      buffer[i * 2 + 1] = info[i].vol;
    }
    env->SetFloatArrayRegion(result, 0, MAX_GRAINS * 2, buffer);
  }
  return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSaveSample(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->saveSample(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nLoadSample(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->loadSample(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nStartRecordingSample(JNIEnv *env, jobject thiz,
                                                  jint track_index) {
  if (engine)
    engine->startRecordingSample(track_index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nStopRecordingSample(JNIEnv *env, jobject thiz,
                                                 jint track_index) {
  if (engine) {
    engine->stopRecordingSample(track_index);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetRecordingLocked(JNIEnv *env, jobject thiz,
                                                jboolean locked) {
  if (engine)
    engine->setRecordingLocked(locked);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_groovebox_NativeLib_nGetSampleLength(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine) {
    return (jlong)engine->getSampleLength(track_index);
  }
  return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_nGetWaveform(JNIEnv *env, jobject thiz,
                                         jint track_index) {
  if (engine) {
    std::vector<float> waveform = engine->getSamplerWaveform(track_index, 500);
    jfloatArray result = env->NewFloatArray(waveform.size());
    env->SetFloatArrayRegion(result, 0, waveform.size(), waveform.data());
    return result;
  }
  return env->NewFloatArray(0);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_nGetFxSends(JNIEnv *env, jobject thiz,
                                        jint track_index) {
  if (engine) {
    float buffer[17];
    engine->getFxSends(track_index, buffer);
    jfloatArray result = env->NewFloatArray(17);
    env->SetFloatArrayRegion(result, 0, 17, buffer);
    return result;
  }
  return env->NewFloatArray(0);
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_groovebox_NativeLib_nGetFxMix(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine) {
    float buffer[17];
    engine->getFxMix(track_index, buffer);
    jfloatArray result = env->NewFloatArray(17);
    env->SetFloatArrayRegion(result, 0, 17, buffer);
    return result;
  }
  return env->NewFloatArray(0);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetSlices(
    JNIEnv *env, jobject thiz, jint track_index, jintArray starts,
    jintArray ends) {
  if (engine) {
    jint *pStarts = env->GetIntArrayElements(starts, nullptr);
    jint *pEnds = env->GetIntArrayElements(ends, nullptr);
    jsize len = env->GetArrayLength(starts);

    std::vector<int> vStarts(pStarts, pStarts + len);
    std::vector<int> vEnds(pEnds, pEnds + len);

    engine->setSlices(track_index, vStarts, vEnds);

    env->ReleaseIntArrayElements(starts, pStarts, JNI_ABORT);
    env->ReleaseIntArrayElements(ends, pEnds, JNI_ABORT);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nTrimSample(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine)
    engine->trimSample(track_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nResetSampler(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine)
    engine->resetSampler(track_index);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_nGetSlicePoints(JNIEnv *env, jobject thiz,
                                            jint track_index) {
  if (engine) {
    std::vector<float> points = engine->getSamplerSlicePoints(track_index);
    jfloatArray result = env->NewFloatArray(points.size());
    env->SetFloatArrayRegion(result, 0, points.size(), points.data());
    return result;
  }
  return env->NewFloatArray(0);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nClearSequencer(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine)
    engine->clearSequencer(track_index);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_groovebox_NativeLib_nGetStepNotes(JNIEnv *env, jobject thiz,
                                          jint track_index, jint step_index,
                                          jint drum_index) {
  if (engine) {
    std::lock_guard<std::recursive_mutex> lock(engine->getLock());
    const std::vector<AudioEngine::Track> &tracks = engine->getTracks();
    std::vector<int> notes;
    if (track_index >= 0 && track_index < (int)tracks.size()) {
      const AudioEngine::Track &t = tracks[track_index];
      const Sequencer &seq = (drum_index >= 0 && drum_index < 16)
                                 ? t.drumSequencers[drum_index]
                                 : t.sequencer;
      const std::vector<Step> &steps = seq.getSteps();
      if (step_index >= 0 && step_index < (int)steps.size()) {
        for (const auto &ni : steps[step_index].notes) {
          notes.push_back(ni.note);
        }
      }
    }
    jintArray result = env->NewIntArray(notes.size());
    if (notes.size() > 0) {
      env->SetIntArrayRegion(result, 0, notes.size(),
                             (const jint *)notes.data());
    }
    return result;
  }
  return env->NewIntArray(0);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_groovebox_NativeLib_nGetStepVelocity(JNIEnv *env, jobject thiz,
                                             jint track_index, jint step_index,
                                             jint drum_index) {
  if (engine) {
    std::lock_guard<std::recursive_mutex> lock(engine->getLock());
    const std::vector<AudioEngine::Track> &tracks = engine->getTracks();
    if (track_index >= 0 && track_index < (int)tracks.size()) {
      const AudioEngine::Track &t = tracks[track_index];
      const Sequencer &seq = (drum_index >= 0 && drum_index < 16)
                                 ? t.drumSequencers[drum_index]
                                 : t.sequencer;
      const std::vector<Step> &steps = seq.getSteps();
      if (step_index >= 0 && step_index < (int)steps.size()) {
        if (!steps[step_index].notes.empty()) {
          return steps[step_index].notes[0].velocity;
        }
      }
    }
  }
  return 0.8f;
}

extern "C" JNIEXPORT jfloat JNICALL Java_com_groovebox_NativeLib_nGetStepSubStep(
    JNIEnv *env, jobject thiz, jint track_index, jint step_index,
    jint drum_index) {
  if (engine) {
    std::lock_guard<std::recursive_mutex> lock(engine->getLock());
    const std::vector<AudioEngine::Track> &tracks = engine->getTracks();
    if (track_index >= 0 && track_index < (int)tracks.size()) {
      const AudioEngine::Track &t = tracks[track_index];
      const Sequencer &seq = (drum_index >= 0 && drum_index < 16)
                                 ? t.drumSequencers[drum_index]
                                 : t.sequencer;
      const std::vector<Step> &steps = seq.getSteps();
      if (step_index >= 0 && step_index < (int)steps.size()) {
        if (!steps[step_index].notes.empty()) {
          return steps[step_index].notes[0].subStepOffset;
        }
      }
    }
  }
  return 0.0f;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_groovebox_NativeLib_nGetStepActive(JNIEnv *env, jobject thiz,
                                           jint track_index, jint step_index,
                                           jint drum_index) {
  if (engine) {
    return engine->getStepActive(track_index, step_index, drum_index);
  }
  return false;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_groovebox_NativeLib_nGetCpuLoad(JNIEnv *env, jobject thiz) {
  if (engine) {
    return engine->getCpuLoad();
  }
  return 0.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetGenericLfoParam(JNIEnv *env, jobject thiz,
                                                jint lfo_index, jint param_id,
                                                jfloat value) {
  if (engine)
    engine->setGenericLfoParam(lfo_index, param_id, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetMacroValue(
    JNIEnv *env, jobject thiz, jint macro_index, jfloat value) {
  if (engine)
    engine->setMacroValue(macro_index, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetMacroSource(
    JNIEnv *env, jobject thiz, jint macro_index, jint source_type,
    jint source_index, jint source_track_index) {
  if (engine)
    engine->setMacroSource(macro_index, source_type, source_index,
                           source_track_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetFxChain(
    JNIEnv *env, jobject thiz, jint source_fx, jint dest_fx) {
  if (engine)
    engine->setFxChain(source_fx, dest_fx);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_groovebox_NativeLib_nFetchMidiEvents(JNIEnv *env, jobject thiz) {
  if (!engine)
    return env->NewIntArray(0);

  const int MAX_EVENTS = 64;
  int buffer[MAX_EVENTS * 4]; // type, ch, d1, d2

  int count = engine->fetchMidiEvents(buffer, MAX_EVENTS);

  if (count == 0)
    return env->NewIntArray(0);

  jintArray result = env->NewIntArray(count * 4);
  env->SetIntArrayRegion(result, 0, count * 4, buffer);
  return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetMasterVolume(
    JNIEnv *env, jobject thiz, jfloat volume) {
  if (engine) {
    engine->setMasterVolume(volume);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetTrackTranspose(JNIEnv *env, jobject thiz,
                                               jint trackIndex,
                                               jint semitones) {
  if (engine) {
    engine->setTrackTranspose(trackIndex, semitones);
  }
}

extern jlong JNICALL Java_com_groovebox_NativeLib_nGetSampleLength(
    JNIEnv *env, jobject thiz, jint track_index);
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetPadMod(
    JNIEnv *env, jobject thiz, jint track_index, jfloat value) {
  if (engine) {
    engine->setPadMod(track_index, value);
  }
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetPitchBend(
    JNIEnv *env, jobject thiz, jint trackIndex, jfloat semitones) {
  if (engine) {
    engine->setPitchBend(trackIndex, semitones);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nLoadFmPreset(
    JNIEnv *env, jobject thiz, jint track_index, jint preset_id) {
  if (engine)
    engine->loadFmPreset(track_index, preset_id);
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetAppDataDir(
    JNIEnv *env, jobject thiz, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->setAppDataDir(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nLoadWavetable(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->loadWavetable(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nLoadSoundFont(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->loadSoundFont(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetSoundFontPreset(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jint preset_index) {
  if (engine) {
    engine->setSoundFontPreset(track_index, preset_index);
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_groovebox_NativeLib_nGetSoundFontPresetCount(JNIEnv *env, jobject thiz,
                                                     jint track_index) {
  if (engine) {
    return engine->getSoundFontPresetCount(track_index);
  }
  return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_groovebox_NativeLib_nGetSoundFontPresetName(JNIEnv *env, jobject thiz,
                                                    jint track_index,
                                                    jint preset_index) {
  if (engine) {
    std::string name =
        engine->getSoundFontPresetName(track_index, preset_index);
    return env->NewStringUTF(name.c_str());
  }
  return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nLoadDefaultWavetable(JNIEnv *env, jobject thiz,
                                                  jint track_index) {
  if (engine)
    engine->loadDefaultWavetable(track_index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nLoadAppState(JNIEnv *env, jobject thiz) {

  if (engine)
    engine->loadAppState();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_groovebox_NativeLib_nGetLastSamplePath(JNIEnv *env, jobject thiz,
                                               jint track_index) {
  if (engine) {
    std::string path = engine->getLastSamplePath(track_index);
    return env->NewStringUTF(path.c_str());
  }
  return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetClockMultiplier(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jfloat multiplier) {
  if (engine)
    engine->setClockMultiplier(track_index, multiplier);
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetArpTriplet(
    JNIEnv *env, jobject thiz, jint track_index, jboolean is_triplet) {
  if (engine)
    engine->setArpTriplet(track_index, is_triplet);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_nGetAllTrackParameters(JNIEnv *env, jobject thiz,
                                                   jint track_index) {
  if (engine) {
    std::vector<float> params = engine->getAllTrackParameters(track_index);
    jfloatArray result = env->NewFloatArray(params.size());
    env->SetFloatArrayRegion(result, 0, params.size(), params.data());
    return result;
  }
  return env->NewFloatArray(0);
}

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_com_groovebox_NativeLib_nGetAllStepActiveStates(JNIEnv *env, jobject thiz,
                                                    jint track_index) {
  if (engine && track_index >= 0 && track_index < 8) {
    // Optimized path: Direct boolean fetch
    const int MAX_STEPS = 64;
    bool states[MAX_STEPS];
    engine->getStepActiveStates(track_index, states, MAX_STEPS);

    jbooleanArray result = env->NewBooleanArray(MAX_STEPS);
    jboolean temp[MAX_STEPS];
    for (int i = 0; i < MAX_STEPS; ++i) {
      temp[i] = states[i] ? JNI_TRUE : JNI_FALSE;
    }
    env->SetBooleanArrayRegion(result, 0, MAX_STEPS, temp);
    return result;
  }
  return env->NewBooleanArray(0);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetFilterMode(
    JNIEnv *env, jobject thiz, jint track_index, jint mode) {
  if (engine) {
    engine->setFilterMode(track_index, mode);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nRestorePresets(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->restorePresets();
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nRestoreTrackPreset(JNIEnv *env, jobject thiz,
                                                jint track_index) {
  if (engine)
    engine->restoreTrackPreset(track_index);
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSaveTrackPreset(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine)
    engine->saveTrackPreset(track_index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSaveTrackPresetToPath(JNIEnv *env, jobject thiz,
                                                   jint track_index,
                                                   jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, nullptr);
    engine->saveTrackPresetToPath(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_groovebox_NativeLib_nFetchEngineEvents(JNIEnv *env, jobject thiz) {
  if (engine) {
    int buffer[300]; // Max 100 events
    int count = engine->fetchEngineEvents(buffer, 100);
    jintArray result = env->NewIntArray(count * 3);
    env->SetIntArrayRegion(result, 0, count * 3, buffer);
    return result;
  }
  return env->NewIntArray(0);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_nGetRecordedSampleData(JNIEnv *env, jobject thiz,
                                                   jint track_index,
                                                   jfloat target_sample_rate) {
  if (engine) {
    std::vector<float> data =
        engine->getRecordedSampleData(track_index, target_sample_rate);
    if (!data.empty()) {
      jfloatArray result = env->NewFloatArray(data.size());
      env->SetFloatArrayRegion(result, 0, data.size(), data.data());
      return result;
    }
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetSoundFontMapping(JNIEnv *env, jobject thiz,
                                                 jint track_index,
                                                 jint knob_index,
                                                 jint param_id) {
  if (engine)
    engine->setSoundFontMapping(track_index, knob_index, param_id);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_groovebox_NativeLib_nGetActiveNoteMask(JNIEnv *env, jobject thiz,
                                               jint track_index) {
  if (engine)
    return engine->getActiveNoteMask(track_index);
  return 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetChordProgConfig(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jboolean enabled, jint mood,
                                                jint complexity) {
  if (engine) {
    engine->setChordProgConfig(track_index, enabled, mood, complexity);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetScaleConfig(
    JNIEnv *env, jobject thiz, jint root_note, jintArray intervals) {
  if (engine) {
    std::vector<int> intervalVec;
    if (intervals != nullptr) {
      jsize len = env->GetArrayLength(intervals);
      jint *elems = env->GetIntArrayElements(intervals, nullptr);
      for (int i = 0; i < len; ++i)
        intervalVec.push_back(elems[i]);
      env->ReleaseIntArrayElements(intervals, elems, JNI_ABORT);
    }
    engine->setScaleConfig(root_note, intervalVec);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetTrackActive(
    JNIEnv *env, jobject thiz, jint track_index, jboolean active) {
  if (engine)
    engine->setTrackActive(track_index, active);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetTrackPan(
    JNIEnv *env, jobject thiz, jint track_index, jfloat pan) {
  if (engine)
    engine->setTrackPan(track_index, pan);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetTrackMute(
    JNIEnv *env, jobject thiz, jint track_index, jboolean muted) {
  if (engine)
    engine->setTrackMute(track_index, muted);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetTrackSolo(
    JNIEnv *env, jobject thiz, jint track_index, jboolean soloed) {
  if (engine)
    engine->setTrackSolo(track_index, soloed);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_groovebox_NativeLib_nGetFxChain(JNIEnv *env, jobject thiz) {
  if (engine) {
    int chain[17];
    engine->getFxChain(chain); // Copies mFxChainDest to chain
    jintArray result = env->NewIntArray(17);
    env->SetIntArrayRegion(result, 0, 17, chain);
    return result;
  }
  return env->NewIntArray(0);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetSlicePosition(
    JNIEnv *env, jobject thiz, jint track_index, jint slice_index,
    jfloat position) {
  if (engine) {
    engine->setSlicePosition(track_index, slice_index, position);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_nSetSidechainConfig(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jint drum_index) {
  if (engine) {
    engine->setSidechainConfig(track_index, drum_index);
  }
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetChainEnabled(
    JNIEnv *env, jobject thiz, jint track_index, jboolean enabled) {
  if (engine)
    engine->setChainEnabled(track_index, enabled);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetChainLength(
    JNIEnv *env, jobject thiz, jint track_index, jint length) {
  if (engine)
    engine->setChainLength(track_index, length);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_nSetChainSlot(
    JNIEnv *env, jobject thiz, jint track_index, jint slot_index,
    jint lane_index, jobjectArray steps) {
  if (!engine || steps == nullptr)
    return;

  jsize len = env->GetArrayLength(steps);
  std::vector<Step> stepVec;

  jclass stepClass = env->FindClass("com/groovebox/StepState");
  jfieldID activeField = env->GetFieldID(stepClass, "active", "Z");
  jfieldID notesField = env->GetFieldID(stepClass, "notes", "Ljava/util/List;");
  jfieldID velocityField = env->GetFieldID(stepClass, "velocity", "F");
  jfieldID ratchetField = env->GetFieldID(stepClass, "ratchet", "I");
  jfieldID punchField = env->GetFieldID(stepClass, "punch", "Z");
  jfieldID probabilityField = env->GetFieldID(stepClass, "probability", "F");
  jfieldID gateField = env->GetFieldID(stepClass, "gate", "F");
  jfieldID skippedField = env->GetFieldID(stepClass, "isSkipped", "Z");
  jfieldID subStepField = env->GetFieldID(stepClass, "subStepOffset", "F");

  jclass listClass = env->FindClass("java/util/List");
  jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
  jmethodID getMethod =
      env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");

  jclass integerClass = env->FindClass("java/lang/Integer");
  jmethodID intValueMethod = env->GetMethodID(integerClass, "intValue", "()I");

  for (int i = 0; i < len; ++i) {
    jobject stepObj = env->GetObjectArrayElement(steps, i);
    Step s;
    s.active = env->GetBooleanField(stepObj, activeField);
    s.isSkipped = env->GetBooleanField(stepObj, skippedField);
    s.ratchet = env->GetIntField(stepObj, ratchetField);
    s.punch = env->GetBooleanField(stepObj, punchField);
    s.probability = env->GetFloatField(stepObj, probabilityField);
    s.gate = env->GetFloatField(stepObj, gateField);

    jobject notesList = env->GetObjectField(stepObj, notesField);
    if (notesList) {
      int listSize = env->CallIntMethod(notesList, sizeMethod);
      float subStep = env->GetFloatField(stepObj, subStepField);
      float vel = env->GetFloatField(stepObj, velocityField);

      for (int j = 0; j < listSize; ++j) {
        jobject intObj = env->CallObjectMethod(notesList, getMethod, j);
        int note = env->CallIntMethod(intObj, intValueMethod);
        s.addNote(note, vel, subStep);
        env->DeleteLocalRef(intObj);
      }
      env->DeleteLocalRef(notesList);
    }
    stepVec.push_back(s);
    env->DeleteLocalRef(stepObj);
  }

  engine->setChainSlot(track_index, slot_index, lane_index, stepVec);
}
