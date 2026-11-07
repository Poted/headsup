package app.headsup.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Light or dark: as the system is set, or always one of them. */
enum class Appearance(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The app's own settings (not a project's), kept on the phone. */
object AppSettings {
    private const val FILE = "settings"
    private const val APPEARANCE = "appearance"

    var appearance by mutableStateOf(Appearance.SYSTEM)
        private set

    fun load(context: Context) {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(APPEARANCE, null)
        appearance = Appearance.entries.firstOrNull { it.name == saved } ?: Appearance.SYSTEM
    }

    fun setAppearance(context: Context, value: Appearance) {
        appearance = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(APPEARANCE, value.name).apply()
    }

    /** Whether the app is dark, given whether the system is. */
    fun dark(systemDark: Boolean) = when (appearance) {
        Appearance.SYSTEM -> systemDark
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
}

/** The app's settings, as a sheet over the projects (they are for the whole app, not a project). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HeadsupColors.Background,
        contentColor = HeadsupColors.Ink,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            ToolSection(
                "Appearance",
                info = "System follows the phone's light or dark setting. The picture is always shown on black, whichever you choose.",
            ) {
                ChoiceGrid(
                    Appearance.entries.map { it.label },
                    AppSettings.appearance.ordinal,
                    { AppSettings.setAppearance(context, Appearance.entries[it]) },
                )
            }
        }
    }
}
