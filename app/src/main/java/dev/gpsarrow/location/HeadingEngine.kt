package dev.gpsarrow.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.gpsarrow.core.CircularSmoother
import dev.gpsarrow.core.Geo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Device heading from the rotation-vector sensor.
 *
 * TYPE_ROTATION_VECTOR fuses accelerometer + gyroscope + magnetometer, so it is far steadier
 * than hand-rolling `getRotationMatrix(accel, mag)`. Its azimuth is relative to **magnetic**
 * north; the caller adds declination for true north.
 *
 * TYPE_GAME_ROTATION_VECTOR is explicitly not used: it excludes the magnetometer, so its yaw
 * is relative and drifts, which is useless for an absolute bearing.
 */
class HeadingEngine(private val context: Context) {

    data class Reading(
        /** Degrees clockwise from magnetic north, `[0, 360)`. */
        val magneticDeg: Double,
        /** false once the magnetometer reports LOW accuracy — surface this, never hide it. */
        val reliable: Boolean,
        val hasCompass: Boolean,
    )

    private val sensorManager: SensorManager? =
        ContextCompat.getSystemService(context, SensorManager::class.java)

    fun hasCompass(): Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    /**
     * @param displayRotation `Display.rotation` — without remapping, the arrow is 90 degrees
     *   wrong in landscape, which is the classic compass bug.
     * @param alpha smoothing weight; lower is calmer but laggier.
     */
    fun readings(
        displayRotation: () -> Int,
        alpha: Double = 0.15,
    ): Flow<Reading> = callbackFlow {
        val sm = sensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) {
            trySend(Reading(0.0, reliable = false, hasCompass = false))
            awaitClose { }
            return@callbackFlow
        }

        val smoother = CircularSmoother(alpha)
        val rotationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var reliable = true

        val listener = object : SensorEventListener {
            // Anything thrown here lands on the main thread inside the sensor dispatch and
            // takes the whole process down, typically before the first frame is drawn.
            // The arrow is a best-effort readout; a bad sample must never be fatal.
            override fun onSensorChanged(event: SensorEvent) {
                runCatching { updateFrom(event) }.onFailure {
                    Log.w("HeadingEngine", "dropped a sensor sample", it)
                }
            }

            private fun updateFrom(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val (axisX, axisY) = when (displayRotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)

                val azimuth = Geo.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
                trySend(
                    Reading(
                        magneticDeg = smoother.update(azimuth),
                        reliable = reliable,
                        hasCompass = true,
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // UNRELIABLE or LOW means the magnetometer needs a figure-of-eight wave.
                reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            }
        }

        // SENSOR_DELAY_GAME is ~50 Hz. FASTEST just burns battery for an arrow a human reads.
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }

    companion object {
        /**
         * Display rotation, readable from ANY context including an Application context.
         *
         * Do not use `Context.getDisplay()` here. It throws
         * `UnsupportedOperationException: Tried to obtain display from a Context not associated
         * with one` on any non-visual context, and the context this engine is given comes from
         * a ViewModel, so it is always the Application context. `DisplayManager` has no such
         * restriction.
         *
         * Wrapped defensively as well: a wrong rotation only tilts the arrow by 90 degrees,
         * which is far better than taking the process down from a sensor callback.
         */
        fun displayRotationOf(context: Context): Int = runCatching {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                manager?.getDisplay(Display.DEFAULT_DISPLAY)
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
                    ?.defaultDisplay
            }
            display?.rotation ?: Surface.ROTATION_0
        }.getOrDefault(Surface.ROTATION_0)
    }
}
