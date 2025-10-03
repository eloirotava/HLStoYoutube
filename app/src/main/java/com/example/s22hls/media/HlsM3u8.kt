package com.example.s22hls.media

class HlsPlaylist(private val targetDurationSec: Int = 2, private val maxSegments:Int = 6) {
    private val entries = ArrayDeque<Pair<String, Double>>() // (filename, duration)

    fun add(name: String, durSec: Double) {
        entries.addLast(name to durSec)
        while (entries.size > maxSegments) entries.removeFirst()
    }

    fun toText(mediaSeqStart: Int): String {
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        sb.appendLine("#EXT-X-TARGETDURATION:${targetDurationSec}")
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:${mediaSeqStart}")
        entries.forEach { (n, d) ->
            sb.appendLine("#EXTINF:${"%.3f".format(d)},")
            sb.appendLine(n)
        }
        return sb.toString()
    }
}
