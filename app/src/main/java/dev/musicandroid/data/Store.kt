package dev.musicandroid.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the app keeps, as JSON files in its private storage: the
 * library (playlists and likes), settings, radio favorites, podcasts, song
 * matches, downloads and the session. Each is a StateFlow; changes are
 * written out shortly after they happen.
 */
object Store {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File

    val settings = MutableStateFlow(Settings())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val radioFavs = MutableStateFlow<List<Track>>(emptyList())
    val pods = MutableStateFlow(PodStore())
    val matches = MutableStateFlow<Map<String, Match>>(emptyMap())
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val radios = MutableStateFlow<List<SavedRadio>>(emptyList())

    /** Tracks by key, for whatever's been listed or queued (the player knows only keys). */
    val known = ConcurrentHashMap<String, Track>()

    private class Doc<T>(val name: String, val flow: MutableStateFlow<T>, val ser: KSerializer<T>)

    private val docs = listOf(
        Doc("settings.json", settings, Settings.serializer()),
        Doc("library.json", playlists, ListSerializer(Playlist.serializer())),
        Doc("radio.json", radioFavs, ListSerializer(Track.serializer())),
        Doc("podcasts.json", pods, PodStore.serializer()),
        Doc("matches.json", matches, MapSerializer(String.serializer(), Match.serializer())),
        Doc("downloads.json", downloads, MapSerializer(String.serializer(), Download.serializer())),
        Doc("radios.json", radios, ListSerializer(SavedRadio.serializer())),
    )

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            dir = context.filesDir
            for (d in docs) load(d)
            if (playlists.value.none { it.id == Playlist.LIKED }) {
                playlists.value = listOf(Playlist(Playlist.LIKED, "Liked Songs", source = Playlist.LIKED)) + playlists.value
            }
            for (d in docs) watch(d)
            loaded = true
        }
    }

    private fun <T> load(d: Doc<T>) {
        val f = File(dir, d.name)
        if (!f.exists()) return
        runCatching { d.flow.value = json.decodeFromString(d.ser, f.readText()) }
    }

    private fun <T> watch(d: Doc<T>) {
        var pending: Job? = null
        var last = d.flow.value
        scope.launch {
            d.flow.collect { v ->
                if (v === last) return@collect
                last = v
                pending?.cancel()
                pending = scope.launch {
                    delay(400)
                    write(d.name, json.encodeToString(d.ser, v))
                }
            }
        }
    }

    private fun write(name: String, body: String) = synchronized(dir) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(body)
        tmp.renameTo(File(dir, name))
    }

    /** Write everything now (before a process may be killed). */
    fun saveNow() = synchronized(this) { for (d in docs) writeDoc(d) }

    private fun <T> writeDoc(d: Doc<T>) = write(d.name, json.encodeToString(d.ser, d.flow.value))

    fun saveSession(s: Session) = scope.launch { write("session.json", json.encodeToString(Session.serializer(), s)) }

    fun loadSession(): Session = runCatching {
        json.decodeFromString(Session.serializer(), File(dir, "session.json").readText())
    }.getOrDefault(Session())

    fun remember(tracks: Collection<Track>) = tracks.forEach { known[it.key] = it }

    // --- library -----------------------------------------------------------

    val liked: StateFlow<List<Playlist>> get() = playlists

    fun likedKeys(): Set<String> = playlists.value.firstOrNull { it.id == Playlist.LIKED }?.tracks?.mapTo(HashSet()) { it.key } ?: emptySet()

    fun isLiked(t: Track): Boolean = playlists.value.firstOrNull { it.id == Playlist.LIKED }?.tracks?.any { it.key == t.key } == true

    fun toggleLike(t: Track): Boolean {
        var now = false
        playlists.update { lists ->
            lists.map { p ->
                if (p.id != Playlist.LIKED) p
                else if (p.tracks.any { it.key == t.key }) p.copy(tracks = p.tracks.filter { it.key != t.key }, updatedAt = System.currentTimeMillis())
                else { now = true; p.copy(tracks = p.tracks + t.copy(addedAt = System.currentTimeMillis()), updatedAt = System.currentTimeMillis()) }
            }
        }
        return now
    }

    fun newPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val p = Playlist("p" + System.currentTimeMillis().toString(36), name, tracks)
        playlists.update { it + p }
        return p
    }

    fun addToPlaylist(id: String, tracks: List<Track>) = playlists.update { lists ->
        lists.map { p ->
            if (p.id != id) p
            else p.copy(tracks = p.tracks + tracks.filter { t -> p.tracks.none { it.key == t.key } }, updatedAt = System.currentTimeMillis())
        }
    }

    fun removeFromPlaylist(id: String, key: String) = playlists.update { lists ->
        lists.map { p -> if (p.id != id) p else p.copy(tracks = p.tracks.filter { it.key != key }, updatedAt = System.currentTimeMillis()) }
    }

    fun deletePlaylist(id: String) = playlists.update { lists -> lists.filter { it.id != id || it.id == Playlist.LIKED } }

    fun renamePlaylist(id: String, name: String) = playlists.update { lists -> lists.map { if (it.id == id) it.copy(name = name) else it } }

    // --- matches -----------------------------------------------------------

    fun match(key: String): Match = matches.value[key] ?: Match()

    fun setMatch(key: String, f: (Match) -> Match) = matches.update { it + (key to f(it[key] ?: Match())) }

    // --- podcasts ----------------------------------------------------------

    fun subscribed(feed: String) = pods.value.subscriptions.any { it.feed == feed }

    fun toggleSubscription(show: Show): Boolean {
        val now = !subscribed(show.feed)
        pods.update { p -> p.copy(subscriptions = if (now) p.subscriptions + show else p.subscriptions.filter { it.feed != show.feed }) }
        return now
    }

    fun setProgress(key: String, secs: Long) = pods.update { it.copy(progress = it.progress + (key to secs)) }

    fun setPlayed(key: String, played: Boolean) = pods.update {
        it.copy(played = if (played) it.played + key else it.played - key, progress = if (played) it.progress - key else it.progress)
    }

    // --- radio -------------------------------------------------------------

    fun isFavorite(t: Track) = radioFavs.value.any { it.key == t.key }

    fun toggleFavorite(t: Track): Boolean {
        val now = !isFavorite(t)
        radioFavs.update { if (now) listOf(t) + it else it.filter { f -> f.key != t.key } }
        return now
    }

    fun saveRadio(r: SavedRadio) = radios.update { (listOf(r) + it.filter { x -> x.seed != r.seed }).take(5) }
}
