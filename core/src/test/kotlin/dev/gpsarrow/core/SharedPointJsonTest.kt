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
            ),
        )

        assertEquals("The well", body.getString("name"))
        assertEquals(18.0735, body.getDouble("lat"), 1e-12)
        assertEquals(-16.0289, body.getDouble("lon"), 1e-12)
        assertEquals("bring rope", body.getString("note"))
        assertEquals(1_720_000_000_000L, body.getLong("createdAt"))
        // Five keys, and no sixth. The point node is the privacy policy made concrete, so this
        // asserts the absence as hard as the presence: an owner id here would be world-readable.
        assertEquals(setOf("name", "lat", "lon", "note", "createdAt"), body.keys().asSequence().toSet())
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
            ),
        )
        assertTrue(!body.has("note"))
    }

    /**
     * The atomic pair. If these two paths could ever land separately, the failure mode is a
     * public point with no owner digest — visible to everyone and withdrawable by nobody.
     */
    @Test
    fun `publishPatchWritesThePointAndTheOwnerDigestAtTheirOwnPaths`() {
        val point = SharedPoint(
            id = "abc-123",
            name = "Camp",
            position = LatLon(31.5, -8.0),
            note = null,
            createdAtMillis = 7L,
        )

        val patch = JSONObject(SharedPointJson.encodePublishPatch(point, ownerHash = "deadbeef"))

        assertEquals(setOf("sharedPoints/abc-123", "owners/abc-123"), patch.keys().asSequence().toSet())
        assertEquals("deadbeef", patch.getString("owners/abc-123"))
        val node = patch.getJSONObject("sharedPoints/abc-123")
        assertEquals("Camp", node.getString("name"))
        assertEquals(31.5, node.getDouble("lat"), 1e-12)
        assertTrue(!node.has("note"))
    }

    /**
     * The queued edit carries the token and the four editable fields — and nothing else. In
     * particular no `createdAt`: the job applies this with a PATCH, and an edit must not be able
     * to rewrite when a point was created.
     */
    @Test
    fun `pendingEditCarriesTheTokenAndTheEditableFields`() {
        val body = JSONObject(
            SharedPointJson.encodePendingEdit(
                SharedPoint(
                    id = "abc-123",
                    name = "The well",
                    position = LatLon(18.0735, -16.0289),
                    note = "bring rope",
                    createdAtMillis = 1_720_000_000_000,
                ),
                token = "a".repeat(64),
            ),
        )

        assertEquals(setOf("t", "name", "lat", "lon", "note"), body.keys().asSequence().toSet())
        assertEquals("a".repeat(64), body.getString("t"))
        assertEquals("The well", body.getString("name"))
        assertEquals(18.0735, body.getDouble("lat"), 1e-12)
        assertEquals("bring rope", body.getString("note"))
    }

    /**
     * The one that would be silent if it were wrong. Removing a note is applied by the job as a
     * `PATCH`, and a PATCH that omits a key leaves the old value — so an absent note has to go
     * out as an explicit JSON null, or "delete my note" becomes the single edit that does
     * nothing while the app reports it as sent.
     */
    @Test
    fun `pendingEditWritesAnAbsentNoteAsNullRatherThanOmittingIt`() {
        listOf(null, "", "   ").forEach { note ->
            val body = JSONObject(
                SharedPointJson.encodePendingEdit(
                    SharedPoint("id-1", "North dune", LatLon(20.0, -10.0), note, 1L),
                    token = "b".repeat(64),
                ),
            )
            assertTrue("note=${note.toString()}", body.has("note"))
            assertTrue("note=${note.toString()}", body.isNull("note"))
        }
    }

    /**
     * The publish body does the opposite, and must: it OMITS a blank note. The reader turns a
     * blank note into null coming back in, so publishing `""` would give a point that never
     * compares equal to itself and reads as permanently edited-but-not-published.
     */
    @Test
    fun `publishBodyOmitsABlankNoteAsWellAsAnAbsentOne`() {
        listOf(null, "", "   ").forEach { note ->
            val body = JSONObject(
                SharedPointJson.encodeForPublish(
                    SharedPoint("id-1", "North dune", LatLon(20.0, -10.0), note, 1L),
                ),
            )
            assertTrue("note=${note.toString()}", !body.has("note"))
        }
    }

    @Test
    fun `roundTripThroughTheFeedKeepsNamesAndCoordinatesExact`() {
        // A name with quotes, backslashes, newlines and non-Latin text — every class of escape
        // the JSON string format has.
        val nasty = "Wells \"old\" \\new\\ ماء\nline2"
        // An id this app would refuse to publish — see SharedPoints.isPublishableId — because
        // the reader must tolerate whatever is already in the feed, not only what it writes.
        val point = SharedPoint(
            id = "point/with odd;chars",
            name = nasty,
            position = LatLon(-33.8568123, 151.2153001),
            note = null,
            createdAtMillis = 99L,
        )
        val feed = JSONObject()
            .put(point.id, JSONObject(SharedPointJson.encodeForPublish(point)))
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
