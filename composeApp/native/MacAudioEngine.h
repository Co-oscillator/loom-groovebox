#ifndef MAC_AUDIO_ENGINE_H
#define MAC_AUDIO_ENGINE_H

#include <AudioUnit/AudioUnit.h>
#include <AudioToolbox/AudioToolbox.h>
#include "AudioEngineCore.h"
#include <memory>

class MacAudioEngine {
public:
    MacAudioEngine();
    ~MacAudioEngine();

    bool start();
    void stop();

    AudioEngineCore& getCore() { return *mCore; }

private:
    static OSStatus audioCallback(void *inRefCon,
                                AudioUnitRenderActionFlags *ioActionFlags,
                                const AudioTimeStamp *inTimeStamp,
                                UInt32 inBusNumber,
                                UInt32 inNumberFrames,
                                AudioBufferList *ioData);

    std::unique_ptr<AudioEngineCore> mCore;
    AudioComponentInstance mAudioUnit;
    AudioComponentInstance mInputUnit;
    bool mIsRunning = false;
    double mSampleRate = 48000.0;
    
    // Input support
    float* mInputBuffer = nullptr;
    UInt32 mInputBufferSize = 0;

    static OSStatus inputCallback(void *inRefCon,
                                 AudioUnitRenderActionFlags *ioActionFlags,
                                 const AudioTimeStamp *inTimeStamp,
                                 UInt32 inBusNumber,
                                 UInt32 inNumberFrames,
                                 AudioBufferList *ioData);
};

#endif // MAC_AUDIO_ENGINE_H
