package app.headsup

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.headsup.ui.AppMotion
import app.headsup.ui.AppSettings
import app.headsup.ui.HeadsupScreen
import app.headsup.ui.HeadsupTheme
import app.headsup.ui.Palette
import app.headsup.ui.SplashOverlay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var motion: AppMotion
    private lateinit var recomposer: Recomposer
    private lateinit var sun: app.headsup.ui.SunWatch

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.load(this)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        bars(AppSettings.current(systemDark).palette)
        super.onCreate(savedInstanceState)
        // The app's own recomposer, so its animations take the app's pace: the phone's animation
        // scale, or none with Settings › Reduce animations. Like the window's own, it rests while
        // the app is not on screen.
        motion = AppMotion(this)
        sun = app.headsup.ui.SunWatch(this)
        val context = AndroidUiDispatcher.Main + motion
        recomposer = Recomposer(context)
        lifecycleScope.launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                recomposer.resumeCompositionFrameClock()
                sun.start()
            }
            override fun onStop(owner: LifecycleOwner) {
                recomposer.pauseCompositionFrameClock()
                sun.stop()
            }
        })
        setContent(parent = recomposer) {
            // The theme chosen in Settings (or Studio / Paper as the phone is set), applied at once.
            val palette = AppSettings.current(isSystemInDarkTheme()).palette
            // Turned on in Settings while the app runs: the sensor starts at once.
            LaunchedEffect(AppSettings.fieldInSun) { if (AppSettings.fieldInSun) sun.start() else sun.stop() }
            LaunchedEffect(palette) { bars(palette) }
            HeadsupTheme(palette) {
                // The logo animation plays once per launch, not on rotation.
                var splash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                // The editor is built once the logo is complete, so building it does not eat the animation's frames.
                var editor by rememberSaveable { mutableStateOf(savedInstanceState != null) }
                // The light planner covers the editor, which stays as it was beneath it.
                var planner by rememberSaveable { mutableStateOf(false) }
                Box {
                    if (editor) HeadsupScreen(onPlanner = { planner = true })
                    if (planner) app.headsup.ui.PlannerScreen(onClose = { planner = false })
                    if (splash) SplashOverlay(onRevealed = { editor = true }) { splash = false; editor = true }
                }
            }
        }
    }

    override fun onDestroy() {
        recomposer.cancel()
        motion.release()
        super.onDestroy()
    }

    /** System bars and the window behind the app for the theme. */
    private fun bars(palette: Palette) {
        val dark = palette.isDark
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(clear, clear) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        window.setBackgroundDrawable(ColorDrawable(palette.background.toArgb()))
    }

    private companion object {
        // The scrims enableEdgeToEdge uses by default behind a three-button navigation bar.
        val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
