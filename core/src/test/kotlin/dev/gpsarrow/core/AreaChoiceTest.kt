package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which area serves the map where two overlap.
 *
 * The boxes here are the real shipped ones, and the positions are real places in the overlap
 * band — Dakhla in particular, because that is where a user actually was when both indicators
 * claimed to cover them.
 */
class AreaChoiceTest {

    private fun morocco(p: LatLon) = AreaChoice.Candidate(
        id = "morocco", maxZoom = 13,
        west = -17.10, south = 20.77, east = -0.99, north = 35.95,
        containsPosition = p.lat in 20.77..35.95 && p.lon in -17.10..-0.99,
    )

    private fun mauritania(p: LatLon) = AreaChoice.Candidate(
        id = "mauritania", maxZoom = 13,
        west = -17.07, south = 14.72, east = -4.80, north = 27.30,
        containsPosition = p.lat in 14.72..27.30 && p.lon in -17.07..-4.80,
    )

    private fun both(p: LatLon) = listOf(morocco(p), mauritania(p))

    private val dakhla = LatLon(23.6848, -15.9580)
    private val casablanca = LatLon(33.5731, -7.5898)
    private val nouakchott = LatLon(18.0735, -15.9582)

    @Test
    fun `outside every area, nothing serves`() {
        val paris = LatLon(48.8566, 2.3522)
        assertNull(AreaChoice.serving(paris, both(paris)))
    }

    @Test
    fun `with no fix, nothing serves`() {
        assertNull(AreaChoice.serving(null, both(dakhla)))
    }

    @Test
    fun `where only one contains the position, it serves`() {
        assertEquals("morocco", AreaChoice.serving(casablanca, both(casablanca)))
        assertEquals("mauritania", AreaChoice.serving(nouakchott, both(nouakchott)))
    }

    /**
     * The case that prompted the rule. Both boxes contain Dakhla; the answer must be stable and
     * must be the area whose place list actually names Dakhla.
     */
    @Test
    fun `in the overlap band the northern area serves`() {
        assertTrue("both must contain Dakhla for this test to mean anything",
            both(dakhla).all { it.containsPosition })
        assertEquals("morocco", AreaChoice.serving(dakhla, both(dakhla)))
    }

    /** Order of the candidate list must not change the answer — that was the old bug. */
    @Test
    fun `the answer does not depend on catalogue order`() {
        val forward = AreaChoice.serving(dakhla, listOf(morocco(dakhla), mauritania(dakhla)))
        val reversed = AreaChoice.serving(dakhla, listOf(mauritania(dakhla), morocco(dakhla)))
        assertEquals(forward, reversed)
    }

    /** Detail beats geometry: a deeper archive wins even from the far edge of its box. */
    @Test
    fun `higher maxzoom wins outright`() {
        val coarse = morocco(dakhla).copy(maxZoom = 12)
        val detailed = mauritania(dakhla).copy(maxZoom = 14)
        assertEquals("mauritania", AreaChoice.serving(dakhla, listOf(coarse, detailed)))
    }

    @Test
    fun `edge margin is the distance to the nearest boundary`() {
        val m = AreaChoice.edgeMarginMeters(dakhla, morocco(dakhla))
        // Dakhla sits ~1.14 deg east of the western edge at -17.10; nothing else is closer.
        assertTrue("expected roughly 100-130 km, got ${m / 1000} km", m in 100_000.0..130_000.0)
    }

    /** A position dead centre of one box must prefer it over one whose edge it is hugging. */
    @Test
    fun `deeper inside wins the tie`() {
        val p = LatLon(28.36, -9.04)          // the Morocco box centre
        val hugging = AreaChoice.Candidate(
            id = "sliver", maxZoom = 13,
            west = -9.05, south = 28.35, east = -9.03, north = 28.37,
            containsPosition = true,
        )
        assertEquals("morocco", AreaChoice.serving(p, listOf(morocco(p), hugging)))
    }
}
