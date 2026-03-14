package com.groovebox.utils

/**
 * DX7 Sysex Parser - Parses Yamaha DX7 .syx files into FM engine parameters.
 * 
 * Supports:
 * - 32-voice bulk dump (4104 bytes, F0 43 00 09 20 00 ... F7)
 * - Single voice dump (163 bytes, F0 43 00 00 01 1B ... F7)
 */
data class DX7Voice(
    val name: String,
    val algorithm: Int,           // 0-31
    val feedback: Int,            // 0-7
    val opLevels: FloatArray,     // [6] normalized 0-1
    val opRatios: FloatArray,     // [6] frequency ratios
    val opAttack: FloatArray,     // [6] normalized 0-1
    val opDecay: FloatArray,      // [6] normalized 0-1
    val opSustain: FloatArray,    // [6] normalized 0-1
    val opRelease: FloatArray,    // [6] normalized 0-1
    val carrierMask: Int          // bitmask of which ops are carriers
)

object SysexParser {
    
    // DX7 algorithm carrier masks (which operators are carriers for each algorithm)
    // DX7 operators are numbered 1-6, we use 0-5 index. Bit 0 = Op1, Bit 5 = Op6.
    private val DX7_CARRIER_MASKS = intArrayOf(
        0x01,       // Alg 1:  carrier 1
        0x01,       // Alg 2:  carrier 1
        0x01,       // Alg 3:  carrier 1
        0x01,       // Alg 4:  carrier 1  
        0x09,       // Alg 5:  carriers 1,4
        0x09,       // Alg 6:  carriers 1,4
        0x05,       // Alg 7:  carriers 1,3
        0x05,       // Alg 8:  carriers 1,3
        0x05,       // Alg 9:  carriers 1,3
        0x09,       // Alg 10: carriers 1,4
        0x09,       // Alg 11: carriers 1,4
        0x11,       // Alg 12: carriers 1,5
        0x11,       // Alg 13: carriers 1,5
        0x01,       // Alg 14: carrier 1
        0x01,       // Alg 15: carrier 1
        0x01,       // Alg 16: carrier 1
        0x01,       // Alg 17: carrier 1
        0x03,       // Alg 18: carriers 1,2
        0x07,       // Alg 19: carriers 1,2,3
        0x19,       // Alg 20: carriers 1,4,5
        0x1D,       // Alg 21: carriers 1,3,4,5
        0x1F,       // Alg 22: carriers 1,2,3,4,5
        0x19,       // Alg 23: carriers 1,4,5
        0x0D,       // Alg 24: carriers 1,3,4
        0x0F,       // Alg 25: carriers 1,2,3,4
        0x0D,       // Alg 26: carriers 1,3,4
        0x09,       // Alg 27: carriers 1,4
        0x0B,       // Alg 28: carriers 1,2,4
        0x15,       // Alg 29: carriers 1,3,5
        0x17,       // Alg 30: carriers 1,2,3,5
        0x1F,       // Alg 31: carriers 1,2,3,4,5
        0x3F        // Alg 32: all carriers (additive)
    )

    /**
     * Parse a .syx file byte array into a list of DX7Voice objects.
     */
    fun parse(data: ByteArray): List<DX7Voice> {
        if (data.size < 6) return emptyList()
        
        // Check for sysex start
        if (data[0] != 0xF0.toByte()) return emptyList()
        
        val voices = mutableListOf<DX7Voice>()
        
        // 32-voice bulk dump: F0 43 0s 09 20 00 <4096 bytes> checksum F7
        if (data.size >= 4104 && (data[3].toInt() and 0xFF) == 0x09) {
            val voiceDataStart = 6
            for (i in 0 until 32) {
                val offset = voiceDataStart + (i * 128)
                if (offset + 128 <= data.size) {
                    val voice = parsePackedVoice(data, offset)
                    if (voice != null) voices.add(voice)
                }
            }
        }
        // Single voice dump: F0 43 0s 00 01 1B <155 bytes> checksum F7
        else if (data.size >= 163 && (data[3].toInt() and 0xFF) == 0x00) {
            val voice = parseSingleVoice(data, 6)
            if (voice != null) voices.add(voice)
        }
        
        return voices
    }
    
    /**
     * Parse a packed voice from a 32-voice bulk dump (128 bytes per voice).
     * DX7 packed format: operators are stored in reverse order (op6 first, op1 last).
     */
    private fun parsePackedVoice(data: ByteArray, offset: Int): DX7Voice? {
        try {
            val opLevels = FloatArray(6)
            val opRatios = FloatArray(6)
            val opAttack = FloatArray(6)
            val opDecay = FloatArray(6)
            val opSustain = FloatArray(6)
            val opRelease = FloatArray(6)
            
            // Each operator = 17 bytes in packed format
            // Operators stored in reverse: op6 at offset+0, op1 at offset+85
            for (op in 0 until 6) {
                val opOffset = offset + ((5 - op) * 17) // Reverse order
                
                // Envelope rates (R1-R4) and levels (L1-L4)
                val r1 = (data[opOffset + 0].toInt() and 0x7F).toFloat() / 99f
                val r2 = (data[opOffset + 1].toInt() and 0x7F).toFloat() / 99f
                val r3 = (data[opOffset + 2].toInt() and 0x7F).toFloat() / 99f
                val r4 = (data[opOffset + 3].toInt() and 0x7F).toFloat() / 99f
                
                // Map DX7 rates to ADSR
                opAttack[op] = 1.0f - r1.coerceIn(0f, 1f)    // Higher rate = faster attack
                opDecay[op] = 1.0f - r2.coerceIn(0f, 1f)      // Higher rate = faster decay
                opSustain[op] = (data[opOffset + 6].toInt() and 0x7F).toFloat() / 99f // L3 = sustain level
                opRelease[op] = 1.0f - r4.coerceIn(0f, 1f)    // Higher rate = faster release
                
                // Output Level (byte 16)
                val outputLevel = (data[opOffset + 16].toInt() and 0x7F).toFloat() / 99f
                opLevels[op] = outputLevel.coerceIn(0f, 1f)
                
                // Frequency: coarse (byte 13 bits 0-4) and fine (byte 14)
                val freqCoarse = (data[opOffset + 13].toInt() and 0x1F)
                val freqFine = (data[opOffset + 14].toInt() and 0x7F).toFloat() / 100f
                val detune = ((data[opOffset + 12].toInt() and 0x78) shr 3) - 7 // -7 to +7
                
                // Oscillator mode: 0 = ratio, 1 = fixed
                val oscMode = (data[opOffset + 13].toInt() and 0x20) shr 5
                
                if (oscMode == 0) {
                    // Ratio mode
                    val coarseRatio = if (freqCoarse == 0) 0.5f else freqCoarse.toFloat()
                    opRatios[op] = (coarseRatio + freqFine) / 16f // Normalize to 0-1 range for Loom's *16 scaling
                } else {
                    // Fixed frequency - approximate as ratio
                    opRatios[op] = 1.0f / 16f // Default 1:1 for fixed freq
                }
            }
            
            // Global parameters (bytes 102-117)
            val pitchEnvOffset = offset + 102
            val algorithm = (data[offset + 110].toInt() and 0x1F) // 0-31
            val feedback = (data[offset + 111].toInt() and 0x38) shr 3 // bits 3-5
            
            // Voice name (bytes 118-127, 10 ASCII chars)
            val nameBytes = ByteArray(10)
            for (i in 0 until 10) {
                nameBytes[i] = (data[offset + 118 + i].toInt() and 0x7F).toByte()
            }
            val name = String(nameBytes).trim()
            
            val carrierMask = if (algorithm in DX7_CARRIER_MASKS.indices) 
                DX7_CARRIER_MASKS[algorithm] else 0x01
            
            return DX7Voice(
                name = name.ifEmpty { "DX7 Patch" },
                algorithm = algorithm,
                feedback = feedback,
                opLevels = opLevels,
                opRatios = opRatios,
                opAttack = opAttack,
                opDecay = opDecay,
                opSustain = opSustain,
                opRelease = opRelease,
                carrierMask = carrierMask
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Parse a single voice from the expanded 155-byte format.
     * Each operator = 21 bytes (expanded), operators in reverse order.
     */
    private fun parseSingleVoice(data: ByteArray, offset: Int): DX7Voice? {
        try {
            val opLevels = FloatArray(6)
            val opRatios = FloatArray(6)
            val opAttack = FloatArray(6)
            val opDecay = FloatArray(6)
            val opSustain = FloatArray(6)
            val opRelease = FloatArray(6)
            
            // Each operator = 21 bytes in single voice format
            for (op in 0 until 6) {
                val opOffset = offset + ((5 - op) * 21)
                
                val r1 = (data[opOffset + 0].toInt() and 0x7F).toFloat() / 99f
                val r2 = (data[opOffset + 1].toInt() and 0x7F).toFloat() / 99f
                val r3 = (data[opOffset + 2].toInt() and 0x7F).toFloat() / 99f
                val r4 = (data[opOffset + 3].toInt() and 0x7F).toFloat() / 99f
                
                opAttack[op] = 1.0f - r1.coerceIn(0f, 1f)
                opDecay[op] = 1.0f - r2.coerceIn(0f, 1f)
                opSustain[op] = (data[opOffset + 6].toInt() and 0x7F).toFloat() / 99f
                opRelease[op] = 1.0f - r4.coerceIn(0f, 1f)
                
                val outputLevel = (data[opOffset + 16].toInt() and 0x7F).toFloat() / 99f
                opLevels[op] = outputLevel.coerceIn(0f, 1f)
                
                val oscMode = (data[opOffset + 17].toInt() and 0x01)
                val freqCoarse = (data[opOffset + 18].toInt() and 0x1F)
                val freqFine = (data[opOffset + 19].toInt() and 0x7F).toFloat() / 100f
                val detune = (data[opOffset + 20].toInt() and 0x0F) - 7
                
                if (oscMode == 0) {
                    val coarseRatio = if (freqCoarse == 0) 0.5f else freqCoarse.toFloat()
                    opRatios[op] = (coarseRatio + freqFine) / 16f
                } else {
                    opRatios[op] = 1.0f / 16f
                }
            }
            
            // Global parameters start at byte 126
            val algorithm = (data[offset + 134].toInt() and 0x1F)
            val feedback = (data[offset + 135].toInt() and 0x07)
            
            val nameBytes = ByteArray(10)
            for (i in 0 until 10) {
                nameBytes[i] = (data[offset + 145 + i].toInt() and 0x7F).toByte()
            }
            val name = String(nameBytes).trim()
            
            val carrierMask = if (algorithm in DX7_CARRIER_MASKS.indices)
                DX7_CARRIER_MASKS[algorithm] else 0x01
            
            return DX7Voice(
                name = name.ifEmpty { "DX7 Patch" },
                algorithm = algorithm,
                feedback = feedback,
                opLevels = opLevels,
                opRatios = opRatios,
                opAttack = opAttack,
                opDecay = opDecay,
                opSustain = opSustain,
                opRelease = opRelease,
                carrierMask = carrierMask
            )
        } catch (e: Exception) {
            return null
        }
    }
}
