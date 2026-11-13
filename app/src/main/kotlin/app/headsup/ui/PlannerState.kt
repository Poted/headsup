package app.headsup.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.headsup.core.planner.AutoSetting
import app.headsup.core.planner.CameraLimits
import app.headsup.core.planner.ExposureSetup
import app.headsup.core.planner.NdFilter
import app.headsup.core.planner.Place
import app.headsup.core.planner.SkyConditions
import java.time.LocalDate
import java.time.ZoneOffset
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

    var auto by Saved(AutoSetting.entries[prefs.get("auto", 0f).roundToInt().coerceIn(0, 2)]) { prefs.put("auto", it.ordinal.toFloat()) }
    var aperture by Saved(prefs.get("ap", 2.8f).toDouble()) { prefs.put("ap", it.toFloat()) }
    var shutter by Saved(prefs.get("sh", 0.5f).toDouble()) { prefs.put("sh", it.toFloat()) }
    var iso by Saved(prefs.get("iso", 100f).toDouble()) { prefs.put("iso", it.toFloat()) }
    /** The filter's place in the list, or −1 to let the planner pick. */
    var ndIndex by Saved(prefs.get("nd", 3f).roundToInt()) { prefs.put("nd", it.toFloat()) }
    var isoMax by Saved(prefs.get("isomax", 25_600f).toDouble()) { prefs.put("isomax", it.toFloat()) }
    var isoMin by Saved(prefs.get("isomin", 100f).toDouble()) { prefs.put("isomin", it.toFloat()) }

    // Timelapse: the window of the day (minutes from midnight) and the shutter the camera may use in it.
    var tlFrom by Saved(prefs.get("tlfrom", 4f * 60f)) { prefs.put("tlfrom", it) }
    var tlTo by Saved(prefs.get("tlto", 8f * 60f)) { prefs.put("tlto", it) }
    var shutterMin by Saved(prefs.get("smin", 0.5f).toDouble()) { prefs.put("smin", it.toFloat()) }
    var shutterMax by Saved(prefs.get("smax", 4f).toDouble()) { prefs.put("smax", it.toFloat()) }

    var places by mutableStateOf(readPlaces(prefs))
    var selectedPlace by mutableStateOf<Int?>(null)

    /** Counts the times the place was set from outside the typed fields, so they show the new numbers. */
    var reload by mutableStateOf(0)
    /** Counts the times the map should move to the place (not when the place was picked on the map itself). */
    var recentre by mutableStateOf(0)

    val place: Place get() = Place(lat.coerceIn(-90.0, 90.0), lon.coerceIn(-180.0, 180.0), elevation)
    val zone: ZoneOffset get() = ZoneOffset.ofHours(offsetHours.coerceIn(-12, 14))
    val dayStart: Long get() = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val now: Long get() = dayStart + minute.toLong() * 60_000L
    val conditions: SkyConditions get() = SkyConditions(bortle, cloud.toDouble())
    val nd: NdFilter? get() = if (ndIndex < 0) null else NdFilter.entries[ndIndex.coerceIn(0, NdFilter.entries.size - 1)]
    val limits: CameraLimits get() = CameraLimits(isoMin, isoMax)
    val setup: ExposureSetup get() = ExposureSetup(auto, aperture, shutter, iso, nd, limits)

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
