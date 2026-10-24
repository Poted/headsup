package app.headsup.media

import android.content.Context
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What a photo recorded about itself (EXIF; DNG too): where, when and how. */
data class PhotoInfo(
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    /** "2026:09:20 08:31:43" as in EXIF. */
    val dateTime: String?,
    val make: String?,
    val model: String?,
    val fNumber: Double?,
    val exposureS: Double?,
    val iso: Int?,
    val focalMm: Double?,
) {
    companion object {
        private val cache = ConcurrentHashMap<Uri, PhotoInfo>()

        fun read(context: Context, uri: Uri): PhotoInfo = cache.getOrPut(uri) {
            runCatching {
                Exif.read(context, uri)?.let { e ->
                    val ll = FloatArray(2)
                    val hasLocation = e.getLatLong(ll)
                    PhotoInfo(
                        latitude = if (hasLocation) ll[0].toDouble() else null,
                        longitude = if (hasLocation) ll[1].toDouble() else null,
                        altitudeM = e.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)?.let { e.getAltitude(0.0) },
                        dateTime = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME),
                        make = e.getAttribute(ExifInterface.TAG_MAKE)?.trim(),
                        model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim(),
                        fNumber = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
                        exposureS = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 },
                        // "ISOSpeedRatings", called PhotographicSensitivity in newer EXIF.
                        iso = e.getAttributeInt("ISOSpeedRatings", 0).takeIf { it > 0 },
                        focalMm = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 },
                    )
                }
            }.getOrNull() ?: PhotoInfo(null, null, null, null, null, null, null, null, null, null)
        }
    }
}

/**
 * Place names for coordinates, through the phone's geocoder (needs the internet; without it the
 * coordinates are shown instead). Kept per rounded position.
 */
object Places {
    private val names = ConcurrentHashMap<String, String>()

    fun key(lat: Double, lon: Double) = String.format(Locale.US, "%.3f,%.3f", lat, lon)

    fun known(lat: Double, lon: Double): String? = names[key(lat, lon)]

    /** Looks the place up (at most a few seconds); null when the phone cannot tell. */
    suspend fun lookUp(context: Context, lat: Double, lon: Double): String? {
        names[key(lat, lon)]?.let { return it }
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(6_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) = cont.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            }
        } ?: return null
        // The most specific named place: a peak or feature, the town, then the region.
        val name = listOfNotNull(address.featureName?.takeIf { f -> f.any { it.isLetter() } }, address.locality ?: address.subAdminArea, address.countryName)
            .distinct().take(2).joinToString(", ")
        if (name.isEmpty()) return null
        names[key(lat, lon)] = name
        return name
    }
}
