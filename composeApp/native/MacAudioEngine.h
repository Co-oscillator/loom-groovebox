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
    bool mIsRunning = false;
    double mSampleRate = 44100.0;
};

#endif // MAC_AUDIO_ENGINE_H
