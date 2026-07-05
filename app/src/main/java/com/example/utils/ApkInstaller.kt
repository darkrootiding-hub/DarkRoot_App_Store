package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    private const val TAG = "ApkInstaller"

    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "Error: APK file not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Verify package integrity to prevent "Problem parsing package" dialog
        val packageInfo = try {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } catch (e: Exception) {
            null
        }
        if (packageInfo == null) {
            Log.e(TAG, "Cannot install. APK archive is corrupt: ${apkFile.absolutePath}")
            Toast.makeText(context, "Error: Corrupted APK file detected. Deleting...", Toast.LENGTH_LONG).show()
            try {
                if (apkFile.exists()) {
                    apkFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete corrupt APK file: ${e.message}")
            }
            return
        }

        Toast.makeText(context, "Starting installation...", Toast.LENGTH_SHORT).show()

        // 1. Try silent background installation first
        val silentSucceeded = trySilentInstall(apkFile)
        if (silentSucceeded) {
            Log.i(TAG, "Silent installation via pm install succeeded.")
            Toast.makeText(context, "Installation completed successfully!", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Fallback to standard request user-level install intent
        Log.i(TAG, "Silent install failed or not supported. Falling back to system installer dialog.")
        try {
            // Check Oreo+ unknown sources permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Please allow APK installation from settings", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            // Explicitly grant URI permission to all potential resolving apps (e.g. OEM installers)
            try {
                val resInfoList = context.packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )
                for (resolveInfo in resInfoList) {
                    val targetPkg = resolveInfo.activityInfo.packageName
                    context.grantUriPermission(
                        targetPkg,
                        apkUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to explicitly grant URI permissions to resolving package installers", ex)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch intent to install APK", e)
            Toast.makeText(context, "Installation failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun trySilentInstall(apkFile: File): Boolean {
        val commands = listOf(
            arrayOf("pm", "install", "-r", apkFile.absolutePath),
            arrayOf("su", "-c", "pm install -r ${apkFile.absolutePath}"),
            arrayOf("sh", "-c", "pm install -r ${apkFile.absolutePath}")
        )
        for (cmd in commands) {
            try {
                Log.d(TAG, "Attempting silent package install command: ${cmd.joinToString(" ")}")
                val process = Runtime.getRuntime().exec(cmd)
                
                // Write standard outputs just in case it helps execution
                process.outputStream.close()
                
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i(TAG, "Silent install via '${cmd[0]}' completed successfully!")
                    return true
                } else {
                    Log.w(TAG, "Command '${cmd[0]}' exited with code: $exitCode")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed command execution '${cmd[0]}': ${e.message}")
            }
        }
        return false
    }

    data class InstalledAppInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: Long
    )

    fun getInstalledAppInfo(context: Context, packageName: String): InstalledAppInfo? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            InstalledAppInfo(
                packageName = packageName,
                versionName = packageInfo.versionName ?: "0",
                versionCode = vCode
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getInstalledApps(context: Context): Map<String, InstalledAppInfo> {
        val infoMap = mutableMapOf<String, InstalledAppInfo>()
        try {
            val packages = context.packageManager.getInstalledPackages(0)
            for (packageInfo in packages) {
                val pName = packageInfo.packageName
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                infoMap[pName] = InstalledAppInfo(
                    packageName = pName,
                    versionName = packageInfo.versionName ?: "0",
                    versionCode = vCode
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return infoMap
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun launchApp(context: Context, packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "App cannot be launched", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed launch app $packageName: ${e.message}", e)
            Toast.makeText(context, "Cannot launch: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun uninstallApp(context: Context, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launch uninstall intent for $packageName: ${e.message}", e)
            Toast.makeText(context, "Uninstall failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, MyDeviceAdminReceiver::class.java)
        return dpm?.isAdminActive(adminComponent) == true
    }

    private fun findActivity(context: Context): android.app.Activity? {
        var current = context
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }

    fun requestDeviceAdmin(context: Context) {
        val adminComponent = android.content.ComponentName(context, MyDeviceAdminReceiver::class.java)
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Dark Store requires Device Administrator authorization to unlock uninstallation capabilities.")
            
            val activityCtx = findActivity(context)
            if (activityCtx == null) {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        
        val activityCtx = findActivity(context)
        if (activityCtx != null) {
            activityCtx.startActivity(intent)
        } else {
            context.startActivity(intent)
        }
    }

    fun removeDeviceAdmin(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, MyDeviceAdminReceiver::class.java)
        dpm?.removeActiveAdmin(adminComponent)
    }
}
