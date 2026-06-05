package com.v2ray.ang.limm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks limm.space/vpn/apk/latest once every 6 hours.
 * If a newer version is found, downloads the APK to cache and posts
 * a notification. Tapping the notification launches the system installer.
 */
class LimmUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!LimmConfig.isConfigured()) return Result.success()
        return try {
            checkAndUpdate(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val CHANNEL_ID = "limm_update"
        private const val NOTIF_ID = 8844
        private const val UNIQUE = "limm_update"

        fun schedule(ctx: Context) {
            if (!LimmConfig.isConfigured()) return
            val req = PeriodicWorkRequestBuilder<LimmUpdateWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun checkAndUpdate(ctx: Context) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val resp = client.newCall(
                Request.Builder().url("${LimmConfig.collectorUrl}/vpn/apk/latest")
                    .header("Cache-Control", "no-cache").build()
            ).execute().use { r ->
                if (!r.isSuccessful) return
                r.body?.string() ?: return
            }

            val json = JSONObject(resp)
            val tagName = json.optString("tag_name", "").removePrefix("v")
            if (tagName.isEmpty()) return
            if (compareVersions(tagName, BuildConfig.VERSION_NAME) <= 0) return

            // Download URL comes from API (hash-named file, e.g. limm-vpn_12a.apk — first 3 chars of SHA)
            val apkUrl = json.optJSONArray("assets")
                ?.optJSONObject(0)
                ?.optString("browser_download_url", "")
                ?.takeIf { it.isNotEmpty() }
                ?: return

            val apkFile = File(ctx.cacheDir, LimmSelfUpdater.APK_CACHE_NAME)
            client.newCall(Request.Builder().url(apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) return
                r.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            showInstallNotification(ctx, apkFile, tagName)
        }

        private fun showInstallNotification(ctx: Context, apkFile: File, version: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "limm VPN обновления",
                        NotificationManager.IMPORTANCE_HIGH)
                )
            }

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.cache", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("limm VPN $version готов")
                .setContentText("Нажми для установки обновления")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(NOTIF_ID, notif)
        }

        private fun compareVersions(v1: String, v2: String): Int {
            val a = v1.split(".")
            val b = v2.split(".")
            for (i in 0 until maxOf(a.size, b.size)) {
                val n1 = if (i < a.size) a[i].toIntOrNull() ?: 0 else 0
                val n2 = if (i < b.size) b[i].toIntOrNull() ?: 0 else 0
                if (n1 != n2) return n1 - n2
            }
            return 0
        }
    }
}
