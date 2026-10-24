package com.orbit.player

import java.net.HttpURLConnection
import java.net.URL

// Addon subtitles (OpenSubtitles, …) are shown by Orbit itself rather than through the
// player's tracks, so their timing can be shifted instantly with the subtitle delay.
class AddonSubtitleTrack(val subtitle: StremioSubtitles.Subtitle, private val cues: List<Line>) {

    data class Line(val startMs: Long, val endMs: Long, val text: String)

    // Lines showing at the given position (already adjusted for the delay).
    fun at(positionMs: Long): List<String> {
        // Binary search for the last line starting at or before the position.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (last < 0) return emptyList()
        // Overlapping lines: look back a few for any still showing.
        return (maxOf(0, last - 4)..last).map { cues[it] }
            .filter { positionMs < it.endMs }
            .map { it.text }
    }

    companion object {
        // Downloads and parses an SRT or WebVTT file. Call off the main thread.
        fun load(subtitle: StremioSubtitles.Subtitle): AddonSubtitleTrack? = try {
            val connection = URL(subtitle.url).openConnection() as HttpURLConnection
            val bytes = try {
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
            val text = decode(bytes)
            val lines = parse(text)
            if (lines.isEmpty()) null else AddonSubtitleTrack(subtitle, lines)
        } catch (_: Exception) {
            null
        }

        // UTF-8 when valid, else Windows-1252 (older SRT files).
        private fun decode(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                String(bytes, charset("windows-1252"))
            }.removePrefix("\uFEFF") // byte-order mark
        }

        private val timing = Regex(
            "(\\d{1,2}:)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}:)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})"
        )

        private fun ms(h: String?, m: String, s: String, f: String): Long =
            ((h?.trimEnd(':')?.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 +
                f.padEnd(3, '0').take(3).toLong()

        fun parse(text: String): List<Line> {
            val result = mutableListOf<Line>()
            val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
            for (block in blocks) {
                val rows = block.trim().lines()
                val index = rows.indexOfFirst { timing.containsMatchIn(it) }
                if (index < 0) continue
                val m = timing.find(rows[index])!!.groupValues
                val start = ms(m[1].ifEmpty { null }, m[2], m[3], m[4])
                val end = ms(m[5].ifEmpty { null }, m[6], m[7], m[8])
                val body = rows.drop(index + 1).joinToString("\n")
                    .replace(Regex("<[^>]+>"), "")          // <i>, <b>, <font>, <v Speaker>
                    .replace(Regex("\\{\\\\[^}]*\\}"), "")   // {\an8} position tags
                    .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
                    .trim()
                // Addons sometimes add an advert line at the very start.
                if (body.isEmpty() || (start < 15_000 && body.contains("OpenSubtitles", true))) continue
                if (end > start) result += Line(start, end, body)
            }
            return result.sortedBy { it.startMs }
        }

        // Ranks subtitles by how well their release matches the playing file: same release
        // group, same source (WEB-DL, BluRay, …), same resolution. Subtitles made for the
        // same release are the ones that line up.
        fun rank(subs: List<StremioSubtitles.Subtitle>, fileName: String?): List<StremioSubtitles.Subtitle> {
            val file = tokens(fileName.orEmpty())
            if (file.isEmpty()) return subs
            val group = releaseGroup(fileName.orEmpty())
            val sources = setOf("web", "webdl", "webrip", "bluray", "bdrip", "brrip", "hdtv", "dvdrip", "nf", "amzn", "dsnp", "hmax", "atvp", "hulu", "remux")
            val resolutions = setOf("2160p", "1080p", "720p", "480p")
            return subs.withIndex().sortedByDescending { (i, sub) ->
                val name = tokens(sub.releaseName)
                var score = 0
                if (group != null && group in name) score += 20
                score += (file intersect name intersect sources).size * 4
                score += (file intersect name intersect resolutions).size * 2
                // Words in the subtitle's name that aren't in the file's, e.g. "mexico"
                // for Narcos: Mexico, suggest a different title.
                val stray = name.filter { it.length > 3 && it.all(Char::isLetter) && it !in file && it !in sources && it !in common }
                score -= stray.size * 3
                score * 1000 - i // keep the addon's order among equals
            }.map { it.value }
        }

        private val common = setOf("repack", "proper", "x264", "x265", "h264", "h265", "hevc", "subs", "english", "eng", "complete", "season", "episode", "internal", "dual", "audio", "multi", "subrip")

        private fun tokens(name: String): Set<String> =
            name.lowercase().replace(Regex("\\.(srt|vtt|mkv|mp4|avi)$"), "")
                .split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                .map { if (it == "dl" ) "" else it }.filter { it.isNotEmpty() }
                .toSet() + if (Regex("web[ ._-]?dl", RegexOption.IGNORE_CASE).containsMatchIn(name)) setOf("webdl") else emptySet()

        // "Narcos.S01E01…H.265-SiC.mkv" -> "sic"
        fun releaseGroup(name: String): String? =
            Regex("-([A-Za-z0-9]+)(\\.[a-z0-9]{2,4})?$").find(name.trim())?.groupValues?.get(1)?.lowercase()
    }
}
