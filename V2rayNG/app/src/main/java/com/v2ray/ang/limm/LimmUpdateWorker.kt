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
import org.json.JSONArray
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

            val cmp = compareVersions(tagName, BuildConfig.VERSION_NAME)
            if (cmp < 0) return  // remote is older

            // M3: pick asset whose name/URL is our APK, not blindly assets[0]
            val apkUrl = findApkAssetUrl(json.optJSONArray("assets")) ?: return

            // M2: same version number — only update when the build hash (limm-vpn_XXX.apk) differs
            if (cmp == 0) {
                val remoteTag = buildTagFromUrl(apkUrl)
                if (remoteTag.isEmpty() || remoteTag.equals(LimmConfig.buildTag, ignoreCase = true)) return
            }

            // H5: download to .tmp, verify full length against Content-Length, then atomic rename
            val apkFile = File(ctx.cacheDir, LimmSelfUpdater.APK_CACHE_NAME)
            val tmpFile = File(ctx.cacheDir, "${LimmSelfUpdater.APK_CACHE_NAME}.tmp")
            tmpFile.delete()
            var downloadOk = false
            client.newCall(Request.Builder().url(apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) return
                val body = r.body ?: return
                val expected = body.contentLength()
                var written = 0L
                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(32_768)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            written += n
                        }
                    }
                }
                if (written <= 0L || (expected > 0 && written != expected)) {
                    tmpFile.delete()
                    return
                }
                downloadOk = true
            }
            if (!downloadOk) { tmpFile.delete(); return }
            apkFile.delete()
            if (!tmpFile.renameTo(apkFile)) { tmpFile.delete(); return }

            showInstallNotification(ctx, apkFile, tagName)
        }

        /** M3: finds the APK asset URL from a releases assets array. */
        private fun findApkAssetUrl(assets: JSONArray?): String? {
            if (assets == null) return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val url = a.optString("browser_download_url", "")
                if (url.isEmpty()) continue
                val name = a.optString("name", "")
                val fname = url.substringAfterLast('/')
                if (name.endsWith(".apk", ignoreCase = true) ||
                    fname.endsWith(".apk", ignoreCase = true) ||
                    name.startsWith("limm-vpn_") || fname.startsWith("limm-vpn_")) {
                    return url
                }
            }
            return null
        }

        /** M2: extracts the 3-char build tag from a limm-vpn_XXX.apk URL filename. Empty if pattern doesn't match. */
        private fun buildTagFromUrl(url: String): String {
            val fname = url.substringAfterLast('/')
            if (!fname.startsWith("limm-vpn_") || !fname.endsWith(".apk", ignoreCase = true)) return ""
            return fname.substring("limm-vpn_".length, fname.length - ".apk".length)
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
