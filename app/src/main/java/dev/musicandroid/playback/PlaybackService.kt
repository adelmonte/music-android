package dev.musicandroid.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.musicandroid.MainActivity
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Section
import dev.musicandroid.data.Session
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import dev.musicandroid.media.Downloads
import dev.musicandroid.net.Deezer
import dev.musicandroid.net.Http
import dev.musicandroid.net.Matcher
import dev.musicandroid.net.RadioBrowser
import dev.musicandroid.net.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        /** The live station's current song, as it names it. */
        val liveTitle = MutableStateFlow<String?>(null)

        /** Why the current song couldn't play, for the UI. */
        val error = MutableStateFlow<String?>(null)

        private const val SCHEME = "music"

        /** Stream URLs by key, until they expire. */
        private val resolved = ConcurrentHashMap<String, YouTube.Audio>()

        fun forget(key: String) { resolved.remove(key) }

        fun item(t: Track): MediaItem {
            val meta = MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album.ifEmpty { null })
                .setAlbumArtist(t.albumArtist.ifEmpty { null })
                .setArtworkUri(t.art.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(
                    when (t.kind) {
                        Kind.Station -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
                        Kind.Episode -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                        else -> MediaMetadata.MEDIA_TYPE_MUSIC
                    },
                )
                .setDurationMs(t.durationMs.takeIf { it > 1000 })
                .build()
            val b = MediaItem.Builder().setMediaId(t.key).setMediaMetadata(meta)
            when (t.kind) {
                Kind.Song -> b.setUri(Uri.Builder().scheme(SCHEME).authority("song").appendPath(t.key).build())
                else -> b.setUri(t.uri)
            }
            if (t.kind == Kind.Station && (t.uri.contains(".m3u8") || t.uri.contains("/hls/"))) b.setMimeType(MimeTypes.APPLICATION_M3U8)
            if (t.kind == Kind.Station) b.setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
            return b.build()
        }

        fun track(item: MediaItem): Track = Store.known[item.mediaId] ?: Track(
            key = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            art = item.mediaMetadata.artworkUri?.toString().orEmpty(),
            uri = item.localConfiguration?.uri?.toString().orEmpty(),
        )
    }


    /** `music://song/<key>`: the downloaded file, else the YouTube stream (found and remembered on first play). */
    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SCHEME) return spec
        val key = spec.uri.lastPathSegment ?: throw IOException("bad song")
        Downloads.file(this, key)?.let { return spec.withUri(Uri.parse(it.uri)) }
        resolved[key]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }?.let { return spec.withUri(Uri.parse(it.url)) }
        val t = Store.known[key] ?: throw IOException("unknown song")
        val video = Matcher.videoFor(t) ?: throw IOException("Couldn't find “${t.title}” on YouTube")
        val audio = runCatching { YouTube.audio(video) }.getOrElse {
            // That upload is gone or blocked: try the next match.
            Matcher.reject(t)
            val next = Matcher.videoFor(t) ?: throw IOException("No playable match for “${t.title}”")
            YouTube.audio(next)
        }
        resolved[key] = audio
        return spec.withUri(Uri.parse(audio.url))
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        val data = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { resolve(it) }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(listener)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, callback).setSessionActivity(open).build()
        restore()
        scope.launch {
            Store.settings.map { it.podcastSpeed }.distinctUntilChanged().collect { applySpeed() }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) { noteProgress(); save() }
            }
        }
    }

    private fun current(): Track? = player.currentMediaItem?.let(::track)

    private fun applySpeed() {
        val speed = if (current()?.kind == Kind.Episode) Store.settings.value.podcastSpeed else 1f
        if (player.playbackParameters.speed != speed) player.playbackParameters = PlaybackParameters(speed)
    }

    /** Where an episode was left; finished once within 30 s (or 5%) of the end. */
    private fun noteProgress() {
        val t = current() ?: return
        if (t.kind != Kind.Episode) return
        val pos = player.currentPosition / 1000
        val len = (player.duration.takeIf { it > 0 } ?: t.durationMs) / 1000
        if (len > 60 && (pos + 30 >= len || pos >= len * 95 / 100)) Store.setPlayed(t.key, true)
        else if (pos > 5) Store.setProgress(t.key, pos)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            liveTitle.value = null
            error.value = null
            val t = item?.let(::track) ?: return
            applySpeed()
            when (t.kind) {
                Kind.Song -> Downloads.enqueue(this@PlaybackService, t)
                Kind.Station -> RadioBrowser.countPlay(t)
                Kind.Episode -> {
                    val p = Store.pods.value.progress[t.key] ?: 0
                    val len = t.durationMs / 1000
                    if (p > 10 && (len == 0L || p < len - 30) && player.currentPosition < 5_000) player.seekTo(p * 1000)
                }
                else -> {}
            }
            keepGoing()
            save()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Leaving an episode: remember where.
            if (old.mediaItem?.mediaId != new.mediaItem?.mediaId) old.mediaItem?.let(::track)?.takeIf { it.kind == Kind.Episode }?.let { t ->
                val pos = old.positionMs / 1000
                val len = t.durationMs / 1000
                if (len > 60 && pos + 30 >= len) Store.setPlayed(t.key, true) else if (pos > 5) Store.setProgress(t.key, pos)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) { noteProgress(); save() }
        }

        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val e = metadata.get(i)
                if (e is IcyInfo) liveTitle.value = e.title?.trim()?.takeIf { it.isNotEmpty() }
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val t = current()
            error.value = (e.cause?.message ?: e.message ?: "Playback failed").let { if (t != null) "${t.title}: $it" else it }
            // A stream URL can expire mid-session: forget it and move on.
            t?.let { resolved.remove(it.key) }
            if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
        }
    }

    private var extending = false

    /** Keep going: when the last song starts, a radio from it adds up to 25 more. */
    private fun keepGoing() {
        if (!Store.settings.value.keepGoing || extending || player.shuffleModeEnabled || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val t = current() ?: return
        if (t.kind == Kind.Station || t.kind == Kind.Episode || player.hasNextMediaItem()) return
        extending = true
        scope.launch {
            val r: Section? = withContext(Dispatchers.IO) { runCatching { Deezer.radio(t, 25) }.getOrNull() }
            val have = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            r?.tracks?.filter { it.key !in have }?.let { more -> player.addMediaItems(more.map(::item)) }
            extending = false
        }
    }

    private fun queue(): List<Track> = (0 until player.mediaItemCount).map { track(player.getMediaItemAt(it)) }

    private fun save() {
        if (player.mediaItemCount == 0) return
        Store.saveSession(Session(queue(), player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0)))
    }

    private fun restore() {
        val s = Store.loadSession()
        if (s.queue.isEmpty()) return
        Store.remember(s.queue)
        player.setMediaItems(s.queue.map(::item), s.index.coerceIn(0, s.queue.size - 1), s.positionMs)
        player.prepare()
    }

    private val callback = object : MediaLibrarySession.Callback {
        // Items from a controller arrive without their URIs: rebuild them from the key.
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, items: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(items.map { item(track(it)) }.toMutableList())

        // The system's media resumption (and car screens) browse for a root.
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId("root").setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setTitle("Music").build()).build(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(queue().map(::item)), params))

        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val s = Store.loadSession()
            Store.remember(s.queue)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(s.queue.map(::item), s.index, s.positionMs))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        save()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        noteProgress()
        save()
        Store.saveNow()
        scope.cancel()
        session?.run { release() }
        player.release()
        session = null
        super.onDestroy()
    }
}
