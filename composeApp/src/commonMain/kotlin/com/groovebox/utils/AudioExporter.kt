package com.groovebox.utils

expect object AudioExporter {
    fun encodeToAAC(pcmData: FloatArray, outputPath: String, sampleRate: Int = 44100, bitrate: Int = 256000)
    fun encodeToFLAC(pcmData: FloatArray, outputPath: String, sampleRate: Int = 44100)
}
