package dev.musicandroid.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.musicandroid.data.Item
import dev.musicandroid.data.Playlist
import dev.musicandroid.data.Section
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import dev.musicandroid.media.LocalMusic
import dev.musicandroid.net.Deezer
import dev.musicandroid.ui.AppModel
import dev.musicandroid.ui.ArtistCard
import dev.musicandroid.ui.Card
import dev.musicandroid.ui.Cover
import dev.musicandroid.ui.Empty
import dev.musicandroid.ui.Gutter
import dev.musicandroid.ui.Hero
import dev.musicandroid.ui.MenuTarget
import dev.musicandroid.ui.NameDialog
import dev.musicandroid.ui.PlayButtons
import dev.musicandroid.ui.ScreenTitle
import dev.musicandroid.ui.SectionHeader
import dev.musicandroid.ui.artOf
import dev.musicandroid.ui.trackItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("Playlists", "Liked", "Songs", "Albums", "Artists")

private val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun LibraryScreen(model: AppModel, nav: (String) -> Unit, onMenu: (MenuTarget) -> Unit, bottom: PaddingValues) {
    val pager = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) model.loadLocal() }
    val local by model.local.collectAsState()
    LaunchedEffect(granted) { if (granted && local.isEmpty()) model.loadLocal() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Library") { LibraryActions(model) }
        PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = Gutter, divider = {}) {
            TABS.forEachIndexed { i, name -> Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(name) }) }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, verticalAlignment = Alignment.Top) { page ->
            val needsPermission: @Composable () -> Unit = {
                Empty(Icons.Rounded.LibraryMusic, "Your music on this phone", "Allow access to show the songs, albums and artists on this device.") {
                    Button(onClick = { ask.launch(audioPermission) }) { Text("Allow access") }
                }
            }
            when (page) {
                0 -> PlaylistGrid(model, nav, bottom)
                1 -> LikedList(model, onMenu, bottom)
                2 -> if (!granted) needsPermission() else SongsList(local, model, onMenu, bottom)
                3 -> if (!granted) needsPermission() else AlbumGrid(local, nav, bottom)
                else -> if (!granted) needsPermission() else ArtistGrid(local, nav, bottom)
            }
        }
    }
}

@Composable
private fun LibraryActions(model: AppModel) {
    var menu by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val settings by model.settings.collectAsState()
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) model.importSpotify(it) }
    if (link) NameDialog("Link a Spotify playlist", "", { link = false }, label = "Share link", confirm = "Link") { model.linkPlaylist(it); link = false }
    if (naming) NameDialog("New playlist", "", { naming = false }) { model.newPlaylist(it, emptyList()); naming = false }
    IconButton(onClick = { naming = true }) { Icon(Icons.Rounded.Add, "New playlist") }
    if (settings.showSpotify) Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Import Spotify export") }, leadingIcon = { Icon(Icons.Rounded.FileUpload, null) }, onClick = {
                menu = false
                files.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
            })
            DropdownMenuItem({ Text("Link a Spotify playlist") }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, onClick = { menu = false; link = true })
        }
    }
}

private fun mosaic(p: Playlist): List<String> = p.tracks.asReversed().map { it.art }.filter { it.isNotEmpty() }.distinct().take(4)

@Composable
private fun PlaylistCover(p: Playlist, modifier: Modifier) {
    val arts = mosaic(p)
    val shape = RoundedCornerShape(20.dp)
    if (p.id == Playlist.LIKED) {
        Box(modifier.clip(shape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Favorite, null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.onPrimary)
        }
    } else if (arts.size < 4) Cover(arts.firstOrNull() ?: dev.musicandroid.net.Spotify.linkedCovers[p.id].orEmpty(), modifier, shape)
    else Column(modifier.clip(shape)) {
        for (r in 0..1) Row(Modifier.weight(1f)) {
            for (c in 0..1) Cover(arts[r * 2 + c], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
        }
    }
}

@Composable
private fun PlaylistGrid(model: AppModel, nav: (String) -> Unit, bottom: PaddingValues) {
    val lists by model.playlists.collectAsState()
    val settings by model.settings.collectAsState()
    val importing by model.importing.collectAsState()
    val shown = lists.filter { settings.showSpotify || (it.source != Playlist.SPOTIFY && it.source != Playlist.LINKED) }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = bottom.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (importing) item(span = { GridItemSpan(maxLineSpan) }) { dev.musicandroid.ui.Loading() }
        items(shown, key = { it.id }) { p ->
            Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable { nav("list/${p.id}") }) {
                PlaylistCover(p, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
                val kind = when (p.source) { Playlist.SPOTIFY -> " · Spotify"; Playlist.LINKED -> " · ↻ linked"; else -> "" }
                Text("${songs(p.tracks.size)}$kind", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
        }
        if (shown.size <= 1 && settings.showSpotify) item(span = { GridItemSpan(maxLineSpan) }) {
            Empty(Icons.Rounded.FileUpload, "Bring your Spotify library", "Export it at exportify.app (Export All), then import the zip from the ⋮ menu. Or link a playlist to keep it live.")
        }
    }
}

@Composable
private fun LikedList(model: AppModel, onMenu: (MenuTarget) -> Unit, bottom: PaddingValues) {
    val lists by model.playlists.collectAsState()
    val liked = lists.firstOrNull { it.id == Playlist.LIKED }?.tracks.orEmpty().asReversed()
    LazyColumn(contentPadding = bottom) {
        if (liked.isEmpty()) item { Empty(Icons.Rounded.Favorite, "No liked songs yet", "Long-press any song and choose Like.") }
        else item { PlayButtons(liked, model) }
        trackItems(liked, model, { onMenu(MenuTarget(it, Playlist.LIKED)) })
    }
}

@Composable
private fun SongsList(songs: List<Track>, model: AppModel, onMenu: (MenuTarget) -> Unit, bottom: PaddingValues) {
    LazyColumn(contentPadding = bottom) {
        if (songs.isEmpty()) item { Empty(Icons.Rounded.LibraryMusic, "No music on this phone", "Songs you play from Explore and Spotify are saved to Music/ and show up here.") }
        else item { PlayButtons(songs, model) }
        trackItems(songs, model, { onMenu(MenuTarget(it)) })
    }
}

@Composable
private fun AlbumGrid(songs: List<Track>, nav: (String) -> Unit, bottom: PaddingValues) {
    val albums = remember(songs) { LocalMusic.albums(songs) }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = bottom.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(albums, key = { it.artist + "\u0000" + it.title }) { a ->
            Card(a.art, a.title, a.artist, onClick = { nav("localalbum/" + android.net.Uri.encode(a.artist + "\u0000" + a.tracks.first().album)) }, width = 400.dp, icon = Icons.Rounded.Album)
        }
    }
}

/** An artist's picture from Deezer, for music known only by name. */
@Composable
fun artistPicture(name: String): String {
    val url by produceState("", name) { value = withContext(Dispatchers.IO) { Deezer.artistPicture(name) } }
    return url
}

@Composable
private fun ArtistGrid(songs: List<Track>, nav: (String) -> Unit, bottom: PaddingValues) {
    val artists = remember(songs) { LocalMusic.artists(songs) }
    LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = bottom.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(artists, key = { it.name }) { a -> ArtistCard(artistPicture(a.name), a.name, { nav("localartist/" + android.net.Uri.encode(a.name)) }, size = 110.dp) }
    }
}

private val SORTS = listOf("Recently added", "Oldest", "Title", "Artist", "Album")

@Composable
fun MyPlaylistScreen(model: AppModel, id: String, back: () -> Unit, onMenu: (MenuTarget) -> Unit, bottom: PaddingValues) {
    val lists by model.playlists.collectAsState()
    val p = lists.firstOrNull { it.id == id } ?: return LaunchedEffect(Unit) { back() }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    if (rename) NameDialog("Rename playlist", p.name, { rename = false }) { Store.renamePlaylist(p.id, it); rename = false }
    val tracks = remember(p.tracks, sort) {
        when (sort) {
            0 -> p.tracks.asReversed()
            1 -> p.tracks
            2 -> p.tracks.sortedBy { it.title.lowercase() }
            3 -> p.tracks.sortedBy { LocalMusic.sortName(it.artist) }
            else -> p.tracks.sortedBy { it.album.lowercase() }
        }
    }
    val bg = MaterialTheme.colorScheme.surface
    LazyColumn(contentPadding = bottom) {
        item {
            Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    if (p.id != Playlist.LIKED) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            if (p.source == Playlist.LINKED) {
                                DropdownMenuItem({ Text("Refresh") }, leadingIcon = { Icon(Icons.Rounded.Sync, null) }, onClick = { menu = false; model.linkPlaylist(p.link) })
                                DropdownMenuItem({ Text("Unlink") }, leadingIcon = { Icon(Icons.Rounded.LinkOff, null) }, onClick = { menu = false; model.unlink(p); back() })
                            } else {
                                DropdownMenuItem({ Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; rename = true })
                                DropdownMenuItem({ Text("Delete playlist") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; Store.deletePlaylist(p.id); model.say("Deleted “${p.name}”"); back() })
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PlaylistCover(p, Modifier.size(220.dp))
                Text(p.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, start = Gutter, end = Gutter), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(songs(p.tracks.size) + if (p.source == Playlist.LINKED) " · refreshes every launch" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            PlayButtons(tracks, model) {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SORTS.forEachIndexed { i, s -> DropdownMenuItem({ Text(s, fontWeight = if (i == sort) FontWeight.Bold else null) }, onClick = { sort = i; sortMenu = false }) }
                    }
                }
            }
        }
        if (tracks.isEmpty()) item { Empty(Icons.Rounded.LibraryMusic, "Empty playlist", "Long-press a song anywhere and choose Add to playlist.") }
        trackItems(tracks, model, { onMenu(MenuTarget(it, p.id)) })
    }
}

@Composable
fun LocalAlbumScreen(model: AppModel, key: String, nav: (String) -> Unit, back: () -> Unit, onMenu: (MenuTarget) -> Unit, bottom: PaddingValues) {
    val local by model.local.collectAsState()
    val (artist, title) = key.split('\u0000').let { it[0] to it.getOrElse(1) { "" } }
    val tracks = remember(local, key) {
        local.filter { it.albumArtist == artist && it.album == title }.sortedWith(compareBy({ it.discNo ?: 0 }, { it.trackNo ?: 0 }, { it.title }))
    }
    LazyColumn(contentPadding = bottom) {
        item { Hero(tracks.firstOrNull()?.art.orEmpty(), title.ifEmpty { "Unknown album" }, listOfNotNull(artist, tracks.mapNotNull { it.year }.maxOrNull()?.toString()).joinToString(" · "), back, icon = Icons.Rounded.Album) }
        item { PlayButtons(tracks, model) { androidx.compose.material3.FilledTonalIconButton(onClick = { nav("localartist/" + android.net.Uri.encode(artist)) }) { Icon(Icons.Rounded.Person, "Artist") } } }
        trackItems(tracks, model, { onMenu(MenuTarget(it)) }, numbered = true, showArt = false)
    }
}

@Composable
fun LocalArtistScreen(model: AppModel, name: String, nav: (String) -> Unit, back: () -> Unit, onMenu: (MenuTarget) -> Unit, bottom: PaddingValues) {
    val local by model.local.collectAsState()
    val artist = remember(local, name) { LocalMusic.artists(local.filter { it.albumArtist == name }).firstOrNull() }
    val picture = artistPicture(name)
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = bottom) {
        item { Hero(picture, name, artist?.let { "${it.albums.size} albums · ${it.tracks.size} songs" } ?: "", back, round = true, icon = Icons.Rounded.Person) }
        val tracks = artist?.tracks.orEmpty()
        item {
            PlayButtons(tracks, model) {
                FilledTonalButton(onClick = {
                    scope.launch {
                        val id = withContext(Dispatchers.IO) { Deezer.artistByName(name) }
                        if (id != null) nav("artist/$id") else model.say("Couldn't find $name on Deezer")
                    }
                }) { Text("Explore") }
            }
        }
        if (artist != null) {
            item {
                SectionHeader("Albums")
                LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(artist.albums, key = { it.title }) { a ->
                        Card(a.art, a.title, a.year?.toString().orEmpty(), onClick = { nav("localalbum/" + android.net.Uri.encode(a.artist + "\u0000" + a.tracks.first().album)) }, icon = Icons.Rounded.Album)
                    }
                }
                SectionHeader("Songs")
            }
            trackItems(artist.tracks.sortedBy { it.title.lowercase() }, model, { onMenu(MenuTarget(it)) })
        }
    }
}

fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"
