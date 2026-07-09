package ru.ngscanner.service

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
import ru.ngscanner.MainActivity

/**
 * Foreground-сервис, удерживающий процесс во время активной сессии с адаптером:
 * снижает вероятность выгрузки системой при сворачивании и напоминает
 * пользователю, что адаптер подключён (иначе он может посадить АКБ).
 *
 * Само соединение остаётся в ViewModel; сервис держит процесс и уведомление.
 */
class ObdForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val name = intent?.getStringExtra(EXTRA_NAME)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(name), type)
        // Не sticky: соединение с адаптером живёт в ViewModel и при рестарте процесса
        // не восстанавливается. Sticky-перезапуск показал бы ложное «подключено».
        return START_NOT_STICKY
    }

    private fun buildNotification(name: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NG Scanner: адаптер подключён")
            .setContentText(name?.let { "Адаптер: $it. Отключите его, если не диагностируете." }
                ?: "Отключите адаптер от разъёма, если не диагностируете.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Соединение с адаптером",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "obd_connection"
        private const val NOTIF_ID = 42
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String?) {
            val intent = Intent(context, ObdForegroundService::class.java).putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObdForegroundService::class.java))
        }
    }
}
