package com.example.s22hls.media

object AacAdts {
    fun buildAdts(sampleRate: Int, channels: Int, aacDataLen: Int): ByteArray {
        val freqIdx = when(sampleRate){
            96000->0; 88200->1; 64000->2; 48000->3; 44100->4; 32000->5; 24000->6; 22050->7;
            16000->8; 12000->9; 11025->10; 8000->11; 7350->12; else->3
        }
        val profile = 1 // AAC LC
        val adtsLen = aacDataLen + 7
        val bytes = ByteArray(7)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xF1.toByte()
        bytes[2] = (((profile) shl 6) or (freqIdx shl 2) or ((channels shr 2) and 0x1)).toByte()
        bytes[3] = (((channels and 0x3) shl 6) or ((adtsLen shr 11) and 0x3).toByte().toInt()).toByte()
        bytes[4] = ((adtsLen shr 3) and 0xFF).toByte()
        bytes[5] = (((adtsLen and 0x7) shl 5) or 0x1F).toByte()
        bytes[6] = 0xFC.toByte()
        return bytes
    }
}
