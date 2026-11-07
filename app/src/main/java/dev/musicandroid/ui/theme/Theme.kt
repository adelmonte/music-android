package dev.musicandroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import dev.musicandroid.data.Settings

fun Settings.style(): PaletteStyle = runCatching { PaletteStyle.valueOf(paletteStyle) }.getOrDefault(PaletteStyle.Vibrant)

@Composable
fun Settings.isDark(): Boolean = when (themeMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

private fun ColorScheme.black(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B), surfaceContainer = Color(0xFF121212),
)

@Composable
fun schemeFor(settings: Settings, seed: Color?): ColorScheme {
    val dark = settings.isDark()
    val context = LocalContext.current
    val wallpaper = seed == null && settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val chosen = seed ?: Color(settings.seed)
    return remember(dark, wallpaper, chosen, settings.paletteStyle, settings.pureBlack) {
        if (wallpaper) {
            val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (dark && settings.pureBlack) s.black() else s
        } else dynamicColorScheme(seedColor = chosen, isDark = dark, isAmoled = settings.pureBlack, style = settings.style())
    }
}

/** The app's theme: Material 3 Expressive, colored from a seed (or the wallpaper, or a cover via [seed]). */
@Composable
fun MusicTheme(settings: Settings, seed: Color? = null, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = schemeFor(settings, seed),
        motionScheme = MotionScheme.expressive(),
        typography = MaterialTheme.typography,
        content = content,
    )
}
