package com.imageguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Progress of a full-library scan, observed by the UI. */
data class ScanProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val removed: Int = 0,
)

object ScanState {
    internal val mutable = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = mutable
}

/**
 * Foreground service that
 *  - watches the media library and checks every new image the moment it is saved, and
 *  - runs full-library scans on request.
 */
class GuardService : Service() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1) // one image at a time
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var recentJob: Job? = null
    private var fullScanJob: Job? = null
    private var observerRegistered = false
    private lateinit var scanner: Scanner
    private lateinit var store: Store

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleRecentCheck()
    }

    override fun onCreate() {
        super.onCreate()
        scanner = Scanner(this)
        store = Store.get(this)
        createChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_FULL_SCAN -> startFullScan()
            ACTION_CANCEL_SCAN -> fullScanJob?.cancel()
            ACTION_STOP -> {
                stopIfIdle(force = true)
                return START_NOT_STICKY
            }
        }
        if (store.autoProtect) {
            registerObserver()
            scheduleRecentCheck(0)
        } else {
            unregisterObserver()
            stopIfIdle()
        }
        return START_STICKY
    }

    private fun registerObserver() {
        if (observerRegistered) return
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        observerRegistered = true
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        contentResolver.unregisterContentObserver(observer)
        observerRegistered = false
    }

    /** New images often arrive in bursts (camera, downloads), so wait briefly and batch them. */
    private fun scheduleRecentCheck(delayMs: Long = 1500) {
        recentJob?.cancel()
        recentJob = scope.launch {
            delay(delayMs)
            if (!Permissions.hasStorage(this@GuardService)) return@launch
            for (image in scanner.recentUnscored(RECENT_WINDOW_SECONDS)) {
                if (!isActive) break
                if (scanner.process(image).removed) notifyRemoved(image.displayName)
            }
        }
    }

    private fun startFullScan() {
        if (fullScanJob?.isActive == true) return
        fullScanJob = scope.launch {
            val images = scanner.allImages()
            var removed = 0
            ScanState.mutable.value = ScanProgress(true, 0, images.size, 0)
            try {
                images.forEachIndexed { i, image ->
                    yield() // let checks of brand-new images jump the queue
                    if (!isActive) return@launch
                    if (scanner.process(image).removed) removed++
                    ScanState.mutable.value = ScanProgress(true, i + 1, images.size, removed)
                    if (i % 10 == 0) updateForegroundText(
                        getString(R.string.notif_scanning, i + 1, images.size)
                    )
                }
                store.lastFullScan = System.currentTimeMillis()
                if (removed > 0) notifyRemoved(null, removed)
            } finally {
                ScanState.mutable.value = ScanState.mutable.value.copy(running = false)
                updateForegroundText(foregroundText())
                // This job is still "active" while in finally, so decide on autoProtect alone.
                if (!store.autoProtect) stopIfIdle(force = true)
            }
        }
    }

    private fun stopIfIdle(force: Boolean = false) {
        if (force || (!store.autoProtect && fullScanJob?.isActive != true)) {
            fullScanJob?.cancel()
            unregisterObserver()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        unregisterObserver()
        scope.cancel()
        ScanState.mutable.value = ScanState.mutable.value.copy(running = false)
        super.onDestroy()
    }

    // ---- notifications ----

    private fun foregroundText() =
        if (store.autoProtect) getString(R.string.notif_protecting) else getString(R.string.notif_idle)

    private fun buildForeground(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun goForeground() {
        val n = buildForeground(foregroundText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
    }

    private fun updateForegroundText(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, buildForeground(text))
    }

    private fun notifyRemoved(name: String?, count: Int = 1) {
        if (!Permissions.hasNotifications(this)) return
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, QuarantineActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (count == 1 && name != null) getString(R.string.notif_removed_one, name)
        else resources.getQuantityString(R.plurals.notif_removed_many, count, count)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_removed_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ALERT_BASE + (System.currentTimeMillis() % 1000).toInt(), n)
    }

    companion object {
        const val ACTION_FULL_SCAN = "com.imageguard.app.FULL_SCAN"
        const val ACTION_CANCEL_SCAN = "com.imageguard.app.CANCEL_SCAN"
        const val ACTION_STOP = "com.imageguard.app.STOP"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_ALERT_BASE = 1000
        private const val RECENT_WINDOW_SECONDS = 2L * 24 * 60 * 60

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, GuardService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, i)
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS, context.getString(R.string.channel_status),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS, context.getString(R.string.channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }
}
