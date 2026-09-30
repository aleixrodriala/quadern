package io.github.aleixrodriala.noteai.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.aleixrodriala.noteai.MainActivity
import io.github.aleixrodriala.noteai.R

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_SIGN_IN = "sign_in"
    const val CHANNEL_TRANSCRIPTION = "transcription"
    const val CHANNEL_ALERTS = "alerts"

    const val ID_RECORDING = 1
    const val ID_SIGN_IN = 2
    const val ID_TRANSCRIBING = 3
    const val ID_NEEDS_SIGN_IN = 4
    const val ID_INTERRUPTED = 5
    const val ID_SUMMARIZING = 6

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_SIGN_IN, context.getString(R.string.channel_sign_in), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_TRANSCRIPTION, context.getString(R.string.channel_transcription), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    fun openAppIntent(context: Context, route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route) }
        return PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun transcribing(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.notification_transcribing))
            .setContentIntent(openAppIntent(context))
            .setSilent(true)
            .setOngoing(true)
            .build()

    /** Only shown on Android 10–11, where expedited work runs as a foreground service. */
    fun summarizing(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.notification_summarizing))
            .setContentIntent(openAppIntent(context))
            .setSilent(true)
            .setOngoing(true)
            .build()

    fun showNeedsSignIn(context: Context) = post(
        context, ID_NEEDS_SIGN_IN,
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.notification_sign_in_title))
            .setContentText(context.getString(R.string.notification_sign_in_text))
            .setContentIntent(openAppIntent(context, MainActivity.ROUTE_SETTINGS))
            .setAutoCancel(true)
            .build()
    )

    fun showInterrupted(context: Context) = post(
        context, ID_INTERRUPTED,
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.notification_interrupted_title))
            .setContentText(context.getString(R.string.notification_interrupted_text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
    )

    private fun post(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
