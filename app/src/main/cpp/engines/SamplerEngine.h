#ifndef SAMPLER_ENGINE_H
#define SAMPLER_ENGINE_H

#include "../Utils.h"
#include "Adsr.h"
#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>
#include <vector>

class SamplerEngine {
public:
  enum PlayMode { OneShot, Sustain, Chops };

  struct Slice {
    size_t start;
    size_t end;
  };

  struct Voice {
    bool active = false;
    int note = -1;
    double position = 0.0;      // Traversal position
    double grainPosition = 0.0; // Phase within grain (or offset)
    size_t start = 0;
    size_t end = 0;
    float baseVelocity = 1.0f;
    float pitchRatio = 1.0f;
    Adsr envelope;
    TSvf filter;

    // Simple Granular state
    uint32_t grainTimer = 0;
    static const int GRAIN_SIZE = 1024; // Samples

    void reset() {
      active = false;
      note = -1;
      position = 0.0;
      grainPosition = 0.0;
      grainTimer = 0;
      envelope.reset();
    }
  };

  SamplerEngine() {
    mVoices.resize(16);
    for (auto &v : mVoices)
      v.reset();
  }

  void resetToDefaults() {
    mPitch = 0.0f;
    mStretch = 1.0f;
    mSpeed = 1.0f;
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 1.0f;
    mRelease = 0.2f;
    mFilterCutoff = 1.0f;
    mFilterResonance = 0.0f;
    mFilterEnvAmount = 0.0f;
    mPlayMode = OneShot;
    mUseEnvelope = true;
    mReverse = false;
    for (auto &v : mVoices) {
      if (v.active)
        v.envelope.setParameters(mAttack, mDecay, mSustain, mRelease);
    }
  }

  void setSample(const std::vector<float> &data) {
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock);
    mBuffer = data;
  }
  void loadSample(const std::vector<float> &data) { setSample(data); }
  const std::vector<float> &getSampleData() const { return mBuffer; }

  void setSlicePoints(const std::vector<float> &points) {
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock);
    mSlices.clear();
    if (mBuffer.empty())
      return;

    for (size_t i = 0; i < points.size(); ++i) {
      size_t start = static_cast<size_t>(points[i] * mBuffer.size());
      size_t end = (i + 1 < points.size())
                       ? static_cast<size_t>(points[i + 1] * mBuffer.size())
                       : mBuffer.size();
      if (start < end)
        mSlices.push_back({start, end});
    }
    // Ensure specific count or just use points?
    // Existing logic used regions. WavUtils saves points.
    // If points are just start markers:
    // We can reconstruct regions [p[i], p[i+1]].
  }

  void setPlaybackSpeed(float speed);

  void clearBuffer() {
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock);
    mBuffer.clear();
    mSlices.clear();
    for (auto &v : mVoices)
      v.active = false;
  }

  void pushSample(float sample) {
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock);
    mBuffer.push_back(sample);
  }

  void normalize() {
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock);
    if (mBuffer.empty())
      return;
    float maxVal = 0.0f;
    for (float s : mBuffer)
      maxVal = std::max(maxVal, std::abs(s));
    if (maxVal > 0.0001f) {
      float gain = 0.95f / maxVal;
      for (auto &s : mBuffer)
        s *= gain;
    }
  }

  void trim() {
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock);
    if (mBuffer.empty())
      return;
    size_t start = static_cast<size_t>(mTrimStart * mBuffer.size());
    size_t end = static_cast<size_t>(mTrimEnd * mBuffer.size());
    if (end > mBuffer.size())
      end = mBuffer.size();
    if (start >= end) {
      if (end > 0)
        start = end - 1;
      else
        return;
    }
    std::vector<float> newBuffer(mBuffer.begin() + start,
                                 mBuffer.begin() + end);
    mBuffer = std::move(newBuffer);
    mTrimStart = 0.0f;
    mTrimEnd = 1.0f;
    mSlices.clear();
    for (auto &v : mVoices)
      v.active = false;
  }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.envelope.reset();
      v.active = false; // "isPlaying" equivalent
    }
  }

  void triggerNote(int note, int velocity) {
    if (!mBufferLock->try_lock())
      return;
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock, std::adopt_lock);

    if (mBuffer.empty())
      return;

    int voiceIdx = -1;
    for (int i = 0; i < (int)mVoices.size(); ++i) {
      if (mVoices[i].active && mVoices[i].note == note) {
        voiceIdx = i;
        break;
      }
    }
    if (voiceIdx == -1) {
      for (int i = 0; i < 16; ++i) {
        if (!mVoices[i].active) {
          voiceIdx = i;
          break;
        }
      }
    }
    if (voiceIdx == -1)
      voiceIdx = 0;

    Voice &v = mVoices[voiceIdx];
    v.reset();
    v.active = true;
    v.note = note;
    v.baseVelocity = velocity / 127.0f;

    v.envelope.setSampleRate(44100.0f);
    v.envelope.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.envelope.trigger();

    if (mPlayMode == Chops && !mSlices.empty()) {
      // Map Note 60 -> Slice 0. Safe modulo.
      int sliceIdx = 0;
      if (note >= 60)
        sliceIdx = (note - 60);

      // Explicitly cycle through slices (User requested cycling, not clamping)
      if (!mSlices.empty()) {
        sliceIdx = sliceIdx % (int)mSlices.size();
      } else {
        sliceIdx = 0;
      }

      v.start = mSlices[sliceIdx].start;
      v.end = mSlices[sliceIdx].end;
    } else {
      v.start = static_cast<size_t>(mTrimStart * mBuffer.size());
      v.end = static_cast<size_t>(mTrimEnd * mBuffer.size());
      if (v.end > mBuffer.size())
        v.end = mBuffer.size();
      if (v.start >= v.end && v.end > 0)
        v.start = v.end - 1;
    }

    // Fix: Start at end if reverse
    if (mReverse) {
      v.position = (double)v.end - 1.0;
    } else {
      v.position = (double)v.start;
    }
    v.grainPosition = v.position;
    v.grainTimer = 0;

    float keyShift = (mPlayMode == Chops) ? 0.0f : (float)(note - 60);
    v.pitchRatio = powf(2.0f, (mPitch + keyShift) / 12.0f);
  }

  int getPlayMode() const { return mPlayMode; } // Added Getter for AudioEngine

  void releaseNote(int note) {
    for (auto &v : mVoices) {
      if (v.active && v.note == note) {
        if (mPlayMode == Sustain || mPlayMode == Chops) {
          v.envelope.release();
        }
      }
    }
  }

  void setParameter(int id, float value) {
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock);
    switch (id) {
    case 1: // Cutoff
      setFilterCutoff(value);
      break;
    case 2: // Resonance
      setFilterResonance(value);
      break;
    case 300: // PITCH: changes the pitch but keeps playback time constant
      mPitch = (value - 0.5f) * 48.0f;
      break;
    case 301: // STRETCH: changes playback time but keeps pitch constant
      mStretch = value * 4.0f;
      break;
    case 302: // SPEED: changes both pitch and playback time together
      mSpeed = value * 2.0f;
      break;
    case 303: // Filter Cutoff
      setFilterCutoff(value);
      break;
    case 304: // Filter Resonance
      setFilterResonance(value);
      break;
    case 310:
      mAttack = value;
      break;
    case 311:
      mDecay = value;
      break;
    case 312:
      mSustain = value;
      break;
    case 313:
      mRelease = value;
      break;
    case 314: // Filter EG Intensity
      setFilterEnvAmount(value);
      break;
    case 320:
      mPlayMode = static_cast<PlayMode>(std::min(2, (int)(value * 3.0f)));
      break;
    case 330:
      mTrimStart = value;
      break;
    case 331:
      mTrimEnd = value;
      break;
    case 350:
      mUseEnvelope = value > 0.5f;
      break;
    case 351:
      mReverse = value > 0.5f;
      break;
    case 340: {
      int count = static_cast<int>(value * 14.0f) + 2; // 2 to 16
      findConstrainedSlices(count);
      break;
    }
    case 118: // Filter Env Amount
      setFilterEnvAmount(value);
      break;
    }
    for (auto &v : mVoices) {
      if (v.active)
        v.envelope.setParameters(mAttack, mDecay, mSustain, mRelease);
    }
  }

  void setAttack(float v) { mAttack = v; }
  void setDecay(float v) { mDecay = v; }
  void setSustain(float v) { mSustain = v; }
  void setRelease(float v) { mRelease = v; }
  void setFilterCutoff(float v) { mFilterCutoff = v; }
  void setFilterResonance(float v) { mFilterResonance = v; }
  void setFilterEnvAmount(float v) { mFilterEnvAmount = v; }

  float render() {
    if (!mBufferLock->try_lock())
      return 0.0f;
    std::lock_guard<std::recursive_mutex> lock(*mBufferLock, std::adopt_lock);

    if (mBuffer.empty())
      return 0.0f;

    float mixedOutput = 0.0f;
    int activeCount = 0;

    for (auto &v : mVoices) {
      if (!v.active)
        continue;

      float env = mUseEnvelope ? v.envelope.nextValue() : 1.0f;
      if (env < 0.0001f && (!mUseEnvelope || !v.envelope.isActive())) {
        v.active = false;
        continue;
      }
      activeCount++;

      /*
       * SAMPLER PARAMETER LOGIC:
       * 1. SPEED (mSpeed): Global playback rate. Affects BOTH traversal (time)
       * and read (pitch).
       * 2. STRETCH (mStretch): Decouples time from pitch. Affects traverseRate
       * ONLY.
       * 3. PITCH (mPitch): Decouples pitch from time. Affects readRate ONLY.
       */

      // Base Resampling Rate: Affected by Speed AND Pitch knob (Classic
      // behavior)
      float pitchFactor = v.pitchRatio; // includes mPitch and Note shift
      float baseResampleRate = mSpeed * pitchFactor * (mReverse ? -1.0f : 1.0f);

      // Decoupled Rates for Granular (only used if mStretch != 1.0)
      float traverseRate = baseResampleRate / std::max(0.01f, mStretch);
      float readRate =
          baseResampleRate; // The pitch is already in baseResampleRate

      // ONLY use Granular if actively stretching time (mStretch != 1.0)
      bool useGranular = (std::abs(mStretch - 1.0f) > 0.02f);

      float voiceOutput = 0.0f;
      if (!useGranular) {
        // Classic mode: Resampling (Pitch and Time are linked)
        v.position += baseResampleRate;
        if (v.position >= v.end || v.position < v.start) {
          if (mPlayMode == Sustain) {
            v.position = mReverse ? (double)v.end - 1.0 : (double)v.start;
          } else {
            v.envelope.release();
          }
        }
        int idx = static_cast<int>(v.position);
        if (idx >= 0 && idx < (int)mBuffer.size()) {
          voiceOutput = mBuffer[idx];
        }
      } else {
        // Granular mode: Time-stretching (Decouples traversal from read rate)
        v.position += traverseRate;
        v.grainTimer++;

        // Grain 1
        double gp1 = v.position + (v.grainTimer * (readRate - traverseRate));
        int idx1 = static_cast<int>(gp1);

        // Grain 2
        uint32_t timer2 =
            (v.grainTimer + (Voice::GRAIN_SIZE / 2)) % Voice::GRAIN_SIZE;
        double gp2 = v.position + (timer2 * (readRate - traverseRate));
        int idx2 = static_cast<int>(gp2);

        float phase = (float)v.grainTimer / (float)Voice::GRAIN_SIZE;
        float w1 = 1.0f - std::abs(phase * 2.0f - 1.0f);

        float s1 =
            (idx1 >= 0 && idx1 < (int)mBuffer.size()) ? mBuffer[idx1] : 0.0f;
        float s2 =
            (idx2 >= 0 && idx2 < (int)mBuffer.size()) ? mBuffer[idx2] : 0.0f;

        voiceOutput = (s1 * w1) + (s2 * (1.0f - w1));

        if (v.grainTimer >= Voice::GRAIN_SIZE) {
          v.grainTimer = 0;
        }

        if (v.position >= v.end || v.position < v.start) {
          if (mPlayMode == Sustain) {
            v.position = mReverse ? (double)v.end - 1.0 : (double)v.start;
          } else {
            v.envelope.release();
          }
        }
      }

      // Filter Processing
      float cutoff = 20.0f + (mFilterCutoff * mFilterCutoff * 18000.0f);
      // Integrate envelope to filter cutoff
      cutoff += env * mFilterEnvAmount * 12000.0f;
      cutoff = std::max(20.0f, std::min(20000.0f, cutoff));

      v.filter.setParams(cutoff, 0.7f + mFilterResonance * 5.0f, 44100.0f);
      voiceOutput = v.filter.process(voiceOutput, TSvf::LowPass);

      mixedOutput += voiceOutput * env * v.baseVelocity;
    }

    if (activeCount > 1)
      mixedOutput *= (1.0f / sqrtf((float)activeCount));

    return mixedOutput;
  }

  void findConstrainedSlices(int count) {
    mSlices.clear();
    if (mBuffer.empty() || count <= 0)
      return;

    size_t totalSamples = mBuffer.size();
    size_t avgLength = totalSamples / count;
    size_t windowSize = avgLength; // +/- 50% search window centered at beat

    size_t currentStart = 0;
    for (int i = 1; i < count; ++i) {
      size_t idealEnd = i * avgLength;

      // Search for strongest transient in window [idealEnd - windowSize/2,
      // idealEnd + windowSize/2]
      size_t searchStart =
          (idealEnd > windowSize / 2) ? (idealEnd - windowSize / 2) : 0;
      size_t searchEnd =
          std::min(totalSamples - 256, idealEnd + windowSize / 2);

      size_t bestTransient = idealEnd;
      float maxEnergyJump = 0.0f;
      float prevEnergy = 0.0f;

      // Use smaller window for transient detection within the search window
      const int energyWindow = 256;
      for (size_t j = searchStart; j < searchEnd - energyWindow; j += 128) {
        float energy = 0.0f;
        for (int k = 0; k < energyWindow; ++k) {
          float s = mBuffer[j + k];
          energy += s * s;
        }

        if (j > searchStart) {
          float jump = energy / (prevEnergy + 0.001f);
          if (jump > maxEnergyJump && energy > 0.01f) {
            maxEnergyJump = jump;
            bestTransient = j;
          }
        }
        prevEnergy = energy;
      }

      // Require a decent jump to snap, otherwise stay at ideal beat
      size_t sliceEnd = (maxEnergyJump > 1.4f) ? bestTransient : idealEnd;
      mSlices.push_back({currentStart, sliceEnd});
      currentStart = sliceEnd;
    }
    mSlices.push_back({currentStart, totalSamples});
  }

  void prepareSlices(int count) {
    mSlices.clear();
    if (mBuffer.empty() || count <= 0)
      return;
    size_t step = mBuffer.size() / count;
    for (int i = 0; i < count; ++i) {
      mSlices.push_back({i * step, (i + 1) * step});
    }
  }

  std::vector<float> getSlicePoints() const {
    std::vector<float> points;
    if (mBuffer.empty())
      return points;
    for (const auto &s : mSlices) {
      points.push_back((float)s.start / (float)mBuffer.size());
    }
    return points;
  }

  std::vector<float> getAmplitudeWaveform(int numPoints) const {
    std::vector<float> result;
    if (mBuffer.empty())
      return result;
    int step = mBuffer.size() / numPoints;
    if (step < 1)
      step = 1;
    for (int i = 0; i < numPoints; ++i) {
      float maxVal = 0.0f;
      int end = std::min((int)mBuffer.size(), (i + 1) * step);
      for (int j = i * step; j < end; ++j) {
        maxVal = std::max(maxVal, std::abs(mBuffer[j]));
      }
      result.push_back(maxVal);
    }
    return result;
  }

  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    return false;
  }

  std::shared_ptr<std::recursive_mutex> mBufferLock =
      std::make_shared<std::recursive_mutex>();
  bool mReverse = false;

private:
  std::vector<Voice> mVoices;
  float mTrimStart = 0.0f;
  float mTrimEnd = 1.0f;
  float mPitch = 0.0f;
  float mStretch = 1.0f;
  float mSpeed = 1.0f;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 0.8f, mRelease = 0.2f;
  float mFilterCutoff = 1.0f, mFilterResonance = 0.0f, mFilterEnvAmount = 0.0f;
  PlayMode mPlayMode = OneShot;
  bool mUseEnvelope = true;

  std::vector<Slice> mSlices;
  std::vector<float> mBuffer;
};

#endif // SAMPLER_ENGINE_H
