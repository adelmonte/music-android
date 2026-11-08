package dev.musicandroid.net

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Playlist
import dev.musicandroid.data.Show
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Sync through a `music-tui serve` server. Resume: the queue, the song and the
 * position, so music-tui or music-kodi picks up where this phone stopped (and
 * the other way round); the newest record wins. Library and podcasts: each
 * round sends what changed since the server last confirmed and takes the
 * merged result; the newest change to each song, list or episode wins.
 * Linked Spotify playlists stay on each device.
 */
object Sync {
    @Serializable
    data class Record(
        val updated: Long = 0,
        val device: String = "",
        val queue: List<Track> = emptyList(),
        val index: Int = 0,
        val positionMs: Long = 0,
    )

    /** The last outcome, for Settings. */
    val status = MutableStateFlow<String?>(null)

    private val client = Http.client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    val enabled: Boolean get() = Store.settings.value.let { it.syncServer.isNotBlank() && it.syncKey.isNotBlank() }

    val device: String get() = "music-android on ${Build.MODEL}"

    private fun request(): Request.Builder {
        val s = Store.settings.value
        return Request.Builder()
            .url(s.syncServer.trim().trimEnd('/') + "/v1/now")
            .header("Authorization", "Bearer " + s.syncKey.trim())
            .header("User-Agent", Http.USER_AGENT)
    }

    /** music-android's key for a song known by artist and title (music-tui and music-kodi use the same). */
    fun hashed(artist: String, title: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest("${artist.lowercase()}\u0000${title.lowercase()}".toByteArray())
        return "h:" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Songs on this phone go out as artist and title; the other devices find their own copy. */
    private fun portable(t: Track): Track =
        if (t.kind != Kind.Local) t
        else t.copy(key = hashed(t.artist, t.title), uri = "", art = t.art.takeIf { it.startsWith("http") }.orEmpty())

    fun record(queue: List<Track>, index: Int, positionMs: Long, updated: Long) =
        fromStart(Record(updated, device, queue.map(::portable), index, positionMs))

    /** Only podcast episodes carry their position; songs start over. */
    private fun fromStart(r: Record) =
        if (r.queue.getOrNull(r.index)?.key?.startsWith("ep:") == true) r else r.copy(positionMs = 0)

    /** Blocking; call off the main thread. */
    fun push(r: Record) {
        val body = Store.json.encodeToString(Record.serializer(), r).toRequestBody("application/json".toMediaType())
        runCatching {
            client.newCall(request().put(body).build()).execute().use { resp ->
                if (resp.code == 401) throw IOException("The server doesn't know this key")
                if (!resp.isSuccessful) throw IOException("Server answered ${resp.code}")
            }
        }.onSuccess { status.value = "Synced" }.onFailure { status.value = it.message ?: "Couldn't reach the server" }
    }

    /** The server's record, if any. Blocking. */
    fun pull(): Record? = runCatching {
        client.newCall(request().get().build()).execute().use { resp ->
            when {
                resp.code == 204 -> null
                resp.code == 401 -> throw IOException("The server doesn't know this key")
                !resp.isSuccessful -> throw IOException("Server answered ${resp.code}")
                else -> fromStart(Store.json.decodeFromString(Record.serializer(), resp.body.string()))
            }
        }
    }.onSuccess { status.value = "Connected" }.onFailure { status.value = it.message ?: "Couldn't reach the server" }.getOrNull()

    // --- library and podcasts ------------------------------------------------

    @Serializable
    data class Ep(val pos: Long = 0, val played: Boolean = false)

    @Serializable
    data class SList(val id: String, val name: String, val source: String = "", val tracks: List<Track> = emptyList())

    @Serializable
    data class State(val lists: List<SList> = emptyList(), val eps: Map<String, Ep> = emptyMap(), val subs: List<Show> = emptyList())

    @Serializable
    data class BaseList(val id: String, val name: String, val keys: List<String>)

    /** What the server last confirmed. */
    @Serializable
    data class Base(
        val versions: Map<String, Long> = emptyMap(),
        val lists: List<BaseList> = emptyList(),
        val eps: Map<String, Ep> = emptyMap(),
        val subs: List<String> = emptyList(),
    )

    @Serializable
    data class Answer(
        val versions: Map<String, Long> = emptyMap(),
        val lists: List<SList>? = null,
        val eps: Map<String, Ep>? = null,
        val subs: List<Show>? = null,
    )

    private lateinit var baseFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** The library loop: changes go up within half a minute, the rest comes down every two. */
    fun start(context: Context) {
        if (started) return
        started = true
        baseFile = File(context.filesDir, "sync_base.json")
        scope.launch {
            var last: State? = null
            var polled = 0L
            delay(3_000)
            while (true) {
                if (enabled) {
                    val now = state()
                    if (now != last || System.currentTimeMillis() - polled >= 120_000) {
                        polled = System.currentTimeMillis()
                        last = runCatching { if (syncLibrary()) state() else null }
                            .onFailure { status.value = it.message ?: "Couldn't reach the server" }
                            .getOrElse { now }
                    }
                }
                delay(30_000)
            }
        }
        // Android pauses the app once it's out of sight, so a job also syncs
        // when it leaves the screen and every quarter hour after.
        val work = WorkManager.getInstance(context)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        work.enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build())
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    work.enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build())
                }
            })
        }
    }

    /** One round from the background job. */
    fun runOnce(context: Context) {
        Store.init(context)
        if (!enabled) return
        runCatching { syncLibrary() }.onFailure { status.value = it.message ?: "Couldn't reach the server" }
    }

    private fun state(): State {
        val lists = Store.playlists.value.filter { it.source != Playlist.LINKED }.map { p -> SList(p.id, p.name, p.source, p.tracks.map(::portable)) }
        val p = Store.pods.value
        val eps = p.progress.mapValues { Ep(it.value) } + p.played.associateWith { Ep(0, true) }
        return State(lists, eps, p.subscriptions)
    }

    private fun op(t: String, at: Long, f: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject { put("t", t); put("at", at); f() }

    private fun diff(base: Base?, cur: State, now: Long): List<JsonObject> {
        val at = if (base != null) now else 1L
        val b = base ?: Base()
        val ops = mutableListOf<JsonObject>()
        val old = b.lists.associateBy { it.id }
        cur.lists.forEachIndexed { i, l ->
            val was = old[l.id]
            if (was == null || was.name != l.name) ops += op("list", at) { put("id", l.id); put("name", l.name); put("source", l.source); put("pos", i); put("deleted", false) }
            val had = was?.keys?.toSet().orEmpty()
            val keys = mutableSetOf<String>()
            l.tracks.forEachIndexed { j, t ->
                if (!keys.add(t.key)) return@forEachIndexed
                if (t.key !in had) ops += op("song", at) {
                    put("list", l.id); put("key", t.key); put("track", Store.json.encodeToJsonElement(Track.serializer(), t)); put("pos", j); put("present", true)
                }
            }
            for (k in had - keys) ops += op("song", at) { put("list", l.id); put("key", k); put("track", buildJsonObject { put("key", k) }); put("pos", 0); put("present", false) }
        }
        val ids = cur.lists.map { it.id }.toSet()
        for (l in b.lists) if (l.id !in ids) ops += op("list", at) { put("id", l.id); put("name", l.name); put("source", ""); put("pos", 0); put("deleted", true) }
        for ((k, e) in cur.eps) if (b.eps[k] != e) ops += op("ep", at) { put("key", k); put("pos", e.pos); put("played", e.played) }
        for (k in b.eps.keys - cur.eps.keys) ops += op("ep", at) { put("key", k); put("pos", 0); put("played", false) }
        val feeds = cur.subs.map { it.feed }.toSet()
        for (s in cur.subs) if (s.feed !in b.subs) ops += op("sub", at) { put("feed", s.feed); put("show", Store.json.encodeToJsonElement(Show.serializer(), s)); put("present", true) }
        for (f in b.subs - feeds) ops += op("sub", at) { put("feed", f); put("show", buildJsonObject { put("feed", f) }); put("present", false) }
        return ops
    }

    /** One round, blocking. False if the library changed meanwhile (the next round sends that). */
    private fun syncLibrary(): Boolean = synchronized(this) {
        val base = runCatching { Store.json.decodeFromString(Base.serializer(), baseFile.readText()) }.getOrNull()
        val cur = state()
        val body = buildJsonObject {
            put("versions", buildJsonObject { base?.versions.orEmpty().forEach { (k, v) -> put(k, v) } })
            put("ops", kotlinx.serialization.json.JsonArray(diff(base, cur, System.currentTimeMillis())))
        }.toString().toRequestBody("application/json".toMediaType())
        val s = Store.settings.value
        val req = Request.Builder().url(s.syncServer.trim().trimEnd('/') + "/v1/sync")
            .header("Authorization", "Bearer " + s.syncKey.trim()).header("User-Agent", Http.USER_AGENT).post(body).build()
        val answer = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("The server doesn't know this key")
            if (!resp.isSuccessful) throw IOException("Server answered ${resp.code}")
            Store.json.decodeFromString(Answer.serializer(), resp.body.string())
        }
        if (state() != cur) return false
        var lists = cur.lists
        answer.lists?.let { server ->
            Store.playlists.update { local ->
                val mine = local.flatMap { it.tracks }.associateBy { it.key }
                // Songs on this phone travel as artist and title; keep playing the phone's copy.
                val onPhone = local.flatMap { it.tracks }.filter { it.kind == Kind.Local }.associateBy { hashed(it.artist, it.title) }
                val linked = local.filter { it.source == Playlist.LINKED }
                server.map { l ->
                    val was = local.find { it.id == l.id }
                    Playlist(l.id, l.name, l.tracks.map { t -> onPhone[t.key] ?: mine[t.key]?.let { m -> if (t.art.isEmpty() && m.art.isNotEmpty()) t.copy(art = m.art) else t } ?: t },
                        source = l.source.ifEmpty { Playlist.MINE }, link = was?.link.orEmpty())
                } + linked
            }
            lists = server
        }
        var eps = cur.eps
        var subs = cur.subs
        if (answer.eps != null) {
            eps = answer.eps
            subs = answer.subs.orEmpty()
            Store.pods.update { p ->
                p.copy(
                    progress = eps.filter { !it.value.played && it.value.pos > 0 }.mapValues { it.value.pos },
                    played = eps.filter { it.value.played }.keys,
                    subscriptions = subs,
                )
            }
        }
        val nb = Base(answer.versions, lists.map { l -> BaseList(l.id, l.name, l.tracks.map { it.key }) }, eps, subs.map { it.feed })
        baseFile.writeText(Store.json.encodeToString(Base.serializer(), nb))
        status.value = "Synced"
        return true
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(Dispatchers.IO) {
        Sync.runOnce(applicationContext)
        Result.success()
    }
}
