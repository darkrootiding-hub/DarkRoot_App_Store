package com.example.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.data.AppDao
import com.example.data.NoticeEntity

class MyFirebaseMessagingService : FirebaseMessagingService() {
    private val TAG = "MyFirebaseMessagingService"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Device Token: $token")
        // Store latest device token in SharedPreferences for easy administrative viewing
        try {
            val prefs = getSharedPreferences("dark_store_fcm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("fcm_token", token).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Received message from FCM. Sender: ${remoteMessage.from}")

        val notificationTitle = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "Platform Alert"
        val notificationBody = remoteMessage.notification?.body ?: remoteMessage.data["message"] ?: remoteMessage.data["body"] ?: "New notice received"
        val imageUrl = remoteMessage.data["imageUrl"] ?: ""
        val targetAppId = remoteMessage.data["targetAppId"] ?: "all"
        val noticeId = remoteMessage.data["id"] ?: "ntc_${System.currentTimeMillis()}"
        val timestamp = remoteMessage.data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

        Log.d(TAG, "Received Notice via FCM. Title: $notificationTitle, Message: $notificationBody")

        // CLIENT-SIDE FILTERING SYSTEM BASED ON USER SETTINGS
        val sharedPrefs = getSharedPreferences("dark_store_pref", Context.MODE_PRIVATE)
        val notifyNewApps = sharedPrefs.getBoolean("notify_new_apps", true)
        val notifyUpdates = sharedPrefs.getBoolean("notify_updates", true)
        val notifyAnnouncements = sharedPrefs.getBoolean("notify_announcements", true)
        val notifySubmissions = sharedPrefs.getBoolean("notify_submissions", true)

        var isAllowed = true
        val titleLower = notificationTitle.lowercase()

        if (titleLower.contains("submission") || titleLower.contains("approved") || titleLower.contains("rejected")) {
            if (!notifySubmissions) {
                isAllowed = false
                Log.d(TAG, "Notification blocked: Submission alerts disabled.")
            }
        } else if (titleLower.contains("new app")) {
            if (!notifyNewApps) {
                isAllowed = false
                Log.d(TAG, "Notification blocked: New App alerts disabled.")
            }
        } else if (titleLower.contains("update pack") || titleLower.contains("update available") || titleLower.contains("changelog")) {
            if (!notifyUpdates) {
                isAllowed = false
                Log.d(TAG, "Notification blocked: App Update alerts disabled.")
            }
        } else {
            // General / announcements
            if (!notifyAnnouncements) {
                isAllowed = false
                Log.d(TAG, "Notification blocked: Announcement alerts disabled.")
            }
        }

        if (!isAllowed) {
            return
        }

        // Save notice instantly to localized DAO Cache so it appears on Notice Dashboard as UNREAD
        try {
            val appDao = AppDao(applicationContext)
            appDao.loadCachedNotices()
            val list = appDao.getNoticesList().toMutableList()
            if (list.none { it.id == noticeId }) {
                list.add(0, NoticeEntity(
                    id = noticeId,
                    title = notificationTitle,
                    message = notificationBody,
                    imageUrl = imageUrl,
                    timestamp = timestamp,
                    targetAppId = targetAppId,
                    isRead = false // Saved as unread entry!
                ))
                appDao.insertNotices(list)
                Log.d(TAG, "Notice successfully stored to DAO caching layer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed store notice payload: ${e.message}", e)
        }

        // Show systemic push notification banner
        sendNotification(noticeId, notificationTitle, notificationBody, targetAppId)
    }

    private fun sendNotification(noticeId: String, title: String, messageBody: String, targetAppId: String) {
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

            // ADAPTIVE DEEP LINKS RESOLUTION FOR NOTIFICATION CLICK ACTIONS
            val intent = Intent().apply {
                setClassName(packageName, "com.example.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("view_notice_id", noticeId)
                
                if (targetAppId.startsWith("approved_") || targetAppId.startsWith("rejected_")) {
                    putExtra("open_screen", "submissions")
                } else if (targetAppId.startsWith("update:")) {
                    putExtra("open_screen", "updates")
                    putExtra("app_id", targetAppId.substringAfter("update:"))
                } else if (targetAppId != "all" && !targetAppId.startsWith("token:")) {
                    putExtra("open_screen", "app_details")
                    putExtra("app_id", targetAppId)
                } else {
                    putExtra("open_screen", "announcements")
                }
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                noticeId.hashCode(),
                intent,
                pendingIntentFlags
            )

            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(messageBody)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))

            notificationManager.notify(noticeId.hashCode(), builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying push alert banner: ${e.message}", e)
        }
    }
}
