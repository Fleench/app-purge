package com.apppurge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.apppurge.data.PurgeConfig
import java.util.concurrent.TimeUnit

object PurgeScheduler {
    private const val UNIQUE_WORK_NAME = "app_purge_due_work"
    private const val UNIQUE_RETRY_WORK_NAME = "app_purge_retry_work"
    private const val PURGE_ALARM_REQUEST_CODE = 3001
    private const val RETRY_DELAY_MILLIS = 60_000L

    fun schedule(context: Context, config: PurgeConfig) {
        val delayMillis = (config.purgeAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        enqueueWork(context, UNIQUE_WORK_NAME, delayMillis)
        scheduleAlarm(context, config.purgeAtMillis)
    }

    fun scheduleRetry(context: Context) {
        enqueueWork(context, UNIQUE_RETRY_WORK_NAME, RETRY_DELAY_MILLIS)
        scheduleAlarm(context, System.currentTimeMillis() + RETRY_DELAY_MILLIS)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_RETRY_WORK_NAME)
        existingPurgePendingIntent(context)?.let { alarmManager(context).cancel(it) }
    }

    private fun enqueueWork(context: Context, uniqueName: String, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<PurgeWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
        val alarmManager = alarmManager(context)
        val pendingIntent = purgePendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.applicationContext.getSystemService(AlarmManager::class.java)

    private fun existingPurgePendingIntent(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context.applicationContext,
            PURGE_ALARM_REQUEST_CODE,
            purgeIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun purgePendingIntent(context: Context, flags: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context.applicationContext,
            PURGE_ALARM_REQUEST_CODE,
            purgeIntent(context),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun purgeIntent(context: Context): Intent =
        Intent(context.applicationContext, PurgeAlarmReceiver::class.java).apply {
            action = PurgeAlarmReceiver.ACTION_PURGE_DUE
        }
}
