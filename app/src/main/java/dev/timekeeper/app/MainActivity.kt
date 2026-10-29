package dev.timekeeper.app

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import dev.timekeeper.app.data.TimekeeperStore
import dev.timekeeper.app.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val store = remember { TimekeeperStore.get(this) }
            val snapshot by store.snapshots.collectAsState()
            TimekeeperTheme(snapshot.preferences.appearance) {
                val c = LocalOneUi.current
                val view = LocalView.current
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = c.background != androidx.compose.ui.graphics.Color.Black
                        isAppearanceLightNavigationBars = c.background != androidx.compose.ui.graphics.Color.Black
                    }
                }
                TimekeeperApp(store)
            }
        }
    }
}
