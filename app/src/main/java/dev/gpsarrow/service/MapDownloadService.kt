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
import dev.gpsarrow.maps.AreaLevel
import dev.gpsarrow.maps.DownloadOutcome
import dev.gpsarrow.maps.MapArea
import dev.gpsarrow.maps.RegionCatalogue
import dev.gpsarrow.maps.RegionDownloader
import dev.gpsarrow.maps.RegionIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads one map area in the background, with a notification, so the transfer survives the
 * screen going off.
 *
 * ## Why a foreground service rather than WorkManager
 *
 * This is a single long transfer the user has explicitly asked for and is watching. A foreground
 * service makes it visible, cancellable from the notification, and immune to the scheduler
 * deferring it — which on a 133 MB download over a weak link is the difference between finishing
 * and never starting. `foregroundServiceType="dataSync"` is the correct declaration.
 *
 * ## The arrow cannot see this
 *
 * Nothing on the navigation path references this class, and this class references nothing on it.
 * It touches [RegionDownloader], [RegionIndex] and the catalogue, all of which sit downstream of
 * `:core`. A stalled or failed download cannot reach the arrow because there is no edge in the
 * dependency graph along which it could travel — not because the code is careful about it.
 *
 * ## Progress is observed, not bound
 *
 * State lives in [MapDownloads], a process-wide [StateFlow]. The UI collects it without binding
 * to the service, so rotating the screen or leaving the areas list does not interrupt anything,
 * and there is no lifecycle coupling to get wrong.
 */
class MapDownloadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // Cancelling leaves the .part file alone on purpose: it is the resume point, and
                // throwing away 100 MB because someone tapped Cancel would be its own bug.
                job.cancelChildren()
                MapDownloads.setCancelled()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val areaId = intent?.getStringExtra(EXTRA_AREA_ID)
        val maxZoom = intent?.getIntExtra(EXTRA_MAX_ZOOM, -1) ?: -1
        val area = areaId?.let { RegionCatalogue.byId(it) }
        val level = area?.levels?.firstOrNull { it.maxZoom == maxZoom }

        if (area == null || level == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(area.placesRes), 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        scope.launch { run(area, level) }
        // Not START_STICKY: a restarted service with no intent has nothing to resume, and the
        // user can restart the download themselves. Silently resuming a large transfer the user
        // did not re-request is worse than stopping.
        return START_NOT_STICKY
    }

    private suspend fun run(area: MapArea, level: AreaLevel) {
        val index = RegionIndex(applicationContext)
        val previous = index.scan().firstOrNull { it.area.id == area.id }
        val downloader = RegionDownloader(index.regionsDirectory)

        MapDownloads.setStarted(area.id, level.maxZoom, level.bytes, replacing = previous != null)

        var lastNotified = 0L
        val outcome = downloader.download(area, level) { done, total ->
            MapDownloads.setProgress(done, total)
            // Notification updates are throttled: the read loop calls this every 64 KiB, and
            // posting a notification per buffer would burn battery for no visible benefit.
            if (done - lastNotified > NOTIFY_EVERY_BYTES || done == total) {
                lastNotified = done
                notify(buildNotification(getString(area.placesRes), done, total))
            }
        }

        when (outcome) {
            is DownloadOutcome.Installed, is DownloadOutcome.AlreadyInstalled -> {
                // Prune only now, after the replacement has verified and been renamed into place.
                // A failed switch must leave the user with the level they already had.
                index.scan()
                index.prune(area.id, level)
                MapDownloads.setInstalled(area.id, level.maxZoom)
            }

            DownloadOutcome.NotPublishedYet ->
                MapDownloads.setUnavailable()

            is DownloadOutcome.NotEnoughSpace ->
                MapDownloads.setNoSpace(outcome.neededBytes, outcome.freeBytes)

            is DownloadOutcome.NetworkFailed ->
                MapDownloads.setNetworkFailed()

            is DownloadOutcome.ServerRefused ->
                MapDownloads.setServerRefused(outcome.statusCode)

            is DownloadOutcome.Corrupt ->
                MapDownloads.setCorrupt()
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(places: String, done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, MapDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = if (total > 0L) ((done * 100) / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_arrow)
            .setContentTitle(places)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0L)
            .addAction(0, getString(R.string.download_cancel), cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.areas_title),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun Job.cancelChildren() {
        children.forEach { it.cancel() }
    }

    companion object {
        private const val CHANNEL_ID = "map_downloads"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFY_EVERY_BYTES = 2L * 1024 * 1024
        private const val ACTION_CANCEL = "dev.gpsarrow.action.CANCEL_MAP_DOWNLOAD"
        private const val EXTRA_AREA_ID = "area_id"
        private const val EXTRA_MAX_ZOOM = "max_zoom"

        /** Must be called from a visible activity — see NavigationService for why. */
        fun start(context: Context, area: MapArea, level: AreaLevel) {
            val intent = Intent(context, MapDownloadService::class.java)
                .putExtra(EXTRA_AREA_ID, area.id)
                .putExtra(EXTRA_MAX_ZOOM, level.maxZoom)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, MapDownloadService::class.java).setAction(ACTION_CANCEL)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/**
 * Process-wide download state, so the UI can watch without binding to the service.
 *
 * Deliberately not a Room table or a preferences entry: this is transient state about a transfer
 * in flight. What survives a process restart is the `.part` file on disk, which is the only
 * durable progress record the design has — see `Downloads` in `:core`.
 */
object MapDownloads {

    private val _state = MutableStateFlow<MapDownloadState>(MapDownloadState.Idle)
    val state: StateFlow<MapDownloadState> = _state.asStateFlow()

    fun setStarted(areaId: String, maxZoom: Int, totalBytes: Long, replacing: Boolean) {
        _state.value = MapDownloadState.Running(areaId, maxZoom, 0L, totalBytes, replacing)
    }

    fun setProgress(done: Long, total: Long) {
        val current = _state.value
        if (current is MapDownloadState.Running) {
            _state.value = current.copy(doneBytes = done, totalBytes = total)
        }
    }

    fun setInstalled(areaId: String, maxZoom: Int) {
        _state.value = MapDownloadState.Installed(areaId, maxZoom)
    }

    fun setCancelled() {
        _state.value = MapDownloadState.Cancelled
    }

    fun setUnavailable() {
        _state.value = MapDownloadState.Unavailable
    }

    fun setNoSpace(neededBytes: Long, freeBytes: Long) {
        _state.value = MapDownloadState.NoSpace(neededBytes, freeBytes)
    }

    fun setNetworkFailed() {
        _state.value = MapDownloadState.NetworkFailed
    }

    fun setServerRefused(statusCode: Int) {
        _state.value = MapDownloadState.ServerRefused(statusCode)
    }

    fun setCorrupt() {
        _state.value = MapDownloadState.Corrupt
    }

    fun clear() {
        _state.value = MapDownloadState.Idle
    }
}

/**
 * Every state a download can be observed in.
 *
 * The failure cases are kept distinct rather than collapsed into one "failed" because they need
 * three different things from the user: wait, free some space, or try again. A single generic
 * error tells them nothing they can act on, which on a 133 MB download is a real cost.
 */
sealed interface MapDownloadState {
    data object Idle : MapDownloadState

    data class Running(
        val areaId: String,
        val maxZoom: Int,
        val doneBytes: Long,
        val totalBytes: Long,
        val replacingInstalled: Boolean,
    ) : MapDownloadState

    data class Installed(val areaId: String, val maxZoom: Int) : MapDownloadState

    /** Stopped by the user. The partial file is kept, so this is resumable. */
    data object Cancelled : MapDownloadState

    /**
     * The release does not carry this file (404).
     *
     * Should be unreachable now that the assets are published, but it must still behave: a map
     * update that replaces a release will briefly 404 while the new asset uploads, and the app
     * should say "not available yet" rather than "something went wrong".
     */
    data object Unavailable : MapDownloadState

    data class NoSpace(val neededBytes: Long, val freeBytes: Long) : MapDownloadState

    /** Connection lost. The partial file is kept and the next attempt resumes from it. */
    data object NetworkFailed : MapDownloadState

    data class ServerRefused(val statusCode: Int) : MapDownloadState

    /** Arrived and failed verification. It has been deleted and was never rendered. */
    data object Corrupt : MapDownloadState
}
