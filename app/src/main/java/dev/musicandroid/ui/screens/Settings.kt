package dev.musicandroid.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.musicandroid.BuildConfig
import dev.musicandroid.data.PALETTE_STYLES
import dev.musicandroid.data.Playlist
import dev.musicandroid.data.SEEDS
import dev.musicandroid.data.Settings
import dev.musicandroid.data.Store
import dev.musicandroid.ui.AppModel
import dev.musicandroid.ui.Gutter
import dev.musicandroid.ui.NameDialog
import dev.musicandroid.ui.ScreenTitle

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = Gutter + 4.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Row(title: String, sub: String? = null, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp) else Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, sub: String?, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) =
    Row(title, sub, trailing = { Switch(on, set, enabled = enabled) }, onClick = { if (enabled) set(!on) })

private fun fmtSpeed(s: Float) = if (s % 1f == 0f) "${s.toInt()}×" else "%.2f×".format(s).replace("0×", "×")

@Composable
fun SettingsScreen(model: AppModel, bottom: PaddingValues) {
    val s by model.settings.collectAsState()
    val downloads by Store.downloads.collectAsState()
    val lists by model.playlists.collectAsState()
    val context = LocalContext.current
    var folder by remember { mutableStateOf(false) }
    var hex by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(false) }
    val set: ((Settings) -> Settings) -> Unit = { model.update(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) model.importSpotify(it) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { u -> model.importSpotify(listOf(u), tree = true) } }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    if (folder) NameDialog("Download folder", s.downloadFolder, { folder = false }, label = "Inside Music/") { v -> set { it.copy(downloadFolder = v.trim('/')) }; folder = false }
    if (link) NameDialog("Link a Spotify playlist", "", { link = false }, label = "Share link", confirm = "Link") { model.linkPlaylist(it); link = false }
    if (hex) NameDialog("Custom color", "#%06X".format(s.seed.toInt() and 0xFFFFFF), { hex = false }, label = "Hex, like #FFC107") { v ->
        runCatching { android.graphics.Color.parseColor(if (v.startsWith("#")) v else "#$v") }.getOrNull()?.let { c -> set { it.copy(seed = (c.toLong() and 0xFFFFFFFFL), dynamicColor = false) } }
        hex = false
    }
    LazyColumn(contentPadding = bottom) {
        item { ScreenTitle("Settings") }

        item { Group("Appearance") }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                listOf("System", "Light", "Dark").forEachIndexed { i, label ->
                    SegmentedButton(selected = s.themeMode == i, onClick = { set { it.copy(themeMode = i) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) item { Toggle("Wallpaper colors", "Material You: colors from your wallpaper instead of the accent below", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } } }
        item {
            Text("Accent", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Gutter + 4.dp, top = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = Gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(SEEDS) { c ->
                    val chosen = !s.dynamicColor && s.seed == c
                    Box(
                        Modifier.size(48.dp).clip(if (chosen) MaterialShapes.Cookie9Sided.toShape() else CircleShape).background(Color(c))
                            .clickable { set { it.copy(seed = c, dynamicColor = false) } },
                        contentAlignment = Alignment.Center,
                    ) { if (chosen) Icon(Icons.Rounded.Check, null, tint = Color.Black.copy(alpha = 0.7f)) }
                }
                item {
                    Box(Modifier.size(48.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { hex = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "Custom color")
                    }
                }
            }
        }
        item {
            Text("Palette", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Gutter + 4.dp, top = 4.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PALETTE_STYLES) { p ->
                    FilterChip(selected = s.paletteStyle == p, onClick = { set { it.copy(paletteStyle = p) } }, label = { Text(p.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")) }, enabled = !s.dynamicColor)
                }
            }
        }
        item { Toggle("Pure black", "True black backgrounds in dark theme (OLED)", s.pureBlack) { v -> set { it.copy(pureBlack = v) } } }
        item { Toggle("Color the player from the cover", "The now-playing screen takes its colors from the song's artwork", s.artworkColors) { v -> set { it.copy(artworkColors = v) } } }

        item { Group("Tabs") }
        item { Toggle("Explore", "Search, charts, recommendations and song radios", s.showExplore) { v -> set { it.copy(showExplore = v) } } }
        item { Toggle("Radio", "About 50,000 internet radio stations", s.showRadio) { v -> set { it.copy(showRadio = v) } } }
        item { Toggle("Podcasts", "Subscriptions, new episodes, resume where you left off", s.showPodcasts) { v -> set { it.copy(showPodcasts = v) } } }
        item { Toggle("Spotify library", "Imported and linked Spotify playlists in Library", s.showSpotify) { v -> set { it.copy(showSpotify = v) } } }

        item { Group("Playback") }
        item { Toggle("Keep going", "When the queue runs out, add similar songs (off with shuffle or repeat)", s.keepGoing) { v -> set { it.copy(keepGoing = v) } } }
        item {
            Row("Podcast speed", fmtSpeed(s.podcastSpeed) + " · pitch is kept")
            Slider(
                value = s.podcastSpeed, onValueChange = { v -> set { it.copy(podcastSpeed = (Math.round(v * 20) / 20f)) } },
                valueRange = 0.5f..3f, steps = 9, modifier = Modifier.padding(horizontal = Gutter + 8.dp),
            )
        }
        if (Build.VERSION.SDK_INT >= 33) item { Row("Playback notification", "Allow the notification with playback controls", onClick = { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }) }

        if (s.showSpotify) {
            item { Group("Spotify") }
            item { Row("Import an export", "The zip (or CSV files) from exportify.app → Export All. New songs are added; nothing is removed.", onClick = { files.launch(arrayOf("*/*")) }) }
            item { Row("Import a folder of CSV files", null, onClick = { tree.launch(null) }) }
            item { Row("Open exportify.app", "Log in with Spotify there and export your library", onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://exportify.app/"))) }) }
            val linked = lists.filter { it.source == Playlist.LINKED }
            items(linked, key = { it.id }) { p ->
                Row(p.name, "Linked · ${p.tracks.size} songs", trailing = { IconButton(onClick = { model.unlink(p) }) { Icon(Icons.Rounded.Close, "Unlink") } })
            }
            item { Row("Link a playlist", "Keeps a Spotify playlist live (Release Radar, Discover Weekly); refreshed every launch, first 100 songs", trailing = { Icon(Icons.Rounded.Link, null) }, onClick = { link = true }) }
        }

        item { Group("Downloads") }
        item {
            val mb = downloads.values.sumOf { it.bytes } / (1 shl 20)
            Row("Saved songs", "${downloads.size} songs · $mb MB in Music/${s.downloadFolder}")
        }
        item { Row("Folder", "Music/${s.downloadFolder}/Artist/[Year] Album/NN Title.m4a", onClick = { folder = true }) }

        item { Group("About") }
        item { Row("music-android ${BuildConfig.VERSION_NAME}", "A port of music-tui. Free software under the GPL-3.0.") }
        item { Row("Icon", "From Phonograph by Karim Abou Zeid (GPL-3.0)") }
        item { Row("Made with", "NewPipeExtractor (YouTube), Deezer's API, Radio Browser, Apple Podcasts, Media3, Material 3") }
        item { HorizontalDivider(Modifier.padding(vertical = 16.dp)); Spacer(Modifier.height(16.dp)) }
    }
}
