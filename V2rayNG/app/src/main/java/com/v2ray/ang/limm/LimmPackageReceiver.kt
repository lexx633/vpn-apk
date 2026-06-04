package com.v2ray.ang.limm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.v2ray.ang.ui.MainActivity

/**
 * Receives ACTION_MY_PACKAGE_REPLACED when our own APK is updated.
 * Since the broadcast is delivered to the NEW version of the app, we simply
 * relaunch MainActivity so the user lands in the updated app without
 * having to tap "Open" manually.
 */
class LimmPackageReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("LimmUpdate", "Package replaced — relaunching app")
        val launch = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        ctx.startActivity(launch)
    }
}
