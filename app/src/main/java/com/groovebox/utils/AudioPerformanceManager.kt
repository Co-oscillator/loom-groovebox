package com.groovebox.utils

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log

class AudioPerformanceManager(private val context: Context) {
    private var hintSession: PerformanceHintManager.Session? = null
    private val TAG = "AudioPerformance"

    fun startSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val manager = context.getSystemService(PerformanceHintManager::class.java)
                if (manager == null) {
                    Log.w(TAG, "PerformanceHintManager not available")
                    return
                }
                
                val pids = intArrayOf(android.os.Process.myPid())
                // Target: ~10ms for a typical audio callback (480 samples @ 48kHz is 10ms)
                // We'll report 16ms as a safe ceiling for the UI+Audio work.
                hintSession = manager.createHintSession(pids, 16666666L)
                Log.d(TAG, "ADPF Hint Session started for PID ${pids[0]}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ADPF session: ${e.message}")
            }
        }
    }

    fun stopSession() {
        hintSession?.close()
        hintSession = null
    }

    /**
     * Report actual work duration in nanoseconds.
     * While the audio thread is in C++, reporting UI thread work also helps
     * keep the relevant CPU clusters at appropriate frequencies.
     */
    fun reportActualWork(durationNs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.reportActualWorkDuration(durationNs)
        }
    }
}
