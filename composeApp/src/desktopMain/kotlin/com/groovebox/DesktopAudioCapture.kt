package com.groovebox

import javax.sound.sampled.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DesktopAudioCapture {
    private var targetDataLine: TargetDataLine? = null
    private var captureJob: Job? = null
    private val nativeLib = NativeLib()
    
    fun startCapture() {
        if (captureJob?.isActive == true) return
        
        try {
            // Standard audio format: 48kHz, 16-bit, Mono, signed, little-endian
            val format = AudioFormat(48000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            
            if (!AudioSystem.isLineSupported(info)) {
                println("DesktopAudioCapture: Audio format not supported by system.")
                return
            }
            
            targetDataLine = AudioSystem.getLine(info) as TargetDataLine
            targetDataLine?.open(format)
            targetDataLine?.start()
            
            captureJob = CoroutineScope(Dispatchers.IO).launch {
                val bufferSize = targetDataLine?.bufferSize ?: 4096
                // Read in reasonably small chunks for low latency
                val byteBuffer = ByteArray(bufferSize / 4)
                
                while (isActive && targetDataLine != null) {
                    val bytesRead = targetDataLine?.read(byteBuffer, 0, byteBuffer.size) ?: 0
                    if (bytesRead > 0) {
                        // Convert 16-bit PCM bytes to FloatArray
                        val shorts = ShortArray(bytesRead / 2)
                        ByteBuffer.wrap(byteBuffer, 0, bytesRead)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shorts)
                            
                        val floatBuffer = FloatArray(shorts.size)
                        for (i in shorts.indices) {
                            floatBuffer[i] = shorts[i] / 32768f
                        }
                        
                        // Push to native engine
                        nativeLib.pushSystemAudioSamples(floatBuffer)
                    }
                }
            }
        } catch (e: Exception) {
            println("DesktopAudioCapture: Failed to start capture - ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        
        try {
            targetDataLine?.stop()
            targetDataLine?.close()
        } catch (e: Exception) {
            println("DesktopAudioCapture: Error stopping capture - ${e.message}")
        }
        targetDataLine = null
    }
}
