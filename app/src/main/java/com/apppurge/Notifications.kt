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
import java.text.DateFormat
import java.util.Date

object Notifications {
    const val CHANNEL_ID = "purge_active"
    const val OVERLAY_NOTIFICATION_ID = 2001
    const val PERMISSION_NOTIFICATION_ID = 2002
    const val APP_LOCK_NOTIFICATION_ID = 2003
    const val APP_LOCK_UNLOCKED_NOTIFICATION_ID = 2004
    const val NEW_APP_NOTIFICATION_ID = 2005

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

    fun appUnlockedNotification(context: Context, packageName: String, appName: String, unlockedUntilMillis: Long, grantedMinutes: Int): Notification {
        promotedAppUnlockedNotification(context, packageName, appName, unlockedUntilMillis, grantedMinutes)?.let { return it }

        ensureChannel(context)
        val pendingIntent = appPurgePendingIntent(context, packageName)
        val openAppPendingIntent = openAppPendingIntent(context, packageName)
        val remainingMillis = (unlockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val untilText = formatClockTime(unlockedUntilMillis)
        val title = "$appName unlocked"
        val text = "${formatDuration(remainingMillis)} left, until $untilText"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$appName is temporarily unlocked until $untilText. App Purge will lock it again when time runs out."),
            )
            .setSubText("App lock")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(unlockedUntilMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(remainingMillis)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setProgress((grantedMinutes * 60).coerceAtLeast(1), (remainingMillis / 1000L).toInt().coerceAtLeast(0), false)
            .addAction(android.R.drawable.ic_menu_view, "Open", openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_manage, "App Purge", pendingIntent)
            .build()
    }

    private fun promotedAppUnlockedNotification(context: Context, packageName: String, appName: String, unlockedUntilMillis: Long, grantedMinutes: Int): Notification? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            ensureChannel(context)
            val pendingIntent = appPurgePendingIntent(context, packageName)
            val openAppPendingIntent = openAppPendingIntent(context, packageName)
            val now = System.currentTimeMillis()
            val remainingMillis = (unlockedUntilMillis - now).coerceAtLeast(0L)
            val totalMillis = (grantedMinutes.coerceAtLeast(1) * 60_000L)
            val elapsedProgress = (((totalMillis - remainingMillis).coerceAtLeast(0L) * 100L) / totalMillis).toInt().coerceIn(0, 100)
            val untilText = formatClockTime(unlockedUntilMillis)
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$appName unlocked")
                .setContentText("${formatDuration(remainingMillis)} left, until $untilText")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(unlockedUntilMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(remainingMillis)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view, "Open", openAppPendingIntent).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_manage, "App Purge", pendingIntent).build())

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

    fun showAppUnlockedNotification(context: Context, packageName: String, appName: String, unlockedUntilMillis: Long, grantedMinutes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(
            appUnlockedNotificationId(packageName),
            appUnlockedNotification(context, packageName, appName, unlockedUntilMillis, grantedMinutes),
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

    fun showNewAppPurgePrompt(context: Context, packageName: String, appName: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CONFIGURE_PACKAGE, packageName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode() + 200_000,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Set a purge for $appName?")
            .setContentText("Tap to choose when App Purge should remove this new app.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$appName was just installed. Tap to choose a purge date before it lingers on your device."),
            )
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(newAppNotificationId(packageName), notification)
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

    private fun formatClockTime(millis: Long): String {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    }

    private fun appUnlockedNotificationId(packageName: String): Int {
        return APP_LOCK_UNLOCKED_NOTIFICATION_ID + (packageName.hashCode() and 0x0fffffff)
    }

    private fun newAppNotificationId(packageName: String): Int {
        return NEW_APP_NOTIFICATION_ID + (packageName.hashCode() and 0x0fffffff)
    }

    private fun appPurgePendingIntent(context: Context, packageName: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_LOCKED_PACKAGE, packageName)
        }
        return PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openAppPendingIntent(context: Context, packageName: String): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_SHOW_LOCK_GATE, true)
                putExtra(MainActivity.EXTRA_LOCKED_PACKAGE, packageName)
            }
        return PendingIntent.getActivity(
            context,
            packageName.hashCode() + 100_000,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
