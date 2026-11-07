package dev.musicandroid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player as P
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Playlist
import dev.musicandroid.data.Track
import dev.musicandroid.ui.theme.MusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The cover's most vivid color, to theme the player with. */
@Composable
fun artColor(url: String): Color? {
    val context = LocalContext.current
    val c by produceState<Color?>(null, url) {
        if (url.isEmpty()) { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(url).size(160).allowHardware(false).build()
                val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bmp).generate()
                (p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb?.let { Color(it) }
            }.getOrNull()
        }
    }
    return c
}

@Composable
private fun PlayPause(model: AppModel, size: Int) {
    val playing by model.player.playing.collectAsState()
    val buffering by model.player.buffering.collectAsState()
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(Modifier.size(size.dp).clickable(interactionSource = null, indication = null) { model.player.toggle() }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = if (playing) angle else 0f }
                .clip(if (playing) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Circle.toShape())
                .background(MaterialTheme.colorScheme.primary),
        )
        if (buffering) LoadingIndicator(Modifier.size((size * 0.55).dp), color = MaterialTheme.colorScheme.onPrimary)
        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size((size * 0.45).dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun MiniPlayer(model: AppModel, onExpand: () -> Unit) {
    val t by model.player.current.collectAsState()
    val track = t ?: return
    val pos by model.player.position.collectAsState()
    val dur by model.player.duration.collectAsState()
    val playing by model.player.playing.collectAsState()
    val live by model.liveTitle.collectAsState()
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -12) onExpand() } }
            .clickable(onClick = onExpand),
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(artOf(track), Modifier.size(48.dp), RoundedCornerShape(14.dp), iconFor(track))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (track.kind == Kind.Station) live ?: track.artist else track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlayPause(model, 44)
                IconButton(onClick = { model.player.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
            if (track.kind != Kind.Station) LinearWavyProgressIndicator(
                progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                amplitude = { if (playing) 1f else 0f },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) else Spacer(Modifier.height(6.dp))
        }
    }
}

private fun fmtSpeed(s: Float) = (if (s % 1f == 0f) "${s.toInt()}" else "%.2f".format(s).trimEnd('0')) + "×"

@Composable
fun FullPlayer(model: AppModel, onClose: () -> Unit, onMenu: (MenuTarget) -> Unit) {
    val t by model.player.current.collectAsState()
    val settings by model.settings.collectAsState()
    val track = t ?: return onClose()
    val art = artOf(track)
    val seed = if (settings.artworkColors) artColor(art) else null
    MusicTheme(settings, seed) {
        PlayerBody(track, art, model, onClose, onMenu)
    }
}

@Composable
private fun PlayerBody(track: Track, art: String, model: AppModel, onClose: () -> Unit, onMenu: (MenuTarget) -> Unit) {
    val pos by model.player.position.collectAsState()
    val dur by model.player.duration.collectAsState()
    val playing by model.player.playing.collectAsState()
    val shuffle by model.player.shuffle.collectAsState()
    val repeat by model.player.repeat.collectAsState()
    val live by model.liveTitle.collectAsState()
    val lists by model.playlists.collectAsState()
    val favs by model.radioFavs.collectAsState()
    val settings by model.settings.collectAsState()
    var queue by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    val scheme = MaterialTheme.colorScheme
    if (queue) QueueSheet(model, onMenu) { queue = false }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.surface, scheme.surface)))
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d > 30) onClose() } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Text(
                    when (track.kind) { Kind.Station -> "Live radio"; Kind.Episode -> "Podcast"; Kind.Local -> "On this phone"; else -> "Streaming" },
                    style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { onMenu(MenuTarget(track)) }) { Icon(Icons.Rounded.MoreVert, "More") }
            }
            Spacer(Modifier.weight(0.6f))
            AnimatedContent(art, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }, label = "art") { a ->
                Cover(
                    a, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(32.dp)),
                    RoundedCornerShape(32.dp), iconFor(track),
                )
            }
            Spacer(Modifier.weight(0.5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(
                        if (track.kind == Kind.Station) live ?: track.artist else track.artist,
                        style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.basicMarquee().clickable { onClose(); model.goToArtist(track) },
                    )
                }
                val liked = when (track.kind) {
                    Kind.Station -> favs.any { it.key == track.key }
                    Kind.Episode -> false
                    else -> lists.firstOrNull { it.id == Playlist.LIKED }?.tracks?.any { it.key == track.key } == true
                }
                if (track.kind != Kind.Episode) FilledTonalIconButton(onClick = { model.like(track) }, modifier = Modifier.size(52.dp)) {
                    Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", tint = if (liked) scheme.primary else scheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (track.kind == Kind.Station) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveChip()
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.GraphicEq, null, tint = scheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(fmtTime(pos) + " listening", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
            } else {
                val shown = seeking ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    LinearWavyProgressIndicator(progress = { shown }, amplitude = { if (playing && seeking == null) 1f else 0f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
                    Slider(
                        value = shown, onValueChange = { seeking = it },
                        onValueChangeFinished = { seeking?.let { model.player.seekTo((it * dur).toLong()) }; seeking = null },
                        colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, thumbColor = scheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    Text(fmtTime(((seeking ?: 0f) * dur).toLong().takeIf { seeking != null } ?: pos), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(fmtTime(dur), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (track.kind == Kind.Episode) IconButton(onClick = { model.player.seekBy(-10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", Modifier.size(30.dp)) }
                else IconButton(onClick = { model.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) scheme.primary else scheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { model.player.previous() }, modifier = Modifier.size(64.dp), shape = IconButtonDefaults.mediumSquareShape) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp))
                }
                PlayPause(model, 92)
                FilledTonalIconButton(onClick = { model.player.next() }, modifier = Modifier.size(64.dp), shape = IconButtonDefaults.mediumSquareShape) {
                    Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp))
                }
                if (track.kind == Kind.Episode) IconButton(onClick = { model.player.seekBy(30_000) }) { Icon(Icons.Rounded.Forward30, "Ahead 30 seconds", Modifier.size(30.dp)) }
                else IconButton(onClick = { model.player.cycleRepeat() }) {
                    Icon(if (repeat == P.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (repeat != P.REPEAT_MODE_OFF) scheme.primary else scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.weight(0.4f))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (track.kind == Kind.Episode) {
                    AssistChip(onClick = {
                        val steps = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 0.75f)
                        val next = steps[(steps.indexOfFirst { kotlin.math.abs(it - settings.podcastSpeed) < 0.01f } + 1) % steps.size]
                        model.update { it.copy(podcastSpeed = next) }
                    }, label = { Text(fmtSpeed(settings.podcastSpeed)) }, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                } else if (track.kind != Kind.Station) {
                    TextButton(onClick = { onClose(); model.songRadio(track) }) { Icon(Icons.Rounded.Sensors, null); Spacer(Modifier.width(6.dp)); Text("Song radio") }
                } else Spacer(Modifier)
                TextButton(onClick = { queue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null); Spacer(Modifier.width(6.dp)); Text("Queue") }
            }
        }
    }
}

@Composable
private fun QueueSheet(model: AppModel, onMenu: (MenuTarget) -> Unit, onDismiss: () -> Unit) {
    val queue by model.player.queue.collectAsState()
    val index by model.player.index.collectAsState()
    var save by remember { mutableStateOf(false) }
    if (save) return AddToPlaylist(queue, model) { save = false }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 68.dp.toPx() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue · ${queue.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { save = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist") }
            TextButton(onClick = { model.player.clear(); onDismiss() }) { Text("Clear") }
        }
        LazyColumn(state = state, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(queue, key = { i, t -> "$i/${t.key}" }) { i, t ->
                val current = i == index
                Row(
                    Modifier.fillMaxWidth().height(68.dp).zIndex(if (dragging == i) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging == i) offset else 0f }
                        .background(if (dragging == i) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                        .clickable { model.player.jump(i) }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle, "Drag to move",
                        Modifier.padding(end = 8.dp).pointerInput(i, queue.size) {
                            detectDragGestures(
                                onDragStart = { dragging = i; offset = 0f },
                                onDragEnd = {
                                    val to = (i + (offset / rowPx).roundToInt()).coerceIn(0, queue.size - 1)
                                    if (to != i) model.player.move(i, to)
                                    dragging = -1; offset = 0f
                                },
                                onDragCancel = { dragging = -1; offset = 0f },
                            ) { change, d -> change.consume(); offset += d.y }
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Cover(artOf(t), Modifier.size(48.dp), RoundedCornerShape(12.dp), iconFor(t))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { model.player.remove(i) }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
        }
    }
}
