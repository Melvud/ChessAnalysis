package com.github.movesense.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.github.movesense.MainActivity
import com.github.movesense.R
import com.github.movesense.util.LocaleManager
import kotlin.random.Random

class RetentionWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        sendNotification()
        return Result.success()
    }

    private fun sendNotification() {
        // Apply saved locale to context to ensure notification is in correct language
        val context = LocaleManager.applyLocale(applicationContext)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create Channel (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daily Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders to analyze games"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Select random message
        // Select random message
        val messages = listOf(
            context.getString(R.string.notif_title_1) to context.getString(R.string.notif_msg_1),
            context.getString(R.string.notif_title_2) to context.getString(R.string.notif_msg_2),
            context.getString(R.string.notif_title_3) to context.getString(R.string.notif_msg_3),
            context.getString(R.string.notif_title_4) to context.getString(R.string.notif_msg_4)
        )
        val (title, message) = messages.random()

        // Intent to open app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round) // Using app icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // Use a fixed ID (1001) so we don't spam multiple notifications, just update the existing one
        notificationManager.notify(1001, notification)
    }

    companion object {
        const val CHANNEL_ID = "retention_channel"
        const val WORK_NAME = "retention_work"
    }
}
