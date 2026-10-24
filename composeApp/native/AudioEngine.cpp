#include "AudioEngine.h"
#include "DenormalDisabler.h"
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/resource.h>

#undef LOG_TAG
#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioEngine::AudioEngine() {
    mCore = std::make_unique<AudioEngineCore>();
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    // Idempotent: if output stream is already running, do nothing
    if (mOutputStream && mOutputStream->getState() == oboe::StreamState::Started) {
        return true;
    }

    // Close any stale streams
    stop();

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output);
    outBuilder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    outBuilder.setSharingMode(oboe::SharingMode::Exclusive);
    outBuilder.setFormat(oboe::AudioFormat::Float);
    outBuilder.setChannelCount(2);
    outBuilder.setSampleRate(48000);
    outBuilder.setCallback(this);

    oboe::Result result = outBuilder.openStream(mOutputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    // Request 4x burst size to provide a massive safety margin for the OS scheduler
    // when the CPU is throttled or under high load (60%+).
    mOutputStream->setBufferSizeInFrames(mOutputStream->getFramesPerBurst() * 4);

    // Open Input Stream
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input);
    inBuilder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    inBuilder.setSharingMode(oboe::SharingMode::Exclusive);
    inBuilder.setFormat(oboe::AudioFormat::Float);
    inBuilder.setChannelCount(1); // Mic is mono
    inBuilder.setSampleRate(mOutputStream->getSampleRate());
    inBuilder.setInputPreset(oboe::InputPreset::Camcorder);
    inBuilder.setCallback(this);

    result = inBuilder.openStream(mInputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream. Error: %s. Continuing with output only.", oboe::convertToText(result));
        mInputStream.reset();
    }

    // Update Core with actual sample rate
    mCore->updateSampleRate((float)mOutputStream->getSampleRate());

    // --- ADPF Symbol Resolution (v3.1.25: Jitter Fix) ---
    void* libandroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (libandroid) {
        mGetManagerFn = (APHint_getManagerFn)dlsym(libandroid, "APerformanceHintManager_getManager");
        mCreateSessionFn = (APHint_createSessionFn)dlsym(libandroid, "APerformanceHintManager_createSession");
        mReportDurationFn = (APHint_reportActualWorkDurationFn)dlsym(libandroid, "APerformanceHintSession_reportActualWorkDuration");
        
        if (mGetManagerFn && mCreateSessionFn && mReportDurationFn) {
            mAdpfManager = mGetManagerFn();
        }
    }

    if (mInputStream && mCore->isInputNeeded()) {
        result = mInputStream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start input stream: %s", oboe::convertToText(result));
            mInputStream->close();
            mInputStream.reset();
        }
    }

    result = mOutputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        stop();
        return false;
    }

    LOGD("Audio engine started: Out=%d Hz, In=%s", 
         mOutputStream->getSampleRate(), mInputStream ? "Active" : "Failed");
    return true;
}

void AudioEngine::stop() {
    if (mOutputStream) {
        mOutputStream->stop();
        mOutputStream->close();
        mOutputStream.reset();
    }
    if (mInputStream) {
        mInputStream->stop();
        mInputStream->close();
        mInputStream.reset();
    }
}

void AudioEngine::refreshInputStreamState() {
    if (!mInputStream) return;

    bool needed = mCore->isInputNeeded();
    oboe::StreamState state = mInputStream->getState();

    if (needed && state == oboe::StreamState::Open) {
        LOGD("Starting input stream (dynamic)");
        mInputStream->requestStart();
    } else if (!needed && state == oboe::StreamState::Started) {
        // We could stop it, but often it's better to just leave it running 
        // to avoid glitching when starting again.
        // For now, let's just leave it running once started, 
        // or stop it if we want to save power.
        // LOGD("Stopping input stream (dynamic)");
        // mInputStream->requestStop();
    }
}

#include <unistd.h>
#include <dlfcn.h>

// NDK ADPF Function Pointers
typedef APerformanceHintManager* (*APHint_getManagerFn)();
typedef APerformanceHintSession* (*APHint_createSessionFn)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
typedef int (*APHint_reportActualWorkDurationFn)(APerformanceHintSession*, int64_t);

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *audioStream,
                                                   void *audioData,
                                                   int32_t numFrames) {
    // 1. Start Timing for ADPF (v3.1.25: Real-time safe loop)
    struct timespec startTime, endTime;
    if (mAdpfInitialized && mReportDurationFn) {
        clock_gettime(CLOCK_MONOTONIC, &startTime);
    }

    // 2. Initialize ADPF session on first callback once TID is known
    // (Actual functions already resolved in start())
    if (!mAdpfInitialized && mAdpfManager && mCreateSessionFn) {
        mAudioThreadTid = gettid();
        int32_t tids[1] = { mAudioThreadTid };
        // v3.2.5: Dynamic target duration based on actual numFrames
        mTargetDurationNs = static_cast<int64_t>(numFrames * 1000000000LL / audioStream->getSampleRate());
        mAdpfSession = mCreateSessionFn(mAdpfManager, tids, 1, mTargetDurationNs);
        mAdpfInitialized = (mAdpfSession != nullptr);
        
        // v3.1.38: Thread Priority Boost
        // Signal to the Android scheduler that this thread is critical.
#ifdef __ANDROID__
        setpriority(PRIO_PROCESS, 0, -19);
#endif
    }

    DenormalDisabler disabler;
    if (audioStream->getDirection() == oboe::Direction::Output) {
        mCore->render(static_cast<float *>(audioData), numFrames);

        // 3. Report Performance to OS (Wait-free pointer call)
        if (mAdpfInitialized && mAdpfSession && mReportDurationFn) {
            clock_gettime(CLOCK_MONOTONIC, &endTime);
            int64_t actualDurationNs = (endTime.tv_sec - startTime.tv_sec) * 1000000000LL + (endTime.tv_nsec - startTime.tv_nsec);
            mReportDurationFn(mAdpfSession, actualDurationNs);
        }
    } else {
        // Asynchronous Input Callback
        if (!mCore->isInputNeeded()) {
            return oboe::DataCallbackResult::Continue; 
        }
        mCore->pushInputSamples(static_cast<const float *>(audioData), numFrames, 1);
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result result) {
    if (result == oboe::Result::ErrorDisconnected) {
        LOGE("Audio stream disconnected, restarting...");
        start();
    }
}
