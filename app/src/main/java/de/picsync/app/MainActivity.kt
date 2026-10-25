package de.picsync.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.picsync.app.ui.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PicSyncApp
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        // The keep-alive notification shows the pairing code while the user is in WhatsApp.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // Keep the screen on while a sync runs in the foreground.
        lifecycleScope.launch {
            app.sync.state.collect { s ->
                if (s.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        setContent {
            MainScreen(
                session = app.session,
                sync = app.sync,
                contacts = app.contacts,
                initialOverwrite = prefs.getBoolean(KEY_OVERWRITE, false),
                onOverwriteChanged = { prefs.edit().putBoolean(KEY_OVERWRITE, it).apply() },
                hasContactPermission = ::hasContactPermission,
            )
        }
    }

    private fun hasContactPermission(): Boolean = CONTACT_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val KEY_OVERWRITE = "overwrite_foreign"
        val CONTACT_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }
}
