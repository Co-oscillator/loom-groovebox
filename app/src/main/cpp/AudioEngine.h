#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include "Arpeggiator.h"
#include "EnvelopeFollower.h"
#include "RoutingMatrix.h"
#include "Sequencer.h"
#include "engines/AnalogDrumEngine.h"
#include "engines/BitcrusherFx.h"
#include "engines/ChorusFx.h"
#include "engines/CompressorFx.h"
#include "engines/DelayFx.h"
#include "engines/FilterLfoFx.h"
#include "engines/FlangerFx.h"
#include "engines/FmDrumEngine.h"
#include "engines/FmEngine.h"
#include "engines/GalacticReverb.h"
#include "engines/GranularEngine.h"
#include "engines/LfoEngine.h"
#include "engines/OctaverFx.h"
#include "engines/OverdriveFx.h"
#include "engines/PhaserFx.h"
#include "engines/SamplerEngine.h"
#include "engines/SlicerFx.h"
#include "engines/StereoSpreadFx.h"
#include "engines/SubtractiveEngine.h"
#include "engines/TapeEchoFx.h"
#include "engines/TapeWobbleFx.h"
#include "engines/WavetableEngine.h"

#include <memory>
#include <mutex>
#include <oboe/Oboe.h>
#include <vector>

class AudioEngine : public oboe::AudioStreamCallback {
public:
  AudioEngine();
  virtual ~AudioEngine();

  bool start();
  void stop();

  // AudioStreamCallback methods
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                        void *audioData,
                                        int32_t numFrames) override;

  void onErrorAfterClose(oboe::AudioStream *audioStream,
                         oboe::Result result) override;

  void setAppDataDir(const std::string &dir);
  void saveAppState();
  void loadAppState();
  std::string getLastSamplePath(int trackIndex);

  // Control methods
  void setTrackVolume(int trackIndex, float volume);
  void setEngineType(int trackIndex, int type);
  void triggerNote(int trackIndex, int note, int velocity);
  void releaseNote(int trackIndex, int note);
  void setTempo(float bpm);
  void setPlaying(bool playing);
  void setStep(int trackIndex, int stepIndex, bool active,
               const std::vector<int> &notes, float velocity = 0.8f,
               int ratchet = 1, bool punch = false, float probability = 1.0f,
               float gate = 1.0f);
  void setSequencerConfig(int trackIndex, int numPages, int stepsPerPage);
  // New helper for modulation without affecting UI state
  void updateEngineParameter(int trackIndex, int parameterId, float value);

  void setFilterType(int trackIndex, int filterType);
  void setFilterCutoff(int trackIndex, float cutoff);
  void setFilterResonance(int trackIndex, float resonance);

  // setRouting takes specific destParamId now
  void setRouting(int destTrack, int sourceTrack, int source, int dest,
                  float amount, int destParamId = -1);
  void setParameter(int trackIndex, int parameterId, float value);
  void setSwing(float swing);
  void setPatternLength(int length);
  void setPlaybackDirection(int trackIndex, int direction);
  void setIsRandomOrder(int trackIndex, bool isRandom);
  void setIsJumpMode(int trackIndex, bool isJump);
  void setSelectedFmDrumInstrument(int trackIndex, int drumIndex);
  void jumpToStep(int stepIndex);
  void setParameterLock(int trackIndex, int stepIndex, int parameterId,
                        float value);
  void clearParameterLocks(int trackIndex, int stepIndex);
  void setIsRecording(bool isRecording);
  void setResampling(bool isResampling); // New: Resampling Mode Setter
  int getCurrentStep(int trackIndex);
  void setArpConfig(int trackIndex, int mode, int octaves, int inversion,
                    bool isLatched,
                    const std::vector<std::vector<bool>> &rhythms,
                    const std::vector<int> &sequence);
  void getGranularPlayheads(int trackIndex, GranularEngine::PlayheadInfo *out,
                            int maxCount);
  void startRecordingSample(int trackIndex);
  void stopRecordingSample(int trackIndex);
  std::vector<float> getSamplerWaveform(int trackIndex, int numPoints);
  void normalizeSample(int trackIndex);
  void resetSampler(int trackIndex);
  void loadSample(int trackIndex, const std::string &path);
  void loadWavetable(int trackIndex, const std::string &path);
  void loadDefaultWavetable(int trackIndex);
  void saveSample(int trackIndex, const std::string &path); // New
  void trimSample(int trackIndex);
  void clearSequencer(int trackIndex);
  void setMasterVolume(float volume);
  bool getStepActive(int trackIndex, int stepIndex, int drumIndex = -1);
  void getStepActiveStates(int trackIndex, bool *out, int maxSize);
  std::vector<Step> getSequencerSteps(int trackIndex);
  std::vector<float> getAllTrackParameters(int trackIndex); // New sync method
  std::vector<float> getSamplerSlicePoints(int trackIndex);
  void panic();
  void loadFmPreset(int trackIndex, int presetId);
  void setClockMultiplier(int trackIndex, float multiplier);
  void setArpTriplet(int trackIndex, bool isTriplet);
  void setArpRate(int trackIndex, float rate, int divisionMode);
  float getCpuLoad();

  // Audio Export
  void renderToWav(int numCycles, const std::string &path);
  void renderStereo(float *outBuffer, int numFrames);

  // Routing / Macro Controls
  void setGenericLfoParam(int lfoIndex, int paramId, float value);
  void setMacroValue(int macroIndex, float value);
  void setFxChain(int sourceFx, int destFx);

  // MIDI Mode
  struct MidiMessage {
    int type;    // 0x90 (NoteOn), 0x80 (NoteOff)
    int channel; // Track Index (0-7)
    int data1;   // Note
    int data2;   // Velocity
  };

  // Returns flat array: [type, ch, d1, d2, type, ch, d1, d2...]
  int fetchMidiEvents(int *outBuffer, int maxEvents);

private:
  void enqueueMidiEvent(int type, int channel, int data1, int data2);
  std::vector<MidiMessage> mMidiQueue;
  std::mutex mMidiLock;

  // Command Queue for Race-Free UI->Audio Communication
  struct AudioCommand {
    enum Type { NOTE_ON, NOTE_OFF, PARAM_SET, GLOBAL_PARAM_SET };
    Type type;
    int trackIndex;
    int data1;     // note, or paramId
    float value;   // velocity, or paramValue
    int extraData; // extra
  };
  std::vector<AudioCommand> mCommandQueue;
  std::mutex mCommandLock;

  void processCommands();
  void setParameterLocked(int trackIndex, int parameterId, float value,
                          bool isFromSequencer = false);

  std::atomic<float> mCpuLoad{0.0f};
  std::shared_ptr<oboe::AudioStream> mStream;
  std::shared_ptr<oboe::AudioStream> mInputStream;

  struct Track {
    float volume = 0.8f;
    float smoothedVolume = 0.8f;
    int engineType = 0; // 0=Subtractive, 1=FM, 2=Sampler, etc.
    int selectedFmDrumInstrument = 0;
    SubtractiveEngine subtractiveEngine;
    FmEngine fmEngine;
    FmDrumEngine fmDrumEngine;
    SamplerEngine samplerEngine;
    GranularEngine granularEngine;
    WavetableEngine wavetableEngine;
    AnalogDrumEngine analogDrumEngine;

    float parameters[2500] = {0.0f};
    float appliedParameters[2500] = {0.0f}; // Values after P-locks and Mods

    struct RecordingNote {
      int note;
      int stepIndex;
      int drumIdx; // -1 if synth
      uint64_t startGlobalStep;
      double startOffset; // within step
    };
    std::vector<RecordingNote> mRecordingNotes;

    Sequencer sequencer;
    Sequencer drumSequencers[16];
    Arpeggiator arpeggiator;
    EnvelopeFollower follower;
    float fxSends[15] = {0.0f}; // Increased to 15 for new FX
    float smoothedFxSends[15] = {0.0f};

    bool isActive = false;
    float currentFrequency = 440.0f;
    float lastOutput = 0.0f;
    float gainReduction = 1.0f;
    int patternLength = 16;
    int mPhysicallyHeldNoteCount = 0;
    int midiInChannel = 17; // 1-16, 17=ALL, 0=NONE
    int midiOutChannel = 1; // 1-16
    struct PendingNote {
      int note;
      float velocity;
      double samplesRemaining;
      float gate = 0.5f;
      int ratchetCount = 1;
      bool punch = false;
    };
    std::vector<PendingNote> mPendingNotes;
    float mClockMultiplier = 1.0f;
    float mArpRate = 1.0f;    // 1.0 = 1/16th, 0.5 = 1/8th, etc.
    int mArpDivisionMode = 0; // 0=Reg, 1=Dotted, 2=Triplet
    bool mArpTriplet = false;
    double mStepCountdown = 0.0;
    double mArpCountdown = 0.0;
    int mInternalStepIndex = 0;

    struct ActiveNote {
      int note = -1;
      double durationRemaining = 0.0;
      bool active = false;
    };
    static const int MAX_POLYPHONY = 16;
    ActiveNote mActiveNotes[MAX_POLYPHONY];

    int mPunchCounter = 0; // Frames remaining for punch compression

    int mLastTriggeredNote = -1;
    double mNoteDurationRemaining = 0.0;
    double mDrumNoteDurationRemaining[16] = {0.0};
    int mDrumLastTriggeredNote[16] = {-1, -1, -1, -1, -1, -1, -1, -1,
                                      -1, -1, -1, -1, -1, -1, -1, -1};
    std::string lastSamplePath = "";
    int mSilenceFrames = 0;
  };

  std::vector<Track> mTracks;
  RoutingMatrix mRoutingMatrix;
  bool mIsPlaying = false;
  bool mIsRecording = false;       // Transport record (sequencer)
  bool mIsRecordingSample = false; // Sample capture
  bool mIsResampling = false;      // New: Record Master Mix
  int mRecordingTrackIndex = -1;
  float mBpm = 120.0f;
  double mSampleCount = 0;
  double mSamplesPerStep = 0;
  int mGlobalStepIndex = 0;
  int mPatternLength = 16;
  float mSwing = 0.0f;

  double mSampleRate = 44100.0;
  std::recursive_mutex mLock;
  void triggerNoteLocked(int trackIndex, int note, int velocity,
                         bool isSequencerTrigger = false, float gate = 0.95f,
                         bool punch = false);
  void releaseNoteLocked(int trackIndex, int note,
                         bool isSequencerTrigger = false);
  void setupTracks();

  // Global Effects
  GalacticReverb mReverbFx;
  ChorusFx mChorusFx;
  OverdriveFx mOverdriveFx;
  BitcrusherFx mBitcrusherFx;
  PhaserFx mPhaserFx;
  TapeWobbleFx mTapeWobbleFx;
  DelayFx mDelayFx;
  SlicerFx mSlicerFx;
  CompressorFx mCompressorFx;
  FilterLfoFx mFilterLfoFx{FilterLfoMode::LowPass};

  // New Effects
  FlangerFx mFlangerFx;
  StereoSpreadFx mStereoSpreadFx;
  TapeEchoFx mTapeEchoFx;
  OctaverFx mOctaverFx;

  // Generic LFOs for Routing
  LfoEngine mLfos[5];

  // Macros (Patch Points)
  struct MacroModule {
    float value = 0.0f;
    int sourceType = 0; // 0=None, 1=Strip, 2=Knob, 3=LFO
    int sourceIndex = -1;
  };
  MacroModule mMacros[6];

  // Public methods for modulation
public:
  void setMacroSource(int macroIndex, int sourceType, int sourceIndex);
  void applyModulations();

  // FX Chaining (Soft Routing)
  // Maps SourceFX Index -> DestinationFX Index. -1 means Master Mix.
  int mFxChainDest[15];

  // New Filter LFO Effects
  FilterLfoFx mHpLfoFx{FilterLfoMode::HighPass};
  FilterLfoFx mLpLfoFx{FilterLfoMode::LowPass};

  int mSidechainSourceTrack = -1;
  int mSidechainSourceDrumIdx = -1;
  float mMasterVolume = 0.8f;
  std::string mAppDataDir = "";
};

#endif // AUDIO_ENGINE_H
