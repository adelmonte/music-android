package dev.musicandroid.data

import kotlinx.serialization.Serializable

/**
 * Anything playable. The key says what it is:
 * `local:<id>` a song on the device (MediaStore), `sp:`/`dz:`/`yt:`/`h:` a song
 * streamed from YouTube (Spotify id, Deezer id, YouTube video, or a hash of
 * artist and title), `radio:<id>` a live station, `ep:<url>` a podcast episode.
 */
@Serializable
data class Track(
    val key: String,
    val title: String,
    val artist: String,
    val albumArtist: String = "",
    val album: String = "",
    val year: Int? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val durationMs: Long = 0,
    /** Cover art: a URL, or a content URI for local songs. */
    val art: String = "",
    /** Where it plays from, when that's fixed: a content URI, a station or episode URL. */
    val uri: String = "",
    val isrc: String = "",
    /** Episodes: the show's feed, and the publish date (ms). */
    val feed: String = "",
    val date: Long = 0,
    val addedAt: Long = 0,
) {
    val kind: Kind
        get() = when {
            key.startsWith("local:") -> Kind.Local
            key.startsWith("radio:") -> Kind.Station
            key.startsWith("ep:") -> Kind.Episode
            else -> Kind.Song
        }

    /** Streamed songs come from YouTube; the rest have a fixed source. */
    val streamed: Boolean get() = kind == Kind.Song

    val mainArtist: String get() = artist.split(",").first().trim()

    val sameSong: Pair<String, String> get() = artist.lowercase() to title.lowercase()
}

enum class Kind { Local, Song, Station, Episode }

@Serializable
data class Show(
    val feed: String,
    val title: String,
    val author: String = "",
    val art: String = "",
    val description: String = "",
)

/** A row in a browsing list. */
sealed interface Item {
    data class Song(val track: Track) : Item
    data class Album(val id: Long, val title: String, val artist: String, val cover: String, val sub: String = "") : Item
    data class Artist(val id: Long, val name: String, val picture: String) : Item
    data class DeezerPlaylist(val id: Long, val title: String, val count: Int, val cover: String) : Item
    data class Podcast(val show: Show) : Item
}

data class Section(val title: String, val items: List<Item>) {
    val tracks: List<Track> get() = items.mapNotNull { (it as? Item.Song)?.track }
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    /** `liked`, `spotify` (imported), `linked` (a live Spotify link), `mine`. */
    val source: String = MINE,
    val link: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val LIKED = "liked"
        const val SPOTIFY = "spotify"
        const val LINKED = "linked"
        const val MINE = "mine"
    }
}

/** The YouTube video a song was matched to, and videos rejected for it. */
@Serializable
data class Match(val source: String? = null, val rejected: List<String> = emptyList())

/** A finished download: the file in shared storage, and where it shows in a file manager. */
@Serializable
data class Download(val uri: String, val path: String, val bytes: Long = 0)

@Serializable
data class PodStore(
    val subscriptions: List<Show> = emptyList(),
    /** Seconds into each episode, by key. */
    val progress: Map<String, Long> = emptyMap(),
    val played: Set<String> = emptySet(),
)

@Serializable
data class SavedRadio(val title: String, val seed: String, val tracks: List<Track>)

@Serializable
data class Session(val queue: List<Track> = emptyList(), val index: Int = 0, val positionMs: Long = 0)
