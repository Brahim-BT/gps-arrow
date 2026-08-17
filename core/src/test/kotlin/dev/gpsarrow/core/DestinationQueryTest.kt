package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationQueryTest {

    private val here = LatLon(48.8566, 2.3522)     // Paris

    private fun d(
        id: String,
        name: String,
        lat: Double = 48.0,
        lon: Double = 2.0,
        created: Long = 0L,
        used: Long? = null,
        favourite: Boolean = false,
        note: String? = null,
    ) = Destination(
        id = id,
        name = name,
        position = LatLon(lat, lon),
        note = note,
        createdAtMillis = created,
        isFavourite = favourite,
        lastUsedAtMillis = used,
    )

    // Paris, then Brussels (~260 km), then Berlin (~880 km).
    private val paris = d("p", "Paris", 48.8566, 2.3522, created = 300, used = 10)
    private val brussels = d("b", "Brussels", 50.8503, 4.3517, created = 200, used = 30)
    private val berlin = d("z", "Berlin", 52.52, 13.405, created = 100, used = null)
    private val all = listOf(berlin, paris, brussels)

    private fun ids(list: List<Destination>) = list.map { it.id }

    // ---------------------------------------------------------------- sorting

    @Test
    fun `name ascending and descending`() {
        assertEquals(
            listOf("z", "b", "p"),
            ids(DestinationQuery.apply(all, sort = DestinationSort.NAME_ASC)),
        )
        assertEquals(
            listOf("p", "b", "z"),
            ids(DestinationQuery.apply(all, sort = DestinationSort.NAME_DESC)),
        )
    }

    @Test
    fun `distance nearest and farthest`() {
        assertEquals(
            listOf("p", "b", "z"),
            ids(DestinationQuery.apply(all, sort = DestinationSort.DISTANCE_NEAREST, origin = here)),
        )
        assertEquals(
            listOf("z", "b", "p"),
            ids(
                DestinationQuery.apply(
                    all, sort = DestinationSort.DISTANCE_FARTHEST, origin = here,
                ),
            ),
        )
    }

    /** The wrinkle: no fix means no distance. It must degrade visibly, not silently. */
    @Test
    fun `distance sort with no position falls back to name order`() {
        for (sort in listOf(DestinationSort.DISTANCE_NEAREST, DestinationSort.DISTANCE_FARTHEST)) {
            assertEquals(
                "$sort should fall back to A-Z with a null origin",
                ids(DestinationQuery.apply(all, sort = DestinationSort.NAME_ASC)),
                ids(DestinationQuery.apply(all, sort = sort, origin = null)),
            )
            assertEquals(DestinationSort.NAME_ASC, DestinationQuery.effectiveSort(sort, null))
        }
    }

    @Test
    fun `distance sort with no position does not crash on an empty list`() {
        assertEquals(
            emptyList<String>(),
            ids(DestinationQuery.apply(emptyList(), sort = DestinationSort.DISTANCE_NEAREST)),
        )
    }

    @Test
    fun `only distance orders need a position`() {
        assertTrue(DestinationSort.DISTANCE_NEAREST.needsPosition)
        assertTrue(DestinationSort.DISTANCE_FARTHEST.needsPosition)
        listOf(
            DestinationSort.NAME_ASC, DestinationSort.NAME_DESC,
            DestinationSort.ADDED_NEWEST, DestinationSort.ADDED_OLDEST,
            DestinationSort.RECENTLY_USED,
        ).forEach { assertFalse("$it", it.needsPosition) }
        // ...and those are unaffected by a null origin.
        assertEquals(
            DestinationSort.RECENTLY_USED,
            DestinationQuery.effectiveSort(DestinationSort.RECENTLY_USED, null),
        )
    }

    @Test
    fun `date added newest and oldest`() {
        assertEquals(
            listOf("p", "b", "z"),
            ids(DestinationQuery.apply(all, sort = DestinationSort.ADDED_NEWEST)),
        )
        assertEquals(
            listOf("z", "b", "p"),
            ids(DestinationQuery.apply(all, sort = DestinationSort.ADDED_OLDEST)),
        )
    }

    @Test
    fun `recently used puts never-used points last`() {
        assertEquals(
            listOf("b", "p", "z"),
            ids(DestinationQuery.apply(all, sort = DestinationSort.RECENTLY_USED)),
        )
    }

    @Test
    fun `sort is total so equal keys never shuffle`() {
        val tied = listOf(d("2", "Same", created = 5), d("1", "Same", created = 5))
        repeat(5) {
            assertEquals(
                listOf("1", "2"),
                ids(DestinationQuery.apply(tied, sort = DestinationSort.ADDED_NEWEST)),
            )
        }
    }

    // ---------------------------------------------------------------- filtering

    @Test
    fun `search is case insensitive and incremental`() {
        listOf("b", "br", "BRU", "brussels").forEach { q ->
            assertEquals("query '$q'", listOf("b"), ids(DestinationQuery.apply(all, query = q)))
        }
    }

    @Test
    fun `search is accent insensitive both ways`() {
        val cafe = listOf(d("1", "Café de Flore"), d("2", "Zoo"))
        assertEquals(listOf("1"), ids(DestinationQuery.apply(cafe, query = "cafe")))
        assertEquals(listOf("1"), ids(DestinationQuery.apply(cafe, query = "CAFÉ")))

        val plain = listOf(d("1", "Cafe de Flore"))
        assertEquals(listOf("1"), ids(DestinationQuery.apply(plain, query = "café")))
    }

    @Test
    fun `search also covers the note`() {
        val withNote = listOf(d("1", "Point A", note = "blue gate"), d("2", "Point B"))
        assertEquals(listOf("1"), ids(DestinationQuery.apply(withNote, query = "gate")))
    }

    @Test
    fun `blank query matches everything`() {
        assertEquals(3, DestinationQuery.apply(all, query = "   ").size)
        assertEquals(3, DestinationQuery.apply(all, query = "").size)
    }

    @Test
    fun `no match returns empty rather than everything`() {
        assertTrue(DestinationQuery.apply(all, query = "zzzz").isEmpty())
    }

    @Test
    fun `favourites filter combines with search`() {
        val list = listOf(
            d("1", "Camp north", favourite = true),
            d("2", "Camp south", favourite = false),
            d("3", "Summit", favourite = true),
        )
        assertEquals(listOf("1", "3"), ids(DestinationQuery.apply(list, favouritesOnly = true)))
        assertEquals(
            listOf("1"),
            ids(DestinationQuery.apply(list, query = "camp", favouritesOnly = true)),
        )
    }

    @Test
    fun `filtering happens before sorting`() {
        val result = DestinationQuery.apply(
            all, query = "e", sort = DestinationSort.DISTANCE_NEAREST, origin = here,
        )
        // "Berlin" and "Brussels" contain 'e'; "Paris" does not.
        assertEquals(listOf("b", "z"), ids(result))
    }

    @Test
    fun `fold handles the cases the UI relies on`() {
        assertEquals("cafe", DestinationQuery.fold("  CAFÉ  "))
        assertEquals("uber", DestinationQuery.fold("Über"))
        assertEquals("", DestinationQuery.fold("   "))
    }

    @Test
    fun `persisted sort name round trips and bad input falls back`() {
        DestinationSort.entries.forEach {
            assertEquals(it, DestinationSort.fromName(it.name))
        }
        assertEquals(DestinationSort.DEFAULT, DestinationSort.fromName(null))
        assertEquals(DestinationSort.DEFAULT, DestinationSort.fromName("NOT_A_SORT"))
    }
}
