package com.ys.pleosapp.shared.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import com.ys.pleosapp.shared.util.CastLog

/**
 * Keep-alive for the commentary: a foreground service running [CastDirector]
 * so the voice and the caption survive the navigation app taking the screen
 * while driving.
 *
 * The ongoing notification is the "showable somehow" surface — it carries
 * the latest call and a Stop action, reachable from the system UI on any
 * host. Template apps cannot pin or split themselves; the host owns the
 * foreground.
 */
class CastService : Service() {

    private val ui = CastDirector.UiListener { headline, _ -> refresh(headline) }

    override fun onCreate() {
        super.onCreate()
        CastLog.init(applicationContext)
        CastLog.installCrashHandler()
        createChannel()
        CastDirector.start(applicationContext)
        CastDirector.addListener(ui)
        startForeground(
            NOTIF_ID,
            buildNotification("Warming up the commentary…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        CastLog.i("keep-alive service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CastDirector.setMuted(true)
            CastDirector.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        CastDirector.removeListener(ui)
        CastDirector.stop()
        super.onDestroy()
    }

    private fun refresh(headline: String) {
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification(headline))
        }
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🏁 Live Drive Cast")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
        // Tap the notification to jump back to our screen, even mid-drive.
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, launch,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
        }
        return builder
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "Stop", stop
                ).build()
            )
            .build()
    }

    private fun createChannel() {
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Live Drive Cast", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.ys.pleosapp.shared.cast.STOP"
        private const val NOTIF_ID = 41
        private const val CHANNEL_ID = "live_drive_cast"
    }
}
