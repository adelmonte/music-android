package dev.musicandroid.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The UI's side of the player: state as flows, and the commands. */
class Connection(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null

    val current = MutableStateFlow<Track?>(null)
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val index = MutableStateFlow(-1)
    val playing = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val speed = MutableStateFlow(1f)

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            })
            sync()
            pending?.invoke(c)
            pending = null
        }, MoreExecutors.directExecutor())
        scope.launch {
            while (isActive) {
                controller?.let {
                    position.value = it.currentPosition.coerceAtLeast(0)
                    duration.value = it.duration.takeIf { d -> d > 0 } ?: (current.value?.durationMs ?: 0)
                }
                delay(if (playing.value) 250 else 1000)
            }
        }
    }

    private var pending: ((MediaController) -> Unit)? = null

    private fun withController(f: (MediaController) -> Unit) {
        val c = controller
        if (c != null) f(c) else pending = f
    }

    private fun sync() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).map { PlaybackService.track(c.getMediaItemAt(it)) }
        queue.value = items
        index.value = c.currentMediaItemIndex
        current.value = c.currentMediaItem?.let(PlaybackService::track)
        playing.value = c.isPlaying
        buffering.value = c.playbackState == Player.STATE_BUFFERING
        shuffle.value = c.shuffleModeEnabled
        repeat.value = c.repeatMode
        speed.value = c.playbackParameters.speed
        position.value = c.currentPosition.coerceAtLeast(0)
        duration.value = c.duration.takeIf { it > 0 } ?: (current.value?.durationMs ?: 0)
    }

    private fun items(tracks: List<Track>): List<MediaItem> {
        Store.remember(tracks)
        return tracks.map(PlaybackService::item)
    }

    /** Replace the queue with [tracks], starting at [start]. */
    fun play(tracks: List<Track>, start: Int = 0, shuffled: Boolean = false) = withController { c ->
        if (tracks.isEmpty()) return@withController
        c.shuffleModeEnabled = shuffled
        val first = if (shuffled) tracks.indices.random() else start.coerceIn(0, tracks.size - 1)
        c.setMediaItems(items(tracks), first, 0)
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<Track>) = withController { c ->
        if (c.mediaItemCount == 0) return@withController play(tracks)
        c.addMediaItems(c.currentMediaItemIndex + 1, items(tracks))
    }

    fun enqueue(tracks: List<Track>) = withController { c ->
        if (c.mediaItemCount == 0) return@withController play(tracks)
        c.addMediaItems(items(tracks))
    }

    fun toggle() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun seekBy(ms: Long) = withController { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) }
    fun jump(i: Int) = withController { it.seekTo(i, 0); it.play() }
    fun remove(i: Int) = withController { it.removeMediaItem(i) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun clear() = withController { c -> c.stop(); c.clearMediaItems() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Load a song's queue entry again (after a new match), from the start if it's playing. */
    fun reload(key: String) = withController { c ->
        PlaybackService.forget(key)
        val i = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == key } ?: return@withController
        val t = Store.known[key] ?: return@withController
        val wasCurrent = i == c.currentMediaItemIndex
        c.removeMediaItem(i)
        c.addMediaItem(i, PlaybackService.item(t))
        if (wasCurrent) { c.seekTo(i, 0); c.prepare(); c.play() }
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
