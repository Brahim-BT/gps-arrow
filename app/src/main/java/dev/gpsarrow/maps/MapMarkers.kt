package dev.gpsarrow.maps

import dev.gpsarrow.core.Fix
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.MapMarker
import dev.gpsarrow.core.NavigationState
import dev.gpsarrow.core.SharedPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the GeoJSON the position and destination layers read.
 *
 * Two rules, both of which are about not drawing something that looks like information:
 *
 *  - **No dot without a fresh fix.** No fix, or a stale one, produces an empty feature collection
 *    — a real absence, not a dot at 0,0. The position band above the map can caveat a stale
 *    position in words; the map cannot, and an uncaveated dot reads as "you are here".
 *  - **No accuracy circle without an accuracy.** The circle is a claim about how well the device
 *    knows where it is. If the fix does not carry one, nothing is drawn rather than a default
 *    that would be a made-up number rendered at pixel precision.
 */
object MapMarkers {

    const val POSITION_SOURCE = "position"
    const val DESTINATION_SOURCE = "destination"
    const val SHARED_SOURCE = "shared"

    /** Layer ids the map-click handler queries when looking for a shared dot. */
    val SHARED_LAYERS = listOf("shared-dot")

    private val EMPTY = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray())
        .toString()

    /**
     * @param fixAgeMillis how old the fix is; null when there is none.
     * @return GeoJSON for the position layers, or an empty collection.
     */
    fun position(fix: Fix?, fixAgeMillis: Long?, smoothed: LatLon? = null): String {
        if (fix == null || !MapMarker.shouldDrawPosition(fixAgeMillis)) return EMPTY

        // The ONLY place a smoothed coordinate is used, and only for drawing.
        //
        // The ring is centred on the same point as the dot — otherwise the dot would sit
        // off-centre inside its own ring, which looks like a rendering bug. Note what that means:
        // the ring's CENTRE is filtered while its RADIUS is the raw reported accuracy. That is
        // the right split. The radius is the claim about uncertainty and stays untouched; the
        // centre is cosmetic, and PositionSmoothing guarantees it never leaves the disc the raw
        // fix already permitted. Do not "fix" the ring to track the raw fix — that reintroduces
        // the off-centre dot and gains no honesty.
        val drawAt = smoothed ?: fix.position
        val accuracy = fix.accuracyMeters
        val r0 = if (accuracy > 0.0 && accuracy <= NavigationState.REJECT_ACCURACY_M) {
            MapMarker.accuracyRadiusAtZoomZero(accuracy, drawAt.lat)
        } else {
            // Either no accuracy at all, or one this app would not have accepted anyway. Drawing
            // a circle here would assert a precision nothing supports.
            0.0
        }
        return feature(drawAt, JSONObject().put("r0", r0))
    }

    fun destination(target: LatLon?, name: String?): String {
        if (target == null) return EMPTY
        return feature(target, JSONObject().put("name", name.orEmpty()))
    }

    /**
     * The public layer: one feature per point somebody else chose to share.
     *
     * `pid` rides in the properties so the tap handler can identify the point without a second
     * lookup; `name` feeds the label layer's text-field.
     */
    fun shared(points: List<SharedPoint>): String {
        if (points.isEmpty()) return EMPTY
        val features = JSONArray()
        points.forEach { p ->
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put(
                        "properties",
                        JSONObject()
                            .put("name", p.name)
                            .put("pid", p.id),
                    )
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Point")
                            // GeoJSON is lon,lat — see the note in [feature].
                            .put("coordinates", JSONArray().put(p.position.lon).put(p.position.lat)),
                    ),
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }

    private fun feature(p: LatLon, properties: JSONObject): String =
        JSONObject()
            .put("type", "FeatureCollection")
            .put(
                "features",
                JSONArray().put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", properties)
                        .put(
                            "geometry",
                            JSONObject()
                                .put("type", "Point")
                                // GeoJSON is lon,lat — the reverse of how this app writes
                                // coordinates everywhere else, and a classic silent swap.
                                .put("coordinates", JSONArray().put(p.lon).put(p.lat)),
                        ),
                ),
            )
            .toString()
}
