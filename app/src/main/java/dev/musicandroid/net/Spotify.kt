package dev.musicandroid.net

import dev.musicandroid.data.Playlist
import dev.musicandroid.data.Track
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/**
 * The Spotify library, without logging in: an Exportify (or TuneMyMusic)
 * CSV export, read from a zip, a folder or single files; and live linked
 * playlists, read from Spotify's public embed page.
 */
object Spotify {
    const val LIKED_NAME = "Liked Songs"

    /** RFC 4180 rows. */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += field.toString(); field.clear() }
                '\r' -> {}
                '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private class Columns(h: List<String>) {
        private val names = h.map { it.trim().lowercase() }
        private fun find(vararg c: String) = names.indexOfFirst { it in c }.takeIf { it >= 0 }
        val title = find("track name", "title", "name")
        val artist = find("artist name", "artist name(s)", "artist", "artists")
        val album = find("album", "album name")
        val playlist = find("playlist name", "playlist")
        val kind = find("type")
        val id = find("spotify - id", "track uri", "spotify uri", "track id", "spotify id")
        val isrc = find("isrc")
        val duration = find("track duration (ms)", "duration (ms)", "duration_ms")
        val cover = find("album image url")
        val release = find("album release date", "release date")
        val trackNo = find("track number")
        val discNo = find("disc number")
        val added = find("added at", "date added")
    }

    private fun safeId(s: String) = s.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 } }

    private fun hashed(artist: String, title: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest("${artist.lowercase()}\u0000${title.lowercase()}".toByteArray())
        return "h:" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * One CSV file into [into], keyed by playlist name. Exportify writes one
     * file per playlist, named after it; `Liked_Songs.csv` is the likes.
     */
    fun importCsv(fileName: String, text: String, into: LinkedHashMap<String, MutableList<Pair<Long, Track>>>) {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return
        val c = Columns(rows[0])
        val ti = c.title ?: throw IOException("$fileName: no track name column")
        val ai = c.artist ?: throw IOException("$fileName: no artist column")
        val fileList = fileName.substringAfterLast('/').removeSuffix(".csv").replace('_', ' ')
        for ((n, r) in rows.drop(1).withIndex()) {
            fun get(i: Int?) = i?.let { r.getOrNull(it) }?.trim().orEmpty()
            val title = get(ti).ifEmpty { continue }
            val artist = get(ai).replace(";", ", ")
            val liked = get(c.kind).equals("favorite", true) || fileList.equals(LIKED_NAME, true)
            val list = if (liked) LIKED_NAME else get(c.playlist).ifEmpty { fileList }
            val sp = safeId(get(c.id).substringAfterLast(':'))
            val key = when {
                sp != null -> "sp:$sp"
                safeId(get(c.isrc)) != null -> "sp:isrc${get(c.isrc)}"
                else -> hashed(artist, title)
            }
            val release = get(c.release)
            val added = get(c.added).filter { it.isDigit() }.take(14).toLongOrNull() ?: n.toLong()
            val t = Track(
                key = key,
                title = title,
                artist = artist,
                albumArtist = artist,
                album = get(c.album),
                year = release.take(4).toIntOrNull(),
                trackNo = get(c.trackNo).toIntOrNull(),
                discNo = get(c.discNo).toIntOrNull(),
                durationMs = get(c.duration).toLongOrNull() ?: 0,
                art = get(c.cover),
                isrc = get(c.isrc),
                addedAt = added,
            )
            into.getOrPut(list) { mutableListOf() } += added to t
        }
    }

    /** Lists from an import, each oldest-added first. */
    fun lists(found: LinkedHashMap<String, MutableList<Pair<Long, Track>>>): List<Pair<String, List<Track>>> =
        found.map { (name, rows) -> name to rows.sortedBy { it.first }.map { it.second }.distinctBy { it.key } }

    /**
     * Add an import to the library without removing anything: new lists are
     * created, new songs appended. Songs added in the app, or gone from the
     * export, stay. Returns how many songs were added.
     */
    fun merge(store: List<Playlist>, imported: List<Pair<String, List<Track>>>): Pair<List<Playlist>, Int> {
        val out = store.toMutableList()
        var added = 0
        for ((name, tracks) in imported) {
            val liked = name == LIKED_NAME
            val i = out.indexOfFirst { if (liked) it.id == Playlist.LIKED else it.source == Playlist.SPOTIFY && it.name == name }
            val existing = if (i >= 0) out[i] else Playlist("s" + Integer.toHexString(name.hashCode()), name, source = Playlist.SPOTIFY)
            val have = existing.tracks.mapTo(HashSet()) { it.key }
            val new = tracks.filter { it.key !in have }
            added += new.size
            val merged = existing.copy(tracks = existing.tracks + new, updatedAt = System.currentTimeMillis())
            if (i >= 0) out[i] = merged else out += merged
        }
        return out to added
    }

    fun playlistId(link: String): String? {
        val l = link.trim()
        val rest = l.substringAfter("playlist/", "").ifEmpty { l.removePrefix("spotify:playlist:").takeIf { it != l } ?: return null }
        return rest.takeWhile { it.isLetterOrDigit() }.takeIf { it.length == 22 }
    }

    /** A playlist read anonymously from its embed page: at most 100 songs, without album names. */
    fun fetchLinked(link: String): Playlist {
        val id = playlistId(link) ?: throw IOException("not a Spotify playlist link")
        val html = Http.text("https://open.spotify.com/embed/playlist/$id")
        val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        val start = html.indexOf(marker).takeIf { it >= 0 } ?: throw IOException("embed page has no playlist data")
        val body = html.substring(start + marker.length).substringBefore("</script>")
        val entity = JSONObject(body).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: throw IOException("embed page changed")
        val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources").objects().maxByOrNull { it.optInt("width") }?.str("url").orEmpty()
        val tracks = entity.optJSONArray("trackList").objects().mapIndexedNotNull { i, t ->
            val tid = safeId(t.str("uri").substringAfterLast(':')) ?: return@mapIndexedNotNull null
            Track(
                key = "sp:$tid",
                title = t.str("title"),
                artist = t.str("subtitle").replace(" ", " "),
                albumArtist = t.str("subtitle"),
                durationMs = t.optLong("duration"),
                addedAt = i.toLong(),
            )
        }
        return Playlist("l$id", entity.str("name").ifEmpty { "Spotify playlist" }, tracks, Playlist.LINKED, link, System.currentTimeMillis())
            .also { if (cover.isNotEmpty()) linkedCovers[it.id] = cover }
    }

    val linkedCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Album art for a Spotify track id, from Spotify's public oEmbed endpoint (640 px). */
    fun cover(key: String): String = runCatching {
        val id = key.removePrefix("sp:")
        val v = Http.json("https://open.spotify.com/oembed?url=https://open.spotify.com/track/$id")
        v.str("thumbnail_url").replace("ab67616d00001e02", "ab67616d0000b273")
    }.getOrDefault("")
}
