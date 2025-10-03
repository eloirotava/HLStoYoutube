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
        const val STREAM_TYPE_HEVC = 0x24   // 0x1B para H.264/AVC
        const val STREAM_TYPE_AAC  = 0x0F
    }

    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var ccAudio = 0

    // ------------------------------------------------------------
    // TS packet (adaptation/PCR/stuffing)
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

        val pusi = if (payloadUnitStart) 0x40 else 0x00
        buf[1] = (pusi or ((pid shr 8) and 0x1F)).toByte()
        buf[2] = (pid and 0xFF).toByte()

        val needPcr = (pcrBase != null) && (pid == pcrPid)
        val minimalPayloadAvail = 184 - (if (needPcr) 1 + 6 else 0)
        val willNeedStuffing = len < minimalPayloadAvail

        val hasAdaptation = needPcr || willNeedStuffing
        var p = 4
        var afFlags = 0
        var pcrLen = 0
        var stuffing = 0

        val cc = when (pid) {
            PAT_PID -> ccPat
            PMT_PID -> ccPmt
            VIDEO_PID -> ccVideo
            else -> ccAudio
        }

        if (hasAdaptation) {
            if (needPcr) { afFlags = afFlags or 0x10; pcrLen = 6 }
            val availAfterAF = 188 - (4 + 1 + 1 + pcrLen)
            stuffing = if (len < availAfterAF) (availAfterAF - len) else 0

            buf[3] = ((0x2 or 0x1) shl 4 or (cc and 0x0F)).toByte() // afc=3
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
            p = 4 + 1 + afLen
        } else {
            buf[3] = ((0x1 shl 4) or (cc and 0x0F)).toByte() // afc=1 (payload only)
        }

        val n = min(188 - p, len)
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
    // PSI (PAT/PMT)
    // ------------------------------------------------------------
    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) {
            val c = (b.toInt() xor ((crc ushr 24) and 0xFF)) and 0xFF
            var r = c shl 24
            repeat(8) { r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            crc = (crc shl 8) xor r
        }
        return crc
    }

    private fun buildPAT(): ByteArray {
        val sec = ByteArrayOutputStream()
        fun w(v: Int) = sec.write(v and 0xFF)
        w(0x00)               // table_id
        w(0xB0); w(0x00)      // section_length placeholder
        w(0x00); w(0x01)      // transport_stream_id
        w(0xC1)               // version/current_next
        w(0x00)               // section_number
        w(0x00)               // last_section_number
        // program_number=1 -> PMT_PID
        w(0x00); w(0x01)
        w(0xE0 or ((PMT_PID shr 8) and 0x1F)); w(PMT_PID and 0xFF)

        val bytesNoCrc = sec.toByteArray()
        val sectionLen = bytesNoCrc.size - 3 + 4
        bytesNoCrc[1] = (0xB0 or ((sectionLen shr 8) and 0x0F)).toByte()
        bytesNoCrc[2] = (sectionLen and 0xFF).toByte()

        val crc = crc32(bytesNoCrc)
        return bytesNoCrc + byteArrayOf(
            ((crc ushr 24) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte()
        )
    }

    private fun buildPMT(): ByteArray {
        fun esEntry(streamType: Int, pid: Int): ByteArray =
            byteArrayOf(
                streamType.toByte(),
                (0xE0 or ((pid shr 8) and 0x1F)).toByte(),
                (pid and 0xFF).toByte(),
                0xF0.toByte(), 0x00
            )

        val sec = ByteArrayOutputStream()
        fun w(v: Int) = sec.write(v and 0xFF)
        w(0x02)               // table_id
        w(0xB0); w(0x00)      // section_length placeholder
        w(0x00); w(0x01)      // program_number
        w(0xC1)               // version/current_next
        w(0x00)               // section_number
        w(0x00)               // last_section_number
        // PCR PID = vídeo
        w(0xE0 or ((VIDEO_PID shr 8) and 0x1F)); w(VIDEO_PID and 0xFF)
        // program_info_length = 0
        w(0xF0); w(0x00)
        sec.write(esEntry(STREAM_TYPE_HEVC, VIDEO_PID))
        sec.write(esEntry(STREAM_TYPE_AAC,  AUDIO_PID))

        val bytesNoCrc = sec.toByteArray()
        val sectionLen = bytesNoCrc.size - 3 + 4
        bytesNoCrc[1] = (0xB0 or ((sectionLen shr 8) and 0x0F)).toByte()
        bytesNoCrc[2] = (sectionLen and 0xFF).toByte()

        val crc = crc32(bytesNoCrc)
        return bytesNoCrc + byteArrayOf(
            ((crc ushr 24) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte()
        )
    }

    // ------------------------------------------------------------
    // PES
    // ------------------------------------------------------------
    private fun buildPesHeader(streamId: Int, ptsUs: Long, dtsUs: Long?): ByteArray {
        val pts90 = ptsUs * 90L
        val dts90 = dtsUs?.let { it * 90L }
        val flags = if (dts90 != null) 0xC0 else 0x80
        val headerDataLen = if (dts90 != null) 10 else 5
        val pesLen = 0

        val baos = ByteArrayOutputStream()
        fun w(v: Int) = baos.write(v and 0xFF)
        w(0x00); w(0x00); w(0x01)
        w(streamId)
        w((pesLen shr 8) and 0xFF); w(pesLen and 0xFF)
        w(0x80); w(flags); w(headerDataLen)

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
    // HEVC helpers (Annex-B, hvcC parsing, AUD, ordenação VPS/SPS/PPS)
    // ------------------------------------------------------------
    private fun isAnnexB(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return (b0 == 0x00 && b1 == 0x00 && (b2 == 0x01 || (b2 == 0x00 && b3 == 0x01)))
    }

    private fun splitAnnexB(nals: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        fun isStart(pos: Int): Int {
            if (pos + 3 < nals.size && nals[pos] == 0.toByte() && nals[pos + 1] == 0.toByte()) {
                if (nals[pos + 2] == 1.toByte()) return 3
                if (pos + 4 < nals.size && nals[pos + 2] == 0.toByte() && nals[pos + 3] == 1.toByte()) return 4
            }
            return 0
        }
        while (i < nals.size) {
            val sc = isStart(i)
            if (sc == 0) { i++ ; continue }
            val start = i + sc
            var j = start
            while (j < nals.size && isStart(j) == 0) j++
            out += nals.copyOfRange(start, j)
            i = j
        }
        return out
    }

    private fun nalType(hdr: Int): Int = (hdr shr 1) and 0x3F // HEVC

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

    private fun hevcHvccToAnnexB(csd: ByteArray): ByteArray? {
        if (csd.isEmpty() || csd[0].toInt() != 1) return null // não é hvcC
        for (start in intArrayOf(21, 22)) {
            try {
                var pos = start
                val out = ByteArrayOutputStream()
                val numOfArrays = csd[pos++].toInt() and 0xFF
                repeat(numOfArrays) {
                    if (pos + 3 > csd.size) return@repeat
                    val arrayHdr = csd[pos++].toInt() and 0xFF
                    val nalType = arrayHdr and 0x3F
                    val numNalus = ((csd[pos].toInt() and 0xFF) shl 8) or (csd[pos + 1].toInt() and 0xFF); pos += 2
                    repeat(numNalus) {
                        if (pos + 2 > csd.size) return@repeat
                        val len = ((csd[pos].toInt() and 0xFF) shl 8) or (csd[pos + 1].toInt() and 0xFF); pos += 2
                        if (pos + len > csd.size) return@repeat
                        out.write(byteArrayOf(0, 0, 0, 1))
                        out.write(csd, pos, len)
                        pos += len
                    }
                }
                val arr = out.toByteArray()
                if (arr.isNotEmpty()) return arr
            } catch (_: Throwable) { /* tenta offset seguinte */ }
        }
        return null
    }

    private fun hevcConfigToAnnexB(bytes: ByteArray): ByteArray {
        return when {
            isAnnexB(bytes) -> bytes
            (bytes.isNotEmpty() && bytes[0].toInt() == 1) -> hevcHvccToAnnexB(bytes) ?: hevcLengthPrefixedToAnnexB(bytes)
            else -> hevcLengthPrefixedToAnnexB(bytes)
        }
    }

    private fun buildAudNal(): ByteArray {
        // AUD (nal_unit_type 35). Conteúdo mínimo (no_rbsp), startcode + header + 1 byte.
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(0,0,0,1))
        // forbidden_zero_bit=0, nal_unit_type=35 (AUD), nuh_layer_id=0, nuh_temporal_id_plus1=1
        baos.write( (0 shl 7) or (35 shl 1) or 0 )          // first byte (forbidden+type high)
        baos.write( (0 shl 5) or (1) )                      // second byte (layer_id=0, tid+1=1)
        baos.write(0x50) // aud_irap_or_idr_pic_flag(1)=0 etc. byte dummy aceitável
        return baos.toByteArray()
    }

    private fun orderedVpsSpsPps(prefixBytes: ByteArray): ByteArray {
        if (prefixBytes.isEmpty()) return ByteArray(0)
        val ann = if (isAnnexB(prefixBytes)) prefixBytes else hevcConfigToAnnexB(prefixBytes)
        val nals = splitAnnexB(ann)
        val vps = ArrayList<ByteArray>()
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (nalType(nal[0].toInt() and 0xFF)) {
                32 -> vps += nal
                33 -> sps += nal
                34 -> pps += nal
            }
        }
        val out = ByteArrayOutputStream()
        for (x in vps) { out.write(byteArrayOf(0,0,0,1)); out.write(x) }
        for (x in sps) { out.write(byteArrayOf(0,0,0,1)); out.write(x) }
        for (x in pps) { out.write(byteArrayOf(0,0,0,1)); out.write(x) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------
    // AAC (ADTS)
    // ------------------------------------------------------------
    private fun aacWithAdts(raw: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val srIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            7350  -> 12
            else  -> 3
        }
        val profileIndex = 1 // AAC LC
        val chanCfg = channels.coerceIn(1, 7)

        val adtsLen = 7 + raw.size
        val hdr = ByteArray(7)

        hdr[0] = 0xFF.toByte()
        hdr[1] = 0xF1.toByte()
        hdr[2] = (((profileIndex and 0x3) shl 6)
                 or ((srIndex and 0xF) shl 2)
                 or ((chanCfg shr 2) and 0x1)).toByte()
        hdr[3] = (((chanCfg and 0x3) shl 6)
                 or ((adtsLen shr 11) and 0x03)).toByte()
        hdr[4] = ((adtsLen shr 3) and 0xFF).toByte()
        hdr[5] = (((adtsLen and 0x7) shl 5)
                 or 0x1F).toByte()
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

    // ------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------
    fun writePatPmt() {
        writePsi(PAT_PID, buildPAT())
        writePsi(PMT_PID, buildPMT())
    }

    fun writeVideoAccessUnit(nalOrFrame: ByteArray, isKeyframe: Boolean, ptsUs: Long, dtsUs: Long, vpsSpsPps: ByteArray?) {
        val frameAnnexB = when {
            isAnnexB(nalOrFrame) -> nalOrFrame
            else -> hevcLengthPrefixedToAnnexB(nalOrFrame)
        }

        val aud = buildAudNal()
        val cfg = if (isKeyframe && vpsSpsPps != null && vpsSpsPps.isNotEmpty())
            orderedVpsSpsPps(vpsSpsPps) else ByteArray(0)

        val payload = ByteArray(aud.size + cfg.size + frameAnnexB.size).also {
            var p = 0
            System.arraycopy(aud, 0, it, p, aud.size); p += aud.size
            if (cfg.isNotEmpty()) { System.arraycopy(cfg, 0, it, p, cfg.size); p += cfg.size }
            System.arraycopy(frameAnnexB, 0, it, p, frameAnnexB.size)
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
