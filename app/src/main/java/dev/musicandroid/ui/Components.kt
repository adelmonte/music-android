package dev.musicandroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import dev.musicandroid.data.Section
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.musicandroid.data.Item
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import dev.musicandroid.net.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

val Gutter = 16.dp

private val lateArt = ConcurrentHashMap<String, String>()

/** Cover art for a song, fetched for Spotify songs that came without it (linked playlists). */
@Composable
fun artOf(t: Track): String {
    if (t.art.isNotEmpty() || !t.key.startsWith("sp:") || t.key.startsWith("sp:isrc")) return t.art
    val v by produceState(lateArt[t.key].orEmpty(), t.key) {
        if (value.isEmpty()) value = withContext(Dispatchers.IO) { Spotify.cover(t.key) }.also {
            lateArt[t.key] = it
            Store.known[t.key]?.let { k -> if (k.art.isEmpty()) Store.known[t.key] = k.copy(art = it) }
        }
    }
    return v
}

fun iconFor(t: Track): ImageVector = when (t.kind) {
    Kind.Station -> Icons.Rounded.Radio
    Kind.Episode -> Icons.Rounded.Podcasts
    else -> Icons.Rounded.MusicNote
}

@Composable
fun Cover(url: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), icon: ImageVector = Icons.Rounded.MusicNote) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxSize(0.42f))
        if (url.isNotEmpty()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(56.dp)) }
}

@Composable
fun Empty(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}

/** A tab's big title, with actions on the right. */
@Composable
fun ScreenTitle(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Gutter, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

/** A search field look-alike that opens search. */
@Composable
fun SearchPill(hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp).height(56.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onMore != null) TextButton(onClick = onMore) {
            Text("See all")
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 4.dp).size(18.dp))
        }
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A song, station or episode in a list. */
@Composable
fun TrackRow(
    t: Track,
    model: AppModel,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    number: Int? = null,
    showArt: Boolean = true,
) {
    val current by model.player.current.collectAsState()
    val downloading by model.downloading.collectAsState()
    val lists by model.playlists.collectAsState()
    val favs by model.radioFavs.collectAsState()
    val pods by model.pods.collectAsState()
    val playing = current?.key == t.key
    val liked = when (t.kind) {
        Kind.Station -> favs.any { it.key == t.key }
        else -> lists.firstOrNull { it.id == dev.musicandroid.data.Playlist.LIKED }?.tracks?.any { it.key == t.key } == true
    }
    val played = t.key in pods.played
    val progress = pods.progress[t.key]
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu).padding(start = Gutter, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null && !showArt) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (playing) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                else Text("$number", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (showArt) {
            Box {
                Cover(artOf(t), Modifier.size(52.dp), RoundedCornerShape(10.dp), iconFor(t))
                if (playing) Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Color.White)
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium, color = if (playing) MaterialTheme.colorScheme.primary else if (played) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val sub = when (t.kind) {
                Kind.Station -> listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · ")
                Kind.Episode -> listOfNotNull(t.date.takeIf { it > 0 }?.let(::fmtDate), episodeLength(t, progress, played)).joinToString(" · ")
                else -> t.artist
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (liked) Icon(Icons.Rounded.Favorite, null, Modifier.size(14.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                if (played) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.kind == Kind.Episode && progress != null && !played && t.durationMs > 0) {
                LinearProgressIndicator(progress = { (progress * 1000f / t.durationMs).coerceIn(0f, 1f) }, Modifier.padding(top = 6.dp).width(120.dp).height(3.dp))
            }
            if (t.key in downloading) LinearProgressIndicator(Modifier.padding(top = 6.dp).width(80.dp).height(2.dp))
        }
        when {
            t.kind == Kind.Station -> LiveChip()
            t.durationMs > 1000 && t.kind != Kind.Episode -> Text(fmtTime(t.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "More") }
    }
}

fun episodeLength(t: Track, progress: Long?, played: Boolean): String? {
    if (t.durationMs <= 1000) return null
    if (progress != null && !played) return fmtTime(t.durationMs - progress * 1000) + " left"
    return fmtTime(t.durationMs)
}

fun fmtDate(ms: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))

@Composable
fun LiveChip() {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text("LIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** A square card: album, playlist, show, song or station. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Card(art: String, title: String, subtitle: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, width: Dp = 156.dp, icon: ImageVector = Icons.Rounded.MusicNote, shape: Shape = RoundedCornerShape(20.dp)) {
    Column(Modifier.width(width).clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(bottom = 4.dp)) {
        Cover(art, Modifier.fillMaxWidth().aspectRatio(1f), shape, icon)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun ArtistCard(picture: String, name: String, onClick: () -> Unit, size: Dp = 120.dp) {
    Column(Modifier.width(size).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(picture, Modifier.size(size), MaterialShapes.Cookie12Sided.toShape(), Icons.Rounded.Person)
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A row of cards for a section's items. Songs play the section from the one tapped. */
@Composable
fun Shelf(section: Section, model: AppModel, nav: (String) -> Unit, onMenu: (Track) -> Unit, title: String = section.title) {
    SectionHeader(title, onMore = { nav(model.openSection(section)) }.takeIf { section.items.size > 6 })
    ItemRow(section.items, model, nav, onMenu)
}

@Composable
fun ItemRow(items: List<Item>, model: AppModel, nav: (String) -> Unit, onMenu: (Track) -> Unit) {
    val tracks = items.mapNotNull { (it as? Item.Song)?.track }
    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(items.take(30)) { _, item -> ItemCard(item, model, nav, onMenu, tracks) }
    }
}

@Composable
fun ItemCard(item: Item, model: AppModel, nav: (String) -> Unit, onMenu: (Track) -> Unit, queue: List<Track> = emptyList()) {
    when (item) {
        is Item.Song -> {
            val t = item.track
            Card(artOf(t), t.title, t.artist, onClick = {
                if (t.kind == Kind.Station) model.player.play(listOf(t))
                else model.player.play(queue.ifEmpty { listOf(t) }, queue.indexOf(t).coerceAtLeast(0))
            }, onLongClick = { onMenu(t) }, icon = iconFor(t), width = if (t.kind == Kind.Station) 132.dp else 156.dp)
        }
        is Item.Album -> Card(item.cover, item.title, item.sub.ifEmpty { item.artist }, onClick = { nav("album/${item.id}") })
        is Item.Artist -> ArtistCard(item.picture, item.name, onClick = { nav("artist/${item.id}") })
        is Item.DeezerPlaylist -> Card(item.cover, item.title, "${item.count} songs", onClick = { nav("dzplaylist/${item.id}") })
        is Item.Podcast -> Card(item.show.art, item.show.title, item.show.author, onClick = { nav(model.openShow(item.show)) }, icon = Icons.Rounded.Podcasts)
    }
}

/** Songs as list rows, each playing the whole list from itself. */
fun LazyListScope.trackItems(tracks: List<Track>, model: AppModel, onMenu: (Track) -> Unit, numbered: Boolean = false, showArt: Boolean = true, limit: Int = Int.MAX_VALUE) {
    val shown = tracks.take(limit)
    items(shown.size, key = { "${shown[it].key}#$it" }) { i ->
        val t = shown[i]
        TrackRow(t, model, onClick = {
            if (t.kind == Kind.Station) model.player.play(listOf(t)) else model.player.play(tracks, i)
        }, onMenu = { onMenu(t) }, number = if (numbered) (t.trackNo ?: (i + 1)) else null, showArt = showArt)
    }
}

/** A detail page's header: big image fading into the page, back button, title. */
@Composable
fun Hero(image: String, title: String, subtitle: String, onBack: () -> Unit, round: Boolean = false, icon: ImageVector = Icons.Rounded.MusicNote, actions: @Composable RowScope.() -> Unit = {}) {
    val bg = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().height(if (round) 380.dp else 420.dp)) {
        if (image.isNotEmpty()) {
            AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to bg)))
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp), content = actions)
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Gutter, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Play and shuffle buttons for a list page. */
@Composable
fun PlayButtons(tracks: List<Track>, model: AppModel, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Button(onClick = { model.player.play(tracks) }, enabled = tracks.isNotEmpty(), modifier = Modifier.height(52.dp)) {
            Icon(androidx.compose.material.icons.Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        androidx.compose.material3.FilledTonalButton(onClick = { model.player.play(tracks, shuffled = true) }, enabled = tracks.isNotEmpty(), modifier = Modifier.height(52.dp)) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Shuffle, null)
            Spacer(Modifier.width(8.dp))
            Text("Shuffle")
        }
        Spacer(Modifier.weight(1f))
        extra()
    }
}

/** Run once per key. */
@Composable
fun Once(key: Any?, f: suspend () -> Unit) = LaunchedEffect(key) { f() }
