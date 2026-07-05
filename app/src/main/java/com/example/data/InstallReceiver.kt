package com.example.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

class InstallReceiver : BroadcastReceiver() {
    private val TAG = "InstallReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val apkPath = intent.getStringExtra("apk_path")
        if (apkPath.isNullOrBlank()) {
            Log.e(TAG, "InstallReceiver received null or empty APK path.")
            return
        }

        Log.d(TAG, "InstallReceiver triggered for background APK install: $apkPath")
        val apkFile = File(apkPath)
        if (apkFile.exists()) {
            ApkInstaller.installApk(context.applicationContext, apkFile)
        } else {
            Log.e(TAG, "InstallReceiver error: APK file does not exist at location $apkPath")
        }
    }
}
