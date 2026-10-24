#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include "AudioEngineCore.h"
#include <memory>
#include <android/performance_hint.h>
#include <time.h>

class AudioEngine : public oboe::AudioStreamCallback {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void stop();
    void refreshInputStreamState();

    AudioEngineCore& getCore() { return *mCore; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *audioStream,
                           oboe::Result result) override;
    int getAudioThreadTid() const { return mAudioThreadTid; }

private:
    int mAudioThreadTid = -1;
    std::unique_ptr<AudioEngineCore> mCore;
    std::shared_ptr<oboe::AudioStream> mOutputStream;
    std::shared_ptr<oboe::AudioStream> mInputStream;
    // ADPF Native Support & Function Pointers (v3.1.25)
    typedef APerformanceHintManager* (*APHint_getManagerFn)();
    typedef APerformanceHintSession* (*APHint_createSessionFn)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
    typedef int (*APHint_reportActualWorkDurationFn)(APerformanceHintSession*, int64_t);

    APerformanceHintManager* mAdpfManager = nullptr;
    APerformanceHintSession* mAdpfSession = nullptr;
    APHint_getManagerFn mGetManagerFn = nullptr;
    APHint_createSessionFn mCreateSessionFn = nullptr;
    APHint_reportActualWorkDurationFn mReportDurationFn = nullptr;
    
    int64_t mTargetDurationNs = 0;
    bool mAdpfInitialized = false;
};

#endif // AUDIO_ENGINE_H
