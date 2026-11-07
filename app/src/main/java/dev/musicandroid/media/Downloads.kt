package dev.musicandroid.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.musicandroid.data.Download
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import dev.musicandroid.net.Deezer
import dev.musicandroid.net.Http
import dev.musicandroid.net.Matcher
import dev.musicandroid.net.Spotify
import dev.musicandroid.net.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Streamed songs are saved as they're first played, into the shared Music
 * folder: `Music/<folder>/Album Artist/[Year] Album/NN Title.m4a`, tagged.
 * Downloads run as WorkManager jobs, so they finish across skips, closing
 * the app and restarts; a cut-off download resumes where it stopped.
 */
object Downloads {
    private const val TAG = "download"

    fun enqueue(context: Context, t: Track, replace: Boolean = false) {
        if (!t.streamed) return
        if (!replace && Store.downloads.value.containsKey(t.key)) return
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("track" to Store.json.encodeToString(Track.serializer(), t)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .addTag("key:${t.key}")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("dl-${t.key}", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** Keys of songs downloading now. */
    fun active(context: Context): Flow<Set<String>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
        infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            .mapNotNullTo(HashSet()) { i -> i.tags.firstOrNull { it.startsWith("key:") }?.removePrefix("key:") }
    }

    /** A finished download that's still there, or null (and forgotten if it was deleted). */
    fun file(context: Context, key: String): Download? {
        val d = Store.downloads.value[key] ?: return null
        val ok = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(d.uri), "r")?.use { true } ?: false }.getOrDefault(false)
        if (!ok) Store.downloads.value -= key
        return d.takeIf { ok }
    }

    /** Delete a song's file (to fetch another match, or to free space). */
    fun delete(context: Context, key: String) {
        val d = Store.downloads.value[key] ?: return
        runCatching { context.contentResolver.delete(Uri.parse(d.uri), null, null) }
        Store.downloads.value -= key
    }

    private fun part(s: String): String {
        var out = s.replace('/', '⁄').replace(':', '_').replace(Regex("[\\u0000\"*<>?\\\\|]"), "")
        while (out.endsWith('.') || out.endsWith(' ')) out = out.dropLast(1)
        return out.take(120).ifEmpty { "Unknown" }
    }

    /** Folder and file name, in the library layout music-tui uses. */
    fun layout(t: Track, ext: String): Pair<String, String> {
        val artist = if (t.albumArtist.equals("various artists", true)) "Compilations and Soundtracks" else part(t.albumArtist.ifEmpty { t.mainArtist })
        val album = (if (t.album.isEmpty()) "Singles" else part(t.album)).let { a -> t.year?.let { "[$it] $a" } ?: a }
        val title = part(t.title)
        val name = when {
            t.discNo != null && t.discNo > 1 && t.trackNo != null -> "${t.discNo}-%02d $title".format(t.trackNo)
            t.trackNo != null && t.album.isNotEmpty() -> "%02d $title".format(t.trackNo)
            else -> title
        }
        return "$artist/$album" to "$name.$ext"
    }

    /** Fill in what the source lacks from Deezer: album artist, album, numbering, year, cover. */
    fun enrich(t: Track): Track {
        val d = Deezer.lookup(t)
        var out = t
        if (d != null) {
            out = out.copy(
                albumArtist = d.albumArtist,
                album = out.album.ifEmpty { d.album },
                trackNo = out.trackNo ?: d.trackNo,
                discNo = out.discNo ?: d.discNo,
                year = out.year ?: d.year,
                art = out.art.ifEmpty { d.cover },
            )
        } else if (out.albumArtist.isEmpty() || out.albumArtist.contains(',')) {
            out = out.copy(albumArtist = out.mainArtist)
        }
        if (out.art.isEmpty() && out.key.startsWith("sp:") && !out.key.startsWith("sp:isrc")) out = out.copy(art = Spotify.cover(out.key))
        return out
    }

    /** Fetch [url] into [dest] in 1 MB ranges (YouTube throttles open-ended requests), continuing a partial file. */
    fun fetch(url: String, dest: File, progress: (Float) -> Unit) {
        var total = -1L
        RandomAccessFile(dest, "rw").use { out ->
            var at = out.length()
            out.seek(at)
            while (total < 0 || at < total) {
                val end = at + (1 shl 20) - 1
                val req = Request.Builder().url(url).header("Range", "bytes=$at-$end").build()
                Http.client.newCall(req).execute().use { resp ->
                    if (resp.code == 416 && total < 0) { total = at; return@use }
                    if (resp.code != 206 && resp.code != 200) throw IOException("HTTP ${resp.code}")
                    total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: resp.body.contentLength().also {
                        if (resp.code == 200) { out.setLength(0); out.seek(0); at = 0 }
                    }
                    val bytes = resp.body.bytes()
                    out.write(bytes)
                    at += bytes.size
                    if (resp.code == 200) total = at
                    if (bytes.isEmpty()) throw IOException("empty response")
                }
                if (total > 0) progress(at.toFloat() / total)
            }
        }
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Store.init(applicationContext)
        val t = runCatching { Store.json.decodeFromString(Track.serializer(), inputData.getString("track")!!) }.getOrNull()
            ?: return@withContext Result.failure()
        if (Downloads.file(applicationContext, t.key) != null) return@withContext Result.success()
        try {
            save(t)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 4) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "download failed")))
        }
    }

    private suspend fun save(base: Track) {
        val dir = File(applicationContext.cacheDir, "downloads").apply { mkdirs() }
        val safe = base.key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val meta = File(dir, "$safe.meta")
        val partFile = File(dir, "$safe.part")
        val t = Downloads.enrich(base)
        // The video already chosen for this song when it started streaming.
        val tried = mutableListOf<String>()
        var audio: YouTube.Audio? = null
        var video: String? = Matcher.videoFor(base)
        while (audio == null && video != null && tried.size < 3) {
            tried += video
            audio = runCatching { YouTube.audio(video) }.getOrNull()
            if (audio == null) {
                Matcher.reject(base)
                video = Matcher.videoFor(base)?.takeIf { it !in tried }
            }
        }
        audio ?: throw IOException("no YouTube match for ${base.title}")
        // A partial file only continues the same stream.
        val stamp = "$video.${audio.ext}"
        if (meta.exists() && meta.readText() != stamp) partFile.delete()
        meta.writeText(stamp)
        Downloads.fetch(audio.url, partFile) { setProgressAsync(workDataOf("p" to it)) }

        var bytes = partFile.readBytes()
        if (audio.ext == "m4a") {
            val cover = t.art.takeIf { it.startsWith("http") }?.let { runCatching { Http.bytes(it) }.getOrNull() }
            bytes = runCatching {
                Mp4Tags.write(bytes, Mp4Tags.Tags(t.title, t.artist, t.albumArtist, t.album, t.year, t.trackNo, t.discNo, cover))
            }.getOrDefault(bytes)
        }
        val settings = Store.settings.value
        val (folder, name) = Downloads.layout(t, audio.ext)
        val relative = "Music/${settings.downloadFolder.trim('/').ifEmpty { "music-android" }}/$folder"
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // A file this app already saved there (an earlier run of this download) is replaced, not duplicated.
        val existing = resolver.query(
            collection, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf("$relative/", name), null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, audio.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relative)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }) ?: throw IOException("couldn't create $relative/$name")
        try {
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            if (existing == null) resolver.delete(uri, null, null)
            throw e
        }
        Store.downloads.value += t.key to Download(uri.toString(), "$relative/$name", bytes.size.toLong())
        Store.saveNow()
        partFile.delete()
        meta.delete()
    }
}
