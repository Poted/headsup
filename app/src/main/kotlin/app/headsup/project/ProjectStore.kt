package app.headsup.project

import app.headsup.core.edit.TextAlign
import app.headsup.core.edit.TextAnim
import app.headsup.core.edit.TextFont
import app.headsup.core.edit.TextItem
import app.headsup.core.raw.RawDevelop
import app.headsup.render.RouteMap

import android.content.Context
import android.net.Uri
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.ColourMask
import app.headsup.core.color.MaskKind
import app.headsup.core.color.MaskKey
import app.headsup.core.frame.Reframe
import app.headsup.render.EffectSettings
import app.headsup.export.AudioClip
import app.headsup.export.AudioFile
import app.headsup.render.GraphMetric
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/** One clip on the timeline as saved: the file it comes from and everything set for it. */
data class SavedClip(
    val uri: Uri,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val manualOffsetMs: Long,
    val colorGrade: Boolean,
    val lutName: String?,
    val manualStart: LocalDateTime?,
    val grade: ColorGrade,
    val effects: EffectSettings,
    val masks: List<ColourMask>,
    val groupId: Long?,
    val transitionMs: Long,
    val reframe: Reframe = Reframe(),
    val speed: Float = 1f,
    val muteChangedSpeed: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val soundLevel: Float = 1f,
    val rawDevelop: RawDevelop = RawDevelop(),
    val picked: Boolean = false,
)

/** A film made on a timeline, or an album of photos edited one by one. */
enum class ProjectKind { VIDEO, PHOTOS }

data class SavedGroup(val id: Long, val name: String, val grade: ColorGrade, val lutName: String?)

/**
 * A saved project. Video and FIT files are only referenced (by URI), not copied, so a project
 * stays small; files deleted from the phone are reported as missing when it is opened.
 */
data class ProjectData(
    val name: String,
    val activities: List<Uri>,
    val clips: List<SavedClip>,
    val groups: List<SavedGroup>,
    val globalGrade: ColorGrade,
    /** Timeline frame rate, or null for the first clip's. */
    val timelineFps: Float? = null,
    /** Timeline frame shape (width / height), or null for the first clip's. */
    val frameAspect: Float? = null,
    /** The project's sound files and the clips placed from them on the audio tracks. */
    val audioFiles: List<AudioFile> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    /** Level of the video clips' own sound. */
    val clipSound: Float = 1f,
    val texts: List<TextItem> = emptyList(),
    /** Null in projects saved before albums existed. */
    val kind: ProjectKind? = null,
    val routeMap: RouteMap = RouteMap(),
) {
    /** Every media file the project uses. */
    val mediaUris: List<Uri> get() = activities + clips.map { it.uri } + audioFiles.map { it.uri } + audioClips.map { it.uri }
}

/** A saved project as listed; [bytes] is the space its copied media take (shared copies counted in each). */
data class ProjectInfo(val name: String, val modifiedMs: Long, val bytes: Long = 0L, val kind: ProjectKind = ProjectKind.VIDEO)

/** Projects as JSON files in the app's private storage; the last one used reopens on start. */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("projects", Context.MODE_PRIVATE)

    var lastProject: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    fun list(): List<ProjectInfo> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .map { ProjectInfo(it.nameWithoutExtension, it.lastModified()) }
        .sortedByDescending { it.modifiedMs }

    fun exists(name: String) = fileFor(name).exists()

    /** Every saved project, e.g. to see which media copies are still used. */
    fun all(): List<ProjectData> = list().mapNotNull { runCatching { load(it.name) }.getOrNull() }

    fun save(project: ProjectData) {
        val tmp = File(dir, "${safe(project.name)}.json.tmp")
        tmp.writeText(Json.project(project).toString())
        tmp.renameTo(fileFor(project.name))
    }

    fun load(name: String): ProjectData? = fileFor(name).takeIf { it.exists() }?.let { Json.project(JSONObject(it.readText())) }

    fun delete(name: String) {
        fileFor(name).delete()
        if (lastProject == name) lastProject = null
    }

    fun rename(from: String, to: String): Boolean {
        if (exists(to)) return false
        val ok = !exists(from) || fileFor(from).renameTo(fileFor(to))
        if (ok && lastProject == from) lastProject = to
        return ok
    }

    private fun fileFor(name: String) = File(dir, "${safe(name)}.json")

    private fun safe(name: String) = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80)

    private companion object {
        const val KEY_LAST = "last"
    }
}

/** JSON form of the project; unknown or missing fields fall back to defaults, so old files still open. */
internal object Json {
    private const val VERSION = 3

    fun project(p: ProjectData) = JSONObject()
        .put("version", VERSION)
        .put("name", p.name)
        .put("activities", JSONArray(p.activities.map { it.toString() }))
        .put("clips", JSONArray(p.clips.map(::clip)))
        .put("groups", JSONArray(p.groups.map(::group)))
        .put("globalGrade", grade(p.globalGrade))
        .putOpt("timelineFps", p.timelineFps)
        .putOpt("frameAspect", p.frameAspect)
        .put("audioFiles", JSONArray(p.audioFiles.map { JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("durationMs", it.durationMs) }))
        .put("audioClips", JSONArray(p.audioClips.map(::audioClip)))
        .put("clipSound", p.clipSound)
        .put("texts", JSONArray(p.texts.map(::text)))
        .putOpt("kind", p.kind?.name)
        .put("routeMap", JSONObject().put("enabled", p.routeMap.enabled).put("x", p.routeMap.x.toDouble()).put("y", p.routeMap.y.toDouble())
            .put("size", p.routeMap.size.toDouble()).put("colour", p.routeMap.colour).put("background", p.routeMap.background).put("marker", p.routeMap.marker))

    fun project(o: JSONObject) = ProjectData(
        name = o.optString("name", "Project"),
        activities = o.optJSONArray("activities").strings().map(Uri::parse),
        clips = o.optJSONArray("clips").objects().map(::clip),
        groups = o.optJSONArray("groups").objects().map(::group),
        globalGrade = grade(o.optJSONObject("globalGrade")),
        timelineFps = if (o.has("timelineFps")) o.getDouble("timelineFps").toFloat() else null,
        frameAspect = if (o.has("frameAspect")) o.getDouble("frameAspect").toFloat() else null,
        audioFiles = o.optJSONArray("audioFiles").objects().map {
            AudioFile(Uri.parse(it.getString("uri")), it.optString("name", "Sound"), it.optLong("durationMs", 0L))
        } + listOfNotNull(o.optJSONObject("music")?.let { m -> AudioFile(Uri.parse(m.getString("uri")), m.optString("name", "Music"), m.optLong("durationMs", 0L)) }),
        audioClips = o.optJSONArray("audioClips").objects().map(::audioClip) + oldMusic(o.optJSONObject("music")),
        clipSound = o.optDouble("clipSound", o.optJSONObject("music")?.optDouble("originalVolume", 1.0) ?: 1.0).toFloat(),
        texts = o.optJSONArray("texts").objects().map(::text),
        kind = runCatching { ProjectKind.valueOf(o.optString("kind")) }.getOrNull(),
        routeMap = o.optJSONObject("routeMap")?.let { m ->
            RouteMap(
                m.optBoolean("enabled", false), m.optDouble("x", 0.8).toFloat(), m.optDouble("y", 0.18).toFloat(),
                m.optDouble("size", 0.3).toFloat(), m.optInt("colour", -1), m.optBoolean("background", true), m.optBoolean("marker", true),
            )
        } ?: RouteMap(),
    )

    private fun text(t: TextItem) = JSONObject()
        .put("id", t.id).put("text", t.text).put("startMs", t.startMs).put("lengthMs", t.lengthMs)
        .put("x", t.x.toDouble()).put("y", t.y.toDouble()).put("size", t.size.toDouble())
        .put("font", t.font.name).put("colour", t.colour).put("glow", t.glow.toDouble()).put("shadow", t.shadow)
        .put("align", t.align.name).put("animIn", t.animIn.name).put("animOut", t.animOut.name).put("animMs", t.animMs)
        .putOpt("onlyClip", t.onlyClip).putOpt("onlyGroup", t.onlyGroup)

    private fun text(o: JSONObject) = TextItem(
        id = o.optLong("id"),
        text = o.optString("text", ""),
        startMs = o.optLong("startMs", 0L),
        lengthMs = o.optLong("lengthMs", 3_000L),
        x = o.optDouble("x", 0.5).toFloat(),
        y = o.optDouble("y", 0.8).toFloat(),
        size = o.optDouble("size", 0.07).toFloat(),
        font = runCatching { TextFont.valueOf(o.optString("font")) }.getOrDefault(TextFont.SANS),
        colour = o.optInt("colour", -1),
        glow = o.optDouble("glow", 0.0).toFloat(),
        shadow = o.optBoolean("shadow", true),
        align = runCatching { TextAlign.valueOf(o.optString("align")) }.getOrDefault(TextAlign.CENTER),
        animIn = runCatching { TextAnim.valueOf(o.optString("animIn")) }.getOrDefault(TextAnim.FADE),
        animOut = runCatching { TextAnim.valueOf(o.optString("animOut")) }.getOrDefault(TextAnim.FADE),
        animMs = o.optLong("animMs", 500L),
        onlyClip = if (o.has("onlyClip")) o.getLong("onlyClip") else null,
        onlyGroup = if (o.has("onlyGroup")) o.getLong("onlyGroup") else null,
    )

    private fun audioClip(c: AudioClip) = JSONObject()
        .put("id", c.id).put("uri", c.uri.toString()).put("name", c.name).put("lane", c.lane)
        .put("sourceDurationMs", c.sourceDurationMs).put("startOnTimelineMs", c.startOnTimelineMs)
        .put("sourceStartMs", c.sourceStartMs).put("sourceEndMs", c.sourceEndMs)
        .put("volume", c.volume).put("fadeInMs", c.fadeInMs).put("fadeOutMs", c.fadeOutMs)

    private fun audioClip(o: JSONObject) = AudioClip(
        id = o.optLong("id"),
        uri = Uri.parse(o.getString("uri")),
        name = o.optString("name", "Sound"),
        lane = o.optInt("lane", 0),
        sourceDurationMs = o.optLong("sourceDurationMs", 0L),
        startOnTimelineMs = o.optLong("startOnTimelineMs", 0L),
        sourceStartMs = o.optLong("sourceStartMs", 0L),
        sourceEndMs = o.optLong("sourceEndMs", 0L),
        volume = o.optDouble("volume", 0.8).toFloat(),
        fadeInMs = o.optLong("fadeInMs", 0L),
        fadeOutMs = o.optLong("fadeOutMs", 0L),
    )

    /** Version 2's single music track becomes one clip on the first audio track. */
    private fun oldMusic(m: JSONObject?): List<AudioClip> {
        if (m == null) return emptyList()
        val songEnd = m.optLong("songEndMs", 0L)
        return listOf(
            AudioClip(
                id = 1L, uri = Uri.parse(m.getString("uri")), name = m.optString("name", "Music"),
                sourceDurationMs = m.optLong("durationMs", 0L), startOnTimelineMs = m.optLong("startOnTimelineMs", 0L),
                sourceStartMs = m.optLong("songStartMs", 0L), sourceEndMs = songEnd,
                volume = m.optDouble("volume", 0.8).toFloat(), fadeInMs = m.optLong("fadeInMs", 0L), fadeOutMs = m.optLong("fadeOutMs", 0L),
            ),
        )
    }

    private fun clip(c: SavedClip) = JSONObject()
        .put("uri", c.uri.toString())
        .put("trimStartMs", c.trimStartMs)
        .put("trimEndMs", c.trimEndMs)
        .put("manualOffsetMs", c.manualOffsetMs)
        .put("colorGrade", c.colorGrade)
        .putOpt("lutName", c.lutName)
        .putOpt("manualStart", c.manualStart?.toString())
        .put("grade", grade(c.grade))
        .put("effects", effects(c.effects))
        .put("masks", JSONArray(c.masks.map(::mask)))
        .putOpt("groupId", c.groupId)
        .put("transitionMs", c.transitionMs)
        .put("speed", c.speed)
        .put("muteChangedSpeed", c.muteChangedSpeed)
        .put("fadeInMs", c.fadeInMs)
        .put("fadeOutMs", c.fadeOutMs)
        .put("soundLevel", c.soundLevel)
        .put("picked", c.picked)
        .put("raw", JSONObject().put("exposure", c.rawDevelop.exposure.toDouble()).put("highlights", c.rawDevelop.highlights.toDouble())
            .put("shadows", c.rawDevelop.shadows.toDouble()).put("temperature", c.rawDevelop.temperature.toDouble()).put("tint", c.rawDevelop.tint.toDouble())
            .put("sharpen", c.rawDevelop.sharpen.toDouble()).put("colourNoise", c.rawDevelop.colourNoise.toDouble()))
        .put("reframe", JSONObject().put("zoom", c.reframe.zoom).put("centerX", c.reframe.centerX).put("centerY", c.reframe.centerY).put("angleDegrees", c.reframe.angleDegrees).put("fit", c.reframe.fit)
            .put("vertical", c.reframe.vertical).put("horizontal", c.reframe.horizontal))

    private fun clip(o: JSONObject) = SavedClip(
        uri = Uri.parse(o.getString("uri")),
        trimStartMs = o.optLong("trimStartMs", 0L),
        trimEndMs = o.optLong("trimEndMs", Long.MAX_VALUE),
        manualOffsetMs = o.optLong("manualOffsetMs", 0L),
        colorGrade = o.optBoolean("colorGrade", true),
        lutName = o.optStringOrNull("lutName"),
        manualStart = o.optStringOrNull("manualStart")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        grade = grade(o.optJSONObject("grade")),
        effects = effects(o.optJSONObject("effects")),
        // Version 1 files had at most one graduated mask, stored as "mask".
        masks = o.optJSONArray("masks").objects().map(::mask) + listOfNotNull(o.optJSONObject("mask")?.let(::mask)),
        groupId = if (o.has("groupId")) o.getLong("groupId") else null,
        transitionMs = o.optLong("transitionMs", 0L),
        speed = o.optDouble("speed", 1.0).toFloat(),
        muteChangedSpeed = o.optBoolean("muteChangedSpeed", false),
        fadeInMs = o.optLong("fadeInMs", 0L),
        fadeOutMs = o.optLong("fadeOutMs", 0L),
        soundLevel = o.optDouble("soundLevel", 1.0).toFloat(),
        picked = o.optBoolean("picked", false),
        rawDevelop = o.optJSONObject("raw")?.let { r ->
            RawDevelop(
                r.optDouble("exposure", 0.0).toFloat(), r.optDouble("highlights", 0.0).toFloat(), r.optDouble("shadows", 0.0).toFloat(),
                r.optDouble("temperature", 0.0).toFloat(), r.optDouble("tint", 0.0).toFloat(),
                sharpen = r.optDouble("sharpen", RawDevelop().sharpen.toDouble()).toFloat(),
                colourNoise = r.optDouble("colourNoise", RawDevelop().colourNoise.toDouble()).toFloat(),
            )
        } ?: RawDevelop(),
        reframe = o.optJSONObject("reframe")?.let { r ->
            Reframe(
                zoom = r.optDouble("zoom", 1.0).toFloat(),
                centerX = r.optDouble("centerX", 0.5).toFloat(),
                centerY = r.optDouble("centerY", 0.5).toFloat(),
                angleDegrees = r.optDouble("angleDegrees", 0.0).toFloat(),
                fit = r.optBoolean("fit", false),
                vertical = r.optDouble("vertical", 0.0).toFloat(),
                horizontal = r.optDouble("horizontal", 0.0).toFloat(),
            )
        } ?: Reframe(),
    )

    private fun group(g: SavedGroup) = JSONObject()
        .put("id", g.id)
        .put("name", g.name)
        .put("grade", grade(g.grade))
        .putOpt("lutName", g.lutName)

    private fun group(o: JSONObject) = SavedGroup(
        id = o.getLong("id"),
        name = o.optString("name", "Group"),
        grade = grade(o.optJSONObject("grade")),
        lutName = o.optStringOrNull("lutName"),
    )

    fun grade(g: ColorGrade) = JSONObject()
        .put("exposure", g.exposure)
        .put("brightness", g.brightness)
        .put("contrast", g.contrast)
        .put("shadows", g.shadows)
        .put("highlights", g.highlights)
        .put("saturation", g.saturation)
        .put("temperature", g.temperature)
        .put("vignette", g.vignette)
        .put("blacks", g.blacks)
        .put("whites", g.whites)
        .put("vibrance", g.vibrance)
        .put("tint", g.tint)
        .put("skinHue", g.skinHue)
        .put("rolloff", g.rolloff)
        .put("toneAmount", g.toneAmount)
        .put("shadowHue", g.shadowHue)
        .put("highlightHue", g.highlightHue)
        .put("tame", g.tame)
        .put("mixer", JSONArray(g.mixer.map { it.toDouble() }))
        .putOpt("look", g.look)
        .put("lookAmount", g.lookAmount)
        .put("curves", JSONArray(g.curves.map { JSONArray(it.map { v -> v.toDouble() }) }))
        .put("wheels", JSONArray(g.wheels.map { it.toDouble() }))
        .put("texture", g.texture)
        .put("clarity", g.clarity)
        .put("dehaze", g.dehaze)

    fun grade(o: JSONObject?): ColorGrade {
        if (o == null) return ColorGrade()
        fun f(key: String, default: Float = 0f) = o.optDouble(key, default.toDouble()).toFloat()
        return ColorGrade(
            exposure = f("exposure"),
            brightness = f("brightness"),
            contrast = f("contrast"),
            shadows = f("shadows"),
            highlights = f("highlights"),
            saturation = f("saturation"),
            temperature = f("temperature"),
            vignette = f("vignette"),
            blacks = f("blacks"),
            whites = f("whites"),
            vibrance = f("vibrance"),
            tint = f("tint"),
            skinHue = f("skinHue"),
            rolloff = f("rolloff"),
            toneAmount = f("toneAmount"),
            shadowHue = f("shadowHue", ColorGrade.DEFAULT_SHADOW_HUE),
            highlightHue = f("highlightHue", ColorGrade.DEFAULT_HIGHLIGHT_HUE),
            tame = f("tame"),
            mixer = o.optJSONArray("mixer")?.let { a -> List(app.headsup.core.color.ColourMixer.SIZE) { a.optDouble(it, 0.0).toFloat() } }
                ?: app.headsup.core.color.ColourMixer.NEUTRAL,
            look = o.optStringOrNull("look"),
            lookAmount = f("lookAmount", 1f),
            curves = o.optJSONArray("curves")?.let { a ->
                List(4) { c -> a.optJSONArray(c)?.let { p -> List(p.length()) { p.optDouble(it, 0.0).toFloat() } }.orEmpty() }
            } ?: app.headsup.core.color.ToneCurve.NEUTRAL,
            wheels = o.optJSONArray("wheels")?.let { a -> List(app.headsup.core.color.ColourWheels.SIZE) { a.optDouble(it, 0.0).toFloat() } }
                ?: app.headsup.core.color.ColourWheels.NEUTRAL,
            texture = f("texture"),
            clarity = f("clarity"),
            dehaze = f("dehaze"),
        )
    }

    private fun effects(e: EffectSettings) = JSONObject()
        .put("grain", e.grain)
        .put("grainSize", e.grainSize)
        .put("glow", e.glow)
        .put("glowThreshold", e.glowThreshold)
        .put("halation", e.halation)
        .putOpt("barsRatio", e.barsRatio)
        .putOpt("graph", e.graph?.name)
        .put("dashboard", e.dashboard)

    private fun effects(o: JSONObject?): EffectSettings {
        val d = EffectSettings()
        if (o == null) return d
        return EffectSettings(
            grain = o.optDouble("grain", d.grain.toDouble()).toFloat(),
            grainSize = o.optDouble("grainSize", d.grainSize.toDouble()).toFloat(),
            glow = o.optDouble("glow", d.glow.toDouble()).toFloat(),
            glowThreshold = o.optDouble("glowThreshold", d.glowThreshold.toDouble()).toFloat(),
            halation = o.optDouble("halation", 0.0).toFloat(),
            barsRatio = if (o.has("barsRatio")) o.getDouble("barsRatio").toFloat() else null,
            graph = GraphMetric.entries.firstOrNull { it.name == o.optStringOrNull("graph") },
            dashboard = o.optBoolean("dashboard", true),
        )
    }

    private fun mask(m: ColourMask) = JSONObject()
        .put("kind", m.kind.name)
        .put("centerX", m.centerX)
        .put("centerY", m.centerY)
        .put("sizeX", m.sizeX)
        .put("sizeY", m.sizeY)
        .put("angleDegrees", m.angleDegrees)
        .put("softness", m.softness)
        .put("inverted", m.inverted)
        .put("grade", grade(m.grade))
        .put(
            "keys",
            JSONArray(
                m.keys.map {
                    JSONObject().put("t", it.timeMs).put("x", it.centerX).put("y", it.centerY).put("a", it.angleDegrees).put("w", it.sizeX).put("h", it.sizeY)
                },
            ),
        )
        .put(
            "strokes",
            JSONArray(
                m.strokes.map {
                    JSONObject().put("p", JSONArray(it.points.map { v -> v.toDouble() })).put("r", it.radius)
                        .put("f", it.flow).put("e", it.erase).put("ref", it.reference).put("tol", it.tolerance)
                        .put("lasso", it.lasso).put("seg", it.segment)
                },
            ),
        )

    private fun mask(o: JSONObject): ColourMask {
        val kind = MaskKind.entries.firstOrNull { it.name == o.optString("kind") } ?: MaskKind.LINEAR
        val d = ColourMask(kind)
        fun f(key: String, default: Float) = o.optDouble(key, default.toDouble()).toFloat()
        return ColourMask(
            kind = kind,
            centerX = f("centerX", d.centerX),
            // Version 1 called the line height "position".
            centerY = f("centerY", f("position", d.centerY)),
            sizeX = f("sizeX", d.sizeX),
            sizeY = f("sizeY", d.sizeY),
            angleDegrees = f("angleDegrees", 0f),
            softness = f("softness", d.softness),
            inverted = o.optBoolean("inverted", false),
            grade = grade(o.optJSONObject("grade")),
            keys = o.optJSONArray("keys").objects().map {
                MaskKey(
                    it.optLong("t"), it.optDouble("x").toFloat(), it.optDouble("y").toFloat(), it.optDouble("a").toFloat(),
                    it.optDouble("w").toFloat(), it.optDouble("h").toFloat(),
                )
            },
            strokes = o.optJSONArray("strokes").objects().map {
                val p = it.optJSONArray("p")
                app.headsup.core.color.BrushStroke(
                    points = if (p == null) emptyList() else List(p.length()) { i -> p.optDouble(i, 0.0).toFloat() },
                    radius = it.optDouble("r", 0.06).toFloat(),
                    flow = it.optDouble("f", 1.0).toFloat(),
                    erase = it.optBoolean("e", false),
                    reference = it.optStringOrNull("ref"),
                    tolerance = it.optDouble("tol", 0.15).toFloat(),
                    lasso = it.optBoolean("lasso", false),
                    segment = it.optBoolean("seg", false),
                )
            },
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
}
