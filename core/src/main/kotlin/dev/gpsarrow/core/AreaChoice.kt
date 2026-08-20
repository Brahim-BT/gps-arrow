package dev.gpsarrow.core

/**
 * Which of several overlapping areas should serve the map at a given position.
 *
 * ## Why this needs a rule at all
 *
 * The two areas we ship overlap across a band of the Atlantic coast, roughly 20.8°N to 27.3°N.
 * That is not an obscure edge: it is the coast road south, and Dakhla — a real user's real
 * position — sits in the middle of it. Two areas both legitimately contain the user, and the app
 * has to pick one.
 *
 * Until now it picked whichever came first in the catalogue, which meant the order the constants
 * happened to be written in. That is not a rule, it is an accident that behaved consistently.
 *
 * ## The rule
 *
 * 1. **Must contain the position.** Anything else is not a candidate.
 * 2. **Higher maximum zoom wins.** More detail is strictly better where both cover you, and it
 *    is the only criterion reflecting a real difference in what the user would see.
 * 3. **Then: the larger edge margin** — distance to the nearest boundary of the area. This
 *    answers "how far can I travel before this area stops helping me", which is the question a
 *    traveller actually has, and it is the reason it was chosen over distance-to-centre. Those
 *    two disagree: at Dakhla, centre-distance picks the southern area while edge-margin picks
 *    the northern one, and the northern one is the one whose place list names Dakhla.
 * 4. **Then: the id, alphabetically.** Only reachable on an exact tie. It exists so the answer is
 *    never implementation-defined; it should never decide anything in practice.
 *
 * **How close this is, honestly.** At Dakhla the two margins are 116 km and 113 km — the rule is
 * deterministic but the gap is small, and it comes mostly from the two western box edges
 * differing by 0.03°. Change a bounding box and this could flip. It does not matter much if it
 * does: see below.
 *
 * Note what is deliberately *not* a criterion: data quality. Both archives are cut from the same
 * planet build, so in the overlap they contain byte-identical tiles. Whichever is chosen, the
 * user sees the same map. That is why a narrow tie-break is acceptable here and would not be if
 * the archives differed in content.
 */
object AreaChoice {

    /**
     * @return the id that should serve, or null if none contains [position].
     */
    fun serving(position: LatLon?, candidates: List<Candidate>): String? {
        if (position == null) return null
        return candidates
            .filter { it.containsPosition }
            .minWithOrNull(
                compareByDescending<Candidate> { it.maxZoom }
                    .thenByDescending { edgeMarginMeters(position, it) }
                    .thenBy { it.id },
            )
            ?.id
    }

    /**
     * Great-circle distance from [p] to the nearest edge of the candidate's box.
     *
     * Measured along the meridian for north/south and along the parallel for east/west, which is
     * what "how far until I leave" means for an axis-aligned box. Uses the same haversine as the
     * arrow so the two can never disagree about a distance.
     */
    fun edgeMarginMeters(p: LatLon, c: Candidate): Double = minOf(
        Geo.distanceMeters(p, LatLon(c.north, p.lon)),
        Geo.distanceMeters(p, LatLon(c.south, p.lon)),
        Geo.distanceMeters(p, LatLon(p.lat, c.east)),
        Geo.distanceMeters(p, LatLon(p.lat, c.west)),
    )

    data class Candidate(
        val id: String,
        val maxZoom: Int,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val containsPosition: Boolean,
    )
}
