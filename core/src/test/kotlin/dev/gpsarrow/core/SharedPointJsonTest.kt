package dev.gpsarrow.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared-point wire format, both directions.
 *
 * The feed parser is driven through the shapes the Realtime Database REST API actually
 * produces — including the two degenerate ones (`"null"` for an empty database, and entries a
 * bad client somehow wrote) — because a parser that blanks the whole layer over one malformed
 * row turns a map into an outage.
 */
class SharedPointJsonTest {

    @Test
    fun `publishBodyCarriesExactlyTheOptedInFields`() {
        val body = JSONObject(
            SharedPointJson.encodeForPublish(
                SharedPoint(
                    id = "id-1",
                    name = "The well",
                    position = LatLon(18.0735, -16.0289),
                    note = "bring rope",
                    createdAtMillis = 1_720_000_000_000,
                ),
                deviceId = "device-7",
            ),
        )

        assertEquals("The well", body.getString("name"))
        assertEquals(18.0735, body.getDouble("lat"), 1e-12)
        assertEquals(-16.0289, body.getDouble("lon"), 1e-12)
        assertEquals("bring rope", body.getString("note"))
        assertEquals(1_720_000_000_000L, body.getLong("createdAt"))
        assertEquals("device-7", body.getString("deviceId"))
    }

    /** A null note is OMITTED, not written as JSON null — the rules reject nothing either way,
     *  but absent is what "no note" means everywhere else in this app's files. */
    @Test
    fun `publishBodyOmitsAnAbsentNote`() {
        val body = JSONObject(
            SharedPointJson.encodeForPublish(
                SharedPoint(
                    id = "id-1",
                    name = "North dune",
                    position = LatLon(20.0, -10.0),
                    createdAtMillis = 1L,
                ),
                deviceId = "d",
            ),
        )
        assertTrue(!body.has("note"))
    }

    @Test
    fun `roundTripThroughTheFeedKeepsNamesAndCoordinatesExact`() {
        // A name with quotes, backslashes, newlines and non-Latin text — every class of escape
        // the JSON string format has.
        val nasty = "Wells \"old\" \\new\\ ماء\nline2"
        val point = SharedPoint(
            id = "point/with odd;chars",
            name = nasty,
            position = LatLon(-33.8568123, 151.2153001),
            note = null,
            createdAtMillis = 99L,
        )
        val feed = JSONObject()
            .put(point.id, JSONObject(SharedPointJson.encodeForPublish(point, "d")))
            .toString()

        val parsed = SharedPointJson.decodeFeed(feed)

        assertEquals(listOf(point), parsed)
        assertNull(parsed.single().note)
    }

    @Test
    fun `anEmptyDatabaseComesBackAsTheLiteralNull`() {
        assertTrue(SharedPointJson.decodeFeed("null").isEmpty())
        assertTrue(SharedPointJson.decodeFeed("").isEmpty())
    }

    @Test
    fun `malformedEntriesAreSkippedNotFatal`() {
        val feed = """
            {
              "good": {"name": "Cairn", "lat": 21.0, "lon": -11.0, "createdAt": 5},
              "noCoords": {"name": "ghost"},
              "nanLat": {"name": "x", "lat": "north-ish", "lon": 0.0},
              "noName": {"lat": 1.0, "lon": 2.0},
              "notAnObject": 42,
              "blankName": {"name": "   ", "lat": 1.0, "lon": 2.0},
              "alsoGood": {"name": "Second", "lat": 22.0, "lon": -12.0}
            }
        """.trimIndent()

        val parsed = SharedPointJson.decodeFeed(feed)

        // Key order in a JSON object is not meaningful, so the survivors are compared as a set.
        assertEquals(setOf("good", "alsoGood"), parsed.map { it.id }.toSet())
    }

    @Test
    fun `garbageYieldsEmptyRatherThanThrowing`() {
        assertTrue(SharedPointJson.decodeFeed("<html>gateway timeout</html>").isEmpty())
        assertTrue(SharedPointJson.decodeFeed("[1,2,3]").isEmpty())
    }
}
