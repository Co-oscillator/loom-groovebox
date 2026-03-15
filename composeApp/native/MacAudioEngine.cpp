#include "MacAudioEngine.h"
#include "Log.h"

MacAudioEngine::MacAudioEngine() : mCore(std::make_unique<AudioEngineCore>()) {
    LOGD("MacAudioEngine initialized");
}

MacAudioEngine::~MacAudioEngine() {
    stop();
}

bool MacAudioEngine::start() {
    if (mIsRunning) return true;

    AudioComponentDescription desc;
    desc.componentType = kAudioUnitType_Output;
    desc.componentSubType = kAudioUnitSubType_DefaultOutput;
    desc.componentManufacturer = kAudioUnitManufacturer_Apple;
    desc.componentFlags = 0;
    desc.componentFlagsMask = 0;

    AudioComponent comp = AudioComponentFindNext(NULL, &desc);
    if (comp == NULL) {
        LOGD("Error: Could not find default output audio component");
        return false;
    }

    OSStatus status = AudioComponentInstanceNew(comp, &mAudioUnit);
    if (status != noErr) {
        LOGD("Error: AudioComponentInstanceNew failed with status %d", (int)status);
        return false;
    }

    // Set up format: 32-bit float, stereo, interleaved
    AudioStreamBasicDescription format;
    format.mSampleRate = 44100.0; // We can adjust this if needed
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    format.mFramesPerPacket = 1;
    format.mChannelsPerFrame = 2;
    format.mBitsPerChannel = 32;
    format.mBytesPerPacket = 8;
    format.mBytesPerFrame = 8;

    status = AudioUnitSetProperty(mAudioUnit,
                                 kAudioUnitProperty_StreamFormat,
                                 kAudioUnitScope_Input,
                                 0,
                                 &format,
                                 sizeof(format));
    if (status != noErr) {
        LOGD("Error: Could not set input format on AudioUnit");
        return false;
    }

    // Set callback
    AURenderCallbackStruct callbackStruct;
    callbackStruct.inputProc = MacAudioEngine::audioCallback;
    callbackStruct.inputProcRefCon = this;

    status = AudioUnitSetProperty(mAudioUnit,
                                 kAudioUnitProperty_SetRenderCallback,
                                 kAudioUnitScope_Global,
                                 0,
                                 &callbackStruct,
                                 sizeof(callbackStruct));
    if (status != noErr) {
        LOGD("Error: Could not set render callback");
        return false;
    }

    status = AudioUnitInitialize(mAudioUnit);
    if (status != noErr) {
        LOGD("Error: AudioUnitInitialize failed");
        return false;
    }

    status = AudioOutputUnitStart(mAudioUnit);
    if (status != noErr) {
        LOGD("Error: AudioOutputUnitStart failed");
        return false;
    }

    mIsRunning = true;
    mCore->updateSampleRate(44100.0);
    LOGD("MacAudioEngine started successfully at 44.1kHz");
    return true;
}

void MacAudioEngine::stop() {
    if (!mIsRunning) return;

    AudioOutputUnitStop(mAudioUnit);
    AudioUnitUninitialize(mAudioUnit);
    AudioComponentInstanceDispose(mAudioUnit);
    mIsRunning = false;
    LOGD("MacAudioEngine stopped");
}

OSStatus MacAudioEngine::audioCallback(void *inRefCon,
                                     AudioUnitRenderActionFlags *ioActionFlags,
                                     const AudioTimeStamp *inTimeStamp,
                                     UInt32 inBusNumber,
                                     UInt32 inNumberFrames,
                                     AudioBufferList *ioData) {
    MacAudioEngine *engine = static_cast<MacAudioEngine*>(inRefCon);
    float *outL = static_cast<float*>(ioData->mBuffers[0].mData);
    
    // CoreAudio defaults to interleaved if we requested it, or non-interleaved if configured.
    // Our AudioEngineCore::render expects interleaved float data: [L, R, L, R...]
    // But CoreAudio default output unit often wants interleaved in a single buffer if mono/stereo is defined this way.
    // Let's verify the buffer config. 
    
    if (ioData->mNumberBuffers > 0) {
        float *buffer = static_cast<float*>(ioData->mBuffers[0].mData);
        engine->mCore->render(buffer, inNumberFrames);
    }

    return noErr;
}
