package app.headsup.ui

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.headsup.render.Units
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** The app's own settings (not a project's), kept on the phone. */
object AppSettings {
    private const val FILE = "settings"

    /** The theme, or null: Studio or Paper as the phone is set to dark or light. */
    var theme by mutableStateOf<Theme?>(null)
        private set

    /** Every animation of the app shortened to nothing (and the start-up one to its last frame). */
    var reduceMotion by mutableStateOf(false)
        private set

    /** A light click from the phone on each step of a ruler. */
    var haptics by mutableStateOf(true)
        private set

    /** Held sideways, the picture on the right and the tools on the left (for the left hand). */
    var pictureRight by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        theme = Theme.entries.firstOrNull { it.name == p.getString("theme", null) }
        reduceMotion = p.getBoolean("reduceMotion", false)
        haptics = p.getBoolean("haptics", true)
        pictureRight = p.getBoolean("pictureRight", false)
        Units.imperial = p.getBoolean("imperial", false)
    }

    private fun save(context: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply(edit).apply()

    fun setTheme(context: Context, value: Theme?) {
        theme = value
        save(context) { putString("theme", value?.name) }
    }

    fun setReduceMotion(context: Context, value: Boolean) {
        reduceMotion = value
        save(context) { putBoolean("reduceMotion", value) }
    }

    fun setHaptics(context: Context, value: Boolean) {
        haptics = value
        save(context) { putBoolean("haptics", value) }
    }

    fun setPictureRight(context: Context, value: Boolean) {
        pictureRight = value
        save(context) { putBoolean("pictureRight", value) }
    }

    fun setImperial(context: Context, value: Boolean) {
        Units.imperial = value
        save(context) { putBoolean("imperial", value) }
    }

    /** The theme in use, given whether the phone is set to dark. */
    fun current(systemDark: Boolean): Theme = theme ?: if (systemDark) Theme.STUDIO else Theme.PAPER
}

/**
 * How long the app's animations take: the phone's animation scale, or nothing with Reduce
 * animations. Given to the whole app's composition, so every animation follows it, the ones still
 * to come too. (A few that measure time, as holding Undo, keep their own pace.)
 */
class AppMotion(context: Context) : androidx.compose.ui.MotionDurationScale {
    private val resolver = context.applicationContext.contentResolver
    private var system = read()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { system = read() }
    }

    init {
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
    }

    private fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    override val scaleFactor: Float get() = if (AppSettings.reduceMotion) 0f else system

    fun release() = resolver.unregisterContentObserver(observer)
}

/** Reports of how the app stopped unexpectedly, kept until deleted (Settings › Reports). */
object Reports {
    private fun dir(context: Context) = File(context.filesDir, "reports").apply { mkdirs() }

    fun add(context: Context, text: String) {
        val d = dir(context)
        File(d, "report-${System.currentTimeMillis()}.txt").writeText(text)
        // The last ten are enough.
        d.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
    }

    fun all(context: Context): List<File> = dir(context).listFiles()?.sortedByDescending { it.name }.orEmpty()
}

private fun bytesText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
    else -> "$bytes B"
}

/** The app's settings, as a sheet over the projects (they are for the whole app, not a project). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: HeadsupViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HeadsupColors.Background,
        contentColor = HeadsupColors.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            ThemeSection(context)
            ToolSection(
                "Motion and touch",
                info = "Reduce animations: panels, sheets and the start-up animation appear at once. Ruler clicks: a light tap from the phone on every step of a ruler, a firmer one at zero (if the phone's touch feedback is on).",
            ) {
                SettingSwitch("Reduce animations", "Everything appears at once.", AppSettings.reduceMotion) { AppSettings.setReduceMotion(context, it) }
                SettingSwitch("Ruler clicks", "A light tap on every step.", AppSettings.haptics) { AppSettings.setHaptics(context, it) }
            }
            ToolSection(
                "Layout",
                info = "Held sideways the picture is on the left and the tools on the right. Left-handed: the tools on the left, under the left thumb, and the picture on the right. A swipe towards the tools' side still goes full screen.",
            ) {
                ChoiceGrid(listOf("Tools on the right", "Tools on the left"), if (AppSettings.pictureRight) 1 else 0, { AppSettings.setPictureRight(context, it == 1) })
            }
            ToolSection(
                "Units",
                info = "For the activity data on the dashboard, the data texts and the highlights. Heart rate, cadence and power stay as they are.",
            ) {
                ChoiceGrid(listOf("Metric", "Imperial"), if (Units.imperial) 1 else 0, { AppSettings.setImperial(context, it == 1) })
                Text(
                    if (Units.imperial) "mph · mi · ft · °F · min/mi" else "km/h · km · m · °C · min/km",
                    style = MaterialTheme.typography.labelSmall,
                    color = HeadsupColors.TextMuted,
                )
            }
            StorageSection(vm)
            ReportsSection(context)
            AboutSection(context)
        }
    }
}

@Composable
private fun ThemeSection(context: Context) {
    val options = listOf<Theme?>(null) + Theme.entries
    val chosen = AppSettings.theme
    ToolSection(
        "Theme",
        info = "Each theme is made for a way of working. System: Studio or Paper as the phone is set to dark or light. " +
            Theme.entries.joinToString(" ") { "${it.label}: ${it.about}" },
    ) {
        ChoiceGrid(
            options.map { it?.label ?: "System" },
            options.indexOf(chosen),
            { AppSettings.setTheme(context, options[it]) },
            minCell = 96.dp,
            glyph = { i, _ -> ThemeSwatch(options[i]) },
        )
        Text(
            chosen?.about ?: "Studio or Paper, as the phone is set to dark or light.",
            style = MaterialTheme.typography.labelSmall,
            color = HeadsupColors.TextMuted,
        )
    }
}

/** A theme in small: its ground with its accent dot (System: Studio and Paper halved). */
@Composable
private fun ThemeSwatch(theme: Theme?) {
    val edge = HeadsupColors.ControlEdge
    Canvas(Modifier.size(16.dp)) {
        val r = size.minDimension / 2f
        if (theme == null) {
            drawArc(DarkPalette.background, 90f, 180f, true)
            drawArc(LightPalette.background, -90f, 180f, true)
        } else {
            drawCircle(theme.palette.background, r)
        }
        drawCircle(edge, r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        drawCircle(theme?.palette?.accentMark ?: DarkPalette.accent, r * 0.36f, Offset(size.width / 2f, size.height / 2f))
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted)
        }
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HeadsupColors.OnAccent,
                checkedTrackColor = HeadsupColors.Accent,
                uncheckedThumbColor = HeadsupColors.ControlEdge,
                uncheckedTrackColor = HeadsupColors.SurfaceHigh,
                uncheckedBorderColor = HeadsupColors.ControlEdge,
            ),
        )
    }
}

/** What the app keeps on the phone, with a bar per kind, and what can be cleared without losing work. */
@Composable
private fun StorageSection(vm: HeadsupViewModel) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf<HeadsupViewModel.StorageUsage?>(null) }
    LaunchedEffect(refresh) { usage = vm.storageUsage() }
    ToolSection(
        "Storage",
        info = "What SweetSpot keeps on this phone. Imported files are the projects' own copies (the gallery is never touched). " +
            "Clearing frees space without losing any edit: preview copies and AI models are made again when needed, " +
            "unused files belong to no project any more, temporary files are leftovers. Masks and Clean up are part of the edits and stay.",
    ) {
        val u = usage
        if (u == null) {
            Text("Measuring…", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted)
            return@ToolSection
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bytesText(u.total), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(8.dp))
            Text("in all", Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
        }
        val rows = listOf(
            Triple("Imported files", u.media, null),
            Triple("  of them no project uses", u.unusedMedia, suspend { vm.clearUnusedMedia() }.takeIf { u.unusedMedia > 0 }),
            Triple("Projects and LUTs", u.projects, null),
            Triple("Masks and Clean up", u.masks + u.reversed, null),
            Triple("Preview copies", u.previews, suspend { vm.clearPreviews(); Unit }.takeIf { u.previews > 0 }),
            Triple("AI models (unpacked)", u.models, suspend { vm.clearModelCopies() }.takeIf { u.models > 0 }),
            Triple("Temporary files", u.temporary, suspend { vm.clearTemporary() }.takeIf { u.temporary > 0 }),
        )
        val largest = rows.maxOf { it.second }.coerceAtLeast(1L)
        for ((label, bytes, clear) in rows) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(bytesText(bytes), style = MaterialTheme.typography.labelMedium.copy(fontFamily = GeistMonoFamily), color = HeadsupColors.TextMuted)
                    if (clear != null) SmallAction("Clear", { scope.launch { clear(); refresh++ } }, Modifier.padding(start = 6.dp))
                }
                StorageBar(bytes.toFloat() / largest)
            }
        }
    }
}

@Composable
private fun StorageBar(fraction: Float) {
    val track = HeadsupColors.SurfaceHigh
    val fill = HeadsupColors.Accent
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val h = size.height
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f))
        if (fraction > 0f) {
            drawRoundRect(
                fill,
                size = androidx.compose.ui.geometry.Size((size.width * fraction).coerceAtLeast(h), h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
            )
        }
    }
}

/** How the app stopped unexpectedly, kept here (not shown over the editor) to copy or send. */
@Composable
private fun ReportsSection(context: Context) {
    var refresh by remember { mutableIntStateOf(0) }
    val reports = remember(refresh) { Reports.all(context) }
    var open by remember { mutableStateOf<File?>(null) }
    ToolSection(
        "Reports",
        info = "If the app stops unexpectedly, what happened is kept here, on the phone only. Send a report to help fix it; nothing is sent on its own.",
    ) {
        if (reports.isEmpty()) {
            Text("No reports.", style = MaterialTheme.typography.bodySmall, color = HeadsupColors.TextMuted)
        }
        for (f in reports) {
            val time = f.name.removePrefix("report-").removeSuffix(".txt").toLongOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    time?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: f.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                SmallAction("Open", { open = f })
            }
        }
    }
    open?.let { f ->
        val text = remember(f) { runCatching { f.readText() }.getOrDefault("") }
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("Report") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMonoFamily)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "SweetSpot report").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Send report"))
                }) { Text("Send") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                    TextButton(onClick = { f.delete(); open = null; refresh++ }) { Text("Delete") }
                }
            },
        )
    }
}

/** The mark, the version and the licences of what the app carries. */
@Composable
private fun AboutSection(context: Context) {
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("")
    }
    val licences = remember { runCatching { context.assets.list("licenses")?.sorted().orEmpty() }.getOrDefault(emptyList()) }
    var shown by remember { mutableStateOf<String?>(null) }
    ToolSection("About") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SweetSpotMark(size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("SweetSpot", style = MaterialTheme.typography.titleMedium)
                Text("Version $version", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted)
            }
        }
        Text("An edit has no fixed end. You decide where the sweet spot is.", style = MaterialTheme.typography.bodySmall, color = HeadsupColors.TextMuted)
        Text("Licences", style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted)
        for (name in licences) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(licenceTitle(name), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                SmallAction("Read", { shown = name })
            }
        }
    }
    shown?.let { name ->
        val text = remember(name) { runCatching { context.assets.open("licenses/$name").bufferedReader().readText() }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(licenceTitle(name)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Close") } },
        )
    }
}

/** "geist_OFL.txt" → "Geist · OFL". */
private fun licenceTitle(file: String): String {
    val base = file.substringBeforeLast('.')
    val name = base.substringBefore('_')
    val kind = base.substringAfter('_', "")
    val pretty = when (name) {
        "barlowcondensed" -> "Barlow Condensed"
        "mobilesam" -> "MobileSAM"
        "models" -> "AI models"
        else -> name.replaceFirstChar { it.uppercase() }
    }
    return if (kind.isEmpty()) pretty else "$pretty · ${kind.replace('_', ' ')}"
}
