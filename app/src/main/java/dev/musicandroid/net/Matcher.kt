package dev.musicandroid.net

import dev.musicandroid.data.Store
import dev.musicandroid.data.Track

/**
 * Which YouTube video plays a song: the same rules as music-tui. YouTube
 * Music's songs search first (audio uploads); a general search only when that
 * finds nothing or only other versions; candidates scored on title, version
 * words and length. The pick is remembered, and a rejected pick never returns.
 */
object Matcher {
    private val OFF_VERSIONS = listOf(
        "official video", "music video", "official mv", "(video)", "[video]", "(mv)", "[mv]",
        "live", "cover", "remix", "karaoke", "instrumental", "8d", "slowed", "sped up",
        "nightcore", "reverb", "extended", "teaser", "trailer", "reaction",
    )

    /** Lower is better. */
    fun score(c: YouTube.Candidate, rank: Int, t: Track): Int {
        var s = rank + if (c.song) 0 else 100
        val title = c.title.lowercase()
        val want = t.title.lowercase()
        // The song's name without " - 2011 Remaster" or "(feat. …)".
        val core = want.substringBefore(" - ").substringBefore(" (").trim()
        if (core.isNotEmpty() && !title.contains(core)) s += 30
        for (w in OFF_VERSIONS) if (title.contains(w) && !want.contains(w)) s += 50
        if (title.contains("audio") || c.channel.endsWith(" - Topic")) s -= 20
        val d = c.durationSecs
        if (d != null && t.durationMs > 0) {
            val diff = kotlin.math.abs(d - t.durationMs / 1000)
            s += if (diff > 60) 100 else if (diff > 15) 40 else 0
        }
        return s
    }

    /** Ranked candidates for a song, rejected ones left out. */
    fun candidates(t: Track): List<YouTube.Candidate> {
        val rejected = Store.match(t.key).rejected.toSet()
        val songs = YouTube.searchSongs("${t.artist} ${t.title}").filter { it.id !in rejected }
        var all = songs
        val best = songs.mapIndexed { i, c -> score(c, i, t) }.minOrNull()
        if (best == null || best >= 50) {
            all = songs + YouTube.searchVideos("${t.artist} - ${t.title} audio").filter { c -> c.id !in rejected && songs.none { it.id == c.id } }
        }
        return all.mapIndexed { i, c -> score(c, i, t) to c }.sortedBy { it.first }.map { it.second }
    }

    /** The video for a song: its own, the remembered pick, or the best candidate (then remembered). */
    fun videoFor(t: Track): String? {
        if (t.key.startsWith("yt:")) return t.key.removePrefix("yt:")
        Store.match(t.key).source?.let { return it }
        val pick = candidates(t).firstOrNull()?.id ?: return null
        Store.setMatch(t.key) { it.copy(source = pick) }
        return pick
    }

    /** Remember a specific upload for a song (a radio pick needs no search later). */
    fun setSource(t: Track, video: String) {
        if (t.key.startsWith("yt:")) return
        Store.setMatch(t.key) { m -> if (m.source == null && video !in m.rejected) m.copy(source = video) else m }
    }

    /** Wrong song: never use the current pick again. */
    fun reject(t: Track) {
        val current = Store.match(t.key).source ?: return
        Store.setMatch(t.key) { it.copy(source = null, rejected = it.rejected + current) }
    }

    /** Drop "(Official Audio)", "[Lyric Video]" and the like from a video title. */
    fun stripNoise(title: String): String {
        val noise = setOf("official", "audio", "video", "lyric", "lyrics", "visualizer", "visualiser", "hd", "4k")
        val out = StringBuilder()
        var rest = title
        while (true) {
            val open = rest.indexOfFirst { it == '(' || it == '[' }
            if (open < 0) break
            val close = if (rest[open] == '(') ')' else ']'
            val len = rest.indexOf(close, open)
            if (len < 0) break
            val inner = rest.substring(open + 1, len).lowercase()
            out.append(rest, 0, open)
            if (inner.split(Regex("[^\\p{L}\\p{N}]+")).none { it in noise }) out.append(rest, open, len + 1)
            rest = rest.substring(len + 1)
        }
        out.append(rest)
        return out.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Artist and song from a YouTube pick: "Artist - Song" from label uploads, the channel for an artist's own. */
    fun splitTitle(c: YouTube.Candidate): Pair<String, String> {
        val topic = c.channel.endsWith(" - Topic")
        val channel = c.channel.removeSuffix(" - Topic").trim()
        val title = stripNoise(c.title)
        val quoted = title.split('"').map { it.trim() }
        return when {
            !topic && title.contains(" - ") -> title.substringBefore(" - ").trim() to title.substringAfter(" - ").trim()
            !topic && quoted.size >= 3 && quoted[0].isNotEmpty() -> quoted[0] to quoted[1]
            else -> channel to title
        }
    }

    fun fromYouTube(c: YouTube.Candidate, artist: String? = null, title: String? = null): Track {
        val (a, t) = if (artist != null && title != null) artist to title else splitTitle(c)
        val track = Track(
            key = "yt:${c.id}",
            title = t,
            artist = a,
            albumArtist = a,
            durationMs = (c.durationSecs ?: 0) * 1000,
            art = c.thumbnail,
        )
        Store.remember(listOf(track))
        return track
    }
}
