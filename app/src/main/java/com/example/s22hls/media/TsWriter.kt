package com.example.s22hls.media

import java.io.OutputStream
import java.io.ByteArrayOutputStream
import kotlin.math.min

class TsWriter(
    private val out: OutputStream,
    private val pcrPid: Int = VIDEO_PID
) {
    companion object {
        const val TS_PACKET_SIZE = 188
        const val SYNC_BYTE = 0x47
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0101
        const val AUDIO_PID = 0x0102
        const val STREAM_TYPE_HEVC = 0x24
        const val STREAM_TYPE_AAC = 0x0F
    }

    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var ccAudio = 0

    // ------------------------------------------------------------
    // TS packet
    // ------------------------------------------------------------
    private fun writePacket(
        pid: Int,
        payloadUnitStart: Boolean,
        pcrBase: Long?,
        data: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var off = offset
        var len = length
        val buf = ByteArray(TS_PACKET_SIZE)
        buf[0] = SYNC_BYTE.toByte()
        val pus = if (payloadUnitStart) 0x40 else 0x00
        buf[1] = ((if (pid >= 0x100) 0x40 else 0x00) or pus or ((pid shr 8) and 0x1F)).toByte()
        buf[2] = (pid and 0xFF).toByte()

        var afc = 1 // payload only
        val ccRef = when (pid) {
            PAT_PID -> ccPat
            PMT_PID -> ccPmt
            VIDEO_PID -> ccVideo
            else -> ccAudio
        }

        val headerLen = 4
        var adaptionLen = 0
        var p = 4
        if (pcrBase != null && pid == pcrPid) {
            afc = 3 // adaptation + payload
        }
        buf[3] = ((afc shl 4) or (ccRef and 0x0F)).toByte()

        if ((afc and 0x2) != 0) {
            buf[p++] = 0 // adaptation_field_length (placeholder)
            var flags = 0
            val pcrLen = if (pcrBase != null) 6 else 0
            adaptionLen = 1 + pcrLen

            val stuffing = TS_PACKET_SIZE - headerLen - adaptionLen - len
            val stuff = if (stuffing < 0) 0 else stuffing
            adaptionLen += stuff

            buf[4] = (adaptionLen - 1).toByte()
            if (pcrBase != null) flags = flags or 0x10
            buf[5] = flags.toByte()
            var q = 6
            if (pcrBase != null) {
                val pcr = pcrBase * 300
                buf[q++] = ((pcr shr 25) and 0xFF).toByte()
                buf[q++] = ((pcr shr 17) and 0xFF).toByte()
                buf[q++] = ((pcr shr 9) and 0xFF).toByte()
                buf[q++] = ((pcr shr 1) and 0xFF).toByte()
                buf[q++] = (((pcr and 0x1) shl 7) or 0x7E).toByte()
                buf[q++] = 0x00
            }
            while (q < 4 + adaptionLen) buf[q++] = 0xFF.toByte()
            p = 4 + adaptionLen
        }

        val avail = TS_PACKET_SIZE - p
        val n = min(avail, len)
        System.arraycopy(data, off, buf, p, n)
        out.write(buf)

        when (pid) {
            PAT_PID -> ccPat = (ccPat + 1) and 0x0F
            PMT_PID -> ccPmt = (ccPmt + 1) and 0x0F
            VIDEO_PID -> ccVideo = (ccVideo + 1) and 0x0F
            else -> ccAudio = (ccAudio + 1) and 0x0F
        }
        return n
    }

    private fun writePsi(pid: Int, table: ByteArray) {
        val payload = ByteArray(1 + table.size)
        payload[0] = 0 // pointer_field
        System.arraycopy(table, 0, payload, 1, table.size)
        var off = 0
        var pusi = true
        while (off < payload.size) {
            val chunk = min(184, payload.size - off)
            writePacket(pid, pusi, null, payload, off, chunk)
            off += chunk
            pusi = false
        }
    }

    // ------------------------------------------------------------
    // PSI
    // ------------------------------------------------------------
    private fun crc32(bytes: ByteArray): Int {
        var crc = -0x1
        for (b in bytes) {
            val c = (b.toInt() xor ((crc ushr 24) and 0xFF)) and 0xFF
            var r = c shl 24
            repeat(8) {
                r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1
            }
            crc = (crc shl 8) xor r
        }
        return crc
    }

    private fun buildPAT(): ByteArray {
        val sec = ByteArray(13)
        sec[0] = 0x00
        sec[1] = 0xB0.toByte()
        sec[2] = 0x0D
        sec[3] = 0x00; sec[4] = 0x01
        sec[5] = 0xC1.toByte()
        sec[6] = 0x00; sec[7] = 0x00
        sec[8] = 0xE1.toByte(); sec[9] = 0x00
        val crc = crc32(sec.copyOfRange(0, 12))
        sec[12] = ((crc ushr 24) and 0xFF).toByte()
        val rest = byteArrayOf(
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte()
        )
        return sec + rest
    }

    private fun buildPMT(): ByteArray {
        val esVideo = byteArrayOf(
            STREAM_TYPE_HEVC.toByte(),
            0xE0.toByte(), (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00
        )
        val esAudio = byteArrayOf(
            STREAM_TYPE_AAC.toByte(),
            0xE0.toByte(), (AUDIO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00
        )
        val sec = ByteArray(1024)
        var i = 0
        sec[i++] = 0x02
        sec[i++] = 0xB0.toByte(); sec[i++] = 0 // length placeholder
        sec[i++] = 0x00; sec[i++] = 0x01
        sec[i++] = 0xC1.toByte()
        sec[i++] = 0x00; sec[i++] = 0x00
        sec[i++] = 0xE1.toByte(); sec[i++] = 0x01 // PCR PID = VIDEO_PID
        sec[i++] = 0xF0.toByte(); sec[i++] = 0x00 // program info len
        for (b in esVideo) sec[i++] = b
        for (b in esAudio) sec[i++] = b
        val slen = i - 3 + 4 // from after table_id to end incl CRC
        sec[2] = (slen and 0xFF).toByte()
        val crc = crc32(sec.copyOfRange(0, i))
        sec[i++] = ((crc ushr 24) and 0xFF).toByte()
        sec[i++] = ((crc ushr 16) and 0xFF).toByte()
        sec[i++] = ((crc ushr 8) and 0xFF).toByte()
        sec[i++] = (crc and 0xFF).toByte()
        return sec.copyOfRange(0, i)
    }

    fun writePatPmt() {
        writePsi(PAT_PID, buildPAT())
        writePsi(PMT_PID, buildPMT())
    }

    // ------------------------------------------------------------
    // PES helpers
    // ------------------------------------------------------------
    private fun buildPesHeader(streamId: Int, ptsUs: Long, dtsUs: Long?): ByteArray {
        val pts90 = ptsUs * 90L
        val dts90 = dtsUs?.let { it * 90L }
        val flags = if (dts90 != null) 0xC0 else 0x80
        val headerDataLen = if (dts90 != null) 10 else 5
        val pesLen = 0 // 0 => unbounded in TS

        val baos = ByteArrayOutputStream()
        fun w(v: Int) = baos.write(v and 0xFF)

        w(0x00); w(0x00); w(0x01)        // start code
        w(streamId)
        w((pesLen shr 8) and 0xFF); w(pesLen and 0xFF)
        w(0x80)                          // '10' flags1
        w(flags)                         // PTS/DTS flags
        w(headerDataLen)

        fun stamp(marker: Int, v: Long) {
            val a = (marker shl 4) or (((v shr 30) and 0x07).toInt() shl 1) or 1
            val b = (((v shr 15) and 0x7FFF).toInt() shl 1) or 1
            val c = (((v and 0x7FFF).toInt() shl 1) or 1)
            w(a); w((b shr 8) and 0xFF); w(b and 0xFF); w((c shr 8) and 0xFF); w(c and 0xFF)
        }

        stamp(0x2, pts90)
        if (dts90 != null) stamp(0x1, dts90)

        return baos.toByteArray()
    }

    private fun splitToTs(pid: Int, pcrTimeUs: Long?, data: ByteArray, off0: Int, len0: Int, pusi: Boolean) {
        var off = off0
        var len = len0
        var first = true
        while (len > 0) {
            val n = writePacket(pid, pusi && first, if (first) pcrTimeUs else null, data, off, len)
            off += n
            len -= n
            first = false
        }
    }

    // ------------------------------------------------------------
    // HEVC helpers (Annex-B)
    // ------------------------------------------------------------
    private fun hevcLengthPrefixedToAnnexB(bytes: ByteArray): ByteArray {
        var off = 0
        val out = ByteArrayOutputStream()
        while (off + 4 <= bytes.size) {
            val n = ((bytes[off].toInt() and 0xFF) shl 24) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                    (bytes[off + 3].toInt() and 0xFF)
            off += 4
            if (n <= 0 || off + n > bytes.size) break
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(bytes, off, n)
            off += n
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------
    // AAC ADTS helpers
    // ------------------------------------------------------------
    private fun aacWithAdts(raw: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val srIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            7350  -> 12
            else  -> 3 // default 48k
        }
        val profile = 1 // AAC LC => '01' (profile index = 1)
        val chanCfg = channels.coerceIn(1, 2)

        val adtsLen = 7 + raw.size
        val hdr = ByteArray(7)

        hdr[0] = 0xFF.toByte()
        hdr[1] = 0xF1.toByte() // 1111 0001 (MPEG-4, layer always 00, protection_absent=1)
        hdr[2] = (((profile and 0x3) + 1) shl 6 or ((srIndex and 0xF) shl 2) or ((chanCfg shr 2) and 0x1)).toByte()
        hdr[3] = (((chanCfg and 0x3) shl 6) or ((adtsLen shr 11) and 0x03)).toByte()
        hdr[4] = ((adtsLen shr 3) and 0xFF).toByte()
        hdr[5] = (((adtsLen and 0x7) shl 5) or 0x1F).toByte() // buffer fullness 0x7FF high bits set
        hdr[6] = 0xFC.toByte() // 11111100 (number_of_raw_data_blocks_in_frame = 0)

        val out = ByteArray(hdr.size + raw.size)
        System.arraycopy(hdr, 0, out, 0, hdr.size)
        System.arraycopy(raw, 0, out, hdr.size, raw.size)
        return out
    }

    // ------------------------------------------------------------
    // Public API (usada pelo Pipeline)
    // ------------------------------------------------------------
    fun writeVideoAccessUnit(nalOrFrame: ByteArray, isKeyframe: Boolean, ptsUs: Long, dtsUs: Long, vpsSpsPps: ByteArray?) {
        // Android MediaCodec normalmente entrega HEVC "length-prefixed" (4 bytes).
        // Convertemos para Annex-B e, se for keyframe, prefixamos VPS/SPS/PPS (também em Annex-B).
        val frameAnnexB = hevcLengthPrefixedToAnnexB(nalOrFrame)
        val prefix = if (isKeyframe && vpsSpsPps != null && vpsSpsPps.isNotEmpty()) {
            hevcLengthPrefixedToAnnexB(vpsSpsPps)
        } else ByteArray(0)

        val payload = ByteArray(prefix.size + frameAnnexB.size).also {
            System.arraycopy(prefix, 0, it, 0, prefix.size)
            System.arraycopy(frameAnnexB, 0, it, prefix.size, frameAnnexB.size)
        }

        val header = buildPesHeader(0xE0, ptsUs, dtsUs)
        val pes = ByteArray(header.size + payload.size).also {
            System.arraycopy(header, 0, it, 0, header.size)
            System.arraycopy(payload, 0, it, header.size, payload.size)
        }
        splitToTs(VIDEO_PID, ptsUs, pes, 0, pes.size, true)
    }

    fun writeAudioFrame(aacEncoderOutput: ByteArray, ptsUs: Long, sampleRate: Int, channels: Int) {
        // Muitos encoders do Android entregam AAC sem ADTS dentro do MediaCodec.
        // Garantimos ADTS aqui.
        val withAdts = if (looksLikeAdts(aacEncoderOutput)) aacEncoderOutput
                       else aacWithAdts(aacEncoderOutput, sampleRate, channels)

        val header = buildPesHeader(0xC0, ptsUs, null)
        val pes = ByteArray(header.size + withAdts.size).also {
            System.arraycopy(header, 0, it, 0, header.size)
            System.arraycopy(withAdts, 0, it, header.size, withAdts.size)
        }
        splitToTs(AUDIO_PID, ptsUs, pes, 0, pes.size, true)
    }

    private fun looksLikeAdts(buf: ByteArray): Boolean {
        if (buf.size < 7) return false
        val b0 = buf[0].toInt() and 0xFF
        val b1 = buf[1].toInt() and 0xF6 // mask layer bits to 0
        return (b0 == 0xFF) && (b1 == 0xF0)
    }
}
