#ifndef ARPEGGIATOR_H
#define ARPEGGIATOR_H

#include "ChordProgressionEngine.h"
#include <algorithm>
#include <random>
#include <vector>

enum class ArpMode {
  OFF = 0,
  UP = 1,
  DOWN = 2,
  UP_DOWN = 3,
  STAGGER_UP = 4,
  STAGGER_DOWN = 5,
  RANDOM = 6,
  BACH = 7,
  BROWNIAN = 8,
  CONVERGE = 9,
  DIVERGE = 10
};

class Arpeggiator {
public:
  Arpeggiator()
      : mMode(ArpMode::OFF), mStep(0), mOctaves(0), mInversion(0),
        mIsLatched(false), mIsWaitingForNewGesture(false), mUpperLane1Index(0),
        mUpperLane2Index(0) {
    // Default: Lane 0 (Root) active, Lanes 1 & 2 inactive
    mRhythms.resize(3, std::vector<bool>(16, false));
    std::fill(mRhythms[0].begin(), mRhythms[0].end(), true);
    mScaleIntervals = {0, 2, 4, 5, 7, 9, 11}; // Default Major
  }

  void setChordProgConfig(bool enabled, int mood, int complexity) {
    mIsChordProgEnabled = enabled;
    mChordProgMood = mood;
    mChordProgComplexity = complexity;
    generateChordProgression();
    updateSequence();
  }

  void setScaleConfig(int rootNote, const std::vector<int> &scaleIntervals) {
    mRootNote = rootNote;
    mScaleIntervals = scaleIntervals;
    generateChordProgression();
    updateSequence();
  }

  void setMode(ArpMode mode) {
    mMode = mode;
    mStep = 0;
  }
  ArpMode getMode() const { return mMode; }
  void setOctaves(int octaves) {
    mOctaves = octaves;
    updateSequence();
  }
  void setInversion(int inversion) {
    mInversion = inversion;
    updateSequence();
  }
  void setRhythm(const std::vector<std::vector<bool>> &rhythms) {
    mRhythms = rhythms;
  }
  void setRandomSequence(const std::vector<int> &sequence) {
    mRandomSequence = sequence;
  }
  void setIsMutated(bool mutated) { mIsMutated = mutated; }

  bool isLatched() const { return mIsLatched; }

  void setLatched(bool latched) {
    mIsLatched = latched;
    if (!latched) {
      mHeldNotes.clear();
      mSequence.clear();
      mIsWaitingForNewGesture = false;
    }
  }

  const std::vector<int> &getNotes() const { return mHeldNotes; }

  void addNote(int note) {
    if (mIsLatched && mIsWaitingForNewGesture) {
      mHeldNotes.clear();
      mIsWaitingForNewGesture = false;
    }

    if (std::find(mHeldNotes.begin(), mHeldNotes.end(), note) ==
        mHeldNotes.end()) {
      mHeldNotes.push_back(note);
      std::sort(mHeldNotes.begin(), mHeldNotes.end());
      generateChordProgression();
      updateSequence();
    }
  }

  void removeNote(int note) {
    if (mIsLatched) {
      // In latched mode, we don't remove notes until a new gesture starts
      return;
    }
    auto it = std::find(mHeldNotes.begin(), mHeldNotes.end(), note);
    if (it != mHeldNotes.end()) {
      mHeldNotes.erase(it);
      generateChordProgression();
      updateSequence();
    }
  }

  void onAllPhysicallyReleased() {
    if (mIsLatched) {
      mIsWaitingForNewGesture = true;
    } else {
      mHeldNotes.clear();
      updateSequence();
    }
  }

  void clear() {
    mHeldNotes.clear();
    mSequence.clear();
    mGeneratedChordProgression.clear();
    mStep = 0;
    mLastHarmonicStep = -1;
    mIsWaitingForNewGesture = false;
  }

  std::vector<int> nextNotes() {
    if (mSequence.empty() || mMode == ArpMode::OFF || mRhythms.empty())
      return {};

    // Check for harmonic step change
    if (mIsChordProgEnabled && !mGeneratedChordProgression.empty()) {
      int harmonicStep = (mStep / mStepsPerChord) % 8;
      if (harmonicStep != mLastHarmonicStep) {
        mLastHarmonicStep = harmonicStep;
        updateSequence(); // Refresh sequence with new chord notes merged
      }
    }

    std::vector<int> notesToPlay;
    int stepIndex = mStep % 16; // 16 step pattern

    int seqSize = mSequence.size();

    // Lane 0: Root/Main Note
    if (mRhythms.size() > 0 && mRhythms[0][stepIndex]) {
      int idx = mStep % seqSize;

      // Removed old mutation logic as requested by user ("no longer needed")

      int noteIdx = mSequence[idx];
      if (mInversion != 0 && (mStep % seqSize) == 0) {
        noteIdx += mInversion * 12; // Apply inversion to root of cycle
      }
      notesToPlay.push_back(noteIdx);
    }

    // Lane 1: +1 Walk
    if (mRhythms.size() > 1 && mRhythms[1][stepIndex]) {
      if (seqSize > 1) {
        int idx = (mStep + 1) % seqSize;
        notesToPlay.push_back(mSequence[idx]);
      }
    }

    // Lane 2: +2 Walk
    if (mRhythms.size() > 2 && mRhythms[2][stepIndex]) {
      if (seqSize > 2) {
        int idx = (mStep + 2) % seqSize;
        notesToPlay.push_back(mSequence[idx]);
      }
    }

    mStep++;
    return notesToPlay;
  }

  void reset() { mStep = 0; }

private:
  ArpMode mMode;
  int mStep;
  int mOctaves;
  int mInversion;
  bool mIsLatched;
  bool mIsMutated = false;
  bool mIsWaitingForNewGesture;
  std::vector<int> mHeldNotes;
  std::vector<int> mSequence;
  std::vector<std::vector<bool>> mRhythms; // 3 lanes x 8 steps
  std::vector<int> mRandomSequence;

  bool mIsChordProgEnabled = false;
  int mChordProgMood = 0;
  int mChordProgComplexity = 0;
  int mRootNote = 48; // C3
  std::vector<int> mScaleIntervals;
  std::vector<std::vector<int>> mGeneratedChordProgression;

  int mLastHarmonicStep = -1;
  const int mStepsPerChord = 32;

  // Track staggering indices for upper lanes
  int mUpperLane1Index = 0;
  int mUpperLane2Index = 0;

  void generateChordProgression() {
    if (mIsChordProgEnabled && !mHeldNotes.empty()) {
      mGeneratedChordProgression = ChordProgressionEngine::generateProgression(
          mRootNote, mScaleIntervals, mChordProgMood,
          static_cast<Complexity>(mChordProgComplexity), mHeldNotes);
    } else {
      mGeneratedChordProgression.clear();
    }
    mLastHarmonicStep = -1;
  }

  void updateSequence() {
    mSequence.clear();
    if (mHeldNotes.empty()) {
      mLastHarmonicStep = -1;
      return;
    }

    std::vector<int> baseNotes = mHeldNotes;

    // Merge Chord Progression Notes
    if (mIsChordProgEnabled && !mGeneratedChordProgression.empty()) {
      int harmonicStep = (mStep / mStepsPerChord) % 8;
      std::vector<int> chord = mGeneratedChordProgression[harmonicStep];
      for (int n : chord) {
        if (std::find(baseNotes.begin(), baseNotes.end(), n) ==
            baseNotes.end()) {
          baseNotes.push_back(n);
        }
      }
    }

    // Expand octaves
    std::vector<int> expanded;
    int startOct = std::min(0, mOctaves);
    int endOct = std::max(0, mOctaves);
    for (int o = startOct; o <= endOct; ++o) {
      for (int n : baseNotes) {
        expanded.push_back(n + (o * 12));
      }
    }
    std::sort(expanded.begin(), expanded.end());
    expanded.erase(std::unique(expanded.begin(), expanded.end()),
                   expanded.end());

    switch (mMode) {
    case ArpMode::UP:
      mSequence = expanded;
      break;
    case ArpMode::DOWN:
      mSequence = expanded;
      std::reverse(mSequence.begin(), mSequence.end());
      break;
    case ArpMode::UP_DOWN:
      mSequence = expanded;
      for (int i = expanded.size() - 2; i > 0; --i) {
        mSequence.push_back(expanded[i]);
      }
      break;
    case ArpMode::STAGGER_UP:
      // Example: 1-3-2-4-3-5...
      for (size_t i = 0; i < expanded.size(); ++i) {
        mSequence.push_back(expanded[i]);
        if (i + 2 < expanded.size()) {
          mSequence.push_back(expanded[i + 2]);
        }
      }
      break;
    case ArpMode::STAGGER_DOWN:
      mSequence = expanded;
      std::reverse(mSequence.begin(), mSequence.end());
      // similar stagger logic
      break;
    case ArpMode::RANDOM:
      if (!mRandomSequence.empty()) {
        for (int idx : mRandomSequence) {
          mSequence.push_back(expanded[idx % expanded.size()]);
        }
      } else {
        mSequence = expanded;
        std::shuffle(mSequence.begin(), mSequence.end(),
                     std::mt19937(std::random_device()()));
      }
      break;
    case ArpMode::BACH: {
      // 3 steps forward, 1 step back
      if (expanded.empty())
        break;
      int size = expanded.size();
      // Generate a loop that covers the progression
      // Approx length: size * 1.5
      for (int i = 0; i < size + 4; ++i) { // Safety margin
        int groupSize = 3;
        int baseShift = i / groupSize;
        int internalStep = i % groupSize;
        int idx = (baseShift + internalStep) % size;
        mSequence.push_back(expanded[idx]);
      }
      break;
    }
    case ArpMode::CONVERGE: {
      // 0, Max, 1, Max-1...
      if (expanded.empty())
        break;
      int size = expanded.size();
      for (int i = 0; i < size; ++i) {
        int offset = i / 2;
        int idx = (i % 2 == 0) ? offset : (size - 1 - offset);
        if (idx >= 0 && idx < size) {
          mSequence.push_back(expanded[idx]);
        }
      }
      break;
    }
    case ArpMode::DIVERGE: {
      // Inner -> Outer
      if (expanded.empty())
        break;
      int size = expanded.size();
      int center = size / 2;
      for (int i = 0; i < size; ++i) {
        int offset = (i + 1) / 2;
        int idx = center + (offset * ((i % 2 == 1) ? -1 : 1));
        if (idx >= 0 && idx < size) {
          mSequence.push_back(expanded[idx]);
        }
      }
      break;
    }
    case ArpMode::BROWNIAN: {
      // Random walk simulation (pre-calc for loop stability)
      if (expanded.empty())
        break;
      int size = expanded.size();
      int current = 0;
      std::mt19937 rng(std::random_device{}());
      std::uniform_int_distribution<int> dist(-1, 1);

      // Generate a nice long walk
      for (int i = 0; i < 32; ++i) {
        mSequence.push_back(expanded[current]);
        int move = dist(rng);
        current = std::clamp(current + move, 0, size - 1);
      }
      break;
    }
    default:
      break;
    }
  }
};

#endif
