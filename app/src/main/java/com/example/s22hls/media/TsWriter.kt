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
        const val STREAM_TYPE_HEVC = 0x24   // use 0x1B para H.264/AVC
        const val STREAM_TYPE_AAC  = 0x0F
    }

    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var ccAudio = 0

    // ------------------------------------------------------------
    // TS packet (com stuffing/adaptation field correto)
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

        val tei = 0
        val pusi = if (payloadUnitStart) 0x40 else 0x00
        val tp = 0
        buf[1] = (tei or pusi or tp or ((pid shr 8) and 0x1F)).toByte()  // high 5 bits do PID
        buf[2] = (pid and 0xFF).toByte()                                 // low 8 bits do PID

        // Decidimos se haverá adaptation field: PCR ou stuffing quando payload < 184
        val needPcr = (pcrBase != null) && (pid == pcrPid)
        val minimalPayloadAvail = 184 - (if (needPcr) 1 + 6 else 0) // 1(flags) + 6(PCR)
        val willNeedStuffing = len < minimalPayloadAvail

        val hasAdaptation = needPcr || willNeedStuffing
        var p = 4
        var afFlags = 0
        var pcrLen = 0
        var stuffing = 0

        if (hasAdaptation) {
            if (needPcr) { afFlags = afFlags or 0x10; pcrLen = 6 }
            // cálculo de stuffing: após header(4) + 1(len) + 1(flags) + pcr(0/6),
            // payload disponível = 188 - (4 + 1 + 1 + pcrLen)
            val availAfterAF = 188 - (4 + 1 + 1 + pcrLen)
            stuffing = if (len < availAfterAF) (availAfterAF - len) else 0

            buf[3] = ((0x2 or 0x1) shl 4 or (when (pid) {
                PAT_PID -> ccPat
                PMT_PID -> ccPmt
                VIDEO_PID -> ccVideo
                else -> ccAudio
            } and 0x0F)).toByte() // afc=3 (AF + payload)

            // adaptation_field_length = flags(1) + PCR(0/6) + stuffing
            val afLen = 1 + pcrLen + stuffing
            buf[4] = afLen.toByte()
            buf[5] = afFlags.toByte()
            var q = 6

            if (needPcr) {
                val pcr = pcrBase!! * 300
                buf[q++] = ((pcr shr 25) and 0xFF).toByte()
                buf[q++] = ((pcr shr 17) and 0xFF).toByte()
                buf[q++] = ((pcr shr 9) and 0xFF).toByte()
                buf[q++] = ((pcr shr 1) and 0xFF).toByte()
                buf[q++] = (((pcr and 0x1) shl 7) or 0x7E).toByte()
                buf[q++] = 0x00
            }
            repeat(stuffing) { buf[q++] = 0xFF.toByte() }

            p = 4 + 1 + afLen // header(4) + length + afLen
        } else {
            buf[3] = ((0x1 shl 4) or (when (pid) {
                PAT_PID -> ccPat
                PMT_PID -> ccPmt
                VIDEO_PID -> ccVideo
                else -> ccAudio
            } and 0x0F)).toByte() // afc=1 (somente payload)
        }

        val availPayload = 188 - p
        val n = min(availPayload, len)
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
        // pointer_field + seção
        val payload = ByteArray(1 + table.size)
        payload[0] = 0 // pointer_field = 0
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
    // PSI (PAT/PMT) com section_length correto
    // ------------------------------------------------------------
    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) {
            val c = (b.toInt() xor ((crc ushr 24) and 0xFF)) and 0xFF
            var r = c shl 24
            repeat(8) { r = if ((r and 0x8000_0000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            crc = (crc shl 8) xor r
        }
        return crc
    }

    private fun buildPAT(): ByteArray {
        val sec = ByteArrayOutputStream()
        fun w(v: Int) = sec.write(v and 0xFF)

        w(0x00) // table_id
        w(0xB0); w(0x00) // section_syntax_indicator(1), '0'(1), reserved(2), section_length(12) [placeholder]

        w(0x00); w(0x01) // transport_stream_id
        w(0xC1)          // version_number(5=0), current_next=1
        w(0x00)          // section_number
        w(0x00)          // last_section_number

        // program_number=1 -> PMT_PID
        w(0x00); w(0x01)
        w(0xE0 or ((PMT_PID shr 8) and 0x1F))
        w(PMT_PID and 0xFF)

        val bytesNoCrc = sec.toByteArray()
        val sectionLen = bytesNoCrc.size - 3 + 4 // from after table_id to end incl CRC
        bytesNoCrc[1] = (0xB0 or ((sectionLen shr 8) and 0x0F)).toByte()
        bytesNoCrc[2] = (sectionLen and 0xFF).toByte()

        val crc = crc32(bytesNoCrc)
        val out = ByteArray(bytesNoCrc.size + 4)
        System.arraycopy(bytesNoCrc, 0, out, 0, bytesNoCrc.size)
        out[out.size - 4] = ((crc ushr 24) and 0xFF).toByte()
        out[out.size - 3] = ((crc ushr 16) and 0xFF).toByte()
        out[out.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
    }

    private fun buildPMT(): ByteArray {
        fun esEntry(streamType: Int, pid: Int): ByteArray {
            return byteArrayOf(
                streamType.toByte(),
                (0xE0 or ((pid shr 8) and 0x1F)).toByte(),
                (pid and 0xFF).toByte(),
                0xF0.toByte(), 0x00  // ES_info_length=0
            )
        }

        val esVideo = esEntry(STREAM_TYPE_HEVC, VIDEO_PID)
        val esAudio = esEntry(STREAM_TYPE_AAC,  AUDIO_PID)

        val sec = ByteArrayOutputStream()
        fun w(v: Int) = sec.write(v and 0xFF)

        w(0x02) // table_id
        w(0xB0); w(0x00) // section_length placeholder

        w(0x00); w(0x01) // program_number
        w(0xC1)          // version/current_next
        w(0x00)          // section_number
        w(0x00)          // last_section_number

        // PCR PID (vídeo)
        w(0xE0 or ((VIDEO_PID shr 8) and 0x1F))
        w(VIDEO_PID and 0xFF)

        // program_info_length = 0
        w(0xF0); w(0x00)

        // ES
        sec.write(esVideo)
        sec.write(esAudio)

        val bytesNoCrc = sec.toByteArray()
        val sectionLen = bytesNoCrc.size - 3 + 4
        bytesNoCrc[1] = (0xB0 or ((sectionLen shr 8) and 0x0F)).toByte()
        bytesNoCrc[2] = (sectionLen and 0xFF).toByte()

        val crc = crc32(bytesNoCrc)
        val out = ByteArray(bytesNoCrc.size + 4)
        System.arraycopy(bytesNoCrc, 0, out, 0, bytesNoCrc.size)
        out[out.size - 4] = ((crc ushr 24) and 0xFF).toByte()
        out[out.size - 3] = ((crc ushr 16) and 0xFF).toByte()
        out[out.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
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
        val pesLen = 0 // unbounded

        val baos = ByteArrayOutputStream()
        fun w(v: Int) = baos.write(v and 0xFF)

        w(0x00); w(0x00); w(0x01)
        w(streamId)
        w((pesLen shr 8) and 0xFF); w(pesLen and 0xFF)
        w(0x80)
        w(flags)
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
    // HEVC: length-prefixed → Annex-B
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
            else  -> 3
        }
        val profile = 1 // AAC LC
        val chanCfg = channels.coerceIn(1, 2)

        val adtsLen = 7 + raw.size
        val hdr = ByteArray(7)
        hdr[0] = 0xFF.toByte()
        hdr[1] = 0xF1.toByte()
        hdr[2] = (((profile and 0x3) + 1) shl 6 or ((srIndex and 0xF) shl 2) or ((chanCfg shr 2) and 0x1)).toByte()
        hdr[3] = (((chanCfg and 0x3) shl 6) or ((adtsLen shr 11) and 0x03)).toByte()
        hdr[4] = ((adtsLen shr 3) and 0xFF).toByte()
        hdr[5] = (((adtsLen and 0x7) shl 5) or 0x1F).toByte()
        hdr[6] = 0xFC.toByte()

        val out = ByteArray(hdr.size + raw.size)
        System.arraycopy(hdr, 0, out, 0, hdr.size)
        System.arraycopy(raw, 0, out, hdr.size, raw.size)
        return out
    }

    private fun looksLikeAdts(buf: ByteArray): Boolean {
        if (buf.size < 2) return false
        val b0 = buf[0].toInt() and 0xFF
        val b1 = buf[1].toInt() and 0xF0
        return b0 == 0xFF && b1 == 0xF0
    }


    fun writeVideoAccessUnit(nalOrFrame: ByteArray, isKeyframe: Boolean, ptsUs: Long, dtsUs: Long, vpsSpsPps: ByteArray?) {
        val frameAnnexB = hevcLengthPrefixedToAnnexB(nalOrFrame)
        val prefix = if (isKeyframe && vpsSpsPps != null && vpsSpsPps.isNotEmpty())
            hevcLengthPrefixedToAnnexB(vpsSpsPps) else ByteArray(0)

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
        val withAdts = if (looksLikeAdts(aacEncoderOutput)) aacEncoderOutput
                       else aacWithAdts(aacEncoderOutput, sampleRate, channels)

        val header = buildPesHeader(0xC0, ptsUs, null)
        val pes = ByteArray(header.size + withAdts.size).also {
            System.arraycopy(header, 0, it, 0, header.size)
            System.arraycopy(withAdts, 0, it, header.size, withAdts.size)
        }
        splitToTs(AUDIO_PID, ptsUs, pes, 0, pes.size, true)
    }
}
