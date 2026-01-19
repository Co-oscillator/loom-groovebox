#include "AudioEngine.h"
#include <jni.h>

static AudioEngine *engine = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_init(JNIEnv *env, jobject thiz) {
  if (engine != nullptr) {
    AudioEngine *oldEngine = engine;
    engine = nullptr; // Null out global pointer BEFORE deleting to avoid race
                      // conditions
    delete oldEngine;
  }
  engine = new AudioEngine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_start(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_stop(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setTempo(JNIEnv *env, jobject thiz, jfloat bpm) {
  if (engine)
    engine->setTempo(bpm);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setPlaying(
    JNIEnv *env, jobject thiz, jboolean playing) {
  if (engine)
    engine->setPlaying(playing);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setStep(
    JNIEnv *env, jobject thiz, jint track_index, jint step_index,
    jboolean active, jintArray notes, jfloat velocity, jint ratchet,
    jboolean punch, jfloat probability, jfloat gate, jboolean is_skipped) {
  if (engine) {
    std::vector<int> noteVec;
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

    // Clamp notes to MIDI range 0-127
    for (int &n : noteVec) {
      if (n < 0)
        n = 0;
      else if (n > 127)
        n = 127;
    }

    engine->setStep(track_index, step_index, safeActive, noteVec, safeVelocity,
                    safeRatchet, punch, safeProb, safeGate, is_skipped);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setSequencerConfig(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jint num_pages,
                                                jint steps_per_page) {
  if (engine)
    engine->setSequencerConfig(track_index, num_pages, steps_per_page);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setTrackVolume(
    JNIEnv *env, jobject thiz, jint track_index, jfloat volume) {
  if (engine)
    engine->setTrackVolume(track_index, volume);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setEngineType(
    JNIEnv *env, jobject thiz, jint track_index, jint type) {
  if (engine)
    engine->setEngineType(track_index, type);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_triggerNote(
    JNIEnv *env, jobject thiz, jint track_index, jint note, jint velocity) {
  if (engine)
    engine->triggerNote(track_index, note, velocity);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_releaseNote(
    JNIEnv *env, jobject thiz, jint track_index, jint note) {
  if (engine)
    engine->releaseNote(track_index, note);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setArpRate(
    JNIEnv *env, jobject thiz, jint track_index, jfloat rate,
    jint division_mode) {
  if (engine)
    engine->setArpRate(track_index, rate, division_mode);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setParameter(
    JNIEnv *env, jobject thiz, jint track_index, jint parameter_id,
    jfloat value) {
  if (engine)
    engine->setParameter(track_index, parameter_id, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setRouting(
    JNIEnv *env, jobject thiz, jint dest_track, jint source_track, jint source,
    jint dest, jfloat amount, jint dest_param_id) {
  if (engine)
    engine->setRouting(dest_track, source_track, source, dest, amount,
                       dest_param_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setSwing(JNIEnv *env, jobject thiz, jfloat swing) {
  if (engine)
    engine->setSwing(swing);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setPlaybackDirection(JNIEnv *env, jobject thiz,
                                                  jint track_index,
                                                  jint direction) {
  if (engine)
    engine->setPlaybackDirection(track_index, direction);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_exportAudio(
    JNIEnv *env, jobject thiz, jint num_repeats, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->renderToWav(num_repeats, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setIsRandomOrder(
    JNIEnv *env, jobject thiz, jint track_index, jboolean is_random) {
  if (engine)
    engine->setIsRandomOrder(track_index, is_random);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setIsJumpMode(
    JNIEnv *env, jobject thiz, jint track_index, jboolean is_jump) {
  if (engine)
    engine->setIsJumpMode(track_index, is_jump);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setParameterLock(
    JNIEnv *env, jobject thiz, jint track_index, jint step_index,
    jint parameter_id, jfloat value) {
  if (engine)
    engine->setParameterLock(track_index, step_index, parameter_id, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_clearParameterLocks(JNIEnv *env, jobject thiz,
                                                 jint track_index,
                                                 jint step_index) {
  if (engine)
    engine->clearParameterLocks(track_index, step_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setIsRecording(
    JNIEnv *env, jobject thiz, jboolean is_recording) {
  if (engine)
    engine->setIsRecording(is_recording);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setResampling(
    JNIEnv *env, jobject thiz, jboolean is_resampling) {
  if (engine)
    engine->setResampling(is_resampling);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setPatternLength(
    JNIEnv *env, jobject thiz, jint length) {
  if (engine)
    engine->setPatternLength(length);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setSelectedFmDrumInstrument(JNIEnv *env,
                                                         jobject thiz,
                                                         jint track_index,
                                                         jint drum_index) {
  if (engine)
    engine->setSelectedFmDrumInstrument(track_index, drum_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_jumpToStep(
    JNIEnv *env, jobject thiz, jint step_index) {
  if (engine)
    engine->jumpToStep(step_index);
}

extern "C" JNIEXPORT jint JNICALL Java_com_groovebox_NativeLib_getCurrentStep(
    JNIEnv *env, jobject thiz, jint track_index, jint drum_index) {
  if (engine)
    return engine->getCurrentStep(track_index, drum_index);
  return 0;
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setArpConfig(
    JNIEnv *env, jobject thiz, jint track_index, jint mode, jint octaves,
    jint inversion, jboolean is_latched, jboolean is_mutated,
    jobjectArray rhythms, jintArray sequence) {
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

    engine->setArpConfig(track_index, mode, octaves, inversion, is_latched,
                         is_mutated, rhythmVecs, seqVec);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_panic(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->panic();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_getGranularPlayheads(JNIEnv *env, jobject thiz,
                                                  jint track_index) {
  const int MAX_GRAINS = 32;
  jfloatArray result = env->NewFloatArray(MAX_GRAINS * 2);
  if (engine) {
    GranularEngine::PlayheadInfo info[MAX_GRAINS];
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

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_saveSample(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->saveSample(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_loadSample(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->loadSample(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_startRecordingSample(JNIEnv *env, jobject thiz,
                                                  jint track_index) {
  if (engine)
    engine->startRecordingSample(track_index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_stopRecordingSample(JNIEnv *env, jobject thiz,
                                                 jint track_index) {
  if (engine) {
    engine->stopRecordingSample(track_index);
    engine->normalizeSample(track_index);
  }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_getWaveform(JNIEnv *env, jobject thiz,
                                         jint track_index) {
  if (engine) {
    std::vector<float> waveform = engine->getSamplerWaveform(track_index, 500);
    jfloatArray result = env->NewFloatArray(waveform.size());
    env->SetFloatArrayRegion(result, 0, waveform.size(), waveform.data());
    return result;
  }
  return env->NewFloatArray(0);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setSlices(
    JNIEnv *env, jobject thiz, jint track_index, jintArray starts,
    jintArray ends) {
  // TODO: Implement internal slice mapping if needed by passing to engine
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_trimSample(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine)
    engine->trimSample(track_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_resetSampler(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine)
    engine->resetSampler(track_index);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_getSlicePoints(JNIEnv *env, jobject thiz,
                                            jint track_index) {
  if (engine) {
    std::vector<float> points = engine->getSamplerSlicePoints(track_index);
    jfloatArray result = env->NewFloatArray(points.size());
    env->SetFloatArrayRegion(result, 0, points.size(), points.data());
    return result;
  }
  return env->NewFloatArray(0);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_clearSequencer(
    JNIEnv *env, jobject thiz, jint track_index) {
  if (engine)
    engine->clearSequencer(track_index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_groovebox_NativeLib_getStepActive(JNIEnv *env, jobject thiz,
                                           jint track_index, jint step_index,
                                           jint drum_index) {
  if (engine) {
    return engine->getStepActive(track_index, step_index, drum_index);
  }
  return false;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_groovebox_NativeLib_getCpuLoad(JNIEnv *env, jobject thiz) {
  if (engine) {
    return engine->getCpuLoad();
  }
  return 0.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setGenericLfoParam(JNIEnv *env, jobject thiz,
                                                jint lfo_index, jint param_id,
                                                jfloat value) {
  if (engine)
    engine->setGenericLfoParam(lfo_index, param_id, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setMacroValue(
    JNIEnv *env, jobject thiz, jint macro_index, jfloat value) {
  if (engine)
    engine->setMacroValue(macro_index, value);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setMacroSource(
    JNIEnv *env, jobject thiz, jint macro_index, jint source_type,
    jint source_index) {
  if (engine)
    engine->setMacroSource(macro_index, source_type, source_index);
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setFxChain(
    JNIEnv *env, jobject thiz, jint source_fx, jint dest_fx) {
  if (engine)
    engine->setFxChain(source_fx, dest_fx);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_groovebox_NativeLib_fetchMidiEvents(JNIEnv *env, jobject thiz) {
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

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setMasterVolume(
    JNIEnv *env, jobject thiz, jfloat volume) {
  if (engine) {
    engine->setMasterVolume(volume);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_loadFmPreset(
    JNIEnv *env, jobject thiz, jint track_index, jint preset_id) {
  if (engine)
    engine->loadFmPreset(track_index, preset_id);
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setAppDataDir(
    JNIEnv *env, jobject thiz, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->setAppDataDir(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_loadWavetable(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->loadWavetable(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_loadSoundFont(
    JNIEnv *env, jobject thiz, jint track_index, jstring path) {
  if (engine) {
    const char *nativePath = env->GetStringUTFChars(path, 0);
    engine->loadSoundFont(track_index, std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setSoundFontPreset(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jint preset_index) {
  if (engine) {
    engine->setSoundFontPreset(track_index, preset_index);
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_groovebox_NativeLib_getSoundFontPresetCount(JNIEnv *env, jobject thiz,
                                                     jint track_index) {
  if (engine) {
    return engine->getSoundFontPresetCount(track_index);
  }
  return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_groovebox_NativeLib_getSoundFontPresetName(JNIEnv *env, jobject thiz,
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
Java_com_groovebox_NativeLib_loadDefaultWavetable(JNIEnv *env, jobject thiz,
                                                  jint track_index) {
  if (engine)
    engine->loadDefaultWavetable(track_index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_loadAppState(JNIEnv *env, jobject thiz) {

  if (engine)
    engine->loadAppState();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_groovebox_NativeLib_getLastSamplePath(JNIEnv *env, jobject thiz,
                                               jint track_index) {
  if (engine) {
    std::string path = engine->getLastSamplePath(track_index);
    return env->NewStringUTF(path.c_str());
  }
  return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_setClockMultiplier(JNIEnv *env, jobject thiz,
                                                jint track_index,
                                                jfloat multiplier) {
  if (engine)
    engine->setClockMultiplier(track_index, multiplier);
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setArpTriplet(
    JNIEnv *env, jobject thiz, jint track_index, jboolean is_triplet) {
  if (engine)
    engine->setArpTriplet(track_index, is_triplet);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_getAllTrackParameters(JNIEnv *env, jobject thiz,
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
Java_com_groovebox_NativeLib_getAllStepActiveStates(JNIEnv *env, jobject thiz,
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

extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setFilterMode(
    JNIEnv *env, jobject thiz, jint track_index, jint mode) {
  if (engine) {
    engine->setFilterMode(track_index, mode);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_groovebox_NativeLib_restorePresets(JNIEnv *env, jobject thiz) {
  if (engine)
    engine->restorePresets();
}
extern "C" JNIEXPORT void JNICALL Java_com_groovebox_NativeLib_setInputDevice(
    JNIEnv *env, jobject thiz, jint device_id) {
  if (engine)
    engine->setInputDevice(device_id);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_groovebox_NativeLib_getRecordedSampleData(JNIEnv *env, jobject thiz,
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
Java_com_groovebox_NativeLib_setSoundFontMapping(JNIEnv *env, jobject thiz,
                                                 jint track_index,
                                                 jint knob_index,
                                                 jint param_id) {
  if (engine)
    engine->setSoundFontMapping(track_index, knob_index, param_id);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_groovebox_NativeLib_getActiveNoteMask(JNIEnv *env, jobject thiz,
                                               jint track_index) {
  if (engine)
    return engine->getActiveNoteMask(track_index);
  return 0;
}
