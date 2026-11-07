package dev.musicandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.musicandroid.data.Item
import dev.musicandroid.data.Section
import dev.musicandroid.data.Track
import dev.musicandroid.net.Deezer
import dev.musicandroid.ui.AddToPlaylist
import dev.musicandroid.ui.AppModel
import dev.musicandroid.ui.ArtistCard
import dev.musicandroid.ui.Card
import dev.musicandroid.ui.Empty
import dev.musicandroid.ui.Gutter
import dev.musicandroid.ui.Hero
import dev.musicandroid.ui.ItemRow
import dev.musicandroid.ui.Loading
import dev.musicandroid.ui.PlayButtons
import dev.musicandroid.ui.ScreenTitle
import dev.musicandroid.ui.SearchPill
import dev.musicandroid.ui.SectionHeader
import dev.musicandroid.ui.Shelf
import dev.musicandroid.ui.trackItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExploreScreen(model: AppModel, nav: (String) -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val home by model.home.collectAsState()
    val loading by model.homeLoading.collectAsState()
    val radios by model.radios.collectAsState()
    LaunchedEffect(Unit) { model.loadHome() }
    LazyColumn(contentPadding = bottom) {
        item {
            ScreenTitle("Explore") { IconButton(onClick = { model.loadHome(force = true) }) { Icon(Icons.Rounded.Refresh, "Refresh") } }
            SearchPill("Songs, artists, albums") { nav("search?q=") }
        }
        val albums = home.firstOrNull { it.title == "Top albums" }
        if (albums != null) item { HeroCarousel(albums, nav) }
        for (s in home) {
            when {
                s.title == "Top albums" -> {}
                s.title == "Top songs" -> {
                    item { SectionHeader(s.title) { nav(model.openSection(s)) } }
                    trackItems(s.tracks, model, onMenu, limit = 5)
                }
                else -> item(key = s.title) { Shelf(s, model, nav, onMenu) }
            }
            if (s.title == "New releases for you" || (s.title == "Popular artists" && radios.isNotEmpty() && home.none { it.title == "New releases for you" })) {
                if (radios.isNotEmpty()) item {
                    SectionHeader("Your song radios")
                    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        items(radios, key = { it.seed }) { r ->
                            Card(r.tracks.firstOrNull()?.art.orEmpty(), r.title.removePrefix("Radio: "), "${r.tracks.size} songs", onClick = { model.openRadio(r) }, icon = Icons.Rounded.Sensors)
                        }
                    }
                }
            }
        }
        if (home.isEmpty() && loading) item { Loading() }
        if (home.isEmpty() && !loading) item {
            Empty(Icons.Rounded.Explore, "Nothing to explore yet", "Check your connection, then pull refresh.")
        }
    }
}

@Composable
private fun HeroCarousel(s: Section, nav: (String) -> Unit) {
    val albums = s.items.filterIsInstance<Item.Album>().take(12)
    if (albums.isEmpty()) return
    SectionHeader("Top albums")
    HorizontalMultiBrowseCarousel(
        state = rememberCarouselState { albums.size },
        preferredItemWidth = 260.dp,
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = Gutter),
        modifier = Modifier.fillMaxWidth().height(260.dp),
    ) { i ->
        val a = albums[i]
        Box(Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { nav("album/${a.id}") }) {
            AsyncImage(a.cover, a.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(a.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.artist, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A search field and its results; used by Explore, Radio and Podcasts. */
@Composable
fun SearchScreen(
    initial: String,
    hint: String,
    onBack: () -> Unit,
    search: suspend (String) -> List<Section>,
    content: androidx.compose.foundation.lazy.LazyListScope.(List<Section>) -> Unit,
    bottom: PaddingValues,
) {
    var q by rememberSaveable { mutableStateOf(initial) }
    var results by remember { mutableStateOf<List<Section>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun go() {
        if (q.isBlank()) return
        busy = true
        scope.launch { results = search(q.trim()); busy = false }
    }
    LaunchedEffect(Unit) { if (initial.isNotBlank()) go() else focus.requestFocus() }
    LazyColumn(contentPadding = bottom) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 4.dp, end = Gutter, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                TextField(
                    q, { q = it },
                    placeholder = { Text(hint) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = ""; results = null; focus.requestFocus() }) { Icon(Icons.Rounded.Close, "Clear") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        }
        if (busy) item { Loading() }
        val r = results
        if (!busy && r != null) {
            if (r.isEmpty()) item { Empty(Icons.Rounded.Search, "No results", "Try different words.") } else content(r)
        }
    }
}

@Composable
fun ExploreSearch(model: AppModel, initial: String, nav: (String) -> Unit, back: () -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    SearchScreen(initial, "Songs, artists, albums", back, { model.searchExplore(it) }, { sections ->
        for (s in sections) {
            if (s.title == "Songs" || s.title == "YouTube Music") {
                item { SectionHeader(s.title, onMore = { nav(model.openSection(s)) }.takeIf { s.items.size > 6 }) }
                trackItems(s.tracks, model, onMenu, limit = 6)
            } else item { SectionHeader(s.title); ItemRow(s.items, model, nav, onMenu) }
        }
    }, bottom)
}

@Composable
fun SectionScreen(model: AppModel, id: String, nav: (String) -> Unit, back: () -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val all by model.sections.collectAsState()
    val s = all[id]
    var save by remember { mutableStateOf(false) }
    if (save && s != null) AddToPlaylist(s.tracks, model) { save = false }
    LazyColumn(contentPadding = bottom) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(s?.title ?: "Making a radio…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (s == null) {
            item { Loading(); Text("Finding songs with a similar sound and mood. This takes about 20 seconds.", Modifier.fillMaxWidth().padding(horizontal = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            return@LazyColumn
        }
        val tracks = s.tracks
        if (tracks.isNotEmpty()) {
            item { PlayButtons(tracks, model) { IconButton(onClick = { save = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist") } } }
            trackItems(tracks, model, onMenu)
        }
        val others = s.items.filter { it !is Item.Song }
        if (others.isNotEmpty()) item {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxWidth().height(((others.size + 1) / 2 * 230).dp),
                contentPadding = PaddingValues(Gutter),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                items(others.size) { i -> dev.musicandroid.ui.ItemCard(others[i], model, nav, onMenu) }
            }
        }
    }
}

@Composable
private fun <T> loadPage(key: Any, f: () -> T): Pair<T?, Boolean> {
    var v by remember(key) { mutableStateOf<T?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val r = withContext(Dispatchers.IO) { runCatching(f).getOrNull() }
        if (r == null) failed = true else v = r
    }
    return v to failed
}

@Composable
fun ArtistScreen(model: AppModel, id: Long, nav: (String) -> Unit, back: () -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val (page, failed) = loadPage(id) { Deezer.artist(id) }
    LazyColumn(contentPadding = bottom) {
        if (page == null) {
            item { Box(Modifier.statusBarsPadding()) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } } }
            item { if (failed) Empty(Icons.Rounded.Explore, "Couldn't load this artist", "Check your connection.") else Loading() }
            return@LazyColumn
        }
        val fans = if (page.info.fans > 0) "%,d fans".format(page.info.fans) else ""
        item { Hero(page.info.picture, page.info.name, fans, back, round = true) }
        item {
            PlayButtons(page.top, model) {
                androidx.compose.material3.FilledTonalIconButton(onClick = { page.top.firstOrNull()?.let(model::songRadio) }) { Icon(Icons.Rounded.Sensors, "Artist radio") }
            }
        }
        item { SectionHeader("Popular", onMore = { nav(model.openSection(Section("${page.info.name}: popular", page.top.map(Item::Song)))) }.takeIf { page.top.size > 5 }) }
        trackItems(page.top, model, onMenu, limit = 5)
        val albums = page.albums.filter { !it.sub.startsWith("Single") }
        val singles = page.albums.filter { it.sub.startsWith("Single") }
        if (albums.isNotEmpty()) item { SectionHeader("Albums"); ItemRow(albums, model, nav, onMenu) }
        if (singles.isNotEmpty()) item { SectionHeader("Singles and EPs"); ItemRow(singles, model, nav, onMenu) }
        if (page.related.isNotEmpty()) item {
            SectionHeader("Fans also like")
            LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                items(page.related) { a -> ArtistCard(a.picture, a.name, { nav("artist/${a.id}") }) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AlbumScreen(model: AppModel, id: Long, nav: (String) -> Unit, back: () -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val (page, failed) = loadPage(id) { Deezer.album(id) }
    var save by remember { mutableStateOf(false) }
    if (save && page != null) AddToPlaylist(page.tracks, model) { save = false }
    LazyColumn(contentPadding = bottom) {
        if (page == null) {
            item { Box(Modifier.statusBarsPadding()) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } } }
            item { if (failed) Empty(Icons.Rounded.Explore, "Couldn't load this album", "Check your connection.") else Loading() }
            return@LazyColumn
        }
        item { Hero(page.cover, page.title, listOf(page.artist, page.year).filter { it.isNotEmpty() }.joinToString(" · "), back) }
        item {
            PlayButtons(page.tracks, model) {
                IconButton(onClick = { save = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist") }
                if (page.artistId > 0) androidx.compose.material3.FilledTonalIconButton(onClick = { nav("artist/${page.artistId}") }) { Icon(androidx.compose.material.icons.Icons.Rounded.AccountCircle, "Artist") }
            }
        }
        trackItems(page.tracks, model, onMenu, numbered = true, showArt = false)
    }
}

@Composable
fun DeezerPlaylistScreen(model: AppModel, id: Long, back: () -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val (page, failed) = loadPage(id) { Deezer.playlist(id) }
    var save by remember { mutableStateOf(false) }
    if (save && page != null) dev.musicandroid.ui.NameDialog("Save as playlist", page.title, { save = false }) { model.newPlaylist(it, page.tracks); save = false }
    LazyColumn(contentPadding = bottom) {
        if (page == null) {
            item { Box(Modifier.statusBarsPadding()) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } } }
            item { if (failed) Empty(Icons.Rounded.Explore, "Couldn't load this playlist", "Check your connection.") else Loading() }
            return@LazyColumn
        }
        item { Hero(page.cover, page.title, listOf(page.by, "${page.tracks.size} songs").filter { it.isNotEmpty() }.joinToString(" · "), back) }
        item { PlayButtons(page.tracks, model) { IconButton(onClick = { save = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as playlist") } } }
        trackItems(page.tracks, model, onMenu)
    }
}
