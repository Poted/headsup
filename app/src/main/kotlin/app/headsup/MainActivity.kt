package app.headsup

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import app.headsup.ui.AppSettings
import app.headsup.ui.DarkPalette
import app.headsup.ui.HeadsupScreen
import app.headsup.ui.LightPalette
import app.headsup.ui.HeadsupTheme
import app.headsup.ui.SplashOverlay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.load(this)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        bars(AppSettings.dark(systemDark))
        super.onCreate(savedInstanceState)
        setContent {
            // The appearance chosen in Settings (System, Light or Dark), applied at once.
            val dark = AppSettings.dark(isSystemInDarkTheme())
            LaunchedEffect(dark) { bars(dark) }
            HeadsupTheme(dark = dark) {
                // The logo animation plays once per launch, not on rotation.
                var splash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                // The editor is built once the logo is complete, so building it does not eat the animation's frames.
                var editor by rememberSaveable { mutableStateOf(savedInstanceState != null) }
                Box {
                    if (editor) HeadsupScreen()
                    if (splash) SplashOverlay(onRevealed = { editor = true }) { splash = false; editor = true }
                }
            }
        }
    }

    /** System bars and the window behind the app for the light or dark appearance. */
    private fun bars(dark: Boolean) {
        val light = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(light, light) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        val ground = if (dark) DarkPalette.background else LightPalette.background
        window.setBackgroundDrawable(ColorDrawable(ground.toArgb()))
    }

    private companion object {
        // The scrims enableEdgeToEdge uses by default behind a three-button navigation bar.
        val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
