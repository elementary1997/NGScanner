package ru.ngscanner.update

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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.ngscanner.MainActivity
import java.io.File
import java.util.concurrent.TimeUnit

/** Сведения о доступной версии из GitHub Releases. */
data class UpdateInfo(
    val version: String, // нормализованная, без префикса «v»
    val notes: String, // тело релиза — что нового
    val apkUrl: String, // ссылка на APK-ассет
    val newer: Boolean, // новее установленной
)

/**
 * Проверка и установка обновлений приложения из GitHub Releases (репозиторий
 * [REPO]). Сравнивает последний релиз с установленной версией, качает APK и
 * запускает системный установщик.
 *
 * Важно: «бесшовная» установка поверх работает только если новый APK подписан ТЕМ
 * ЖЕ ключом, что и установленный (для debug-сборок это общий debug-ключ; для
 * release нужен один и тот же keystore). Иначе система потребует удалить старую
 * версию. Установка также требует, чтобы пользователь разрешил приложению
 * «Установку неизвестных приложений» (REQUEST_INSTALL_PACKAGES).
 */
object AppUpdater {

    // Репозиторий релизов. При форке/переносе — поменять здесь.
    private const val REPO = "elementary1997/NGScanner"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHANNEL = "app_updates"
    private const val NOTIF_ID = 77

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(
        val tag_name: String = "",
        val name: String = "",
        val body: String = "",
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String = "", val browser_download_url: String = "")

    /** Установленная версия (versionName), например «0.1.0». */
    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"

    /** Запрашивает последний релиз. `null` — сеть недоступна, релизов/APK нет. */
    suspend fun checkLatest(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = runCatching {
            val req = Request.Builder().url(LATEST_URL)
                .header("Accept", "application/vnd.github+json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return@withContext null
        val release = runCatching { json.decodeFromString<Release>(body) }.getOrNull() ?: return@withContext null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        val latest = normalize(release.tag_name.ifBlank { release.name })
        if (latest.isBlank()) return@withContext null
        UpdateInfo(
            version = latest,
            notes = release.body.trim(),
            apkUrl = apk.browser_download_url,
            newer = isNewer(latest, normalize(currentVersion(context))),
        )
    }

    /** Качает APK во внутренний cache и запускает системный установщик. */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // не копим старые APK
            val out = File(dir, "ngscanner-${info.version}.apk")
            val req = Request.Builder().url(info.apkUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Загрузка не удалась (HTTP ${resp.code})")
                val bytes = resp.body?.bytes() ?: throw IllegalStateException("Пустой ответ загрузки")
                out.writeBytes(bytes)
            }
            out
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Показывает уведомление о новой версии (для проверки при запуске). */
    fun notifyAvailable(context: Context, info: UpdateInfo) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Доступна новая версия ${info.version}")
            .setContentText("Откройте «Настройки», чтобы обновить NG Scanner.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // На Android 13+ показ уведомления требует POST_NOTIFICATIONS.
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    /** «v0.2.0» / «0.2.0» → «0.2.0». */
    internal fun normalize(v: String): String = v.trim().removePrefix("v").removePrefix("V").trim()

    /** true, если [latest] строго новее [current] по числовым сегментам версии. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(v: String): List<Int> =
        v.split('.', '-').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
}
