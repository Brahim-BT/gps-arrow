package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four notations the position band offers.
 *
 * The property that matters is not what each one looks like — that is covered by the MGRS,
 * plus-code and format tests — but that **whatever we put in front of the user, and on their
 * clipboard, this app can read back**. A coordinate the app displays but cannot parse is a trap:
 * the user copies it, pastes it into the Add-point field, and gets nothing.
 */
class CoordinateFormatCycleTest {

    /** Deployment-region points plus two that stress the grid systems. */
    private val places = listOf(
        "Casablanca" to LatLon(33.5731, -7.5898),
        "Nouakchott" to LatLon(18.0858, -15.9785),
        "Nema" to LatLon(16.6089, -7.2568),
        "eastern desert" to LatLon(22.5, -5.5),
        "prime meridian" to LatLon(51.4779, 0.0),
        "southern hemisphere" to LatLon(-33.8688, 151.2093),
    )

    @Test
    fun `cycling visits every notation once and returns to the start`() {
        var f = CoordinateFormat.DECIMAL
        val seen = mutableListOf(f)
        repeat(CoordinateFormat.entries.size - 1) {
            f = f.next()
            seen += f
        }
        assertEquals(CoordinateFormat.entries.toList(), seen)
        // One more tap comes back round.
        assertEquals(CoordinateFormat.DECIMAL, f.next())
    }

    @Test
    fun `every notation renders something at every place`() {
        places.forEach { (name, position) ->
            CoordinateFormat.entries.forEach { format ->
                val rendered = format.render(position)
                assertTrue("$format at $name was blank", rendered.isNotBlank())
                // No directional isolates and no stray whitespace: this string goes on the
                // clipboard, and U+2066/U+2069 would corrupt it in the receiving app.
                assertTrue("$format at $name carried an isolate", rendered.none { it == '\u2066' || it == '\u2069' })
                assertEquals("$format at $name was not trimmed", rendered.trim(), rendered)
            }
        }
    }

    @Test
    fun `everything the app displays, the app can read back`() {
        places.forEach { (name, position) ->
            CoordinateFormat.entries.forEach { format ->
                val rendered = format.render(position)
                val parsed = DestinationParser.parse(rendered, position)
                assertTrue(
                    "$format at $name did not parse: $rendered -> $parsed",
                    parsed is ParseResult.Success,
                )
                val back = (parsed as ParseResult.Success).position
                val error = Geo.distanceMeters(position, back)
                // 5 m covers all four. Three are grid systems and cannot be exact by
                // construction: an 11-character plus code is a 3.5 m by 2.8 m cell read at its
                // centre, MGRS at five digits is a 1 m square, and decimal and DMS land well
                // under a metre. Measured worst case across these places is 2.2 m. All of it is
                // far tighter than any GNSS fix.
                //
                // The plus code length matters here: at `encode`'s 10-character default the
                // worst case is 9.5 m and this assertion fails, which is why
                // CoordinateFormat.PLUS_CODE_LENGTH exists.
                assertTrue(
                    "$format at $name round-tripped $error m away via $rendered",
                    error < 5.0,
                )
            }
        }
    }

    @Test
    fun `MGRS falls back rather than hiding the position where it is undefined`() {
        // MGRS is undefined above 84N and below 80S. The band must still show the position.
        val arctic = LatLon(87.0, 20.0)
        assertEquals(null, Mgrs.toMgrs(arctic, spaced = true))
        val rendered = CoordinateFormat.MGRS.render(arctic)
        assertEquals(Format.decimal(arctic), rendered)
        assertTrue(rendered.isNotBlank())
    }

    @Test
    fun `the notation order runs towards something you can say out loud`() {
        // Decimal first because it is the most familiar, plus code and MGRS last because they are
        // what actually survives a bad radio link. If someone reorders these, the cycle stops
        // being a progression and this says so.
        assertEquals(CoordinateFormat.DECIMAL, CoordinateFormat.entries.first())
        assertEquals(CoordinateFormat.MGRS, CoordinateFormat.entries.last())
        assertTrue(
            CoordinateFormat.PLUS_CODE.ordinal > CoordinateFormat.DMS.ordinal,
        )
    }
}
