#ifndef SEQUENCER_H
#define SEQUENCER_H

#include <map>
#include <vector>

struct Step {
  struct NoteInfo {
    int note = 60;
    float velocity = 0.8f;
    float subStepOffset = 0.0f; // 0.0 to 1.0 within the step
  };

  bool active = false;
  std::vector<NoteInfo> notes;
  int ratchet = 1;    // 1 = regular, 2 = double, etc.
  bool punch = false; // 1.1x volume + overdrive
  float probability = 1.0f;
  float gate = 1.0f;                   // 1.0 = full step
  std::map<int, float> parameterLocks; // CC ID -> Locked Value

  void addNote(int n, float vel = 0.8f, float offset = 0.0f) {
    for (auto &existing : notes) {
      if (existing.note == n) {
        existing.velocity = vel;
        existing.subStepOffset = offset;
        return;
      }
    }
    notes.push_back({n, vel, offset});
    active = true;
  }

  void removeNote(int n) {
    for (auto it = notes.begin(); it != notes.end(); ++it) {
      if (it->note == n) {
        notes.erase(it);
        break;
      }
    }
    if (notes.empty())
      active = false;
  }
};

class Sequencer {
public:
  Sequencer() {
    mSteps.resize(128);
    mNextStep = 0;
  }

  void setConfiguration(int numPages, int stepsPerPage) {
    mNumPages = numPages;
    mStepsPerPage = stepsPerPage;
  }

  void setStep(int index, const Step &step) {
    if (index >= 0 && index < 128)
      mSteps[index] = step;
  }

  void setSwing(float swing) { mSwing = swing; }
  void setPlaybackDirection(int direction) { mDirection = direction; }
  void setIsRandomOrder(bool isRandom) { mIsRandom = isRandom; }
  void setIsJumpMode(bool isJump) { mIsJumpMode = isJump; }

  void jumpToStep(int step) {
    if (step >= 0 && step < mSteps.size()) {
      mNextStep = step;
      mCurrentStep = step; // Immediate update
    }
  }

  void setParameterLock(int stepIndex, int parameterId, float value) {
    if (stepIndex >= 0 && stepIndex < 128) {
      mSteps[stepIndex].parameterLocks[parameterId] = value;
    }
  }

  void clearParameterLocks(int stepIndex) {
    if (stepIndex >= 0 && stepIndex < 128) {
      mSteps[stepIndex].parameterLocks.clear();
    }
  }

  void clear() {
    for (auto &step : mSteps) {
      step.active = false;
      step.notes.clear();
      step.parameterLocks.clear();
    }
  }

  void advance() {
    int totalSteps = mNumPages * mStepsPerPage;
    if (totalSteps <= 0)
      return;

    mCurrentStep = mNextStep;

    if (mIsRandom) {
      mNextStep = rand() % totalSteps;
    } else {
      if (mDirection == 0) { // Forward
        mNextStep = (mCurrentStep + 1) % totalSteps;
      } else { // Backward
        mNextStep = (mCurrentStep - 1 + totalSteps) % totalSteps;
      }
    }
  }

  const Step &getCurrentStep() const { return mSteps[mCurrentStep]; }
  int getCurrentStepIndex() const { return mCurrentStep; }
  int getCurrentPage() const { return mCurrentStep / mStepsPerPage; }

  float getSwing() const { return mSwing; }
  bool isEvenStep() const { return (mCurrentStep % 2) == 0; }
  const std::vector<Step> &getSteps() const { return mSteps; }
  std::vector<Step> &getStepsMutable() { return mSteps; }

private:
  std::vector<Step> mSteps;
  int mCurrentStep = 0;
  int mNextStep = 0;
  int mNumPages = 1;
  int mStepsPerPage = 16;

  float mSwing = 0.0f;
  int mDirection = 0; // 0: Forward, 1: Backward
  bool mIsRandom = false;
  bool mIsJumpMode = false;
};

#endif // SEQUENCER_H
