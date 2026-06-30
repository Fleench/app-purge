package com.apppurge

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

object Notifications {
    const val CHANNEL_ID = "purge_active"
    const val OVERLAY_NOTIFICATION_ID = 2001
    const val PERMISSION_NOTIFICATION_ID = 2002
    const val APP_LOCK_NOTIFICATION_ID = 2003
    const val APP_LOCK_UNLOCKED_NOTIFICATION_ID = 2004

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active purge",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Persistent alerts when a selected app reaches its purge date."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun activePurgeNotification(context: Context, appName: String): Notification {
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Time to purge $appName")
            .setContentText("Tap Uninstall Now in the overlay to remove the app.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
    }

    fun activeAppLockNotification(context: Context, appName: String): Notification {
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$appName is locked")
            .setContentText("Use the overlay to request an unlock or quit the app.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    fun appUnlockedNotification(context: Context, appName: String, unlockedUntilMillis: Long): Notification {
        promotedAppUnlockedNotification(context, appName, unlockedUntilMillis)?.let { return it }

        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remainingMillis = (unlockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$appName unlocked")
            .setContentText("Time left: ${formatDuration(remainingMillis)}")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(unlockedUntilMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(remainingMillis)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun promotedAppUnlockedNotification(context: Context, appName: String, unlockedUntilMillis: Long): Notification? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            ensureChannel(context)
            val pendingIntent = PendingIntent.getActivity(
                context,
                3,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val now = System.currentTimeMillis()
            val remainingMillis = (unlockedUntilMillis - now).coerceAtLeast(0L)
            val totalMillis = remainingMillis.coerceAtLeast(1L)
            val elapsedProgress = 100 - ((remainingMillis * 100L) / totalMillis).toInt().coerceIn(0, 100)
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$appName unlocked")
                .setContentText("Time left: ${formatDuration(remainingMillis)}")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(unlockedUntilMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(remainingMillis)
                .setCategory(Notification.CATEGORY_STATUS)

            builder.javaClass
                .getMethod("setShortCriticalText", String::class.java)
                .invoke(builder, formatDuration(remainingMillis))
            builder.javaClass
                .getMethod("setRequestPromotedOngoing", java.lang.Boolean.TYPE)
                .invoke(builder, true)

            val progressStyle = Class.forName("android.app.Notification\$ProgressStyle")
                .getConstructor()
                .newInstance()
            progressStyle.javaClass
                .getMethod("setProgress", Integer.TYPE)
                .invoke(progressStyle, elapsedProgress)
            progressStyle.javaClass
                .getMethod("setStyledByProgress", java.lang.Boolean.TYPE)
                .invoke(progressStyle, true)
            builder.javaClass
                .getMethod("setStyle", Notification.Style::class.java)
                .invoke(builder, progressStyle)

            builder.build()
        }.getOrNull()
    }

    fun showAppUnlockedNotification(context: Context, appName: String, unlockedUntilMillis: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(
            APP_LOCK_UNLOCKED_NOTIFICATION_ID,
            appUnlockedNotification(context, appName, unlockedUntilMillis),
        )
    }

    fun showOverlayPermissionNotification(context: Context, appName: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Overlay permission needed")
            .setContentText("App Purge needs overlay access to purge $appName.")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(PERMISSION_NOTIFICATION_ID, notification)
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = (millis / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}
