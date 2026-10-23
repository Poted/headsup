package app.headsup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.headsup.ui.HeadsupScreen
import app.headsup.ui.HeadsupTheme
import app.headsup.ui.SplashOverlay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HeadsupTheme {
                // The logo animation plays once per launch, not on rotation.
                var splash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                Box {
                    HeadsupScreen()
                    if (splash) SplashOverlay { splash = false }
                }
            }
        }
    }
}
