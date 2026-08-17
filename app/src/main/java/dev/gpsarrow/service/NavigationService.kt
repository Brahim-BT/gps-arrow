package dev.gpsarrow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.gpsarrow.MainActivity
import dev.gpsarrow.R

/**
 * Keeps the arrow alive with the screen off.
 *
 * Play/platform rules this is built around (BUILD_PLAN.md 2.5):
 *  - `foregroundServiceType="location"` and `FOREGROUND_SERVICE_LOCATION` are both declared.
 *  - It is started ONLY from a visible activity. From Android 14 you cannot start a location
 *    foreground service from the background without `ACCESS_BACKGROUND_LOCATION`, which this
 *    app deliberately never requests.
 *  - The notification shows live distance and bearing, so it is genuinely useful rather than
 *    the "this app is running" tax.
 */
class NavigationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        createChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.navigating)

        // Declare the type explicitly; required from Android 14 (API 34) upward.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title, text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        return START_STICKY
    }

    /** Called from the ViewModel each time the state changes materially. */
    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_arrow)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_navigation),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_navigation_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "dev.gpsarrow.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** Must be called while an activity is visible — see the class comment. */
        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, NavigationService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
