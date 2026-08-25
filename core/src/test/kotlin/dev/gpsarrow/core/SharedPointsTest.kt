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

    /**
     * Publishing is one multi-path `PATCH` whose keys are `sharedPoints/<id>` and
     * `owners/<id>`, so a character that ends a path segment does not merely look untidy — it
     * sends half the write somewhere else and breaks the atomicity the whole ownership scheme
     * rests on.
     */
    @Test
    fun `idsThatWouldEscapeTheirPathAreRefused`() {
        assertTrue(SharedPoints.isPublishableId("2f6d1e0c-9a44-4c1b-b0e6-5f7a8d9c1234"))
        // The Firebase-illegal punctuation, and the control range at both ends of it. Written
        // as escapes: a raw NUL byte in a source file is invisible to whoever reviews it.
        listOf("a/b", "a.b", "a\$b", "a#b", "a[b", "a]b", "a\u0000b", "a\u007Fb", "a\nb")
            .forEach { assertFalse(it, SharedPoints.isPublishableId(it)) }
        // A space is legal in a database key, so the guard must not sweep it up as well.
        assertTrue(SharedPoints.isPublishableId("well by the road"))
    }

    // ---------------------------------------------------------------- the wire form

    /**
     * The narrowing that is the privacy policy, asserted rather than described.
     *
     * [SharedPoint] has no field for the star, the last-used stamp, the fix accuracy or the
     * share intent, so this cannot fail while the types hold — which is the point: if somebody
     * widens the wire format one day, this test is where the argument for it has to be made.
     */
    @Test
    fun `theWireFormCarriesOnlyWhatTheOwnerOptedInTo`() {
        val saved = Destination(
            id = "id-1",
            name = "The well",
            position = LatLon(18.0735, -16.0289),
            note = "bring rope",
            createdAtMillis = 7L,
            source = "manual",
            isFavourite = true,
            lastUsedAtMillis = 99L,
            accuracyMeters = 4.5f,
            shareIntent = ShareIntent.SHARED,
        )

        val wire = SharedPoints.wireFormOf(saved)

        assertEquals(
            SharedPoint("id-1", "The well", LatLon(18.0735, -16.0289), "bring rope", 7L),
            wire,
        )
    }

    // ---------------------------------------------------------------- what may be claimed

    private val local = SharedPoint("a", "The well", LatLon(18.0735, -16.0289), "bring rope", 7L)

    @Test
    fun `withNoCacheNothingHasBeenObserved`() {
        assertEquals(
            ShareObservation.NEVER_LOOKED,
            SharedPoints.observationOf(published = local, local = local, cachedAtMillis = null),
        )
    }

    @Test
    fun `aCachedFeedDistinguishesPresentFromAbsent`() {
        assertEquals(
            ShareObservation.PRESENT_MATCHING,
            SharedPoints.observationOf(local, local, cachedAtMillis = 1L),
        )
        assertEquals(
            ShareObservation.ABSENT,
            SharedPoints.observationOf(null, local, cachedAtMillis = 1L),
        )
    }

    /** Each published field on its own, so a comparison that quietly stops covering one shows. */
    @Test
    fun `anEditToAnyPublishedFieldReadsAsStale`() {
        listOf(
            local.copy(name = "The other well"),
            local.copy(position = LatLon(18.0736, -16.0289)),
            local.copy(position = LatLon(18.0735, -16.0290)),
            local.copy(note = "bring two ropes"),
            local.copy(note = null),
        ).forEach { published ->
            assertEquals(
                published.toString(),
                ShareObservation.PRESENT_STALE,
                SharedPoints.observationOf(published, local, cachedAtMillis = 1L),
            )
        }
    }

    /**
     * The id is what the two sides are matched BY, and `createdAt` is never edited. Including
     * either in the comparison would make points stale for ever rather than never.
     */
    @Test
    fun `idAndCreatedAtAreNotPartOfTheComparison`() {
        assertTrue(SharedPoints.publishedMatches(local.copy(id = "different"), local))
        assertTrue(SharedPoints.publishedMatches(local.copy(createdAtMillis = 999L), local))
    }

    /**
     * A blank note and no note are the same thing. If they were not, a point holding `""` would
     * be stale against its own published copy for ever: every sync would queue an edit, the edit
     * would change nothing, and the row would say "not published yet" permanently.
     */
    @Test
    fun `aBlankNoteAndNoNoteAreTheSameThing`() {
        val blank = local.copy(note = "")
        val spaces = local.copy(note = "   ")
        val absent = local.copy(note = null)
        assertTrue(SharedPoints.publishedMatches(absent, blank))
        assertTrue(SharedPoints.publishedMatches(blank, absent))
        assertTrue(SharedPoints.publishedMatches(spaces, absent))
        assertEquals(
            ShareObservation.PRESENT_MATCHING,
            SharedPoints.observationOf(absent, blank, cachedAtMillis = 1L),
        )
    }

    /**
     * The whole table, spelled out. It is twelve rows and it is the entire honesty argument of
     * this feature, so it is pinned rather than described.
     */
    @Test
    fun `everyIntentAndObservationPairMapsToOneClaim`() {
        val expected = mapOf(
            (ShareIntent.PRIVATE to ShareObservation.PRESENT_MATCHING) to ShareStatus.NOT_SHARED,
            (ShareIntent.PRIVATE to ShareObservation.PRESENT_STALE) to ShareStatus.NOT_SHARED,
            (ShareIntent.PRIVATE to ShareObservation.ABSENT) to ShareStatus.NOT_SHARED,
            (ShareIntent.PRIVATE to ShareObservation.NEVER_LOOKED) to ShareStatus.NOT_SHARED,

            (ShareIntent.SHARED to ShareObservation.PRESENT_MATCHING) to ShareStatus.PUBLISHED,
            (ShareIntent.SHARED to ShareObservation.PRESENT_STALE) to ShareStatus.EDIT_UNPUBLISHED,
            (ShareIntent.SHARED to ShareObservation.ABSENT) to ShareStatus.PUBLISH_UNCONFIRMED,
            (ShareIntent.SHARED to ShareObservation.NEVER_LOOKED) to ShareStatus.PUBLISH_UNCONFIRMED,

            (ShareIntent.WITHDRAWN to ShareObservation.PRESENT_MATCHING) to ShareStatus.STILL_PUBLIC,
            (ShareIntent.WITHDRAWN to ShareObservation.PRESENT_STALE) to ShareStatus.STILL_PUBLIC,
            (ShareIntent.WITHDRAWN to ShareObservation.ABSENT) to ShareStatus.NOT_SHARED,
            (ShareIntent.WITHDRAWN to ShareObservation.NEVER_LOOKED) to
                ShareStatus.WITHDRAWAL_UNCONFIRMED,
        )

        // Asserting over the cross product rather than over `expected` — a missing row would
        // otherwise pass by not being looked at, which is this project's most repeated bug.
        assertEquals(
            ShareIntent.values().size * ShareObservation.values().size,
            expected.size,
        )
        ShareIntent.values().forEach { intent ->
            ShareObservation.values().forEach { observation ->
                assertEquals(
                    "$intent x $observation",
                    expected[intent to observation],
                    SharedPoints.statusOf(intent, observation),
                )
            }
        }
    }

    /**
     * The row that matters most, called out on its own so a future edit to the table above
     * cannot quietly relax it: a user who has never been online taps the switch off, and the
     * app must not tell them it worked.
     */
    @Test
    fun `anOfflineWithdrawalIsNeverReportedAsDone`() {
        assertEquals(
            ShareStatus.WITHDRAWAL_UNCONFIRMED,
            SharedPoints.statusOf(
                ShareIntent.WITHDRAWN,
                SharedPoints.observationOf(published = null, local = local, cachedAtMillis = null),
            ),
        )
    }

    /**
     * The offline edit, which is the same discipline one step along: a user with no signal
     * changes a published point and must be told the world still has the old one. It reads off
     * the CACHED feed, so it is true immediately and stays true until a fetch says otherwise.
     */
    @Test
    fun `anOfflineEditIsReportedAsUnpublishedFromTheCachedFeedAlone`() {
        val cached = local                          // what the last fetch saw
        val edited = local.copy(note = null)        // what the user has just done, offline

        assertEquals(
            ShareStatus.EDIT_UNPUBLISHED,
            SharedPoints.statusOf(
                ShareIntent.SHARED,
                SharedPoints.observationOf(cached, edited, cachedAtMillis = 1L),
            ),
        )
    }

    // ---------------------------------------------------------------- what the switch means

    @Test
    fun `turningTheSwitchOffOnlyWithdrawsSomethingThatWasOffered`() {
        assertEquals(
            ShareIntent.PRIVATE,
            SharedPoints.intentAfterSwitch(ShareIntent.PRIVATE, sharePublicly = false),
        )
        assertEquals(
            ShareIntent.WITHDRAWN,
            SharedPoints.intentAfterSwitch(ShareIntent.SHARED, sharePublicly = false),
        )
        assertEquals(
            ShareIntent.WITHDRAWN,
            SharedPoints.intentAfterSwitch(ShareIntent.WITHDRAWN, sharePublicly = false),
        )
    }

    @Test
    fun `turningTheSwitchOnAlwaysMeansShared`() {
        ShareIntent.values().forEach {
            assertEquals(ShareIntent.SHARED, SharedPoints.intentAfterSwitch(it, sharePublicly = true))
        }
    }

    @Test
    fun `anUnreadableStoredIntentReadsAsPrivate`() {
        assertEquals(ShareIntent.SHARED, ShareIntent.fromStoredName("SHARED"))
        assertEquals(ShareIntent.WITHDRAWN, ShareIntent.fromStoredName("WITHDRAWN"))
        assertEquals(ShareIntent.PRIVATE, ShareIntent.fromStoredName(null))
        assertEquals(ShareIntent.PRIVATE, ShareIntent.fromStoredName(""))
        assertEquals(ShareIntent.PRIVATE, ShareIntent.fromStoredName("shared"))
        assertEquals(ShareIntent.PRIVATE, ShareIntent.fromStoredName("SOMETHING_NEWER"))
    }

    /** Only one pair may produce the certain claim. */
    @Test
    fun `publishedIsReachableOnlyByHavingSeenThePointInAFeed`() {
        val certain = ShareIntent.values().flatMap { intent ->
            ShareObservation.values().map { observation -> intent to observation }
        }.filter { (intent, observation) ->
            SharedPoints.statusOf(intent, observation) == ShareStatus.PUBLISHED
        }
        assertEquals(listOf(ShareIntent.SHARED to ShareObservation.PRESENT_MATCHING), certain)
    }
}
