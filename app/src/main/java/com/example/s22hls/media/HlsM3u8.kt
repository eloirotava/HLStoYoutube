package com.example.s22hls.media

import java.util.ArrayDeque
import java.util.Locale

class HlsPlaylist(
    private val targetDurationSec: Int = 3,
    private val maxSegments: Int = 6
) {
    private val entries = ArrayDeque<Pair<String, Double>>() // (filename, duration)

    fun add(name: String, durSec: Double) {
        entries.addLast(name to durSec)
        while (entries.size > maxSegments) entries.removeFirst()
    }

    /** Gera o m3u8 com media sequence passado por quem chama */
    fun toText(mediaSeqStart: Int): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDurationSec).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSeqStart).append('\n')
        for ((name, dur) in entries) {
            sb.append("#EXTINF:")
                .append(String.format(Locale.US, "%.3f", dur))
                .append(",\n")
            sb.append(name).append('\n')
        }
        return sb.toString()
    }
}
