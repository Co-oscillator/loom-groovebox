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

    // Output first: playback must come up even if the microphone is
    // unavailable. On a first launch the input unit blocks inside CoreAudio
    // until the macOS permission prompt is answered, and that used to run
    // before the output unit was started, leaving the app silent.
    if (!startOutput()) return false;

    mIsRunning = true;
    mCore->updateSampleRate(48000.0);
    LOGD("MacAudioEngine output started at 48kHz");

    if (startInput()) {
        LOGD("MacAudioEngine started successfully. Input (HAL) and Output (Default) active at 48kHz");
    } else {
        LOGD("MacAudioEngine: microphone input unavailable, continuing without it");
    }
    return true;
}

bool MacAudioEngine::startOutput() {
    // 1. Output Unit (Default Output)
    AudioComponentDescription outDesc;
    outDesc.componentType = kAudioUnitType_Output;
    outDesc.componentSubType = kAudioUnitSubType_DefaultOutput;
    outDesc.componentManufacturer = kAudioUnitManufacturer_Apple;
    outDesc.componentFlags = 0;
    outDesc.componentFlagsMask = 0;

    AudioComponent outComp = AudioComponentFindNext(NULL, &outDesc);
    OSStatus status = AudioComponentInstanceNew(outComp, &mAudioUnit);
    if (status != noErr) {
        LOGD("Error: Could not create output AudioUnit: %d", (int)status);
        mAudioUnit = nullptr;
        return false;
    }

    UInt32 maxFrames = 4096;
    AudioUnitSetProperty(mAudioUnit, kAudioUnitProperty_MaximumFramesPerSlice, kAudioUnitScope_Global, 0, &maxFrames, sizeof(maxFrames));

    // --- Output format ---
    AudioStreamBasicDescription format;
    format.mSampleRate = 48000.0;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    format.mFramesPerPacket = 1;
    format.mChannelsPerFrame = 2; // Output remains Stereo
    format.mBitsPerChannel = 32;
    format.mBytesPerPacket = 8;
    format.mBytesPerFrame = 8;

    AudioUnitSetProperty(mAudioUnit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof(format));

    // --- Render callback ---
    AURenderCallbackStruct outputCallbackStruct;
    outputCallbackStruct.inputProc = MacAudioEngine::audioCallback;
    outputCallbackStruct.inputProcRefCon = this;
    AudioUnitSetProperty(mAudioUnit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &outputCallbackStruct, sizeof(outputCallbackStruct));

    status = AudioUnitInitialize(mAudioUnit);
    if (status != noErr) {
        LOGD("Error: AudioUnitInitialize failed for output unit: %d", (int)status);
        AudioComponentInstanceDispose(mAudioUnit);
        mAudioUnit = nullptr;
        return false;
    }

    status = AudioOutputUnitStart(mAudioUnit);
    if (status != noErr) {
        LOGD("Error: AudioOutputUnitStart failed for output unit: %d", (int)status);
        AudioUnitUninitialize(mAudioUnit);
        AudioComponentInstanceDispose(mAudioUnit);
        mAudioUnit = nullptr;
        return false;
    }

    mOutputRunning = true;
    return true;
}

bool MacAudioEngine::startInput() {
    // 2. Input Unit (HAL Output)
    AudioComponentDescription inDesc;
    inDesc.componentType = kAudioUnitType_Output;
    inDesc.componentSubType = kAudioUnitSubType_HALOutput;
    inDesc.componentManufacturer = kAudioUnitManufacturer_Apple;
    inDesc.componentFlags = 0;
    inDesc.componentFlagsMask = 0;

    AudioComponent inComp = AudioComponentFindNext(NULL, &inDesc);
    OSStatus status = AudioComponentInstanceNew(inComp, &mInputUnit);
    if (status != noErr) {
        LOGD("Error: Could not create input AudioUnit: %d", (int)status);
        mInputUnit = nullptr;
        return false;
    }

    // Any failure from here on releases the partially built unit; the output
    // side keeps running either way.
    auto fail = [this](const char *what, OSStatus st) {
        LOGD("Error: %s: %d", what, (int)st);
        if (mInputUnit) {
            AudioComponentInstanceDispose(mInputUnit);
            mInputUnit = nullptr;
        }
        return false;
    };

    // --- Configure Input Unit ---
    UInt32 enableIO = 1;
    status = AudioUnitSetProperty(mInputUnit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input, 1, &enableIO, sizeof(enableIO));
    if (status != noErr) LOGD("Error enabling input IO: %d", (int)status);

    enableIO = 0;
    status = AudioUnitSetProperty(mInputUnit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Output, 0, &enableIO, sizeof(enableIO));
    if (status != noErr) LOGD("Error disabling input output-scope IO: %d", (int)status);

    UInt32 maxFrames = 4096;
    AudioUnitSetProperty(mInputUnit, kAudioUnitProperty_MaximumFramesPerSlice, kAudioUnitScope_Global, 0, &maxFrames, sizeof(maxFrames));

    // Default Input Device
    AudioDeviceID inputDevice;
    UInt32 size = sizeof(AudioDeviceID);
    AudioObjectPropertyAddress address = { kAudioHardwarePropertyDefaultInputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
    status = AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, NULL, &size, &inputDevice);
    if (status == noErr) {
        LOGD("Selected Default Input Device ID: %u", (unsigned int)inputDevice);
        status = AudioUnitSetProperty(mInputUnit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &inputDevice, sizeof(inputDevice));
        if (status != noErr) {
            LOGD("Error: Could not set current device on input unit: %d", (int)status);
        }
    } else {
        LOGD("Error: Could not get default input device: %d", (int)status);
    }

    // Input AU: Switch to MONO for better hardware compatibility
    AudioStreamBasicDescription format;
    format.mSampleRate = 48000.0;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    format.mFramesPerPacket = 1;
    format.mChannelsPerFrame = 1;
    format.mBitsPerChannel = 32;
    format.mBytesPerPacket = 4;
    format.mBytesPerFrame = 4;

    status = AudioUnitSetProperty(mInputUnit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output, 1, &format, sizeof(format));
    if (status != noErr) LOGD("Error setting input stream format: %d", (int)status);

    // --- Set callbacks ---
    AURenderCallbackStruct inputCallbackStruct;
    inputCallbackStruct.inputProc = MacAudioEngine::inputCallback;
    inputCallbackStruct.inputProcRefCon = this;
    status = AudioUnitSetProperty(mInputUnit, kAudioOutputUnitProperty_SetInputCallback, kAudioUnitScope_Global, 0, &inputCallbackStruct, sizeof(inputCallbackStruct));
    if (status != noErr) LOGD("Error setting input callback: %d", (int)status);

    mInputBufferSize = 4096 * 2 * sizeof(float);
    mInputBuffer = (float*)malloc(mInputBufferSize);

    status = AudioUnitInitialize(mInputUnit);
    if (status != noErr) return fail("AudioUnitInitialize failed for input unit", status);

    status = AudioOutputUnitStart(mInputUnit);
    if (status != noErr) return fail("AudioOutputUnitStart failed for input unit", status);

    mInputRunning = true;
    return true;
}

void MacAudioEngine::stop() {
    if (mInputRunning) {
        AudioOutputUnitStop(mInputUnit);
        mInputRunning = false;
    }
    if (mInputUnit) {
        AudioUnitUninitialize(mInputUnit);
        AudioComponentInstanceDispose(mInputUnit);
        mInputUnit = nullptr;
    }
    if (mOutputRunning) {
        AudioOutputUnitStop(mAudioUnit);
        mOutputRunning = false;
    }
    if (mAudioUnit) {
        AudioUnitUninitialize(mAudioUnit);
        AudioComponentInstanceDispose(mAudioUnit);
        mAudioUnit = nullptr;
    }
    if (mInputBuffer) {
        free(mInputBuffer);
        mInputBuffer = nullptr;
    }
    mIsRunning = false;
    LOGD("MacAudioEngine stopped");
}

OSStatus MacAudioEngine::inputCallback(void *inRefCon,
                                     AudioUnitRenderActionFlags *ioActionFlags,
                                     const AudioTimeStamp *inTimeStamp,
                                     UInt32 inBusNumber,
                                     UInt32 inNumberFrames,
                                     AudioBufferList *ioData) {
    MacAudioEngine *engine = static_cast<MacAudioEngine*>(inRefCon);
    
    AudioBufferList bufferList;
    bufferList.mNumberBuffers = 1;
    bufferList.mBuffers[0].mNumberChannels = 1; // Mono
    bufferList.mBuffers[0].mDataByteSize = inNumberFrames * sizeof(float);
    bufferList.mBuffers[0].mData = engine->mInputBuffer;

    OSStatus status = AudioUnitRender(engine->mInputUnit, ioActionFlags, inTimeStamp, 1, inNumberFrames, &bufferList);
    if (status == noErr) {
        float* data = static_cast<float*>(bufferList.mBuffers[0].mData);
        engine->mCore->pushInputSamples(data, inNumberFrames, 1); // Pass as 1 channel
    } else {
        static int errorCount = 0;
        if (errorCount++ % 100 == 0) {
            LOGD("Error: AudioUnitRender failed for input (bus %u): %d", (unsigned int)inBusNumber, (int)status);
        }
    }
    return noErr;
}

OSStatus MacAudioEngine::audioCallback(void *inRefCon,
                                     AudioUnitRenderActionFlags *ioActionFlags,
                                     const AudioTimeStamp *inTimeStamp,
                                     UInt32 inBusNumber,
                                     UInt32 inNumberFrames,
                                     AudioBufferList *ioData) {
    MacAudioEngine *engine = static_cast<MacAudioEngine*>(inRefCon);
    if (ioData->mNumberBuffers > 0) {
        float *buffer = static_cast<float*>(ioData->mBuffers[0].mData);
        engine->mCore->render(buffer, inNumberFrames);
    }
    return noErr;
}
