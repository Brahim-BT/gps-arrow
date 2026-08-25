package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an edit may change and what it must carry over.
 *
 * The first test is the regression: editing a point used to wipe its note, because the store's
 * `update` took a defaulted `note` parameter and the editor — which has no note field — never
 * passed one. Every field the editor does not present is checked here, not just the note, since
 * the bug was the *shape* rather than the one field it happened to hit.
 */
class DestinationEditTest {

    private val original = Destination(
        id = "id-1",
        name = "The well",
        position = LatLon(18.0735, -16.0289),
        note = "water here in winter",
        createdAtMillis = 1_720_000_000_000,
        source = "current position",
        isFavourite = true,
        lastUsedAtMillis = 1_720_000_500_000,
        accuracyMeters = 4.5f,
    )

    // ---------------------------------------------------------------- carry-over

    @Test
    fun `editingNameAndPositionKeepsTheNote`() {
        val edited = DestinationEdit.applied(original, "The north well", LatLon(18.10, -16.00))
        assertEquals("water here in winter", edited.note)
    }

    @Test
    fun `anEditCarriesOverEveryFieldTheEditorDoesNotShow`() {
        val edited = DestinationEdit.applied(original, "Renamed", LatLon(1.0, 2.0))

        assertEquals(original.id, edited.id)
        assertEquals(original.note, edited.note)
        assertEquals(original.createdAtMillis, edited.createdAtMillis)
        assertEquals(original.source, edited.source)
        assertEquals(original.isFavourite, edited.isFavourite)
        assertEquals(original.lastUsedAtMillis, edited.lastUsedAtMillis)
    }

    /** A point with no note must not gain one, either. */
    @Test
    fun `anAbsentNoteStaysAbsent`() {
        val noNote = original.copy(note = null)
        assertNull(DestinationEdit.applied(noNote, "Renamed", LatLon(1.0, 2.0)).note)
    }

    // ---------------------------------------------------------------- what an edit does change

    @Test
    fun `nameAndPositionAreApplied`() {
        val moved = LatLon(20.0, -12.0)
        val edited = DestinationEdit.applied(original, "New name", moved)
        assertEquals("New name", edited.name)
        assertEquals(moved, edited.position)
    }

    @Test
    fun `aBlankNameKeepsTheOldOne`() {
        assertEquals("The well", DestinationEdit.applied(original, "", original.position).name)
        assertEquals("The well", DestinationEdit.applied(original, "   ", original.position).name)
    }

    // ---------------------------------------------------------------- the accuracy rule
    //
    // A badge that outlives the fix it describes is a confident wrong answer, which is the one
    // thing this app must never show.

    @Test
    fun `accuracySurvivesAnEditThatDoesNotMoveThePoint`() {
        val renamed = DestinationEdit.applied(original, "Renamed", original.position)
        assertEquals(4.5f, renamed.accuracyMeters!!, 1e-6f)
    }

    @Test
    fun `accuracyIsDroppedTheMomentThePositionChanges`() {
        // One ten-thousandth of a degree — about 11 m, and far more than a 4.5 m badge claims.
        val nudged = LatLon(original.position.lat + 0.0001, original.position.lon)
        assertNull(DestinationEdit.applied(original, "The well", nudged).accuracyMeters)
    }

    @Test
    fun `aPointWithNoAccuracyNeverGainsOne`() {
        val typed = original.copy(accuracyMeters = null)
        assertNull(DestinationEdit.applied(typed, "x", typed.position).accuracyMeters)
    }

    /** Editing nothing at all is a no-op, not a quiet demotion of the point's provenance. */
    @Test
    fun `anEditThatChangesNothingReturnsAnEqualPoint`() {
        assertTrue(
            DestinationEdit.applied(original, original.name, original.position) == original,
        )
    }
}
