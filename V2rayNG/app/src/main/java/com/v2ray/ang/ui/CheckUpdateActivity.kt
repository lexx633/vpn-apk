package com.v2ray.ang.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.limm.LimmSelfUpdater
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        binding.checkBrowserDownload.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_BROWSER_DOWNLOAD, isChecked)
        }
        binding.checkBrowserDownload.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_BROWSER_DOWNLOAD, false)

        "${com.v2ray.ang.limm.LimmConfig.displayVersion} (${CoreNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates()
    }

    private fun checkForUpdates() {
        toast(R.string.update_checking_for_update)
        showLoading()

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(false)
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            } finally {
                hideLoading()
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        val url = result.downloadUrl ?: return
        val useBrowser = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_BROWSER_DOWNLOAD, false)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setMessage(result.releaseNotes)
            .setPositiveButton(R.string.update_now) { dlg, _ ->
                dlg.dismiss()
                if (useBrowser) {
                    com.v2ray.ang.util.Utils.openUri(this, com.v2ray.ang.limm.LimmConfig.collectorUrl + "/vpn/app")
                } else {
                    startInAppDownload(url, result.latestVersion ?: "")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Downloads the APK in-app (with a progress dialog), then stops the VPN
     * and launches the system package installer. The process exits cleanly so
     * Android can replace the running APK without conflicts.
     * After installation, LimmPackageReceiver fires ACTION_MY_PACKAGE_REPLACED
     * and auto-relaunches the app.
     */
    private fun startInAppDownload(apkUrl: String, version: String) {
        // Build a simple progress dialog
        val dp = resources.displayMetrics.density
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100
        }
        val label = TextView(this).apply {
            text = "Загрузка 0%…"
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            addView(progress)
            addView(label)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Обновление $version")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val apkFile = LimmSelfUpdater.downloadApk(applicationContext, apkUrl) { pct ->
                lifecycleScope.launch(Dispatchers.Main) {
                    progress.progress = pct
                    label.text = if (pct < 100) "Загрузка $pct%…" else "Готово, запускаю установку…"
                }
            }

            withContext(Dispatchers.Main) {
                if (apkFile == null) {
                    dialog.dismiss()
                    toastError("Ошибка загрузки APK")
                    return@withContext
                }
                // Update label before we exit the process
                label.text = "Останавливаю VPN и запускаю установку…"
            }

            if (apkFile != null) {
                // stopAndInstall() stops VPN, launches system installer, then calls exitProcess(0)
                LimmSelfUpdater.stopAndInstall(applicationContext, apkFile)
            }
        }
    }
}