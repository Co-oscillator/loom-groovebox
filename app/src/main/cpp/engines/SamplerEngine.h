#ifndef SAMPLER_ENGINE_H
#define SAMPLER_ENGINE_H

#include "../Utils.h"
#include "Adsr.h"
#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>
#include <vector>

class SamplerEngine {
public:
  enum PlayMode { OneShot, Sustain, Loop, Chops, OneShotChops, LoopChops };

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
    float targetPitchRatio = 1.0f;
    Adsr envelope;
    TSvf filter;

    // Simple Granular state
    uint32_t grainTimer = 0;
    static const int GRAIN_SIZE = 1024; // Samples

    uint32_t controlCounter = 0;

    void reset() {
      active = false;
      note = -1;
      position = 0.0;
      grainPosition = 0.0;
      grainTimer = 0;
      envelope.reset();
      pitchRatio = 1.0f;
      targetPitchRatio = 1.0f;
    }
  };

  SamplerEngine() {
    mVoices.resize(16);
    for (auto &v : mVoices)
      v.reset();
  }

  // Disable copy (atomic is not copyable)
  SamplerEngine(const SamplerEngine &) = delete;
  SamplerEngine &operator=(const SamplerEngine &) = delete;

  // Move constructor (atomics need explicit handling)
  SamplerEngine(SamplerEngine &&other) noexcept
      : mActiveBuffer(other.mActiveBuffer.load(std::memory_order_relaxed)),
        mBuffers{std::move(other.mBuffers[0]), std::move(other.mBuffers[1])},
        mRecordingBuffer(std::move(other.mRecordingBuffer)),
        mReverse(other.mReverse), mVoices(std::move(other.mVoices)),
        mTrimStart(other.mTrimStart), mTrimEnd(other.mTrimEnd),
        mPitch(other.mPitch), mStretch(other.mStretch), mSpeed(other.mSpeed),
        mAttack(other.mAttack), mDecay(other.mDecay), mSustain(other.mSustain),
        mRelease(other.mRelease), mFilterCutoff(other.mFilterCutoff),
        mFilterResonance(other.mFilterResonance),
        mFilterEnvAmount(other.mFilterEnvAmount), mGlide(other.mGlide),
        mLastPitchRatio(other.mLastPitchRatio), mPlayMode(other.mPlayMode),
        mUseEnvelope(other.mUseEnvelope), mSampleRate(other.mSampleRate),
        mSlices(std::move(other.mSlices)) {}

  SamplerEngine &operator=(SamplerEngine &&other) noexcept {
    if (this != &other) {
      mActiveBuffer.store(other.mActiveBuffer.load(std::memory_order_relaxed),
                          std::memory_order_relaxed);
      mBuffers[0] = std::move(other.mBuffers[0]);
      mBuffers[1] = std::move(other.mBuffers[1]);
      mRecordingBuffer = std::move(other.mRecordingBuffer);
      mReverse = other.mReverse;
      mVoices = std::move(other.mVoices);
      mTrimStart = other.mTrimStart;
      mTrimEnd = other.mTrimEnd;
      mPitch = other.mPitch;
      mStretch = other.mStretch;
      mSpeed = other.mSpeed;
      mAttack = other.mAttack;
      mDecay = other.mDecay;
      mSustain = other.mSustain;
      mRelease = other.mRelease;
      mFilterCutoff = other.mFilterCutoff;
      mFilterResonance = other.mFilterResonance;
      mFilterEnvAmount = other.mFilterEnvAmount;
      mGlide = other.mGlide;
      mLastPitchRatio = other.mLastPitchRatio;
      mPlayMode = other.mPlayMode;
      mUseEnvelope = other.mUseEnvelope;
      mSampleRate = other.mSampleRate;
      mSlices = std::move(other.mSlices);
    }
    return *this;
  }

  void resetToDefaults() {
    mPitch = 0.0f;
    mStretch = 1.0f;
    mSpeed = 1.0f;
    mAttack = 0.002f; // Fast attack but no click
    mDecay = 0.5f;    // Longer decay
    mSustain = 1.0f;  // Full sustain
    mRelease = 0.5f;  // Smooth release
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
    // Write to inactive buffer, then swap
    int inactive = 1 - mActiveBuffer.load(std::memory_order_acquire);
    mBuffers[inactive] = data;
    mActiveBuffer.store(inactive, std::memory_order_release);
  }
  void loadSample(const std::vector<float> &data) { setSample(data); }
  const std::vector<float> &getSampleData() const {
    return mBuffers[mActiveBuffer.load(std::memory_order_acquire)];
  }

  // Helper for internal access to active buffer
  const std::vector<float> &getBuffer() const {
    return mBuffers[mActiveBuffer.load(std::memory_order_acquire)];
  }

  void setSlicePoints(const std::vector<float> &points) {
    std::lock_guard<std::mutex> lock(mSliceLock);
    mSlices.clear();
    int active = mActiveBuffer.load(std::memory_order_acquire);
    const auto &buf = mBuffers[active];
    if (buf.empty())
      return;

    for (size_t i = 0; i < points.size(); ++i) {
      size_t start = static_cast<size_t>(points[i] * buf.size());
      size_t end = (i + 1 < points.size())
                       ? static_cast<size_t>(points[i + 1] * buf.size())
                       : buf.size();
      if (start < end)
        mSlices.push_back({start, end});
    }
  }

  void setPlaybackSpeed(float speed);

  void setSlicePosition(int index, float position) {
    std::lock_guard<std::mutex> lock(mSliceLock);
    int active = mActiveBuffer.load(std::memory_order_acquire);
    const auto &buf = mBuffers[active];
    if (index >= 0 && index < (int)mSlices.size() && !buf.empty()) {
      size_t newStart = static_cast<size_t>(position * buf.size());
      if (newStart >= buf.size())
        newStart = buf.size() - 1;

      mSlices[index].start = newStart;
      if (index > 0) {
        mSlices[index - 1].end = newStart;
      }
    }
  }

  void clearBuffer() {
    int inactive = 1 - mActiveBuffer.load(std::memory_order_acquire);
    mBuffers[inactive].clear();
    mActiveBuffer.store(inactive, std::memory_order_release);
    std::lock_guard<std::mutex> lock(mSliceLock);
    mSlices.clear();
    for (auto &v : mVoices)
      v.active = false;
  }

  void pushSample(float sample) {
    // During recording, accumulate in recording buffer
    std::lock_guard<std::mutex> lock(mRecordingLock);
    mRecordingBuffer.push_back(sample);
  }

  void commitRecording() {
    // Called when recording stops - swap to active
    std::lock_guard<std::mutex> lock(mRecordingLock);
    int inactive = 1 - mActiveBuffer.load(std::memory_order_acquire);
    mBuffers[inactive] = std::move(mRecordingBuffer);
    mRecordingBuffer.clear();
    mActiveBuffer.store(inactive, std::memory_order_release);
  }

  void normalize() {
    int active = mActiveBuffer.load(std::memory_order_acquire);
    if (mBuffers[active].empty())
      return;
    int inactive = 1 - active;
    mBuffers[inactive] = mBuffers[active];
    float maxVal = 0.0f;
    for (float s : mBuffers[inactive])
      maxVal = std::max(maxVal, std::abs(s));
    if (maxVal > 0.0001f) {
      float gain = 0.95f / maxVal;
      for (auto &s : mBuffers[inactive])
        s *= gain;
    }
    mActiveBuffer.store(inactive, std::memory_order_release);
  }

  void trim() {
    int active = mActiveBuffer.load(std::memory_order_acquire);
    const auto &buf = mBuffers[active];
    if (buf.empty())
      return;
    size_t start = static_cast<size_t>(mTrimStart * buf.size());
    size_t end = static_cast<size_t>(mTrimEnd * buf.size());
    if (end > buf.size())
      end = buf.size();
    if (start >= end) {
      if (end > 0)
        start = end - 1;
      else
        return;
    }
    int inactive = 1 - active;
    mBuffers[inactive] =
        std::vector<float>(buf.begin() + start, buf.begin() + end);
    mActiveBuffer.store(inactive, std::memory_order_release);
    mTrimStart = 0.0f;
    mTrimEnd = 1.0f;
    std::lock_guard<std::mutex> lock(mSliceLock);
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

  void setSampleRate(float sr) {
    mSampleRate = (int)sr;
    for (auto &v : mVoices) {
      v.envelope.setSampleRate(sr);
      v.filter.setParams(1000.0f, 0.7f, sr);
    }
  }

  void setGlide(float g) { mGlide = g; }

  void triggerNote(int note, int velocity) {
    int active = mActiveBuffer.load(std::memory_order_acquire);
    const auto &buf = mBuffers[active];

    if (buf.empty())
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

    v.envelope.setSampleRate(48000.0f);
    v.envelope.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.envelope.trigger();

    if ((mPlayMode == Chops || mPlayMode == OneShotChops ||
         mPlayMode == LoopChops) &&
        !mSlices.empty()) {
      // Map Note 60 -> Slice 0. Safe modulo.
      int sliceIdx = 0;
      if (note >= 60)
        sliceIdx = (note - 60);

      // Explicitly cycle through slices
      if (!mSlices.empty()) {
        sliceIdx = sliceIdx % (int)mSlices.size();
      } else {
        sliceIdx = 0;
      }

      v.start = mSlices[sliceIdx].start;
      v.end = mSlices[sliceIdx].end;
    } else {
      v.start = static_cast<size_t>(mTrimStart * buf.size());
      v.end = static_cast<size_t>(mTrimEnd * buf.size());
      if (v.end > buf.size())
        v.end = buf.size();
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

    float keyShift = (mPlayMode == Chops || mPlayMode == OneShotChops ||
                      mPlayMode == LoopChops)
                         ? 0.0f
                         : (float)(note - 60);
    float targetRatio = powf(2.0f, (mPitch + keyShift) / 12.0f) * mSpeed;
    v.targetPitchRatio = targetRatio;
    v.pitchRatio = (mGlide > 0.001f) ? mLastPitchRatio : targetRatio;
    mLastPitchRatio = targetRatio;
  }

  int getPlayMode() const { return mPlayMode; } // Added Getter for AudioEngine

  void releaseNote(int note) {
    for (auto &v : mVoices) {
      if (v.active && v.note == note) {
        if (mPlayMode == Sustain || mPlayMode == Chops || mPlayMode == Loop ||
            mPlayMode == LoopChops) {
          v.envelope.release();
        }
      }
    }
  }

  void setParameter(int id, float value) {
    switch (id) {
    case 1: // Cutoff
      setFilterCutoff(value);
      break;
    case 2: // Resonance
      setFilterResonance(value);
      break;
    case 300: // PITCH: changes the pitch but keeps playback time constant
      mPitch = (value - 0.5f) * 48.0f;
      for (auto &v : mVoices) {
        if (v.active) {
          float keyShift = (mPlayMode == Chops || mPlayMode == OneShotChops ||
                            mPlayMode == LoopChops)
                               ? 0.0f
                               : (float)(v.note - 60);
          v.targetPitchRatio = powf(2.0f, (mPitch + keyShift) / 12.0f) * mSpeed;
        }
      }
      break;
    case 301: // STRETCH: changes playback time but keeps pitch constant
      mStretch = value * 4.0f;
      break;
    case 302: // SPEED: changes both pitch and playback time together
      mSpeed = value * 2.0f;
      for (auto &v : mVoices) {
        if (v.active) {
          float keyShift = (mPlayMode == Chops || mPlayMode == OneShotChops ||
                            mPlayMode == LoopChops)
                               ? 0.0f
                               : (float)(v.note - 60);
          v.targetPitchRatio = powf(2.0f, (mPitch + keyShift) / 12.0f) * mSpeed;
        }
      }
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
    case 355:
      setGlide(value);
      break;
    case 320:
      if (value < 0.16f)
        mPlayMode = OneShot;
      else if (value < 0.33f)
        mPlayMode = Sustain;
      else if (value < 0.5f)
        mPlayMode = Loop;
      else if (value < 0.66f)
        mPlayMode = Chops;
      else if (value < 0.83f)
        mPlayMode = OneShotChops;
      else
        mPlayMode = LoopChops;
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
      int count = static_cast<int>(value * 15.0f) + 1; // 1 to 16
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
    const auto &buffer = getBuffer();
    if (buffer.empty())
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

      if (mGlide > 0.001f) {
        float glideTimeSamples = mGlide * mSampleRate * 0.5f;
        float glideAlpha = 1.0f / (glideTimeSamples + 1.0f);
        v.pitchRatio += (v.targetPitchRatio - v.pitchRatio) * glideAlpha;
      } else {
        v.pitchRatio = v.targetPitchRatio;
      }

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
      float traverseRate = mSpeed * (mReverse ? -1.0f : 1.0f);
      float readRate = v.pitchRatio;
      bool useGranular = (std::abs(traverseRate - readRate) > 0.001f) ||
                         (std::abs(mStretch - 1.0f) > 0.02f);
      if (std::abs(mStretch - 1.0f) > 0.02f)
        traverseRate /= std::max(0.01f, mStretch);

      // ONLY use Granular if actively stretching time (mStretch != 1.0)

      // Update loop/trim points dynamically during playback if not in chops
      // mode
      if (mPlayMode != Chops && mPlayMode != OneShotChops &&
          mPlayMode != LoopChops) {
        v.start = static_cast<size_t>(mTrimStart * buffer.size());
        v.end = static_cast<size_t>(mTrimEnd * buffer.size());
      }

      float voiceOutput = 0.0f;
      if (!useGranular) {
        // Classic mode: Resampling (Pitch and Time are linked)
        v.position += baseResampleRate;
        if (v.position >= v.end || v.position < v.start) {
          if (mPlayMode == Sustain || mPlayMode == Loop ||
              mPlayMode == LoopChops) {
            v.position = mReverse ? (double)v.end - 1.0 : (double)v.start;
          } else {
            v.envelope.release();
          }
        }
        int idx = static_cast<int>(v.position);
        // Fix: Prevent playing past slice end in Chops modes (One Chop)
        if (idx >= 0 && idx < (int)buffer.size()) {
          // If we are in a non-looping mode, strictly enforce v.end
          if (mPlayMode == OneShot || mPlayMode == Chops ||
              mPlayMode == OneShotChops) {
            if (idx < (int)v.end) {
              voiceOutput = buffer[idx];
            }
          } else {
            voiceOutput = buffer[idx];
          }
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

        float s1 = 0.0f;
        if (idx1 >= 0 && idx1 < (int)buffer.size()) {
          if (mPlayMode == OneShot || mPlayMode == Chops ||
              mPlayMode == OneShotChops) {
            if (idx1 < (int)v.end)
              s1 = buffer[idx1];
          } else {
            s1 = buffer[idx1];
          }
        }

        float s2 = 0.0f;
        if (idx2 >= 0 && idx2 < (int)buffer.size()) {
          if (mPlayMode == OneShot || mPlayMode == Chops ||
              mPlayMode == OneShotChops) {
            if (idx2 < (int)v.end)
              s2 = buffer[idx2];
          } else {
            s2 = buffer[idx2];
          }
        }

        voiceOutput = (s1 * w1) + (s2 * (1.0f - w1));

        if (v.grainTimer >= Voice::GRAIN_SIZE) {
          v.grainTimer = 0;
        }

        if (v.position >= v.end || v.position < v.start) {
          if (mPlayMode == Sustain || mPlayMode == Loop ||
              mPlayMode == LoopChops) {
            v.position = mReverse ? (double)v.end - 1.0 : (double)v.start;
          } else {
            v.envelope.release();
          }
        }
      }

      // Filter Processing
      if (v.controlCounter++ % 16 == 0) {
        float cutoff = 20.0f + (mFilterCutoff * mFilterCutoff * 18000.0f);
        // Integrate envelope to filter cutoff
        cutoff += env * mFilterEnvAmount * 12000.0f;
        cutoff = std::max(20.0f, std::min(20000.0f, cutoff));

        v.filter.setParams(cutoff, 0.7f + mFilterResonance * 5.0f, 48000.0f);
      }
      voiceOutput = v.filter.process(voiceOutput, TSvf::LowPass);

      mixedOutput += voiceOutput * env * v.baseVelocity;
    }

    if (activeCount > 1)
      mixedOutput *= (1.0f / sqrtf((float)activeCount));

    return mixedOutput;
  }

  void findConstrainedSlices(int count) {
    const auto &buf = getBuffer();
    std::lock_guard<std::mutex> lock(mSliceLock);
    mSlices.clear();
    if (buf.empty() || count <= 0)
      return;

    size_t totalSamples = buf.size();
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
          float s = buf[j + k];
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
    const auto &buf = getBuffer();
    std::lock_guard<std::mutex> lock(mSliceLock);
    mSlices.clear();
    if (buf.empty() || count <= 0)
      return;
    size_t step = buf.size() / count;
    for (int i = 0; i < count; ++i) {
      mSlices.push_back({i * step, (i + 1) * step});
    }
  }

  std::vector<float> getSlicePoints() const {
    const auto &buf = getBuffer();
    std::vector<float> points;
    if (buf.empty())
      return points;
    for (const auto &s : mSlices) {
      points.push_back((float)s.start / (float)buf.size());
    }
    return points;
  }

  std::vector<float> getAmplitudeWaveform(int numPoints) const {
    const auto &buf = getBuffer();
    std::vector<float> result;
    if (buf.empty())
      return result;
    int step = buf.size() / numPoints;
    if (step < 1)
      step = 1;
    for (int i = 0; i < numPoints; ++i) {
      float maxVal = 0.0f;
      int end = std::min((int)buf.size(), (i + 1) * step);
      for (int j = i * step; j < end; ++j) {
        maxVal = std::max(maxVal, std::abs(buf[j]));
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

  // Double-buffering for lock-free audio
  mutable std::atomic<int> mActiveBuffer{0};
  std::vector<float> mBuffers[2];
  std::mutex mRecordingLock;
  std::vector<float> mRecordingBuffer;
  std::mutex mSliceLock;

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
  float mGlide = 0.0f, mLastPitchRatio = 1.0f;
  PlayMode mPlayMode = OneShot;
  bool mUseEnvelope = true;
  int mSampleRate = 48000;

  std::vector<Slice> mSlices;
};

#endif // SAMPLER_ENGINE_H
