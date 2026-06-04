package com.v2ray.ang.limm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.v2ray.ang.core.CoreServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * In-app self-update helper.
 *
 * Flow:
 *   1. downloadApk()     — streams APK to cacheDir, reports 0-100% progress
 *   2. stopAndInstall()  — stops VPN service, launches system package installer, exits process
 *
 * After the system installer completes, LimmPackageReceiver fires ACTION_MY_PACKAGE_REPLACED
 * on the new version and auto-relaunches the app.
 */
object LimmSelfUpdater {

    private const val TAG = "LimmUpdate"
    const val APK_CACHE_NAME = "limm-vpn-update.apk"

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Downloads [apkUrl] to [ctx].cacheDir/limm-vpn-update.apk.
     * Calls [onProgress] with 0-100 as data arrives; 100 = done.
     * Returns the File on success, null on any error.
     */
    suspend fun downloadApk(
        ctx: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Downloading APK: $apkUrl")
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val dest = File(ctx.cacheDir, APK_CACHE_NAME)
        return@withContext try {
            client.newCall(Request.Builder().url(apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${r.code}")
                    return@withContext null
                }
                val total = r.body?.contentLength() ?: -1L
                var downloaded = 0L
                onProgress(0)

                r.body!!.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(32_768)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) onProgress((downloaded * 100L / total).toInt())
                        }
                    }
                }
            }
            onProgress(100)
            Log.i(TAG, "APK ready: ${dest.length()} bytes")
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            null
        }
    }

    // ── Install ──────────────────────────────────────────────────────────────

    /**
     * Stops the VPN service, launches the system package installer for [apkFile],
     * then exits the current process so Android can replace the running APK.
     *
     * The system will show its standard "Update app?" confirmation dialog.
     * After the user confirms, ACTION_MY_PACKAGE_REPLACED fires in the NEW version
     * and LimmPackageReceiver auto-relaunches the app.
     */
    suspend fun stopAndInstall(ctx: Context, apkFile: File) {
        Log.i(TAG, "Stopping VPN before install")
        withContext(Dispatchers.Main) {
            if (CoreServiceManager.isRunning()) CoreServiceManager.stopVService(ctx)
        }
        // Give the VPN service time to release the TUN file descriptor
        delay(600)

        Log.i(TAG, "Launching system installer")
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.cache", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        ctx.startActivity(intent)

        // Small delay to let the Intent reach the system before our process dies
        delay(400)
        Log.i(TAG, "Exiting process for clean APK replacement")
        exitProcess(0)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns cached APK file if it exists and is non-empty. */
    fun getCachedApk(ctx: Context): File? {
        val f = File(ctx.cacheDir, APK_CACHE_NAME)
        return if (f.exists() && f.length() > 0) f else null
    }
}
