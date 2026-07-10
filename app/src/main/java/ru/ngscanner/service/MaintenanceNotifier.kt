package ru.ngscanner.service

import android.Manifest
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
import ru.ngscanner.MainActivity
import ru.ngscanner.garage.MaintenanceItem
import ru.ngscanner.garage.MaintenanceUrgency

/** Уведомление о приближающемся/просроченном ТО (проверка при открытии приложения). */
object MaintenanceNotifier {

    private const val CHANNEL = "maintenance"
    private const val NOTIF_ID = 88

    fun notify(context: Context, carTitle: String, items: List<MaintenanceItem>) {
        val due = items.filter { it.urgency != MaintenanceUrgency.OK }
        if (due.isEmpty()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Напоминания о ТО", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val hasOverdue = due.any { it.urgency == MaintenanceUrgency.OVERDUE }
        val title = if (hasOverdue) "Просрочено ТО: $carTitle" else "Скоро ТО: $carTitle"
        val text = due.joinToString(", ") { it.interval.title }
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }
}
