package app.headsup.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.headsup.core.planner.CameraProfile
import app.headsup.core.planner.CloudForecast
import app.headsup.core.planner.Meter
import app.headsup.core.planner.MeterReading
import app.headsup.core.planner.MeterSettings
import app.headsup.core.planner.Planner
import app.headsup.core.planner.Gazetteer
import app.headsup.core.planner.NdFilter
import app.headsup.core.planner.HorizonProfile
import app.headsup.core.planner.MetNorway
import app.headsup.core.planner.Place
import app.headsup.core.planner.SkyConditions
import app.headsup.core.planner.Measured
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

/** The planner's settings, kept on the phone between visits. */
internal class PlannerPrefs(context: Context) {
    private val p = context.getSharedPreferences("planner", Context.MODE_PRIVATE)
    fun get(k: String, d: Float) = p.getFloat(k, d)
    fun put(k: String, v: Float) = p.edit().putFloat(k, v).apply()
    fun getString(k: String, d: String) = p.getString(k, d) ?: d
    fun putString(k: String, v: String) = p.edit().putString(k, v).apply()
}

/** A value the screen follows, written to the phone each time it is set. */
private class Saved<T>(initial: T, private val write: (T) -> Unit) {
    private var value by mutableStateOf(initial)
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, v: T) { value = v; write(v) }
}

/** A place kept by name: where it is, how high, and its time zone as whole hours from UTC. */
internal data class SavedPlace(val name: String, val lat: Double, val lon: Double, val elev: Double, val utc: Int)

/**
 * Everything the planner's views share: the place, the day and hour, the light's conditions and the
 * camera's settings. The sun view and the calculator both read and set it, so a change in one is
 * seen in the other.
 */
internal class PlannerState(context: Context) {
    private val prefs = PlannerPrefs(context)

    var lat by Saved(prefs.get("lat", 50.06f).toDouble()) { prefs.put("lat", it.toFloat()) }
    var lon by Saved(prefs.get("lon", 19.94f).toDouble()) { prefs.put("lon", it.toFloat()) }
    var elevation by Saved(prefs.get("elev", 200f).toDouble()) { prefs.put("elev", it.toFloat()) }
    var offsetHours by Saved(prefs.get("utc", (java.util.TimeZone.getDefault().rawOffset / 3_600_000).toFloat()).roundToInt()) { prefs.put("utc", it.toFloat()) }
    var day: LocalDate by mutableStateOf(LocalDate.now())
    var minute by Saved(prefs.get("min", 18f * 60f)) { prefs.put("min", it) }
    var bortle by Saved(prefs.get("bortle", 3f).roundToInt()) { prefs.put("bortle", it.toFloat()) }
    var cloud by Saved(prefs.get("cloud", 0f)) { prefs.put("cloud", it) }
    /** The way the phone looks, degrees from north: the centre of the sky chart and the cone on the map. */
    var viewAz by Saved(prefs.get("az", 180f)) { prefs.put("az", it) }

    // The shutter and the ISO each set by hand or left to the camera (Auto), as on the camera itself.
    // Older versions had one setting following the light ("auto": 0 ISO, 1 shutter, 2 aperture).
    private val oldAuto = prefs.get("auto", 0f).roundToInt()
    var shutterAuto by Saved(prefs.get("shauto", if (oldAuto == 1) 1f else 0f) > 0.5f) { prefs.put("shauto", if (it) 1f else 0f) }
    var isoAuto by Saved(prefs.get("isoauto", if (oldAuto == 0) 1f else 0f) > 0.5f) { prefs.put("isoauto", if (it) 1f else 0f) }
    var aperture by Saved(prefs.get("ap", 2.8f).toDouble()) { prefs.put("ap", it.toFloat()) }
    var shutter by Saved(prefs.get("sh", 0.5f).toDouble()) { prefs.put("sh", it.toFloat()) }
    var iso by Saved(prefs.get("iso", 100f).toDouble()) { prefs.put("iso", it.toFloat()) }
    /** The filter's place in the list of all filters (older versions also had −1, "pick for me": now none). */
    // Kept by the filter's name: the list of filters grows, a name stays (older versions kept its place in a shorter list).
    var ndIndex by Saved(ndIndexFrom(prefs.getString("ndl", ""), prefs.get("nd", 4f).roundToInt())) {
        prefs.putString("ndl", NdFilter.entries[it.coerceIn(0, NdFilter.entries.size - 1)].label)
    }

    /** The cameras described (their ISO and shutter range, a fixed aperture, the filters owned) and the one in use. */
    var profiles by mutableStateOf(readProfiles(prefs))
    var profileIndex by Saved(prefs.get("profile", 0f).roundToInt()) { prefs.put("profile", it.toFloat()) }
    val camera: CameraProfile get() = profiles.getOrNull(profileIndex) ?: profiles.first()

    /** Keeps a camera (replacing the one at [at], or adding it) and takes it into use. */
    fun saveProfile(profile: CameraProfile, at: Int?) {
        val replace = at != null && at in profiles.indices
        profiles = if (replace) profiles.toMutableList().also { it[at!!] = profile } else profiles + profile
        profileIndex = if (replace) at!! else profiles.size - 1
        writeProfiles(prefs, profiles)
        keepFilterInProfile()
    }

    fun deleteProfile(i: Int) {
        if (profiles.size <= 1) return
        profiles = profiles.filterIndexed { j, _ -> j != i }
        profileIndex = profileIndex.coerceIn(0, profiles.size - 1)
        writeProfiles(prefs, profiles)
        keepFilterInProfile()
    }

    fun useProfile(i: Int) {
        profileIndex = i.coerceIn(0, profiles.size - 1)
        keepFilterInProfile()
    }

    /** A filter not owned for this camera is taken off (none on the lens). */
    private fun keepFilterInProfile() {
        if (ndFilter !in camera.choices) ndIndex = NdFilter.None.ordinal
    }

    /** The last forecast fetched, and the place it is for. It is kept, so a place seen once has its forecast with no signal. */
    // Read safely: a forecast kept by an older version, or a damaged one, is simply not used.
    var forecast by mutableStateOf(runCatching { MetNorway.parse(prefs.getString("fc_json", "")) }.getOrNull())
    private var forecastKey by mutableStateOf(prefs.getString("fc_key", ""))
    /** What the last fetch said, if it went wrong or is going on. */
    var forecastStatus by mutableStateOf("")

    private fun key() = String.format(Locale.US, "%.1f|%.1f", lat, lon)

    /** The forecast, while it is for this place and reaches the day shown. */
    val forecastHere: CloudForecast? get() =
        forecast?.takeIf { forecastKey == key() && it.covers(dayStart, dayStart + 24 * 3_600_000L) }

    /** Asks MET Norway for the cloud cover of this place, the next nine days or so. */
    suspend fun fetchForecast() {
        val k = key()
        forecastStatus = "Loading the forecast…"
        val body = download(MetNorway.url(lat, lon))?.toString(Charsets.UTF_8)
        val parsed = body?.let { MetNorway.parse(it) }
        if (parsed != null && body != null) {
            forecast = parsed
            forecastKey = k
            prefs.putString("fc_json", body)
            prefs.putString("fc_key", k)
            forecastStatus = if (parsed.covers(dayStart, dayStart + 24 * 3_600_000L)) "" else "The forecast reaches about nine days ahead; this day is outside it."
        } else {
            forecastStatus = "No forecast: no signal, or the service did not answer."
        }
    }

    /** How high above the ground the camera is (a drone, a roof), in metres: the horizon dips, the sun rises sooner. */
    var aboveGround by Saved(prefs.get("above", 0f).toDouble()) { prefs.put("above", it.toFloat()) }
    /** What the last ground-height lookup said. */
    var groundStatus by mutableStateOf("")
    /** Counts the times the ground height was set from the terrain model, so its field shows the new number. */
    var groundVersion by mutableStateOf(0)

    /** Looks up the ground's height at the place in the terrain model (tiles kept on the phone once fetched). */
    suspend fun fetchGround() {
        val la = lat
        val lo = lon
        val height = terrain.height(la, lo)
        if (height == null) {
            groundStatus = "No ground height (no signal): set it by hand."
        } else if (la == lat && lo == lon) {
            elevation = Math.round(height.coerceAtLeast(-430.0)).toDouble()
            groundVersion++
            groundStatus = "Ground height from the terrain model."
        }
    }

    // The skyline: the hills and mountains around the place, seen from the camera's height.
    var skyline by mutableStateOf<HorizonProfile?>(null)
    private var skylineKey by mutableStateOf("")
    var skylineStatus by mutableStateOf("")
    private fun skyKey() = String.format(Locale.US, "%.4f|%.4f|%.0f", lat, lon, aboveGround)

    /** The skyline, while it is for this place and height. */
    val skylineHere: HorizonProfile? get() = skyline?.takeIf { skylineKey == skyKey() }

    /** Reads the land up to 60 km around and works out the skyline (about 70 terrain tiles the first time, a few MB). */
    suspend fun buildSkyline() {
        val k = skyKey()
        if (skylineKey == k && skyline != null) return
        val la = lat
        val lo = lon
        val eye = elevation + aboveGround + 1.6
        skylineStatus = "Reading the land around…"
        val p = terrain.skyline(la, lo, eye) { done, all -> skylineStatus = "Reading the land around… $done / $all" }
        if (skyKey() != k) return
        if (p != null) {
            skyline = p
            skylineKey = k
            skylineStatus = ""
        } else {
            skylineStatus = "No terrain (no signal): the horizon is taken as flat."
        }
    }

    private val cacheDir: File = context.cacheDir
    private val terrain = TerrainStore(cacheDir) { download(it) }

    /** The bytes at [url], or null; names the app to the server, as the weather service asks. */
    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", PLANNER_USER_AGENT)
            try {
                if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    var plans by mutableStateOf(readPlans(prefs))

    /** Keeps everything now set (place, day, hour, camera, sky) as a plan under [name]; a plan of that name is replaced. */
    fun savePlan(name: String) {
        val one = SavedPlan(
            name, lat, lon, elevation, offsetHours, day.toString(), minute,
            shutterAuto, isoAuto, aperture, shutter, iso, ndIndex, camera.name, bortle, cloud,
        )
        val at = plans.indexOfFirst { it.name == name }
        plans = if (at >= 0) plans.toMutableList().also { it[at] = one } else listOf(one) + plans
        writePlans(prefs, plans)
    }

    /** Sets everything as the plan had it. */
    fun openPlan(i: Int) {
        val p = plans.getOrNull(i) ?: return
        lat = p.lat; lon = p.lon; elevation = p.elevation; offsetHours = p.utc
        day = runCatching { LocalDate.parse(p.date) }.getOrDefault(day)
        minute = p.minute
        shutterAuto = p.shutterAuto; isoAuto = p.isoAuto
        aperture = p.aperture; shutter = p.shutter; iso = p.iso; ndIndex = p.nd
        bortle = p.bortle; cloud = p.cloud
        profiles.indexOfFirst { it.name == p.camera }.takeIf { it >= 0 }?.let { profileIndex = it }
        selectedPlace = null
        reload++; recentre++
    }

    fun deletePlan(i: Int) {
        plans = plans.filterIndexed { j, _ -> j != i }
        writePlans(prefs, plans)
    }

    /**
     * Corrections for this phone's compass and tilt (degrees), found in Live by aiming at the sun or
     * the moon, or set by hand. Kept on the phone: a phone's error mostly stays (until it is recalibrated).
     */
    var compassOffset by Saved(prefs.get("compass", 0f).toDouble()) { prefs.put("compass", it.toFloat()) }
    var tiltOffset by Saved(prefs.get("tilt", 0f).toDouble()) { prefs.put("tilt", it.toFloat()) }

    /** Place names for search (towns, peaks, passes, lakes; from GeoNames, built into the app), read once when first needed. */
    var gazetteer by mutableStateOf<Gazetteer?>(null)
    private val assets = context.assets

    suspend fun loadPlaceNames() {
        if (gazetteer != null) return
        gazetteer = withContext(Dispatchers.IO) {
            runCatching {
                java.util.zip.GZIPInputStream(assets.open("places.tsv.gz")).bufferedReader().useLines { Gazetteer.parse(it) }
            }.getOrDefault(Gazetteer(emptyList()))
        }
    }

    var places by mutableStateOf(readPlaces(prefs))
    var selectedPlace by mutableStateOf<Int?>(null)

    /** Counts the times the place was set from outside the typed fields, so they show the new numbers. */
    var reload by mutableStateOf(0)
    /** Counts the times the map should move to the place (not when the place was picked on the map itself). */
    var recentre by mutableStateOf(0)

    // A reading of the light taken with the camera, where and when it was taken. Kept on the phone
    // (it survives turning the phone) and forgotten after 12 hours.
    private var measuredAt = prefs.getString("m_at", "0").toLongOrNull() ?: 0L
    var measuredEv by mutableStateOf(
        prefs.getString("m_ev", "").toDoubleOrNull()?.takeIf { System.currentTimeMillis() - measuredAt < 12 * 3_600_000L },
    )
    private var measuredLat = prefs.get("m_lat", 0f).toDouble()
    private var measuredLon = prefs.get("m_lon", 0f).toDouble()

    /** Takes the camera's reading of the light here and now as the base of the hours. */
    fun useReading(ev: Double) {
        measuredEv = ev
        measuredAt = System.currentTimeMillis()
        measuredLat = lat
        measuredLon = lon
        prefs.putString("m_ev", ev.toString()); prefs.putString("m_at", measuredAt.toString())
        prefs.put("m_lat", lat.toFloat()); prefs.put("m_lon", lon.toFloat())
    }

    fun clearReading() { measuredEv = null; prefs.putString("m_ev", "") }

    /** True when a reading was taken but the place has since moved away from it (it is then not used). */
    val readingElsewhere: Boolean get() = measuredEv != null && measured == null

    /** The reading, while the place is still about where it was taken. */
    val measured: Measured? get() =
        measuredEv?.takeIf { abs(lat - measuredLat) < 0.2 && abs(lon - measuredLon) < 0.3 }?.let { Measured(measuredAt, it) }

    /** The place: where it is, and the camera's height above the sea (the ground's plus how high above it). */
    val place: Place get() = Place(lat.coerceIn(-90.0, 90.0), lon.coerceIn(-180.0, 180.0), elevation + aboveGround)
    val zone: ZoneOffset get() = ZoneOffset.ofHours(offsetHours.coerceIn(-12, 14))
    val dayStart: Long get() = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val now: Long get() = dayStart + minute.toLong() * 60_000L
    val conditions: SkyConditions get() = SkyConditions(bortle, cloud.toDouble())
    val ndFilter: NdFilter get() = if (ndIndex < 0) NdFilter.None else NdFilter.entries[ndIndex.coerceIn(0, NdFilter.entries.size - 1)]

    /** The camera's settings as set: Auto shutter or ISO left to the camera. */
    val settings: MeterSettings get() = MeterSettings(aperture, if (shutterAuto) null else shutter, if (isoAuto) null else iso, ndFilter)

    /** The light of the plan's hour (EV at ISO 100): estimated, or from the camera's reading; under the forecast when given. */
    fun lightEv(forecast: CloudForecast? = null): Double = Planner.ev(place, now, conditions, measured, forecast)

    /** What the camera does in that light with these settings, and how far off the exposure is. */
    fun reading(ev: Double): MeterReading = Meter.read(ev, settings, camera)

    /** Sets the place. [fromMap] is true when it was picked on the map, which then stays where the user has put it. */
    fun setPlace(newLat: Double, newLon: Double, newElevation: Double? = null, fromMap: Boolean = false) {
        lat = newLat.coerceIn(-90.0, 90.0)
        lon = newLon.coerceIn(-180.0, 180.0)
        if (newElevation != null) elevation = newElevation
        selectedPlace = null
        reload++
        if (!fromMap) recentre++
    }

    fun savePlace(name: String) {
        val one = SavedPlace(name, lat, lon, elevation, offsetHours)
        val at = places.indexOfFirst { it.name == name }
        places = if (at >= 0) places.toMutableList().also { it[at] = one } else places + one
        selectedPlace = places.indexOfFirst { it.name == name }
        writePlaces(prefs, places)
    }

    fun openPlace(i: Int) {
        val sp = places.getOrNull(i) ?: return
        lat = sp.lat; lon = sp.lon; elevation = sp.elev; offsetHours = sp.utc
        selectedPlace = i
        reload++; recentre++
    }

    fun deletePlace(i: Int) {
        places = places.filterIndexed { j, _ -> j != i }
        writePlaces(prefs, places)
        selectedPlace = null
    }
}

private fun readPlaces(p: PlannerPrefs): List<SavedPlace> = runCatching {
    val a = org.json.JSONArray(p.getString("places", "[]"))
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        SavedPlace(o.getString("n"), o.getDouble("a"), o.getDouble("o"), o.getDouble("e"), o.getInt("u"))
    }
}.getOrDefault(emptyList())

private fun writePlaces(p: PlannerPrefs, list: List<SavedPlace>) {
    val a = org.json.JSONArray()
    list.forEach { a.put(org.json.JSONObject().put("n", it.name).put("a", it.lat).put("o", it.lon).put("e", it.elev).put("u", it.utc)) }
    p.putString("places", a.toString())
}

/** A plan: a place, a day and hour, and the camera and sky as they were set, kept under a name. */
internal data class SavedPlan(
    val name: String, val lat: Double, val lon: Double, val elevation: Double, val utc: Int,
    val date: String, val minute: Float,
    val shutterAuto: Boolean, val isoAuto: Boolean, val aperture: Double, val shutter: Double, val iso: Double, val nd: Int,
    val camera: String, val bortle: Int, val cloud: Float,
)

private fun readPlans(p: PlannerPrefs): List<SavedPlan> = runCatching {
    val a = org.json.JSONArray(p.getString("plans", "[]"))
    (0 until a.length()).mapNotNull { i ->
        runCatching {
            val o = a.getJSONObject(i)
            // Older plans had one setting following the light ("auto": 0 ISO, 1 shutter).
            val oldAuto = o.optInt("auto", 0)
            SavedPlan(
                o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optDouble("elev", 0.0), o.optInt("utc", 0),
                o.getString("date"), o.optDouble("min", 720.0).toFloat(),
                o.optBoolean("shauto", oldAuto == 1), o.optBoolean("isoauto", oldAuto == 0),
                o.optDouble("ap", 2.8), o.optDouble("sh", 0.5), o.optDouble("iso", 100.0), ndIndexFrom(o.optString("ndl", ""), o.optInt("nd", -1)),
                o.optString("camera", ""), o.optInt("bortle", 3), o.optDouble("cloud", 0.0).toFloat(),
            )
        }.getOrNull()
    }
}.getOrDefault(emptyList())

private fun writePlans(p: PlannerPrefs, list: List<SavedPlan>) {
    val a = org.json.JSONArray()
    for (x in list) {
        a.put(
            org.json.JSONObject()
                .put("name", x.name).put("lat", x.lat).put("lon", x.lon).put("elev", x.elevation).put("utc", x.utc)
                .put("date", x.date).put("min", x.minute.toDouble())
                .put("shauto", x.shutterAuto).put("isoauto", x.isoAuto).put("ap", x.aperture).put("sh", x.shutter).put("iso", x.iso)
                .put("ndl", NdFilter.entries[x.nd.coerceIn(0, NdFilter.entries.size - 1)].label)
                .put("camera", x.camera).put("bortle", x.bortle).put("cloud", x.cloud.toDouble()),
        )
    }
    p.putString("plans", a.toString())
}

/**
 * How the planner names itself to the servers it asks (MET Norway requires a name and a way to
 * reach the maker). Before a release, put the maker's contact (a website or an e-mail) here.
 */
internal const val PLANNER_USER_AGENT = "SweetSpotPlanner/1.0 (Android; github.com/Poted/headsup)"

/**
 * A filter's place in today's list from its kept name ("ND64", or "pick" for the planner's choice),
 * or, for what older versions kept, from its place in their list (None, ND4, ND8, ND16, ND32, ND64, ND1000).
 */
internal fun ndIndexFrom(label: String, oldIndex: Int): Int {
    if (label == "pick") return NdFilter.None.ordinal
    NdFilter.of(label)?.let { return it.ordinal }
    if (oldIndex < 0) return NdFilter.None.ordinal
    val old = listOf("None", "ND4", "ND8", "ND16", "ND32", "ND64", "ND1000")
    return NdFilter.of(old.getOrElse(oldIndex) { "ND16" })?.ordinal ?: NdFilter.Nd16.ordinal
}

/** The cameras kept: at least "Any camera". Older versions' ISO range becomes that one's. */
private fun readProfiles(p: PlannerPrefs): List<CameraProfile> {
    val list = runCatching {
        val a = org.json.JSONArray(p.getString("profiles", "[]"))
        (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                val f = o.optJSONArray("filters")
                val filters = if (f == null) NdFilter.entries.toList() else (0 until f.length()).mapNotNull { NdFilter.of(f.getString(it)) }
                CameraProfile(
                    o.getString("name"), o.optDouble("isomin", 100.0), o.optDouble("isomax", 25_600.0),
                    o.optDouble("shmin", 1.0 / 8000), o.optDouble("shmax", 30.0),
                    if (o.has("aperture")) o.getDouble("aperture") else null, filters,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
    if (list.isNotEmpty()) return list
    return listOf(CameraProfile.ANY.copy(isoMin = p.get("isomin", 100f).toDouble(), isoMax = p.get("isomax", 25_600f).toDouble()))
}

private fun writeProfiles(p: PlannerPrefs, list: List<CameraProfile>) {
    val a = org.json.JSONArray()
    for (c in list) {
        val o = org.json.JSONObject().put("name", c.name).put("isomin", c.isoMin).put("isomax", c.isoMax)
            .put("shmin", c.shutterMin).put("shmax", c.shutterMax)
            .put("filters", org.json.JSONArray(c.filters.map { it.label }))
        c.fixedAperture?.let { o.put("aperture", it) }
        a.put(o)
    }
    p.putString("profiles", a.toString())
}
