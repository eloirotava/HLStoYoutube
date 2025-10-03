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

    // ÚNICA writePacket (sem reflection)
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

        var headerLen = 4
        var adaptionLen = 0
        var p = 4
        if (pcrBase != null && pid == pcrPid) {
            afc = 3 // adaptation + payload
        }
        buf[3] = ((afc shl 4) or (ccRef and 0x0F)).toByte()

        if ((afc and 0x2) != 0) {
            buf[p++] = 0
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
        payload[0] = 0
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
        sec[i++] = 0xB0.toByte(); sec[i++] = 0 // placeholder length
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

    // Refeito sem '+=' em List<Byte>: usa ByteArrayOutputStream
    private fun buildPesHeader(streamId: Int, ptsUs: Long, dtsUs: Long?): ByteArray {
        val pts90 = ptsUs * 90L
        val dts90 = dtsUs?.let { it * 90L }
        val flags = if (dts90 != null) 0xC0 else 0x80
        val headerDataLen = if (dts90 != null) 10 else 5
        val pesLen = 0

        val baos = ByteArrayOutputStream()
        fun w(v: Int) = baos.write(v and 0xFF)

        w(0x00); w(0x00); w(0x01)        // start code
        w(streamId)
        w((pesLen shr 8) and 0xFF); w(pesLen and 0xFF)
        w(0x80)                          // '10' + flags
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

    // Assinaturas públicas usadas pelo Pipeline
    fun writeVideoAccessUnit(nal: ByteArray, isKeyframe: Boolean, ptsUs: Long, dtsUs: Long, vpsSpsPps: ByteArray?) {
        // Constrói PES e divide em TS
        val header = buildPesHeader(0xE0, ptsUs, dtsUs)
        // Se for keyframe e vier VPS/SPS/PPS, prefixa antes do NAL
        val payload = if (isKeyframe && vpsSpsPps != null) {
            ByteArray(vpsSpsPps.size + nal.size).also {
                System.arraycopy(vpsSpsPps, 0, it, 0, vpsSpsPps.size)
                System.arraycopy(nal, 0, it, vpsSpsPps.size, nal.size)
            }
        } else nal
        val pes = ByteArray(header.size + payload.size).also {
            System.arraycopy(header, 0, it, 0, header.size)
            System.arraycopy(payload, 0, it, header.size, payload.size)
        }
        splitToTs(VIDEO_PID, ptsUs, pes, 0, pes.size, true)
    }

    fun writeAudioFrame(aacRaw: ByteArray, ptsUs: Long, sampleRate: Int, channels: Int) {
        // stream_id de áudio normalmente 0xC0
        val header = buildPesHeader(0xC0, ptsUs, null)
        val pes = ByteArray(header.size + aacRaw.size).also {
            System.arraycopy(header, 0, it, 0, header.size)
            System.arraycopy(aacRaw, 0, it, header.size, aacRaw.size)
        }
        splitToTs(AUDIO_PID, ptsUs, pes, 0, pes.size, true)
    }
}
