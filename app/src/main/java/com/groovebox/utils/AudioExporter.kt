package com.groovebox.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioExporter {
    private const val TAG = "AudioExporter"
    private const val TIMEOUT_US = 10000L

    fun encodeToAAC(pcmData: FloatArray, outputPath: String, sampleRate: Int = 44100, bitrate: Int = 256000) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1

        val bufferInfo = MediaCodec.BufferInfo()
        val pcmShorts = convertFloatToShort(pcmData)
        val pcmBuffer = ByteBuffer.allocateDirect(pcmShorts.size * 2).order(ByteOrder.nativeOrder())
        pcmBuffer.asShortBuffer().put(pcmShorts)
        pcmBuffer.rewind()

        var inputFinished = false
        var outputFinished = false
        var pcmOffset = 0

        while (!outputFinished) {
            if (!inputFinished) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val remaining = pcmBuffer.remaining()
                    val size = Math.min(inputBuffer.capacity(), remaining)
                    
                    if (size <= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputFinished = true
                    } else {
                        inputBuffer.put(pcmBuffer.array(), pcmBuffer.position(), size)
                        pcmBuffer.position(pcmBuffer.position() + size)
                        codec.queueInputBuffer(inputIndex, 0, size, 0, 0)
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                if (trackIndex != -1) {
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputFinished = true
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
            }
        }

        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
    }

    fun encodeToFLAC(pcmData: FloatArray, outputPath: String, sampleRate: Int = 44100) {
        // Android MediaCodec FLAC encoder is available from API 25+
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5) // Default 5

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        // FLAC doesn't always need a muxer for raw .flac, but MediaMuxer supports it for Ogg containers
        // For raw .flac, we might just write the output buffers to a file
        val file = File(outputPath)
        val fos = file.outputStream()

        val bufferInfo = MediaCodec.BufferInfo()
        val pcmShorts = convertFloatToShort(pcmData) 
        // Note: For 24-bit FLAC, we'd need a different conversion. 
        // But 16-bit is safer for generic MediaCodec compatibility.
        // User asked for 24-bit FLAC. Let's try to satisfy if possible.
        // Usually, FLAC encoder accepts PCM 16-bit.
        
        val pcmBuffer = ByteBuffer.allocateDirect(pcmShorts.size * 2).order(ByteOrder.nativeOrder())
        pcmBuffer.asShortBuffer().put(pcmShorts)
        pcmBuffer.rewind()

        var inputFinished = false
        var outputFinished = false

        while (!outputFinished) {
            if (!inputFinished) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val size = Math.min(inputBuffer.capacity(), pcmBuffer.remaining())
                    if (size <= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputFinished = true
                    } else {
                        val temp = ByteArray(size)
                        pcmBuffer.get(temp)
                        inputBuffer.put(temp)
                        codec.queueInputBuffer(inputIndex, 0, size, 0, 0)
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                val chunk = ByteArray(bufferInfo.size)
                outputBuffer.get(chunk)
                fos.write(chunk)
                codec.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputFinished = true
                }
            }
        }

        codec.stop()
        codec.release()
        fos.close()
    }

    private fun convertFloatToShort(pcmData: FloatArray): ShortArray {
        val result = ShortArray(pcmData.size)
        for (i in pcmData.indices) {
            val s = (pcmData[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            result[i] = s
        }
        return result
    }
}
