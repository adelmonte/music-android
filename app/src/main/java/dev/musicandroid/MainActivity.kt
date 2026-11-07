package dev.musicandroid

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.musicandroid.data.Store
import dev.musicandroid.ui.App
import dev.musicandroid.ui.AppModel
import dev.musicandroid.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        setContent {
            val settings by model.settings.collectAsState()
            MusicTheme(settings) { App(model) }
        }
    }

    override fun onStop() {
        super.onStop()
        Thread { Store.saveNow() }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A Spotify playlist link shared to the app gets linked. */
    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://open\\.spotify\\.com/\\S*playlist/\\S+").find(text)?.let { model.linkPlaylist(it.value) }
    }
}
