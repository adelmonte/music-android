package dev.musicandroid.net

import android.os.Build
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Resume sync through a `music-tui serve` server: the queue, the song and the
 * position, so music-tui or music-kodi picks up where this phone stopped (and
 * the other way round). The newest record wins.
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
        Record(updated, device, queue.map(::portable), index, positionMs)

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
                else -> Store.json.decodeFromString(Record.serializer(), resp.body.string())
            }
        }
    }.onSuccess { status.value = "Connected" }.onFailure { status.value = it.message ?: "Couldn't reach the server" }.getOrNull()
}
