package dev.musicandroid.net

import dev.musicandroid.data.Item
import dev.musicandroid.data.Section
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Deezer's public API (no key, no account): search, charts, albums, artists,
 * playlists, recommendations and metadata lookups. Songs found here are
 * `dz:<id>` tracks, streamed from YouTube when played.
 */
object Deezer {
    private fun get(path: String): JSONObject {
        val v = Http.json("https://api.deezer.com/$path")
        v.optJSONObject("error")?.let { throw IOException("Deezer: " + it.optString("message", "request failed")) }
        return v
    }

    private fun data(v: JSONObject) = v.optJSONArray("data").objects()

    fun track(v: JSONObject, album: JSONObject? = null): Track? {
        val id = v.optLong("id").takeIf { it > 0 } ?: return null
        val al = album ?: v.optJSONObject("album") ?: JSONObject()
        val artist = v.optJSONObject("artist")?.str("name") ?: ""
        val date = al.str("release_date")
        return Track(
            key = "dz:$id",
            title = v.str("title"),
            artist = artist,
            albumArtist = al.optJSONObject("artist")?.str("name")?.takeIf { it.isNotEmpty() } ?: artist,
            album = al.str("title"),
            year = date.take(4).toIntOrNull(),
            trackNo = v.optInt("track_position").takeIf { it > 0 },
            discNo = v.optInt("disk_number").takeIf { it > 0 },
            durationMs = v.optLong("duration") * 1000,
            art = al.str("cover_xl").ifEmpty { al.str("cover_big") },
            isrc = v.str("isrc"),
        )
    }

    private fun tracks(v: JSONObject, album: JSONObject? = null) =
        data(v).mapNotNull { track(it, album) }.also { Store.remember(it) }.map { Item.Song(it) }

    private fun albums(v: JSONObject, artist: String? = null) = data(v).mapNotNull { a ->
        val id = a.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
        val date = a.str("release_date")
        Item.Album(
            id = id,
            title = a.str("title"),
            artist = a.optJSONObject("artist")?.str("name") ?: artist ?: "",
            cover = a.str("cover_xl").ifEmpty { a.str("cover_big") },
            sub = listOf(a.str("record_type").replaceFirstChar { it.uppercase() }, date.take(4)).filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }

    private fun artists(v: JSONObject) = data(v).mapNotNull { a ->
        val id = a.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
        Item.Artist(id, a.str("name"), a.str("picture_xl").ifEmpty { a.str("picture_big") })
    }

    private fun playlists(v: JSONObject) = data(v).mapNotNull { p ->
        val id = p.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
        Item.DeezerPlaylist(id, p.str("title"), p.optInt("nb_tracks"), p.str("picture_xl").ifEmpty { p.str("picture_big") })
    }

    /** Everything that matches: one section per kind. Songs fall back to YouTube Music when Deezer has none. */
    fun search(q: String): List<Section> {
        val e = Http.enc(q)
        val out = mutableListOf<Section>()
        fun push(title: String, items: List<Item>) { if (items.isNotEmpty()) out += Section(title, items) }
        val songs = tracks(get("search?q=$e&limit=50"))
        push("Songs", songs)
        push("Artists", artists(get("search/artist?q=$e&limit=15")))
        push("Albums", albums(get("search/album?q=$e&limit=25")))
        push("Playlists", playlists(get("search/playlist?q=$e&limit=25")))
        if (songs.isEmpty()) push("YouTube Music", YouTube.searchSongs(q, 15).map { Item.Song(Matcher.fromYouTube(it)) })
        return out
    }

    /** Deezer's charts. */
    fun charts(): List<Section> = listOf(
        Section("Top albums", albums(get("chart/0/albums?limit=50"))),
        Section("Popular artists", artists(get("chart/0/artists?limit=30"))),
        Section("Top songs", tracks(get("chart/0/tracks?limit=100"))),
        Section("Popular playlists", playlists(get("chart/0/playlists?limit=50"))),
    ).filter { it.items.isNotEmpty() }

    data class ArtistInfo(val id: Long, val name: String, val picture: String, val fans: Int, val albums: Int)

    data class ArtistPage(val info: ArtistInfo, val top: List<Track>, val albums: List<Item.Album>, val related: List<Item.Artist>)

    fun artist(id: Long): ArtistPage {
        val a = get("artist/$id")
        val info = ArtistInfo(id, a.str("name"), a.str("picture_xl"), a.optInt("nb_fan"), a.optInt("nb_album"))
        val top = tracks(get("artist/$id/top?limit=50")).map { it.track }
        val albums = albums(get("artist/$id/albums?limit=100"), info.name).sortedByDescending { it.sub.takeLast(4) }
        val related = artists(get("artist/$id/related?limit=20"))
        return ArtistPage(info, top, albums, related)
    }

    data class AlbumPage(val id: Long, val title: String, val artist: String, val artistId: Long, val cover: String, val year: String, val tracks: List<Track>)

    fun album(id: Long): AlbumPage {
        val a = get("album/$id")
        // An album's track list leaves out the numbers: they're its order.
        val list = tracks(a.optJSONObject("tracks") ?: JSONObject(), a).mapIndexed { i, it -> it.track.copy(trackNo = it.track.trackNo ?: (i + 1)) }
        Store.remember(list)
        return AlbumPage(
            id, a.str("title"), a.optJSONObject("artist")?.str("name") ?: "", a.optJSONObject("artist")?.optLong("id") ?: 0,
            a.str("cover_xl"), a.str("release_date").take(4), list,
        )
    }

    data class PlaylistPage(val id: Long, val title: String, val cover: String, val by: String, val tracks: List<Track>)

    fun playlist(id: Long): PlaylistPage {
        val p = get("playlist/$id")
        val list = tracks(get("playlist/$id/tracks?limit=300")).map { it.track }
        return PlaylistPage(id, p.str("title"), p.str("picture_xl"), p.optJSONObject("creator")?.str("name") ?: "", list)
    }

    private val pool = Executors.newFixedThreadPool(6)

    private fun findArtistId(name: String, exact: Boolean): JSONObject? {
        val hit = data(get("search/artist?q=${Http.enc(name)}&limit=1")).firstOrNull() ?: return null
        // A fuzzy hit is someone else.
        if (exact && !hit.str("name").equals(name, ignoreCase = true)) return null
        return hit
    }

    /** "For you": each seed artist's Deezer radio, minus songs already liked, interleaved. */
    fun forYou(seeds: List<String>, liked: Set<Pair<String, String>>): Section? {
        val radios = seeds.map { name ->
            pool.submit<List<Track>> {
                runCatching {
                    val id = findArtistId(name, false)?.optLong("id") ?: return@runCatching emptyList()
                    data(get("artist/$id/radio?limit=25")).mapNotNull { track(it) }.filter { it.sameSong !in liked }
                }.getOrDefault(emptyList())
            }
        }.map { it.get() }
        val seen = HashSet<String>()
        val out = mutableListOf<Track>()
        for (round in 0 until 25) for (r in radios) r.getOrNull(round)?.let { if (seen.add(it.key)) out += it }
        Store.remember(out)
        return out.takeIf { it.isNotEmpty() }?.let { Section("For you", it.map(Item::Song)) }
    }

    /** "New releases for you": records from the last [days] days by the given artists. */
    fun newReleases(artists: List<String>, days: Long = 120): Section? {
        val since = LocalDate.now().minusDays(days).toString()
        val found = artists.map { name ->
            pool.submit<List<Pair<String, Item.Album>>> {
                runCatching {
                    val a = findArtistId(name, true) ?: return@runCatching emptyList()
                    data(get("artist/${a.optLong("id")}/albums?limit=50")).mapNotNull { r ->
                        val date = r.str("release_date")
                        if (date < since) return@mapNotNull null
                        date to Item.Album(r.optLong("id"), r.str("title"), a.str("name"), r.str("cover_xl"),
                            "${r.str("record_type").replaceFirstChar { it.uppercase() }} · $date")
                    }
                }.getOrDefault(emptyList())
            }
        }.flatMap { it.get() }
        val items = found.sortedByDescending { it.first }.map { it.second }.distinctBy { it.id }
        return items.takeIf { it.isNotEmpty() }?.let { Section("New releases for you", it) }
    }

    data class Details(val albumArtist: String, val album: String, val trackNo: Int?, val discNo: Int?, val year: Int?, val cover: String)

    private fun details(v: JSONObject): Details? {
        val albumId = v.optJSONObject("album")?.optLong("id")?.takeIf { it > 0 } ?: return null
        val album = runCatching { get("album/$albumId") }.getOrNull() ?: return null
        val date = v.str("release_date").ifEmpty { album.str("release_date") }
        return Details(
            album.optJSONObject("artist")?.str("name") ?: return null,
            album.str("title"),
            v.optInt("track_position").takeIf { it > 0 },
            v.optInt("disk_number").takeIf { it > 0 },
            date.take(4).toIntOrNull(),
            album.str("cover_xl"),
        )
    }

    /** Library details for a song: by its Deezer id, its ISRC, or an exact artist and title match. */
    fun lookup(t: Track): Details? = runCatching {
        val dz = t.key.removePrefix("dz:").takeIf { t.key.startsWith("dz:") }
        when {
            dz != null -> details(get("track/$dz"))
            t.isrc.isNotEmpty() -> details(get("track/isrc:${Http.enc(t.isrc)}"))
            else -> {
                val q = Http.enc("artist:\"${t.mainArtist}\" track:\"${t.title}\"")
                val hit = data(get("search?q=$q&limit=5")).firstOrNull {
                    it.str("title").equals(t.title, true) && it.optJSONObject("artist")?.str("name").equals(t.mainArtist, true)
                } ?: return@runCatching null
                details(get("track/${hit.optLong("id")}"))
            }
        }
    }.getOrNull()

    /** A song on Deezer by artist and title, loosely: used to turn YouTube picks into proper tracks. */
    fun find(artist: String, title: String): Track? = runCatching {
        val want = artist.lowercase()
        fun artistOk(t: JSONObject): Boolean {
            val a = t.optJSONObject("artist")?.str("name")?.lowercase() ?: return false
            return a.isNotEmpty() && (a.contains(want) || want.contains(a))
        }
        for (q in listOf("artist:\"$artist\" track:\"$title\"", "$artist $title")) {
            data(get("search?q=${Http.enc(q)}&limit=5")).firstOrNull(::artistOk)?.let { return@runCatching track(it) }
        }
        // The "artist" may just be the uploader's channel: trust an exact title.
        fun norm(x: String) = x.filter { it.isLetterOrDigit() }.lowercase()
        data(get("search?q=${Http.enc(title)}&limit=5")).firstOrNull { norm(it.str("title")) == norm(title) }?.let { track(it) }
    }.getOrNull()

    sealed interface Place {
        data class Artist(val id: Long) : Place
        data class Album(val id: Long) : Place
    }

    /** Where "go to artist / album" leads for a song. */
    fun locate(t: Track, album: Boolean): Place? = runCatching {
        val exact = when {
            t.key.startsWith("dz:") -> get("track/${t.key.removePrefix("dz:")}")
            t.isrc.isNotEmpty() -> runCatching { get("track/isrc:${Http.enc(t.isrc)}") }.getOrNull()
            else -> null
        }
        if (exact != null && exact.optLong("id") > 0) {
            val p = if (album) exact.optJSONObject("album")?.optLong("id")?.let { Place.Album(it) }
            else exact.optJSONObject("artist")?.optLong("id")?.let { Place.Artist(it) }
            if (p != null) return@runCatching p
        }
        if (album) {
            if (t.album.isEmpty()) return@runCatching null
            val q = Http.enc("artist:\"${t.mainArtist}\" album:\"${t.album}\"")
            data(get("search/album?q=$q&limit=1")).firstOrNull()?.optLong("id")?.let { Place.Album(it) }
        } else findArtistId(t.mainArtist, false)?.optLong("id")?.let { Place.Artist(it) }
    }.getOrNull()

    fun artistByName(name: String): Long? = runCatching { findArtistId(name, false)?.optLong("id") }.getOrNull()

    private val pictures = ConcurrentHashMap<String, String>()

    /** A picture for an artist known only by name (local music), from Deezer. "" if none. */
    fun artistPicture(name: String): String {
        val k = name.lowercase()
        pictures[k]?.let { return it }
        val url = runCatching { findArtistId(name, true)?.str("picture_xl") }.getOrNull().orEmpty()
            .takeUnless { it.contains("/artist//") }.orEmpty()
        pictures[k] = url
        return url
    }

    /** A song radio: YouTube Music's mix for the seed, each pick matched on Deezer for metadata and covers. */
    fun radio(seed: Track, count: Int = 50): Section? {
        val video = Matcher.videoFor(seed) ?: return null
        val songs = YouTube.mix(video, count)
        if (songs.isEmpty()) return null
        val resolved = songs.map { c ->
            pool.submit<Track> {
                val (artist, title) = Matcher.splitTitle(c)
                val t = find(artist, title) ?: Matcher.fromYouTube(c, artist, title)
                Matcher.setSource(t, c.id)
                t
            }
        }.map { it.get() }
        val seen = hashSetOf(seed.sameSong)
        val items = resolved.filter { seen.add(it.sameSong) }
        Store.remember(items)
        return Section("Radio: ${seed.title}", items.map(Item::Song))
    }
}
