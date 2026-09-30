package io.github.aleixrodriala.noteai.auth

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.aleixrodriala.noteai.R
import io.github.aleixrodriala.noteai.util.Notifications

/**
 * Keeps the app out of Android's cached-app freezer while the browser is in front during sign-in.
 * A frozen process still owns the loopback port but never answers it, and the browser would hang.
 * `shortService` gives about three minutes, which covers a normal sign-in.
 */
class SignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, Notifications.CHANNEL_SIGN_IN)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.sign_in_notification_title))
            .setContentText(getString(R.string.sign_in_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        // Always enter the foreground first: stopping a service started with startForegroundService()
        // before it called startForeground() crashes the app.
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_SIGN_IN, notification, type) }
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SignInService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, SignInService::class.java).setAction(ACTION_STOP)) }
                .onFailure { context.stopService(Intent(context, SignInService::class.java)) }
        }

        private const val ACTION_STOP = "stop"
    }
}
