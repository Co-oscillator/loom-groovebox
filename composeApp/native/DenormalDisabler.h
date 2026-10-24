#ifndef DENORMAL_DISABLER_H
#define DENORMAL_DISABLER_H

#include <cstdint>

/**
 * DenormalDisabler: A RAII utility to disable denormal floating point numbers
 * at the hardware level on ARM/Android. This significantly improves performance
 * when signals decay towards zero (Release phase), preventing audio crackling.
 * 
 * Usage:
 *     void onAudioReady(...) {
 *         DenormalDisabler disabler; // Sets FTZ bits
 *         // ... DSP processing ...
 *     } // Original FP state restored here
 */
class DenormalDisabler {
public:
    DenormalDisabler() {
#if defined(__aarch64__)
        // ARM64: Modify FPCR (Floating-point Control Register)
        asm volatile("mrs %0, fpcr" : "=r"(mOriginalState));
        uint64_t newState = mOriginalState | (1ULL << 24); // FZ (Flush-to-Zero)
        asm volatile("msr fpcr, %0" : : "r"(newState));
#elif defined(__arm__)
        // ARMv7 (32-bit): Modify FPSCR (Floating-point Status and Control Register)
        asm volatile("vmrs %0, fpscr" : "=r"(mOriginalState));
        uint32_t newState = mOriginalState | (1UL << 24); // FZ (Flush-to-Zero)
        asm volatile("vmsr fpscr, %0" : : "r"(newState));
#endif
    }

    ~DenormalDisabler() {
#if defined(__aarch64__)
        asm volatile("msr fpcr, %0" : : "r"(mOriginalState));
#elif defined(__arm__)
        asm volatile("vmsr fpscr, %0" : : "r"(mOriginalState));
#endif
    }

private:
#if defined(__aarch64__)
    uint64_t mOriginalState = 0;
#elif defined(__arm__)
    uint32_t mOriginalState = 0;
#else
    int mOriginalState = 0;
#endif
};

#endif // DENORMAL_DISABLER_H
