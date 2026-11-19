package com.mirrorapps.kit.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android's answer to a committed test-build install: installed, failed,
 * or "the user has to confirm". Declared in this module's manifest, not
 * exported; the installer reaches it through the session's PendingIntent.
 */
class TestBuildInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TestBuildUpdater.onInstallStatus(context.applicationContext, intent)
    }
}
