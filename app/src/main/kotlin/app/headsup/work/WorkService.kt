package app.headsup.work

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
import app.headsup.MainActivity
import app.headsup.ui.BackgroundWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the app running, with a progress notification, while long work goes on in the background
 * (tracking masks with AI, reversing clips), so it goes on with the screen off or another app open.
 */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Work in progress", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIFICATION_ID, notification("Working", null), type)
        if (!observing) {
            observing = true
            scope.launch {
                work.collect { list ->
                    val running = list.firstOrNull { !it.waiting } ?: list.firstOrNull()
                    if (running == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val more = list.size - 1
                    notifications.notify(NOTIFICATION_ID, notification(running.label + if (more > 0) " · $more more" else "", running.progress))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, progress: Float?): Notification {
        val open = PendingIntent.getActivity(
            // As the app's icon does: back to the app as it is (a new screen would start it afresh).
            this, 0, Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sweet Spot")
            .setContentText(text)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "work"
        private const val NOTIFICATION_ID = 2
        private val work = MutableStateFlow<List<BackgroundWork>>(emptyList())

        /** What is running now (the notification follows it; empty ends the service). */
        fun show(list: List<BackgroundWork>) {
            work.value = list
        }

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WorkService::class.java)) }
        }
    }
}
