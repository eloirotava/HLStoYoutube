package com.example.s22hls.media

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class HlsPlaylist(
    private val targetDurationSec: Int = 2,   // mínimo desejado
    private val maxSegments: Int = 6
) {
    // (filename, duration)
    private val entries = ArrayDeque<Pair<String, Double>>()

    @Synchronized
    fun add(name: String, durSec: Double) {
        entries.addLast(name to durSec)
        while (entries.size > maxSegments) entries.removeFirst()
    }

    @Synchronized
    fun toText(mediaSeqStart: Int): String {
        // calcula TARGETDURATION como teto do maior EXTINF visível
        val maxDur = entries.maxOfOrNull { it.second } ?: targetDurationSec.toDouble()
        val target = max(targetDurationSec, ceil(maxDur).toInt().coerceAtLeast(1))

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSeqStart).append('\n')

        for ((name, dur) in entries) {
            val durStr = String.format(Locale.US, "%.3f", dur)
            sb.append("#EXTINF:").append(durStr).append(",\n")
            sb.append(name).append('\n')
        }
        return sb.toString()
    }
}
