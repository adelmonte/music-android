package dev.musicandroid.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.musicandroid.data.Item
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Playlist
import dev.musicandroid.data.SavedRadio
import dev.musicandroid.data.Section
import dev.musicandroid.data.Settings
import dev.musicandroid.data.Show
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import dev.musicandroid.media.Downloads
import dev.musicandroid.media.LocalMusic
import dev.musicandroid.net.Deezer
import dev.musicandroid.net.Podcasts
import dev.musicandroid.net.RadioBrowser
import dev.musicandroid.net.Matcher
import dev.musicandroid.net.Spotify
import dev.musicandroid.playback.Connection
import dev.musicandroid.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class AppModel(app: Application) : AndroidViewModel(app) {
    init { Store.init(app) }

    val player = Connection(app)
    val settings: StateFlow<Settings> = Store.settings
    val playlists: StateFlow<List<Playlist>> = Store.playlists
    val downloading: StateFlow<Set<String>> = Downloads.active(app).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** One-line notices for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Screens to open, from actions that look things up first. */
    val navigation = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun say(s: String) { messages.tryEmit(s) }

    private suspend fun <T> io(f: suspend () -> T): T = withContext(Dispatchers.IO) { f() }

    fun update(f: (Settings) -> Settings) = Store.settings.update(f)

    // --- explore -------------------------------------------------------------

    val home = MutableStateFlow<List<Section>>(emptyList())
    val homeLoading = MutableStateFlow(false)
    private var homeLoaded = false

    /** The home page: built from your likes, then Deezer's charts. Sections arrive as they're ready. */
    fun loadHome(force: Boolean = false) {
        if ((homeLoaded && !force) || homeLoading.value) return
        homeLoaded = true
        homeLoading.value = true
        viewModelScope.launch {
            val liked = playlists.value.firstOrNull { it.id == Playlist.LIKED }?.tracks.orEmpty().filter { it.kind == Kind.Song || it.kind == Kind.Local }
            val likedSet = liked.mapTo(HashSet()) { it.sameSong }
            val topArtists = liked.groupingBy { it.mainArtist }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.filter { it.isNotBlank() }
            val sections = ConcurrentHashMap<String, Section>()
            fun publish() {
                val order = listOf("For you") + liked.takeLast(3).reversed().map { "Because you liked ${it.title}" } +
                    listOf("New releases for you", "Top albums", "Popular artists", "Top songs", "Popular playlists")
                home.value = order.mapNotNull { sections[it] }
            }
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Any?>>(
                async { io { runCatching { Deezer.charts() }.getOrDefault(emptyList()) }.forEach { sections[it.title] = it }; publish() },
            )
            if (topArtists.isNotEmpty()) {
                jobs += async { io { Deezer.forYou(topArtists.take(5), likedSet) }?.let { sections[it.title] = it; publish() } }
                jobs += async { io { Deezer.newReleases(topArtists.take(15)) }?.let { sections[it.title] = it; publish() } }
            }
            for (t in liked.takeLast(3).reversed()) jobs += async {
                io { runCatching { Deezer.radio(t, 30) }.getOrNull() }?.let { r ->
                    val title = "Because you liked ${t.title}"
                    sections[title] = Section(title, r.items.filter { (it as? Item.Song)?.track?.sameSong !in likedSet })
                    publish()
                }
            }
            jobs.awaitAll()
            homeLoading.value = false
        }
    }

    suspend fun searchExplore(q: String) = io { runCatching { Deezer.search(q) }.getOrDefault(emptyList()) }

    /** Lists opened from a section ("See all", song radios), by id. Null while loading. */
    val sections = MutableStateFlow<Map<String, Section?>>(emptyMap())

    fun openSection(s: Section): String {
        val id = "s" + System.nanoTime().toString(36)
        sections.update { it + (id to s) }
        return "section/$id"
    }

    val radios: StateFlow<List<SavedRadio>> = Store.radios

    /** A song radio: about 50 songs with a similar sound and mood. */
    fun songRadio(t: Track) {
        if (t.kind == Kind.Station || t.kind == Kind.Episode) return say("Song radio is for songs")
        val id = "radio-" + t.key.hashCode().toUInt().toString(36)
        sections.update { it + (id to null) }
        navigation.tryEmit("section/$id")
        viewModelScope.launch {
            val r = io { runCatching { Deezer.radio(t) }.getOrNull() }
            if (r == null) {
                sections.update { it - id }
                say("Couldn't make a radio from “${t.title}”")
                return@launch
            }
            sections.update { it + (id to r) }
            Store.saveRadio(SavedRadio(r.title, t.key, r.tracks))
        }
    }

    fun openRadio(r: SavedRadio) = navigation.tryEmit(openSection(Section(r.title, r.tracks.map(Item::Song))))

    fun goToArtist(t: Track) {
        if (t.kind == Kind.Local) return run { navigation.tryEmit("localartist/" + Uri.encode(t.albumArtist)) }
        if (t.kind == Kind.Episode) return goToShow(t)
        viewModelScope.launch {
            when (val p = io { Deezer.locate(t, false) }) {
                is Deezer.Place.Artist -> navigation.tryEmit("artist/${p.id}")
                else -> say("Couldn't find ${t.mainArtist} on Deezer")
            }
        }
    }

    fun goToAlbum(t: Track) {
        if (t.kind == Kind.Local) return run { navigation.tryEmit("localalbum/" + Uri.encode(t.albumArtist + "\u0000" + t.album)) }
        viewModelScope.launch {
            when (val p = io { Deezer.locate(t, true) }) {
                is Deezer.Place.Album -> navigation.tryEmit("album/${p.id}")
                else -> say("Couldn't find the album on Deezer")
            }
        }
    }

    fun goToShow(t: Track) {
        val show = Store.pods.value.subscriptions.firstOrNull { it.feed == t.feed } ?: Show(t.feed, t.artist, t.albumArtist, t.art)
        navigation.tryEmit(openShow(show))
    }

    fun findInExplore(song: String) = navigation.tryEmit("search?q=" + Uri.encode(song))

    // --- likes and playlists -------------------------------------------------

    fun like(t: Track) {
        when (t.kind) {
            Kind.Station -> say(if (Store.toggleFavorite(t)) "Added to favorites" else "Removed from favorites")
            Kind.Episode -> togglePlayed(t)
            else -> say(if (Store.toggleLike(t)) "Liked" else "Removed from Liked Songs")
        }
    }

    fun togglePlayed(t: Track) {
        val now = t.key !in Store.pods.value.played
        Store.setPlayed(t.key, now)
        say(if (now) "Marked as played" else "Marked as unplayed")
    }

    fun addTo(playlistId: String, tracks: List<Track>) {
        Store.addToPlaylist(playlistId, tracks)
        say("Added to ${playlists.value.firstOrNull { it.id == playlistId }?.name ?: "playlist"}")
    }

    fun newPlaylist(name: String, tracks: List<Track>) {
        Store.newPlaylist(name, tracks)
        say("Saved “$name”")
    }

    /** Wrong song: reject the current YouTube match and fetch the next-best. */
    fun reject(t: Track) {
        if (!t.streamed) return
        if (t.key.startsWith("yt:")) return say("This song is a specific YouTube video")
        viewModelScope.launch {
            io {
                Matcher.reject(t)
                Downloads.delete(getApplication(), t.key)
                Matcher.videoFor(t)
            } ?: return@launch say("No other matches for “${t.title}”")
            player.reload(t.key)
            Downloads.enqueue(getApplication(), t, replace = true)
            say("Fetching another match for “${t.title}”")
        }
    }

    // --- local music ---------------------------------------------------------

    val local = MutableStateFlow<List<Track>>(emptyList())

    fun loadLocal() = viewModelScope.launch { local.value = io { LocalMusic.songs(getApplication()) } }

    // --- spotify -------------------------------------------------------------

    val importing = MutableStateFlow(false)

    /** Import an Exportify export: a zip, CSV files, or a folder of them. */
    fun importSpotify(uris: List<Uri>, tree: Boolean = false) {
        importing.value = true
        viewModelScope.launch {
            val result = io {
                runCatching {
                    val cr = getApplication<Application>().contentResolver
                    val found = LinkedHashMap<String, MutableList<Pair<Long, Track>>>()
                    fun csv(name: String, text: String) = runCatching { Spotify.importCsv(name, text, found) }
                    for (u in uris) {
                        if (tree) {
                            DocumentFile.fromTreeUri(getApplication(), u)?.listFiles()?.sortedBy { it.name }?.forEach { f ->
                                if (f.name?.endsWith(".csv", true) == true) cr.openInputStream(f.uri)?.use { csv(f.name!!, it.readBytes().decodeToString()) }
                            }
                            continue
                        }
                        val name = DocumentFile.fromSingleUri(getApplication(), u)?.name.orEmpty()
                        cr.openInputStream(u)?.use { input ->
                            if (name.endsWith(".zip", true)) {
                                ZipInputStream(input).use { z ->
                                    while (true) {
                                        val e = z.nextEntry ?: break
                                        if (e.name.endsWith(".csv", true)) csv(e.name, z.readBytes().decodeToString())
                                    }
                                }
                            } else csv(name, input.readBytes().decodeToString())
                        }
                    }
                    val lists = Spotify.lists(found)
                    if (lists.isEmpty()) throw IllegalArgumentException("No songs found in that export")
                    val (merged, added) = Spotify.merge(Store.playlists.value, lists)
                    Store.playlists.value = merged
                    "Imported ${lists.size} lists, $added new songs"
                }.getOrElse { it.message ?: "Import failed" }
            }
            importing.value = false
            say(result)
        }
    }

    fun linkPlaylist(link: String) {
        if (Spotify.playlistId(link) == null) return say("That isn't a Spotify playlist link")
        viewModelScope.launch {
            val p = io { runCatching { Spotify.fetchLinked(link) }.getOrNull() } ?: return@launch say("Couldn't read that playlist")
            upsertLinked(p)
            update { s -> s.copy(spotifyLinks = (s.spotifyLinks - link) + link) }
            say("Linked “${p.name}”")
        }
    }

    private fun upsertLinked(p: Playlist) = Store.playlists.update { lists ->
        // A linked list replaces the imported snapshot of the same name.
        val rest = lists.filter { it.id != p.id && !(it.source == Playlist.SPOTIFY && it.name == p.name) }
        val i = lists.indexOfFirst { it.id == p.id }
        if (i < 0) rest + p else rest.toMutableList().apply { add(minOf(i, size), p) }
    }

    fun unlink(p: Playlist) {
        update { s -> s.copy(spotifyLinks = s.spotifyLinks - p.link) }
        Store.deletePlaylist(p.id)
    }

    private fun refreshLinks() = viewModelScope.launch {
        for (link in settings.value.spotifyLinks) io { runCatching { Spotify.fetchLinked(link) }.getOrNull() }?.let(::upsertLinked)
    }

    // --- radio ---------------------------------------------------------------

    val radioHome = MutableStateFlow<List<Section>>(emptyList())
    val radioFavs: StateFlow<List<Track>> = Store.radioFavs
    val liveTitle: StateFlow<String?> = PlaybackService.liveTitle

    fun loadRadio() {
        if (radioHome.value.isNotEmpty()) return
        viewModelScope.launch { radioHome.value = io { RadioBrowser.home() } }
    }

    suspend fun searchRadio(q: String) = io { RadioBrowser.search(q) }

    // --- podcasts ------------------------------------------------------------

    val newEpisodes = MutableStateFlow<List<Track>?>(null)
    val topPodcasts = MutableStateFlow<Section?>(null)
    val pods = Store.pods
    private val feeds = MutableStateFlow<Map<String, Podcasts.Feed>>(emptyMap())
    private val openShows = ConcurrentHashMap<String, Show>()

    fun loadPodcasts(force: Boolean = false) {
        if (newEpisodes.value == null || force) viewModelScope.launch { newEpisodes.value = io { Podcasts.newEpisodes(Store.pods.value.subscriptions) } }
        if (topPodcasts.value?.items.isNullOrEmpty()) viewModelScope.launch { topPodcasts.value = io { Podcasts.top() } ?: Section("Top podcasts", emptyList()) }
    }

    suspend fun searchPodcasts(q: String) = io { Podcasts.search(q) }

    fun openShow(s: Show): String {
        val id = "p" + s.feed.hashCode().toUInt().toString(36)
        openShows[id] = s
        return "show/$id"
    }

    fun show(id: String): Show? = openShows[id]

    suspend fun feed(s: Show): Podcasts.Feed? = feeds.value[s.feed] ?: io { runCatching { Podcasts.feed(s) }.getOrNull() }?.also { f -> feeds.update { it + (s.feed to f) } }

    fun subscribe(s: Show) {
        val now = Store.toggleSubscription(s)
        say(if (now) "Subscribed to ${s.title}" else "Unsubscribed")
        if (now) loadPodcasts(force = true)
    }

    // --- startup -------------------------------------------------------------

    init {
        refreshLinks()
        viewModelScope.launch { PlaybackService.error.collect { it?.let(::say) } }
    }

    override fun onCleared() {
        player.release()
    }
}
