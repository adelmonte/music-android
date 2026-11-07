package dev.musicandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Playlist
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track

/** What a menu was opened on, and from where. */
data class MenuTarget(val track: Track, val playlistId: String? = null, val queueIndex: Int? = null)

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The long-press menu for a song, station or episode. */
@Composable
fun TrackMenu(target: MenuTarget, model: AppModel, onDismiss: () -> Unit) {
    val t = target.track
    var addTo by remember { mutableStateOf(false) }
    val live by model.liveTitle.collectAsState()
    val current by model.player.current.collectAsState()
    val lists by model.playlists.collectAsState()
    val pods by model.pods.collectAsState()
    val favs by model.radioFavs.collectAsState()
    if (addTo) return AddToPlaylist(listOf(t), model) { addTo = false; onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(artOf(t), Modifier.size(56.dp), RoundedCornerShape(12.dp), iconFor(t))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            fun run(f: () -> Unit) = { f(); onDismiss() }
            if (target.queueIndex == null) {
                Action(Icons.Rounded.SkipNext, "Play next", run { model.player.playNext(listOf(t)); model.say("Playing next") })
                Action(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", run { model.player.enqueue(listOf(t)); model.say("Added to the queue") })
            } else {
                Action(Icons.Rounded.SkipNext, "Play next", run { model.player.move(target.queueIndex, model.player.index.value + if (target.queueIndex > model.player.index.value) 1 else 0) })
                Action(Icons.Rounded.RemoveCircleOutline, "Remove from queue", run { model.player.remove(target.queueIndex) })
            }
            when (t.kind) {
                Kind.Station -> {
                    val fav = favs.any { it.key == t.key }
                    Action(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (fav) "Remove from favorites" else "Add to favorites", run { model.like(t) })
                    val song = live.takeIf { current?.key == t.key }
                    if (song != null) Action(Icons.Rounded.Search, "Find “$song” in Explore", run { model.findInExplore(song) })
                }
                Kind.Episode -> {
                    val played = t.key in pods.played
                    Action(Icons.Rounded.CheckCircle, if (played) "Mark as unplayed" else "Mark as played", run { model.togglePlayed(t) })
                    Action(Icons.Rounded.Podcasts, "Go to show", run { model.goToShow(t) })
                }
                else -> {
                    val liked = lists.firstOrNull { it.id == Playlist.LIKED }?.tracks?.any { it.key == t.key } == true
                    Action(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", run { model.like(t) })
                    Action(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist…") { addTo = true }
                    Action(Icons.Rounded.Sensors, "Song radio", run { model.songRadio(t) })
                    Action(Icons.Rounded.Person, "Go to ${t.mainArtist.ifEmpty { "artist" }}", run { model.goToArtist(t) })
                    if (t.album.isNotEmpty() || t.kind == Kind.Song) Action(Icons.Rounded.Album, "Go to album", run { model.goToAlbum(t) })
                    if (t.streamed && !t.key.startsWith("yt:")) Action(Icons.Rounded.SwapHoriz, "Wrong song? Use another match", run { model.reject(t) })
                }
            }
            val pid = target.playlistId
            if (pid != null && lists.firstOrNull { it.id == pid }?.source.let { it == Playlist.MINE || it == Playlist.LIKED || it == Playlist.SPOTIFY }) {
                Action(Icons.Rounded.Delete, "Remove from this playlist", run { Store.removeFromPlaylist(pid, t.key); model.say("Removed") })
            }
        }
    }
}

/** Pick a playlist (or make one) for [tracks]. */
@Composable
fun AddToPlaylist(tracks: List<Track>, model: AppModel, onDone: () -> Unit) {
    val lists by model.playlists.collectAsState()
    var naming by remember { mutableStateOf(false) }
    if (naming) return NameDialog("New playlist", "", onDone) { name -> model.newPlaylist(name, tracks); onDone() }
    ModalBottomSheet(onDismissRequest = onDone) {
        Text("Add to playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding()) {
            item { Action(Icons.Rounded.Add, "New playlist") { naming = true } }
            items(lists.filter { it.source != Playlist.LINKED && it.id != Playlist.LIKED }, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.tracks.size} songs") },
                    leadingContent = { Cover(p.tracks.firstOrNull()?.art.orEmpty(), Modifier.size(48.dp), RoundedCornerShape(10.dp)) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { model.addTo(p.id, tracks); onDone() },
                )
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, label: String = "Name", confirm: String = "Save", onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
