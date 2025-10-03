package com.example.s22hls.media

class HlsPlaylist(
    private val targetDurationSec: Int = 3,
    private val maxSegments: Int = 6,
    private val addIndependentSegmentsTag: Boolean = true,
    private val playlistTypeEvent: Boolean = false
) {
    private data class Entry(val seq: Int, val name: String, val durSec: Double)
    private val entries = ArrayDeque<Entry>() // janela deslizante

    fun add(seq: Int, name: String, durSec: Double) {
        entries.addLast(Entry(seq, name, durSec))
        while (entries.size > maxSegments) entries.removeFirst()
    }

    fun toText(): String {
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        if (addIndependentSegmentsTag) sb.appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
        sb.appendLine("#EXT-X-TARGETDURATION:$targetDurationSec")
        if (playlistTypeEvent) sb.appendLine("#EXT-X-PLAYLIST-TYPE:EVENT")

        val mediaSeq = entries.firstOrNull()?.seq ?: 0
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSeq")

        for (e in entries) {
            sb.appendLine("#EXTINF:${"%.3f".format(e.durSec)},")
            sb.appendLine(e.name)
        }
        return sb.toString()
    }
}
