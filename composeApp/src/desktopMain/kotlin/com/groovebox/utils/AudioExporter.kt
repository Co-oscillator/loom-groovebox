package com.groovebox.utils

/**
 * Desktop implementation of AudioExporter.
 * For now, this is a stub. In the future, it could use FFmpeg or a simple WAV writer.
 */
actual object AudioExporter {
    actual fun encodeToAAC(pcmData: FloatArray, outputPath: String, sampleRate: Int, bitrate: Int) {
        // TODO: Implement AAC encoding for Desktop
        println("AAC encoding is not yet implemented for Desktop. Path: $outputPath")
    }

    actual fun encodeToFLAC(pcmData: FloatArray, outputPath: String, sampleRate: Int) {
        // TODO: Implement FLAC encoding for Desktop
        println("FLAC encoding is not yet implemented for Desktop. Path: $outputPath")
    }
}
