package com.example.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppDao
import com.example.data.FirebaseService
import com.example.data.NoticeEntity
import kotlinx.coroutines.*

class StoreBackgroundService : Service() {
    private val TAG = "StoreBackgroundService"
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "StoreBackgroundService onCreate")
        createNotificationChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "StoreBackgroundService onStartCommand")
        if (!isRunning) {
            isRunning = true
            startSyncLoop()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "background_sync_channel"
        val notificationIntent = Intent()
        try {
            notificationIntent.setClassName(packageName, "com.example.MainActivity")
            notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            99,
            notificationIntent,
            pendingIntentFlags
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DarkRoot Sync Active")
            .setContentText("Listening for real-time announcements & updates in the background")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1001,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start failed: ${e.message}. Falling back to standard background.")
            // On older systems, we can rely on standard sticky start
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "background_sync_channel"
            val channelName = "Background Sync Status"
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps DarkRoot Store announcements synched in real-time"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startSyncLoop() {
        serviceScope.launch {
            val appDao = AppDao(applicationContext)
            // Give UI time to fully boot before first sync
            delay(8_000L)
            while (isActive) {
                try {
                    Log.d(TAG, "Sync loop checking for new notices/announcements...")
                    val fetched = FirebaseService.fetchNotices()
                    appDao.loadCachedNotices()
                    val currentLocal = appDao.getNoticesList()
                    val localIds = currentLocal.map { it.id }.toSet()
                    val newNotices = fetched.filter { it.id !in localIds }

                    if (newNotices.isNotEmpty()) {
                        Log.d(TAG, "Found ${newNotices.size} new announcements in background sync loop!")
                        // Add to local database
                        val updatedList = (fetched + currentLocal).distinctBy { it.id }
                        appDao.insertNotices(updatedList)

                        // If not empty (means not first-time cold boot setup), notify user
                        if (currentLocal.isNotEmpty()) {
                            for (notice in newNotices) {
                                showSystemNotification(notice)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background sync periodic polling error: ${e.message}")
                }
                // Poll every 60 seconds
                delay(60_000L)
            }
        }
    }

    private fun showSystemNotification(notice: NoticeEntity) {
        try {
            val channelId = "announcements_channel"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Dark Store Announcements",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Global notifications sent by administrators"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent().apply {
                setClassName(packageName, "com.example.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("view_notice_id", notice.id)
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                notice.id.hashCode(),
                intent,
                pendingIntentFlags
            )

            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(notice.title)
                .setContentText(notice.message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.message))

            notificationManager.notify(notice.id.hashCode(), builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show system notification screen: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "StoreBackgroundService onDestroy")
        serviceJob.cancel()
        isRunning = false
    }
}
