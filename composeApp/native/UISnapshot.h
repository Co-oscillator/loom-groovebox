#ifndef UI_SNAPSHOT_H
#define UI_SNAPSHOT_H

#include <vector>
#include <cstring>
#include <bitset>

/**
 * UISnapshot stores a consistent, low-contention copy of the engine's state
 * specifically for the UI thread to poll via JNI.
 */
struct UISnapshot {
    struct TrackState {
        float parameters[1024];
        std::bitset<64> activeSteps;
        std::bitset<64> activeDrumSteps[16];
        int currentStep = 0;
        
        TrackState() {
            std::memset(parameters, 0, sizeof(parameters));
            activeSteps.reset();
            for(int i=0; i<16; ++i) activeDrumSteps[i].reset();
            currentStep = 0;
        }
    };

    TrackState tracks[8];
    float trackLevels[8] = {0.0f}; // Envelope follower level for each track (activity highlight)
    float masterVolume = 0.8f;
    bool isPlaying = false;
    bool isRecording = false;

    UISnapshot() {
        isPlaying = false;
        isRecording = false;
        masterVolume = 0.8f;
    }
};

#endif // UI_SNAPSHOT_H
