package dev.musicandroid.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track

/** The music on the device, from MediaStore. */
object LocalMusic {
    private val ART = Uri.parse("content://media/external/audio/albumart")

    data class Album(val id: Long, val title: String, val artist: String, val year: Int?, val art: String, val tracks: List<Track>)

    data class Artist(val name: String, val albums: List<Album>, val tracks: List<Track>)

    fun songs(context: Context): List<Track> {
        val out = mutableListOf<Track>()
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            "album_artist", MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val artist = c.getString(2).orEmpty().let { if (it == "<unknown>") "" else it }
                    val n = c.getInt(7)
                    val albumId = c.getLong(5)
                    out += Track(
                        key = "local:$id",
                        title = c.getString(1).orEmpty(),
                        artist = artist.ifEmpty { "Unknown artist" },
                        albumArtist = c.getString(3).orEmpty().ifEmpty { artist.ifEmpty { "Unknown artist" } },
                        album = c.getString(4).orEmpty(),
                        year = c.getInt(6).takeIf { it > 0 },
                        trackNo = (n % 1000).takeIf { it > 0 },
                        discNo = (n / 1000).takeIf { it > 0 },
                        durationMs = c.getLong(8),
                        art = ContentUris.withAppendedId(ART, albumId).toString(),
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        addedAt = c.getLong(9) * 1000,
                    )
                }
            }
        }
        Store.remember(out)
        return out
    }

    fun albums(songs: List<Track>): List<Album> = songs.groupBy { it.albumArtist.lowercase() to it.album.lowercase() }.map { (_, ts) ->
        val sorted = ts.sortedWith(compareBy({ it.discNo ?: 0 }, { it.trackNo ?: 0 }, { it.title }))
        val f = sorted.first()
        Album(f.art.substringAfterLast('/').toLongOrNull() ?: 0, f.album.ifEmpty { "Unknown album" }, f.albumArtist, sorted.mapNotNull { it.year }.maxOrNull(), f.art, sorted)
    }.sortedBy { it.title.lowercase() }

    fun artists(songs: List<Track>): List<Artist> = songs.groupBy { it.albumArtist }.map { (name, ts) ->
        Artist(name, albums(ts).sortedByDescending { it.year ?: 0 }, ts)
    }.sortedBy { sortName(it.name) }

    /** "The Doors" sorts as "Doors". */
    fun sortName(s: String) = s.lowercase().removePrefix("the ").removePrefix("a ")
}
