package com.v2ray.ang.limm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.v2ray.ang.ui.MainActivity

/**
 * Receives ACTION_MY_PACKAGE_REPLACED when our own APK is updated.
 * Intentionally does NOT auto-relaunch: installer shows its own "Open / Done" dialog,
 * and forcing a launch on top of it prevents the user from choosing.
 */
class LimmPackageReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("LimmUpdate", "Package replaced — waiting for user to open app manually")
        // No auto-relaunch: let the system installer's "Open" button do it.
    }
}
