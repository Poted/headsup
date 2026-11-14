package app.headsup.core.planner

import java.text.Normalizer
import java.util.Locale

/** A named place: a town, a village, a peak, a pass, a lake, a hut. */
data class NamedPlace(
    val name: String, val lat: Double, val lon: Double,
    /** Height above the sea in metres, when known. */
    val elevation: Int?,
    /** GeoNames' kind: PPL… a settlement, PK a peak, PASS a pass, LK a lake, HUT a hut… */
    val code: String,
    val population: Long,
    val country: String,
) {
    /** The kind in plain words. */
    val kind: String get() = when {
        code.startsWith("PPL") -> if (population >= 10_000) "town" else "village"
        code in setOf("PK", "PKS", "MT", "MTS", "HLL", "HLLS", "RDGE", "SPUR") -> "peak"
        code == "PASS" || code == "SDL" || code == "GAP" -> "pass"
        code.startsWith("LK") || code == "RSV" || code == "PND" -> "lake"
        code == "WTRF" -> "waterfall"
        code.startsWith("HUT") -> "hut"
        code == "OBPT" || code == "TOWR" -> "viewpoint"
        code == "VAL" || code == "GRGE" || code == "CNYN" -> "valley"
        code == "CSTL" || code == "RUIN" -> "castle"
        code == "CH" || code == "MSTY" -> "church"
        else -> "place"
    }
}

/**
 * Finding places by name on the phone, from a list of GeoNames places (tab-separated lines: name,
 * latitude, longitude, elevation, code, population, country). Names are matched without accents and
 * case ("szczyrk" finds Szczyrk, "lomnica" Łomnica); the best matches first: whole name, then its
 * start, then a word's start, then anywhere; among equals the bigger town or higher peak, then the nearer.
 */
class Gazetteer(val places: List<NamedPlace>) {
    private val keys: List<String> = places.map { normalize(it.name) }

    fun search(query: String, nearLat: Double, nearLon: Double, limit: Int = 10): List<NamedPlace> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val scored = ArrayList<Pair<Double, Int>>()
        for (i in places.indices) {
            val k = keys[i]
            val rank = when {
                k == q -> 0
                k.startsWith(q) -> 1
                k.split(' ', '-').any { it.startsWith(q) } -> 2
                k.contains(q) -> 3
                else -> continue
            }
            val p = places[i]
            val importance = when {
                p.code.startsWith("PPL") -> kotlin.math.ln(1.0 + p.population) / 15.0
                p.kind == "peak" -> (p.elevation ?: 0) / 3000.0
                else -> 0.2
            }
            val km = Geo.distance(nearLat, nearLon, p.lat, p.lon) / 1000.0
            scored += (rank * 10.0 - importance * 2.0 + km / 300.0) to i
        }
        return scored.sortedBy { it.first }.take(limit).map { places[it.second] }
    }

    /** The place a name most likely means (as in "Zakopane, Polska" after a Plus Code): the best match of its first part. */
    fun locate(text: String, nearLat: Double, nearLon: Double): NamedPlace? {
        val first = text.split(',').firstOrNull()?.trim().orEmpty()
        return search(first, nearLat, nearLon, 1).firstOrNull()
    }

    companion object {
        /** Reads the list (one place per line); lines it cannot read are left out. */
        fun parse(lines: Sequence<String>): Gazetteer = Gazetteer(
            lines.mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 7) return@mapNotNull null
                val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
                NamedPlace(f[0], lat, lon, f[3].toIntOrNull(), f[4], f[5].toLongOrNull() ?: 0L, f[6])
            }.toList(),
        )

        /** A name without accents or case, for matching: "Łomnica" → "lomnica". */
        fun normalize(s: String): String {
            val lower = s.lowercase(Locale.ROOT).replace('ł', 'l').replace('đ', 'd').replace('ø', 'o').replace('ß', 's')
            return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()
        }
    }
}
