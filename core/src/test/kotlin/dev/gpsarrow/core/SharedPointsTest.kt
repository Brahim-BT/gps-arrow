package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refresh timing, feed de-duplication and publish validation.
 *
 * Time is driven in absolute milliseconds like every other dwell in this project, because a
 * policy expressed in "how many times did we try" breaks the first time the map tab is opened
 * twice in a minute.
 */
class SharedPointsTest {

    private val refreshAfter = SharedPoints.REFRESH_AFTER_MILLIS

    // ---------------------------------------------------------------- refresh

    @Test
    fun `noCacheAtAllMeansAlwaysRefresh`() {
        assertTrue(SharedPoints.shouldRefresh(cachedAtMillis = null, nowMillis = 5_000L))
    }

    @Test
    fun `aFreshCacheIsNotRefetched`() {
        assertFalse(SharedPoints.shouldRefresh(cachedAtMillis = 1_000L, nowMillis = 1_000L + refreshAfter - 1))
    }

    @Test
    fun `theCacheExpiresExactlyAtTheDwell`() {
        assertTrue(SharedPoints.shouldRefresh(cachedAtMillis = 1_000L, nowMillis = 1_000L + refreshAfter))
    }

    /** A clock set backwards must read as fresh, never as infinitely stale. */
    @Test
    fun `timeRunningBackwardsDoesNotForceARefresh`() {
        assertFalse(SharedPoints.shouldRefresh(cachedAtMillis = 10_000L, nowMillis = 9_000L))
    }

    // ---------------------------------------------------------------- de-duplication

    @Test
    fun `locallyKnownIdsAreHiddenFromTheSharedLayer`() {
        val feed = listOf(
            SharedPoint("mine", "My copy", LatLon(0.0, 0.0)),
            SharedPoint("other", "Someone else's", LatLon(1.0, 1.0)),
        )
        val visible = SharedPoints.visibleFrom(feed, locallyKnownIds = setOf("mine"))
        assertEquals(listOf("other"), visible.map { it.id })
    }

    // ---------------------------------------------------------------- publish validation

    @Test
    fun `aWellFormedPointPublishes`() {
        assertTrue(
            SharedPoints.canPublish("id-1", "The well", 18.07, -16.03, note = null),
        )
    }

    @Test
    fun `nameBoundariesMatchTheServerRules`() {
        assertTrue(SharedPoints.canPublish("id", "x", 0.0, 0.0, null))
        val atLimit = "n".repeat(SharedPoints.MAX_NAME_CHARS)
        assertTrue(SharedPoints.canPublish("id", atLimit, 0.0, 0.0, null))
        val overLimit = "n".repeat(SharedPoints.MAX_NAME_CHARS + 1)
        assertFalse(SharedPoints.canPublish("id", overLimit, 0.0, 0.0, null))
        assertFalse(SharedPoints.canPublish("id", "", 0.0, 0.0, null))
        assertFalse(SharedPoints.canPublish("id", "   ", 0.0, 0.0, null))
    }

    @Test
    fun `noteBoundaryMatchesTheServerRules`() {
        val atLimit = "n".repeat(SharedPoints.MAX_NOTE_CHARS)
        assertTrue(SharedPoints.canPublish("id", "name", 0.0, 0.0, atLimit))
        val overLimit = "n".repeat(SharedPoints.MAX_NOTE_CHARS + 1)
        assertFalse(SharedPoints.canPublish("id", "name", 0.0, 0.0, overLimit))
    }

    @Test
    fun `coordinateRangesMatchTheServerRules`() {
        assertTrue(SharedPoints.canPublish("id", "n", -90.0, -180.0, null))
        assertTrue(SharedPoints.canPublish("id", "n", 90.0, 180.0, null))
        assertFalse(SharedPoints.canPublish("id", "n", 90.0001, 0.0, null))
        assertFalse(SharedPoints.canPublish("id", "n", 0.0, -180.0001, null))
        assertFalse(SharedPoints.canPublish("id", "n", Double.NaN, 0.0, null))
    }

    @Test
    fun `anUnusableIdNeverPublishes`() {
        assertFalse(SharedPoints.canPublish(null, "n", 0.0, 0.0, null))
        assertFalse(SharedPoints.canPublish("", "n", 0.0, 0.0, null))
        assertFalse(SharedPoints.canPublish(" ".repeat(129), "n", 0.0, 0.0, null))
    }
}
