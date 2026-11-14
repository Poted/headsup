package app.headsup.core.planner

import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/** A place read from text: where it is, and what kind of text it was (to say so to the user). */
data class ParsedPlace(val lat: Double, val lon: Double, val from: String)

/**
 * Plus Codes (Open Location Code, the open algorithm Google Maps shows instead of coordinates):
 * decoded on the phone with no service. A full code (8 characters before "+") stands alone; a short
 * one ("X3QR+5F Zakopane") is completed from a nearby place.
 */
object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private val pairResolutions = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)

    /** True for something shaped like a Plus Code (full or short). */
    fun looksLike(code: String): Boolean {
        val c = code.uppercase(Locale.US)
        val plus = c.indexOf('+')
        if (plus < 2 || plus > 8 || plus % 2 != 0 || c.count { it == '+' } != 1) return false
        return c.filter { it != '+' && it != '0' }.all { it in ALPHABET } && c.length - plus - 1 != 1
    }

    /** The middle of a full code's area, or null. */
    fun decode(code: String): Pair<Double, Double>? {
        val c = code.uppercase(Locale.US)
        if (!looksLike(c) || c.indexOf('+') != 8) return null
        val digits = c.replace("+", "").trimEnd('0')
        var lat = -90.0
        var lon = -180.0
        var latSize = 20.0
        var lonSize = 20.0
        for (i in 0 until minOf(10, digits.length) step 2) {
            val r = pairResolutions[i / 2]
            lat += ALPHABET.indexOf(digits[i]) * r
            lon += ALPHABET.indexOf(digits.getOrElse(i + 1) { '2' }) * r
            latSize = r; lonSize = r
        }
        // Past ten digits each character splits the cell into 5 rows and 4 columns.
        for (i in 10 until digits.length) {
            val v = ALPHABET.indexOf(digits[i])
            latSize /= 5.0; lonSize /= 4.0
            lat += (v / 4) * latSize
            lon += (v % 4) * lonSize
        }
        return (lat + latSize / 2).coerceIn(-90.0, 90.0) to (lon + lonSize / 2)
    }

    /** The full code (ten digits) of a place: used to complete short codes. */
    fun encode(lat: Double, lon: Double): String {
        var la = lat.coerceIn(-90.0, 89.9999999) + 90.0
        var lo = ((lon + 180.0) % 360.0 + 360.0) % 360.0
        val sb = StringBuilder()
        for (r in pairResolutions) {
            val a = floor(la / r).toInt().coerceIn(0, 19)
            val b = floor(lo / r).toInt().coerceIn(0, 19)
            sb.append(ALPHABET[a]).append(ALPHABET[b])
            la -= a * r; lo -= b * r
            if (sb.length == 8) sb.append('+')
        }
        return sb.toString()
    }

    /** A short code completed with the nearest matching full code to a reference place. */
    fun recover(short: String, refLat: Double, refLon: Double): Pair<Double, Double>? {
        val c = short.uppercase(Locale.US)
        if (!looksLike(c)) return null
        val plus = c.indexOf('+')
        if (plus == 8) return decode(c)
        val missing = 8 - plus
        val resolution = 20.0.pow(2 - missing / 2)
        val half = resolution / 2
        val full = encode(refLat, refLon).substring(0, missing) + c
        var (lat, lon) = decode(full) ?: return null
        if (refLat + half < lat && lat - resolution >= -90) lat -= resolution
        else if (refLat - half > lat && lat + resolution <= 90) lat += resolution
        if (refLon + half < lon) lon -= resolution else if (refLon - half > lon) lon += resolution
        return lat to lon
    }
}

/**
 * Reading a place pasted as text: coordinates as Google Maps or a GPS shows them (49.2318, 19.9817;
 * 49°13'54.5"N 19°58'54.1"E; 49.2318N 19.9817E), a Google Maps link with coordinates in it, or a
 * Plus Code (short ones completed from [refLat], [refLon]). Nothing is asked of a service.
 */
object PlaceInput {
    // Every brace and bracket in these patterns is escaped or a quantifier: Android's engine rejects lone ones.
    internal const val AT_PATTERN = "@(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)"
    internal const val DATA_PATTERN = "!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)"
    internal const val QUERY_PATTERN = "[?&](?:q|query|ll|center|destination)=(-?\\d{1,2}\\.\\d+)(?:,|%2C)\\s*(-?\\d{1,3}\\.\\d+)"
    internal const val DMS_PATTERN =
        "(\\d{1,3})[°º ]\\s*(\\d{1,2})['′ ]\\s*(\\d{1,2}(?:[.,]\\d+)?)?[\"″]?\\s*([NSns])[ ,;]*(\\d{1,3})[°º ]\\s*(\\d{1,2})['′ ]\\s*(\\d{1,2}(?:[.,]\\d+)?)?[\"″]?\\s*([EWew])"
    internal const val DECIMAL_PATTERN = "(-?\\d{1,2}(?:[.,]\\d+)?)\\s*°?\\s*([NSns])?\\s*[,; ]\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*°?\\s*([EWew])?"
    internal const val PLUS_PATTERN = "([23456789CFGHJMPQRVWXcfghjmpqrvwx0]{2,8}\\+[23456789CFGHJMPQRVWXcfghjmpqrvwx]*)"

    private val at by lazy { Regex(AT_PATTERN) }
    private val data by lazy { Regex(DATA_PATTERN) }
    private val query by lazy { Regex(QUERY_PATTERN) }
    private val dms by lazy { Regex(DMS_PATTERN) }
    private val decimal by lazy { Regex(DECIMAL_PATTERN) }
    private val plus by lazy { Regex(PLUS_PATTERN) }

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    private fun ok(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0

    fun parse(text: String, refLat: Double, refLon: Double): ParsedPlace? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // A link: the place it shows (data!3d!4d is the pin, @ the view's centre).
        data.find(t)?.let { m -> val a = num(m.groupValues[1])!!; val b = num(m.groupValues[2])!!; if (ok(a, b)) return ParsedPlace(a, b, "link") }
        query.find(t)?.let { m -> val a = num(m.groupValues[1])!!; val b = num(m.groupValues[2])!!; if (ok(a, b)) return ParsedPlace(a, b, "link") }
        at.find(t)?.let { m -> val a = num(m.groupValues[1])!!; val b = num(m.groupValues[2])!!; if (ok(a, b)) return ParsedPlace(a, b, "link") }
        // Degrees, minutes, seconds.
        dms.find(t)?.let { m ->
            val g = m.groupValues
            var a = g[1].toDouble() + g[2].toDouble() / 60 + (num(g[3]) ?: 0.0) / 3600
            var b = g[5].toDouble() + g[6].toDouble() / 60 + (num(g[7]) ?: 0.0) / 3600
            if (g[4].equals("S", true)) a = -a
            if (g[8].equals("W", true)) b = -b
            if (ok(a, b)) return ParsedPlace(a, b, "coordinates")
        }
        // A Plus Code (before plain numbers: its digits are not coordinates).
        plus.find(t)?.let { m ->
            val code = m.groupValues[1]
            if (PlusCode.looksLike(code)) PlusCode.recover(code, refLat, refLon)?.let { (a, b) -> return ParsedPlace(a, b, "Plus Code") }
        }
        // Decimal degrees.
        decimal.find(t)?.let { m ->
            val g = m.groupValues
            var a = num(g[1]) ?: return@let
            var b = num(g[3]) ?: return@let
            if (g[2].equals("S", true)) a = -a
            if (g[4].equals("W", true)) b = -b
            if (ok(a, b)) return ParsedPlace(a, b, "coordinates")
        }
        return null
    }
}
