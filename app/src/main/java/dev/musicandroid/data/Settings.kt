package dev.musicandroid.data

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    /** 0 follow the system, 1 light, 2 dark. */
    val themeMode: Int = 0,
    /** Colors from the wallpaper (Android 12+) instead of the seed color. */
    val dynamicColor: Boolean = false,
    /** Phonograph's yellow. */
    val seed: Long = 0xFFFFC107,
    val paletteStyle: String = "Vibrant",
    val pureBlack: Boolean = false,
    /** Tint the player with the playing song's cover. */
    val artworkColors: Boolean = true,
    val showExplore: Boolean = true,
    val showRadio: Boolean = true,
    val showPodcasts: Boolean = true,
    val showSpotify: Boolean = true,
    val keepGoing: Boolean = false,
    val podcastSpeed: Float = 1f,
    /** Under the shared Music folder. */
    val downloadFolder: String = "music-android",
    val spotifyLinks: List<String> = emptyList(),
)

val PALETTE_STYLES = listOf("TonalSpot", "Vibrant", "Expressive", "Fidelity", "Content", "Rainbow", "FruitSalad", "Monochrome", "Neutral")

val SEEDS = listOf(
    0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
    0xFF3F51B5, 0xFF2196F3, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50, 0xFF8BC34A,
)
