package com.groovebox.utils

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log

class AudioPerformanceManager(private val context: Context) {
    private var hintSession: PerformanceHintManager.Session? = null
    private val TAG = "AudioPerformance"

    private var currentTids = intArrayOf(android.os.Process.myPid())

    fun startSession(tids: IntArray = intArrayOf(android.os.Process.myPid())) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                hintSession?.close()
                val manager = context.getSystemService(PerformanceHintManager::class.java)
                if (manager == null) return
                
                currentTids = tids
                // Target: 5ms audio buffer deadline
                // WRAPPING IN COMPLETE SAFETY TO PREVENT BOOT CRASH
                try {
                    hintSession = manager.createHintSession(tids, 5000000L)
                } catch (e: Throwable) {
                    Log.e(TAG, "manager.createHintSession FAILED: ${e.message}")
                    hintSession = null
                }
                Log.d(TAG, "ADPF Hint Session status: ${if (hintSession != null) "Active" else "Unavailable"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ADPF: ${e.message}")
            }
        }
    }

    fun updateTids(newTids: IntArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
           if (hintSession == null || !newTids.contentEquals(currentTids)) {
               Log.d(TAG, "ADPF: Initializing or Refreshing Session with TIDs: ${newTids.joinToString()}")
               startSession(newTids)
           }
        }
    }

    fun stopSession() {
        hintSession?.close()
        hintSession = null
    }

    fun reportActualWork(durationNs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.reportActualWorkDuration(durationNs)
        }
    }
}
