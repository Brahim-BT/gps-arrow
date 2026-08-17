package dev.gpsarrow.location

import android.content.Context
import android.hardware.GeomagneticField
import android.util.Log
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.Wmm
import java.util.Calendar

/**
 * Magnetic declination — the correction from magnetic north (what the compass measures) to
 * true north (what a bearing to a coordinate is expressed in). Entirely offline: it's a
 * closed-form function of position and date.
 */
interface DeclinationProvider {
    /** Degrees east of true north. Add to a magnetic bearing to get a true bearing. */
    fun declinationDegrees(position: LatLon, altitudeMeters: Double): Double
    val sourceName: String
}

/**
 * Reads a NOAA/NGA `.COF` coefficient file from assets.
 *
 * Preferred over the framework because the model epoch is then under your control rather than
 * baked into whatever OS image the device shipped with. See README, "Magnetic declination".
 */
class AssetWmmDeclination private constructor(private val model: Wmm) : DeclinationProvider {

    override val sourceName: String get() = model.name

    override fun declinationDegrees(position: LatLon, altitudeMeters: Double): Double {
        val cal = Calendar.getInstance()
        val year = Wmm.decimalYearOf(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
        return model.declination(position, altitudeMeters, year)
    }

    /** True when today is inside the model's validity window. */
    fun isCurrent(): Boolean {
        val cal = Calendar.getInstance()
        return model.isValidFor(
            Wmm.decimalYearOf(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            ),
        )
    }

    companion object {
        const val ASSET_PATH = "geomag/WMM.COF"

        fun loadOrNull(context: Context): AssetWmmDeclination? = runCatching {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            Wmm.parse(text)?.let { AssetWmmDeclination(it) }
        }.getOrElse {
            Log.i("Declination", "No $ASSET_PATH in assets; falling back to the framework model.")
            null
        }
    }
}

/**
 * Fallback: the platform's own WMM implementation. Always available, but its coefficients ship
 * with the OS image, so on an old device the model epoch may be several years stale.
 */
class FrameworkDeclination : DeclinationProvider {

    override val sourceName: String get() = "Android GeomagneticField"

    override fun declinationDegrees(position: LatLon, altitudeMeters: Double): Double =
        GeomagneticField(
            position.lat.toFloat(),
            position.lon.toFloat(),
            altitudeMeters.toFloat(),
            System.currentTimeMillis(),
        ).declination.toDouble()
}

object Declination {
    /**
     * Picks the asset model when present and in date, otherwise the framework.
     * Cheap to construct; cache it for the process lifetime.
     */
    fun create(context: Context): DeclinationProvider {
        val asset = AssetWmmDeclination.loadOrNull(context)
        return if (asset != null && asset.isCurrent()) asset else FrameworkDeclination()
    }
}
