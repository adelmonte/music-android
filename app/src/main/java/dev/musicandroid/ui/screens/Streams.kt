package dev.musicandroid.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicandroid.data.Item
import dev.musicandroid.data.Kind
import dev.musicandroid.data.Section
import dev.musicandroid.data.Show
import dev.musicandroid.data.Track
import dev.musicandroid.net.Podcasts
import dev.musicandroid.ui.AppModel
import dev.musicandroid.ui.Card
import dev.musicandroid.ui.Cover
import dev.musicandroid.ui.Empty
import dev.musicandroid.ui.Gutter
import dev.musicandroid.ui.Hero
import dev.musicandroid.ui.ItemRow
import dev.musicandroid.ui.Loading
import dev.musicandroid.ui.ScreenTitle
import dev.musicandroid.ui.SearchPill
import dev.musicandroid.ui.SectionHeader
import dev.musicandroid.ui.Shelf
import dev.musicandroid.ui.trackItems

@Composable
private fun OnAir(model: AppModel) {
    val current by model.player.current.collectAsState()
    val live by model.liveTitle.collectAsState()
    val t = current?.takeIf { it.kind == Kind.Station } ?: return
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.art, Modifier.size(56.dp), RoundedCornerShape(14.dp), Icons.Rounded.Radio)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("On air · ${t.title}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(live ?: "Live", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun RadioScreen(model: AppModel, nav: (String) -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val home by model.radioHome.collectAsState()
    val favs by model.radioFavs.collectAsState()
    LaunchedEffect(Unit) { model.loadRadio() }
    LazyColumn(contentPadding = bottom) {
        item {
            ScreenTitle("Radio")
            SearchPill("Stations by name or genre") { nav("radiosearch?q=") }
            OnAir(model)
        }
        if (favs.isNotEmpty()) item { Shelf(Section("Favorites", favs.map(Item::Song)), model, nav, onMenu) }
        if (home.isEmpty()) item { Loading() }
        for (s in home) item(key = s.title) { Shelf(s, model, nav, onMenu) }
    }
}

@Composable
fun RadioSearch(model: AppModel, initial: String, back: () -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    SearchScreen(initial, "Stations by name or genre", back, { model.searchRadio(it) }, { sections ->
        for (s in sections) {
            item { SectionHeader(s.title) }
            trackItems(s.tracks, model, onMenu)
        }
    }, bottom)
}

@Composable
fun PodcastsScreen(model: AppModel, nav: (String) -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val pods by model.pods.collectAsState()
    val fresh by model.newEpisodes.collectAsState()
    val top by model.topPodcasts.collectAsState()
    LaunchedEffect(Unit) { model.loadPodcasts() }
    LazyColumn(contentPadding = bottom) {
        item {
            ScreenTitle("Podcasts") { IconButton(onClick = { model.loadPodcasts(force = true) }) { Icon(Icons.Rounded.Refresh, "Refresh") } }
            SearchPill("Podcasts by name or topic") { nav("podsearch?q=") }
        }
        if (pods.subscriptions.isNotEmpty()) item {
            SectionHeader("Subscriptions")
            ItemRow(pods.subscriptions.map(Item::Podcast), model, nav, onMenu)
        }
        val eps = fresh
        if (pods.subscriptions.isNotEmpty()) {
            item { SectionHeader("New episodes", onMore = { nav(model.openSection(Section("New episodes", eps.orEmpty().map(Item::Song)))) }.takeIf { (eps?.size ?: 0) > 8 }) }
            if (eps == null) item { Loading() } else trackItems(eps, model, onMenu, limit = 8)
        }
        val t = top
        if (t == null) item { Loading() } else if (t.items.isNotEmpty()) item { Shelf(t, model, nav, onMenu) }
        if (pods.subscriptions.isEmpty()) item {
            Empty(Icons.Rounded.Podcasts, "Follow your shows", "Open a podcast and subscribe. New episodes gather here, and each one resumes where you stopped.")
        }
    }
}

@Composable
fun PodcastSearch(model: AppModel, initial: String, nav: (String) -> Unit, back: () -> Unit, bottom: PaddingValues) {
    SearchScreen(initial, "Podcasts by name or topic", back, { model.searchPodcasts(it) }, { sections ->
        for (s in sections) {
            item { SectionHeader(s.title) }
            items(s.items.filterIsInstance<Item.Podcast>(), key = { it.show.feed }) { p -> ShowRow(p.show, model) { nav(model.openShow(p.show)) } }
        }
    }, bottom)
}

@Composable
private fun ShowRow(s: Show, model: AppModel, onClick: () -> Unit) {
    val pods by model.pods.collectAsState()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(s.art, Modifier.size(64.dp), RoundedCornerShape(14.dp), Icons.Rounded.Podcasts)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (pods.subscriptions.any { it.feed == s.feed }) Icon(Icons.Rounded.Check, "Subscribed", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ShowScreen(model: AppModel, id: String, back: () -> Unit, onMenu: (Track) -> Unit, bottom: PaddingValues) {
    val known = model.show(id) ?: return LaunchedEffect(Unit) { back() }
    var feed by remember(id) { mutableStateOf<Podcasts.Feed?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val pods by model.pods.collectAsState()
    LaunchedEffect(id) { feed = model.feed(known); failed = feed == null }
    val show = feed?.show ?: known
    val subscribed = pods.subscriptions.any { it.feed == show.feed }
    LazyColumn(contentPadding = bottom) {
        item { Hero(show.art, show.title, show.author, back, icon = Icons.Rounded.Podcasts) }
        item {
            Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subscribed) FilledTonalButton(onClick = { model.subscribe(show) }) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Subscribed") }
                else Button(onClick = { model.subscribe(show) }) { Text("Subscribe") }
                val eps = feed?.episodes.orEmpty()
                val next = eps.firstOrNull { it.key !in pods.played }
                if (next != null) FilledTonalButton(onClick = { model.player.play(listOf(next)) }) { Text("Play latest") }
            }
        }
        if (show.description.isNotEmpty()) item {
            Text(
                show.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { more = !more }.animateContentSize().padding(horizontal = Gutter, vertical = 4.dp),
            )
        }
        val f = feed
        when {
            f != null -> {
                item { SectionHeader("${f.episodes.size} episodes") }
                // Episodes play one at a time.
                items(f.episodes, key = { it.key }) { e ->
                    dev.musicandroid.ui.TrackRow(e, model, onClick = { model.player.play(listOf(e)) }, onMenu = { onMenu(e) })
                }
            }
            failed -> item { Empty(Icons.Rounded.Podcasts, "Couldn't read this feed", "Check your connection.") }
            else -> item { Loading() }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
