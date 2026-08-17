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
import kotlinx.coroutines.flow.conflate

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
        /** Which sensor path produced this, for the diagnostics panel. */
        val sensorName: String = "rotation vector",
        /** Unsmoothed value, so diagnostics can show whether the filter is the laggy part. */
        val rawMagneticDeg: Double = magneticDeg,
        /** Measured delivery rate. If this is far below 50 Hz, the filter is not the problem. */
        val sampleRateHz: Double = 0.0,
    )

    /** What the device actually offers, surfaced in diagnostics. */
    fun availableSensors(): String {
        val sm = sensorManager ?: return "no SensorManager"
        return listOfNotNull(
            "rotation-vector".takeIf { sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null },
            "accelerometer".takeIf { sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null },
            "magnetometer".takeIf { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null },
            "gyroscope".takeIf { sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null },
        ).joinToString(", ").ifEmpty { "none" }
    }

    private val sensorManager: SensorManager? =
        ContextCompat.getSystemService(context, SensorManager::class.java)

    fun hasCompass(): Boolean {
        val sm = sensorManager ?: return false
        if (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null) return true
        return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
    }

    /**
     * @param displayRotation `Display.rotation` — without remapping, the arrow is 90 degrees
     *   wrong in landscape, which is the classic compass bug.
     * @param alpha smoothing weight; lower is calmer but laggier.
     */
    fun readings(
        displayRotation: () -> Int,
        timeConstantSeconds: Double = SMOOTHING_TIME_CONSTANT_S,
    ): Flow<Reading> = callbackFlow {
        val sm = sensorManager
        val rotationVector = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Prefer the fused rotation vector. Fall back to raw accelerometer + magnetometer,
        // which every device with a compass has even when it lacks a gyroscope and therefore
        // reports no TYPE_ROTATION_VECTOR. Noisier, but a working needle beats none.
        val useRawPair = rotationVector == null && accelerometer != null && magnetometer != null
        if (sm == null || (rotationVector == null && !useRawPair)) {
            trySend(Reading(0.0, reliable = false, hasCompass = false, sensorName = "none"))
            awaitClose { }
            return@callbackFlow
        }

        val smoother = CircularSmoother()
        var lastTimestampNanos = 0L
        var measuredHz = 0.0
        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var haveGravity = false
        var haveGeomagnetic = false
        var reliable = true
        val sensorName = if (useRawPair) "accelerometer + magnetometer" else "rotation vector"

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
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR ->
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, gravity, 0, 3)
                        haveGravity = true
                        if (!haveGeomagnetic) return
                        if (!SensorManager.getRotationMatrix(
                                rotationMatrix, inclinationMatrix, gravity, geomagnetic,
                            )
                        ) return
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                        haveGeomagnetic = true
                        if (!haveGravity) return
                        if (!SensorManager.getRotationMatrix(
                                rotationMatrix, inclinationMatrix, gravity, geomagnetic,
                            )
                        ) return
                    }

                    else -> return
                }

                val (axisX, axisY) = when (displayRotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)

                val azimuth = Geo.normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))

                // Smooth against real elapsed time, not sample count. event.timestamp is the
                // sensor's own monotonic clock in nanoseconds.
                val dt = if (lastTimestampNanos == 0L) 0.0
                else (event.timestamp - lastTimestampNanos) / 1_000_000_000.0
                lastTimestampNanos = event.timestamp
                if (dt > 0.0) {
                    val hz = 1.0 / dt
                    measuredHz = if (measuredHz == 0.0) hz else measuredHz * 0.9 + hz * 0.1
                }

                trySend(
                    Reading(
                        magneticDeg = smoother.update(azimuth, dt, timeConstantSeconds),
                        reliable = reliable,
                        hasCompass = true,
                        sensorName = sensorName,
                        rawMagneticDeg = azimuth,
                        sampleRateHz = measuredHz,
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Only the magnetometer's accuracy is meaningful here; the accelerometer
                // reports its own and would otherwise clobber the flag.
                if (sensor?.type == Sensor.TYPE_ACCELEROMETER) return
                // UNRELIABLE or LOW means the magnetometer needs a figure-of-eight wave.
                reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            }
        }

        // SENSOR_DELAY_GAME is ~50 Hz. FASTEST just burns battery for an arrow a human reads.
        if (useRawPair) {
            sm.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            sm.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        }
        awaitClose { sm.unregisterListener(listener) }
    }
        // CONFLATED, not the default 64-deep buffer. A stale heading has no value whatsoever —
        // if the UI falls behind we want the newest sample, not a queue of old ones being
        // replayed in order, which turns a momentary hitch into permanent, growing lag.
        .conflate()

    companion object {
        /**
         * Time to cover ~63% of a step change in heading.
         *
         * 80 ms is fast enough that a body-turn tracks with no perceptible lag, and slow
         * enough to hide magnetometer jitter. Because the filter is time-based, this holds
         * whatever rate the device actually delivers.
         */
        const val SMOOTHING_TIME_CONSTANT_S = 0.08

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
