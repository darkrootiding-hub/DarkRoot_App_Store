package com.example.data

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers

object FirebaseService {
    private const val TAG = "FirebaseService"
    
    @Volatile
    var activeToken: String = ""

    fun getTokenParam(): String {
        val token = activeToken
        return if (token.isNotBlank() && !token.startsWith("sim_") && !token.startsWith("fake_")) {
            "?auth=$token"
        } else {
            ""
        }
    }

    fun isRealToken(): Boolean {
        val token = activeToken
        return token.isNotBlank() && !token.startsWith("sim_") && !token.startsWith("fake_")
    }
    
    var RTDB_URL = "https://dark-store-6836d-default-rtdb.asia-southeast1.firebasedatabase.app/"
    var FIRESTORE_BASE_URL = "https://firestore.googleapis.com/v1/projects/dark-store-6836d/databases/(default)/documents/apps"
    
    fun updateConfig(projId: String, rtdb: String) {
        if (projId.isNotBlank()) {
            FIRESTORE_BASE_URL = "https://firestore.googleapis.com/v1/projects/${projId.trim()}/databases/(default)/documents/apps"
        }
        if (rtdb.isNotBlank()) {
            var url = rtdb.trim()
            if (!url.endsWith("/")) url += "/"
            RTDB_URL = url
        }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ----------------------------------------------------
    // REALTIME DATABASE PARSER & ENDPOINTS
    // ----------------------------------------------------

    fun parseFirebaseResponse(jsonStr: String?): List<AppEntity> {
        if (jsonStr.isNullOrBlank() || jsonStr == "null" || jsonStr == "{}") {
            return emptyList()
        }

        // Try Pattern 1: Map<String, AppEntity>
        try {
            val mapType = Types.newParameterizedType(Map::class.java, String::class.java, AppEntity::class.java)
            val adapter = moshi.adapter<Map<String, AppEntity>>(mapType)
            val map = adapter.fromJson(jsonStr)
            if (map != null) {
                return map.values.toList()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Parsing RTDB response as map failed, trying as sparse list...")
        }

        // Try Pattern 2: List<AppEntity?>
        try {
            val listType = Types.newParameterizedType(List::class.java, AppEntity::class.java)
            val adapter = moshi.adapter<List<AppEntity?>>(listType)
            val list = adapter.fromJson(jsonStr)
            if (list != null) {
                return list.filterNotNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing RTDB response as list failed: ${e.message}", e)
        }

        return emptyList()
    }

    private fun fetchAppsFromRTDB(): List<AppEntity> {
        val tokenParam = getTokenParam()
        val request = Request.Builder()
            .url("${RTDB_URL}apps.json$tokenParam")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "RTDB HTTP error: ${response.code}")
                    return emptyList()
                }
                val bodyStr = response.body?.string()
                Log.d(TAG, "RTDB Response: $bodyStr")
                parseFirebaseResponse(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTDB Network error: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveAppToRTDB(app: AppEntity): Boolean {
        val adapter = moshi.adapter(AppEntity::class.java)
        val jsonStr = adapter.toJson(app)
        
        val body = jsonStr.toRequestBody(jsonMediaType)
        val tokenParam = getTokenParam()
        val request = Request.Builder()
            .url("${RTDB_URL}apps/${app.id}.json$tokenParam")
            .put(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated App ${app.name} in RTDB")
                    true
                } else {
                    Log.e(TAG, "Failed update App in RTDB: code ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving RTDB app: ${e.message}", e)
            false
        }
    }

    private fun deleteAppFromRTDB(id: String): Boolean {
        val tokenParam = getTokenParam()
        val request = Request.Builder()
            .url("${RTDB_URL}apps/$id.json$tokenParam")
            .delete()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully deleted App $id from RTDB")
                    true
                } else {
                    Log.e(TAG, "Failed delete App from RTDB: code ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting RTDB app: ${e.message}", e)
            false
        }
    }

    // ----------------------------------------------------
    // FIRESTORE DATABASE PARSER & ENDPOINTS
    // ----------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun parseFirestoreResponse(jsonStr: String?): List<AppEntity> {
        if (jsonStr.isNullOrBlank() || jsonStr.trim() == "{}") {
            return emptyList()
        }
        try {
            val adapter = moshi.adapter(Map::class.java)
            val responseMap = adapter.fromJson(jsonStr) ?: return emptyList()
            val documents = responseMap["documents"] as? List<Map<String, Any>> ?: return emptyList()
            
            val apps = mutableListOf<AppEntity>()
            for (doc in documents) {
                // Documents path usually has format: projects/{project_id}/databases/(default)/documents/apps/{app_id}
                val fields = doc["fields"] as? Map<String, Map<String, Any>> ?: continue
                
                val id = (fields["id"]?.get("stringValue") as? String) ?: ""
                val name = (fields["name"]?.get("stringValue") as? String) ?: ""
                val developer = (fields["developer"]?.get("stringValue") as? String) ?: ""
                val version = (fields["version"]?.get("stringValue") as? String) ?: ""
                val size = (fields["size"]?.get("stringValue") as? String) ?: ""
                val category = (fields["category"]?.get("stringValue") as? String) ?: ""
                val rating = (fields["rating"]?.get("stringValue") as? String) ?: ""
                val description = (fields["description"]?.get("stringValue") as? String) ?: ""
                val logo = (fields["logo"]?.get("stringValue") as? String) ?: ""
                val screenshots = (fields["screenshots"]?.get("stringValue") as? String) ?: ""
                val apkUrl = (fields["apkUrl"]?.get("stringValue") as? String) ?: ""
                val packageName = (fields["packageName"]?.get("stringValue") as? String) ?: ""
                
                val isFeatured = (fields["isFeatured"]?.get("booleanValue") as? Boolean) ?: false
                val isPremium = (fields["isPremium"]?.get("booleanValue") as? Boolean) ?: false
                val price = (fields["price"]?.get("stringValue") as? String) ?: ""
                val isUpcoming = (fields["isUpcoming"]?.get("booleanValue") as? Boolean) ?: false
                val isPopular = (fields["isPopular"]?.get("booleanValue") as? Boolean) ?: false
                val isRecent = (fields["isRecent"]?.get("booleanValue") as? Boolean) ?: false
                val isApproved = (fields["isApproved"]?.get("booleanValue") as? Boolean) ?: true
                val submittedBy = (fields["submittedBy"]?.get("stringValue") as? String) ?: ""
                val hasAds = (fields["hasAds"]?.get("booleanValue") as? Boolean) ?: false
                val isSuspended = (fields["isSuspended"]?.get("booleanValue") as? Boolean) ?: false
                val suspensionReason = (fields["suspensionReason"]?.get("stringValue") as? String) ?: ""
                val reportsJson = (fields["reportsJson"]?.get("stringValue") as? String) ?: ""
                
                val rawVersionCode = fields["versionCode"]?.get("integerValue")
                val versionCode = when (rawVersionCode) {
                    is String -> rawVersionCode.toIntOrNull() ?: 1
                    is Number -> rawVersionCode.toInt()
                    else -> 1
                }
                
                if (id.isNotBlank() && name.isNotBlank()) {
                    apps.add(
                        AppEntity(
                            id = id,
                            name = name,
                            developer = developer,
                            version = version,
                            size = size,
                            category = category,
                            rating = rating,
                            description = description,
                            logo = logo,
                            screenshots = screenshots,
                            apkUrl = apkUrl,
                            packageName = packageName,
                            isFeatured = isFeatured,
                            isPremium = isPremium,
                            price = price,
                            isUpcoming = isUpcoming,
                            isPopular = isPopular,
                            isRecent = isRecent,
                            versionCode = versionCode,
                            isApproved = isApproved,
                            submittedBy = submittedBy,
                            hasAds = hasAds,
                            isSuspended = isSuspended,
                            suspensionReason = suspensionReason,
                            reportsJson = reportsJson
                        )
                    )
                }
            }
            return apps
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Firestore GET response: ${e.message}", e)
            return emptyList()
        }
    }

    private fun fetchAppsFromFirestore(): List<AppEntity> {
        val requestBuilder = Request.Builder()
            .url(FIRESTORE_BASE_URL)
            .get()
        if (activeToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $activeToken")
        }
        val request = requestBuilder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Firestore HTTP error during fetch: ${response.code}")
                    return emptyList()
                }
                val bodyStr = response.body?.string()
                Log.d(TAG, "Firestore Response: $bodyStr")
                parseFirestoreResponse(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore Network error: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveAppToFirestore(app: AppEntity): Boolean {
        val fieldsMap = mapOf(
            "id" to mapOf("stringValue" to app.id),
            "name" to mapOf("stringValue" to app.name),
            "developer" to mapOf("stringValue" to app.developer),
            "version" to mapOf("stringValue" to app.version),
            "size" to mapOf("stringValue" to app.size),
            "category" to mapOf("stringValue" to app.category),
            "rating" to mapOf("stringValue" to app.rating),
            "description" to mapOf("stringValue" to app.description),
            "logo" to mapOf("stringValue" to app.logo),
            "screenshots" to mapOf("stringValue" to app.screenshots),
            "apkUrl" to mapOf("stringValue" to app.apkUrl),
            "packageName" to mapOf("stringValue" to app.packageName),
            "isFeatured" to mapOf("booleanValue" to app.isFeatured),
            "isPremium" to mapOf("booleanValue" to app.isPremium),
            "price" to mapOf("stringValue" to app.price),
            "isUpcoming" to mapOf("booleanValue" to app.isUpcoming),
            "isPopular" to mapOf("booleanValue" to app.isPopular),
            "isRecent" to mapOf("booleanValue" to app.isRecent),
            "versionCode" to mapOf("integerValue" to app.versionCode.toString()),
            "isApproved" to mapOf("booleanValue" to app.isApproved),
            "submittedBy" to mapOf("stringValue" to app.submittedBy),
            "hasAds" to mapOf("booleanValue" to app.hasAds),
            "isSuspended" to mapOf("booleanValue" to app.isSuspended),
            "suspensionReason" to mapOf("stringValue" to app.suspensionReason),
            "reportsJson" to mapOf("stringValue" to app.reportsJson)
        )
        val firestorePayload = mapOf("fields" to fieldsMap)
        
        val adapter = moshi.adapter(Map::class.java)
        val jsonStr = adapter.toJson(firestorePayload)
        
        val body = jsonStr.toRequestBody(jsonMediaType)
        
        // A standard PATCH request to the document URL will create or fully overwrite/update it.
        val requestBuilder = Request.Builder()
            .url("$FIRESTORE_BASE_URL/${app.id}")
            .patch(body)
        if (activeToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $activeToken")
        }
        val request = requestBuilder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated App ${app.name} in Firestore")
                    true
                } else {
                    Log.e(TAG, "Failed update App in Firestore: code ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving Firestore app: ${e.message}", e)
            false
        }
    }

    private fun deleteAppFromFirestore(id: String): Boolean {
        val requestBuilder = Request.Builder()
            .url("$FIRESTORE_BASE_URL/$id")
            .delete()
        if (activeToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $activeToken")
        }
        val request = requestBuilder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully deleted App $id from Firestore")
                    true
                } else {
                    Log.e(TAG, "Failed delete App from Firestore: code ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting Firestore app: ${e.message}", e)
            false
        }
    }

    // ----------------------------------------------------
    // DUAL DYNAMIC PUBLIC METHODS
    // ----------------------------------------------------

    suspend fun fetchApps(): List<AppEntity> = coroutineScope {
        val rtdbDeferred = async(Dispatchers.IO) { fetchAppsFromRTDB() }
        val firestoreDeferred = async(Dispatchers.IO) { fetchAppsFromFirestore() }
        
        val rtdbApps = rtdbDeferred.await()
        val firestoreApps = firestoreDeferred.await()
        
        val mergedList = mutableMapOf<String, AppEntity>()
        
        for (app in rtdbApps) {
            mergedList[app.id] = app
        }
        for (app in firestoreApps) {
            mergedList[app.id] = app
        }
        
        Log.d(TAG, "Fetched ${rtdbApps.size} apps from RTDB and ${firestoreApps.size} apps from Firestore. Merged: ${mergedList.size}")
        mergedList.values.toList()
    }

    fun saveApp(app: AppEntity): Boolean {
        val rtdbSuccess = saveAppToRTDB(app)
        val firestoreSuccess = saveAppToFirestore(app)
        return rtdbSuccess || firestoreSuccess
    }

    fun deleteApp(id: String): Boolean {
        val rtdbSuccess = deleteAppFromRTDB(id)
        val firestoreSuccess = deleteAppFromFirestore(id)
        return rtdbSuccess || firestoreSuccess
    }

    // ----------------------------------------------------
    // NOTICES & ANNOUNCEMENTS CAPABILITIES
    // ----------------------------------------------------

    val FIRESTORE_NOTICES_URL: String
        get() {
            val idx = FIRESTORE_BASE_URL.indexOf("/documents")
            return if (idx != -1) {
                FIRESTORE_BASE_URL.substring(0, idx) + "/documents/notices"
            } else {
                FIRESTORE_BASE_URL.replace("/apps", "/notices")
            }
        }

    fun parseRTDBNoticesResponse(jsonStr: String?): List<NoticeEntity> {
        if (jsonStr.isNullOrBlank() || jsonStr == "null" || jsonStr == "{}") {
            return emptyList()
        }
        try {
            val mapType = Types.newParameterizedType(Map::class.java, String::class.java, NoticeEntity::class.java)
            val adapter = moshi.adapter<Map<String, NoticeEntity>>(mapType)
            val map = adapter.fromJson(jsonStr)
            if (map != null) {
                return map.values.toList()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Parsing RTDB notices response as Map failed, trying as List...")
        }

        try {
            val listType = Types.newParameterizedType(List::class.java, NoticeEntity::class.java)
            val adapter = moshi.adapter<List<NoticeEntity?>>(listType)
            val list = adapter.fromJson(jsonStr)
            if (list != null) {
                return list.filterNotNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing RTDB notices response as List failed: ${e.message}", e)
        }
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    fun parseFirestoreNoticesResponse(jsonStr: String?): List<NoticeEntity> {
        if (jsonStr.isNullOrBlank() || jsonStr.trim() == "{}") {
            return emptyList()
        }
        try {
            val adapter = moshi.adapter(Map::class.java)
            val responseMap = adapter.fromJson(jsonStr) ?: return emptyList()
            val documents = responseMap["documents"] as? List<Map<String, Any>> ?: return emptyList()
            
            val notices = mutableListOf<NoticeEntity>()
            for (doc in documents) {
                val fields = doc["fields"] as? Map<String, Map<String, Any>> ?: continue
                
                val id = (fields["id"]?.get("stringValue") as? String) ?: ""
                val title = (fields["title"]?.get("stringValue") as? String) ?: ""
                val message = (fields["message"]?.get("stringValue") as? String) ?: ""
                val imageUrl = (fields["imageUrl"]?.get("stringValue") as? String) ?: ""
                val targetAppId = (fields["targetAppId"]?.get("stringValue") as? String) ?: "all"
                
                val rawTimestamp = fields["timestamp"]?.get("integerValue")
                val timestamp = when (rawTimestamp) {
                    is String -> rawTimestamp.toLongOrNull() ?: 0L
                    is Number -> rawTimestamp.toLong()
                    else -> 0L
                }
                
                if (id.isNotBlank()) {
                    notices.add(
                        NoticeEntity(
                            id = id,
                            title = title,
                            message = message,
                            imageUrl = imageUrl,
                            timestamp = timestamp,
                            targetAppId = targetAppId
                        )
                    )
                }
            }
            return notices
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Firestore notices response: ${e.message}", e)
            return emptyList()
        }
    }

    private fun fetchNoticesFromRTDB(): List<NoticeEntity> {
        val tokenParam = getTokenParam()
        val request = Request.Builder()
            .url("${RTDB_URL}notices.json$tokenParam")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "RTDB notices HTTP error: ${response.code}")
                    return emptyList()
                }
                val bodyStr = response.body?.string()
                Log.d(TAG, "RTDB notices response: $bodyStr")
                parseRTDBNoticesResponse(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTDB notices network error: ${e.message}", e)
            emptyList()
        }
    }

    private fun fetchNoticesFromFirestore(): List<NoticeEntity> {
        val requestBuilder = Request.Builder()
            .url(FIRESTORE_NOTICES_URL)
            .get()
        if (activeToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $activeToken")
        }
        val request = requestBuilder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Firestore notices HTTP error: ${response.code}")
                    return emptyList()
                }
                val bodyStr = response.body?.string()
                Log.d(TAG, "Firestore notices response: $bodyStr")
                parseFirestoreNoticesResponse(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore notices network error: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveNoticeToRTDB(notice: NoticeEntity): Pair<Boolean, String> {
        val adapter = moshi.adapter(NoticeEntity::class.java)
        val jsonStr = adapter.toJson(notice)
        val body = jsonStr.toRequestBody(jsonMediaType)
        val tokenParam = getTokenParam()
        val request = Request.Builder()
            .url("${RTDB_URL}notices/${notice.id}.json$tokenParam")
            .put(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated Notice ${notice.title} in RTDB")
                    Pair(true, "RTDB: Success")
                } else {
                    val code = response.code
                    val msg = response.body?.string()?.take(100) ?: ""
                    Log.e(TAG, "Failed update Notice in RTDB: code $code, msg: $msg")
                    Pair(false, "RTDB code $code ($msg)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving RTDB notice: ${e.message}", e)
            Pair(false, "RTDB error: ${e.message}")
        }
    }

    private fun saveNoticeToFirestore(notice: NoticeEntity): Pair<Boolean, String> {
        val fieldsMap = mapOf(
            "id" to mapOf("stringValue" to notice.id),
            "title" to mapOf("stringValue" to notice.title),
            "message" to mapOf("stringValue" to notice.message),
            "imageUrl" to mapOf("stringValue" to notice.imageUrl),
            "timestamp" to mapOf("integerValue" to notice.timestamp.toString()),
            "targetAppId" to mapOf("stringValue" to notice.targetAppId)
        )
        val firestorePayload = mapOf("fields" to fieldsMap)
        val adapter = moshi.adapter(Map::class.java)
        val jsonStr = adapter.toJson(firestorePayload)
        val body = jsonStr.toRequestBody(jsonMediaType)
        
        val requestBuilder = Request.Builder()
            .url("$FIRESTORE_NOTICES_URL/${notice.id}")
            .patch(body)
        if (activeToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $activeToken")
        }
        val request = requestBuilder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated Notice ${notice.title} in Firestore")
                    Pair(true, "Firestore: Success")
                } else {
                    val code = response.code
                    val msg = response.body?.string()?.take(100) ?: ""
                    Log.e(TAG, "Failed update Notice in Firestore: code $code, msg: $msg")
                    Pair(false, "Firestore code $code ($msg)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving Firestore notice: ${e.message}", e)
            Pair(false, "Firestore error: ${e.message}")
        }
    }

    suspend fun fetchNotices(): List<NoticeEntity> = coroutineScope {
        val rtdbDeferred = async(Dispatchers.IO) { fetchNoticesFromRTDB() }
        val firestoreDeferred = async(Dispatchers.IO) { fetchNoticesFromFirestore() }
        
        val rtdbNotices = rtdbDeferred.await()
        val firestoreNotices = firestoreDeferred.await()
        
        val mergedList = mutableMapOf<String, NoticeEntity>()
        for (n in rtdbNotices) {
            mergedList[n.id] = n
        }
        for (n in firestoreNotices) {
            mergedList[n.id] = n
        }
        Log.d(TAG, "Fetched notices catalog. Merged size: ${mergedList.size}")
        mergedList.values.sortedByDescending { it.timestamp }
    }

    fun saveNotice(notice: NoticeEntity): Pair<Boolean, String> {
        val rtdbRes = saveNoticeToRTDB(notice)
        val firestoreRes = saveNoticeToFirestore(notice)
        val success = rtdbRes.first || firestoreRes.first
        val diagnosticMessage = "Realtime Database: ${rtdbRes.second} | Firestore: ${firestoreRes.second}"
        return Pair(success, diagnosticMessage)
    }

    private fun deleteNoticeFromRTDB(id: String): Boolean {
        val tokenParam = getTokenParam()
        val request = Request.Builder()
            .url("${RTDB_URL}notices/$id.json$tokenParam")
            .delete()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting RTDB notice: ${e.message}", e)
            false
        }
    }

    private fun deleteNoticeFromFirestore(id: String): Boolean {
        val requestBuilder = Request.Builder()
            .url("$FIRESTORE_NOTICES_URL/$id")
            .delete()
        if (activeToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $activeToken")
        }
        val request = requestBuilder.build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting Firestore notice: ${e.message}", e)
            false
        }
    }

    fun deleteNotice(id: String): Boolean {
        val rtdbRes = deleteNoticeFromRTDB(id)
        val firestoreRes = deleteNoticeFromFirestore(id)
        return rtdbRes || firestoreRes
    }

    @Suppress("UNCHECKED_CAST")
    fun sendFCMNotification(serverKey: String, notice: NoticeEntity): Pair<Boolean, String> {
        if (serverKey.trim().isBlank()) return Pair(false, "Server Key is blank")
        
        var topicName: String? = null
        val target = if (notice.targetAppId.startsWith("token:")) {
            notice.targetAppId.substringAfter("token:")
        } else {
            val tName = if (notice.targetAppId == "all") "all" else "app_${notice.targetAppId.replace(".", "_")}"
            topicName = tName
            "/topics/$tName"
        }
        
        val payload = mapOf(
            "to" to target,
            "priority" to "high",
            "time_to_live" to 2419200, // 4 weeks delivery window
            "notification" to mapOf(
                "title" to notice.title,
                "body" to notice.message,
                "sound" to "default",
                "android_channel_id" to "announcements_channel"
            ),
            "data" to mapOf(
                "id" to notice.id,
                "title" to notice.title,
                "message" to notice.message,
                "imageUrl" to notice.imageUrl,
                "targetAppId" to notice.targetAppId,
                "timestamp" to notice.timestamp.toString()
            )
        )
        
        return try {
            val adapter = moshi.adapter(Map::class.java)
            val jsonStr = adapter.toJson(payload)
            val body = jsonStr.toRequestBody(jsonMediaType)
            
            val request = Request.Builder()
                .url("https://fcm.googleapis.com/fcm/send")
                .addHeader("Authorization", "key=${serverKey.trim()}")
                .post(body)
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully broadcast FCM push notification to: ${topicName ?: "Direct Device Token"}")
                    Pair(true, "Successfully transmitted to ${topicName?.let { "FCM topic '$it'" } ?: "direct client token"}")
                } else {
                    val errorBody = response.body?.string()?.take(150) ?: ""
                    Log.e(TAG, "Failed FCM send. HTTP code: ${response.code}, body: $errorBody")
                    Pair(false, "FCM Server returned HTTP ${response.code}: $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FCM broadcast transmission: ${e.message}", e)
            Pair(false, "Exception: ${e.message}")
        }
    }

    fun saveTermsAgreement(agreement: TermsAgreementEntity): Boolean {
        return try {
            val adapter = moshi.adapter(TermsAgreementEntity::class.java)
            val jsonStr = adapter.toJson(agreement)
            val body = jsonStr.toRequestBody(jsonMediaType)
            val tokenParam = getTokenParam()
            val request = Request.Builder()
                .url("${RTDB_URL}terms_agreements/${agreement.id}.json$tokenParam")
                .put(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated terms agreement for ${agreement.userEmail} in RTDB")
                    true
                } else {
                    Log.e(TAG, "Failed update terms agreement in RTDB: code ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving RTDB terms agreement: ${e.message}", e)
            false
        }
    }

    fun parseTermsAgreementsResponse(jsonStr: String?): List<TermsAgreementEntity> {
        if (jsonStr.isNullOrBlank() || jsonStr == "null" || jsonStr == "{}") {
            return emptyList()
        }
        // Try parsing as Map first
        try {
            val mapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, TermsAgreementEntity::class.java)
            val adapter = moshi.adapter<Map<String, TermsAgreementEntity>>(mapType)
            val map = adapter.fromJson(jsonStr)
            if (!map.isNullOrEmpty()) {
                return map.values.filterNotNull()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Parsing RTDB terms agreements as map failed, trying as list: ${e.message}")
        }
        // Try parsing as List second
        try {
            val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, TermsAgreementEntity::class.java)
            val adapter = moshi.adapter<List<TermsAgreementEntity>>(listType)
            val list = adapter.fromJson(jsonStr)
            if (list != null) {
                return list.filterNotNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing RTDB terms agreements as list failed: ${e.message}", e)
        }
        return emptyList()
    }

    fun fetchTermsAgreements(): List<TermsAgreementEntity> {
        return try {
            val tokenParam = getTokenParam()
            val request = Request.Builder()
                .url("${RTDB_URL}terms_agreements.json$tokenParam")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "RTDB terms agreements HTTP error: ${response.code}")
                    return emptyList()
                }
                val bodyStr = response.body?.string()
                Log.d(TAG, "RTDB TermsAgreements Response: $bodyStr")
                parseTermsAgreementsResponse(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTDB TermsAgreements Network error: ${e.message}", e)
            emptyList()
        }
    }

    fun saveAppPolicy(policy: AppPolicyEntity): Boolean {
        return try {
            val adapter = moshi.adapter(AppPolicyEntity::class.java)
            val jsonStr = adapter.toJson(policy)
            val body = jsonStr.toRequestBody(jsonMediaType)
            val tokenParam = getTokenParam()
            val request = Request.Builder()
                .url("${RTDB_URL}app_policy.json$tokenParam")
                .put(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated app policy in RTDB")
                    true
                } else {
                    Log.e(TAG, "Failed to update app policy in RTDB: code ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving RTDB app policy: ${e.message}", e)
            false
        }
    }

    fun fetchAppPolicy(): AppPolicyEntity? {
        return try {
            val tokenParam = getTokenParam()
            val request = Request.Builder()
                .url("${RTDB_URL}app_policy.json$tokenParam")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "RTDB app policy HTTP error: ${response.code}")
                    return null
                }
                val bodyStr = response.body?.string()
                if (bodyStr.isNullOrBlank() || bodyStr == "null" || bodyStr == "{}") {
                    return null
                }
                val adapter = moshi.adapter(AppPolicyEntity::class.java)
                adapter.fromJson(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTDB AppPolicy Network error: ${e.message}", e)
            null
        }
    }

    fun fetchUpdateConfig(): UpdateConfigEntity? {
        // 1. Try RTDB
        try {
            val tokenParam = getTokenParam()
            val request = Request.Builder()
                .url("${RTDB_URL}DarkStoreUpdate.json$tokenParam")
                .get()
                .build()
            val update = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    Log.d(TAG, "RTDB Update Response: $bodyStr")
                    if (!bodyStr.isNullOrBlank() && bodyStr != "null" && bodyStr != "{}") {
                        val adapter = moshi.adapter(UpdateConfigEntity::class.java)
                        adapter.fromJson(bodyStr)
                    } else null
                } else {
                    Log.e(TAG, "RTDB update check HTTP error: ${response.code}")
                    null
                }
            }
            if (update != null && update.latestVersionCode > 0) {
                return update
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTDB update check Network error: ${e.message}")
        }

        // 2. Try Firestore
        try {
            val idx = FIRESTORE_BASE_URL.indexOf("/documents")
            val firestoreUpdateUrl = if (idx != -1) {
                FIRESTORE_BASE_URL.substring(0, idx) + "/documents/configs/DarkStoreUpdate"
            } else {
                FIRESTORE_BASE_URL.replace("/apps", "/configs") + "/DarkStoreUpdate"
            }

            val requestBuilder = Request.Builder()
                .url(firestoreUpdateUrl)
                .get()
            if (activeToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $activeToken")
            }
            val request = requestBuilder.build()

            val update = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    Log.d(TAG, "Firestore Update Response: $bodyStr")
                    if (!bodyStr.isNullOrBlank() && bodyStr != "null" && bodyStr != "{}") {
                        val adapter = moshi.adapter(Map::class.java)
                        val responseMap = adapter.fromJson(bodyStr) as? Map<String, Any>
                        val fields = responseMap?.get("fields") as? Map<String, Map<String, Any>>
                        if (fields != null) {
                            val rawLatestVersionCode = fields["latestVersionCode"]?.get("integerValue")
                            val latestVersionCode = when (rawLatestVersionCode) {
                                is String -> rawLatestVersionCode.toIntOrNull() ?: 0
                                is Number -> rawLatestVersionCode.toInt()
                                else -> 0
                            }
                            
                            val latestVersionName = (fields["latestVersionName"]?.get("stringValue") as? String) ?: ""
                            val apkDownloadUrl = (fields["apkDownloadUrl"]?.get("stringValue") as? String) ?: ""
                            val updateTitle = (fields["updateTitle"]?.get("stringValue") as? String) ?: ""
                            val updateMessage = (fields["updateMessage"]?.get("stringValue") as? String) ?: ""
                            val forceUpdate = (fields["forceUpdate"]?.get("booleanValue") as? Boolean) ?: false
                            
                            UpdateConfigEntity(
                                latestVersionCode = latestVersionCode,
                                latestVersionName = latestVersionName,
                                apkDownloadUrl = apkDownloadUrl,
                                updateTitle = updateTitle,
                                updateMessage = updateMessage,
                                forceUpdate = forceUpdate
                            )
                        } else null
                    } else null
                } else {
                    Log.e(TAG, "Firestore update check HTTP error: ${response.code}")
                    null
                }
            }
            if (update != null) {
                return update
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore update check Network error: ${e.message}")
        }

        return null
    }

    fun saveUpdateConfig(config: UpdateConfigEntity): Pair<Boolean, String?> {
        val errors = mutableListOf<String>()
        var rtdbSuccess = false
        var firestoreSuccess = false

        // 1. Save to RTDB
        try {
            val adapter = moshi.adapter(UpdateConfigEntity::class.java)
            val jsonStr = adapter.toJson(config)
            val body = jsonStr.toRequestBody(jsonMediaType)
            val tokenParam = getTokenParam()
            val requestUrl = "${RTDB_URL}DarkStoreUpdate.json$tokenParam"
            val request = Request.Builder()
                .url(requestUrl)
                .put(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated update config in RTDB")
                    rtdbSuccess = true
                } else {
                    val code = response.code
                    val errBody = response.body?.string() ?: ""
                    var errMsg = "RTDB Response HTTP $code"
                    if (errBody.isNotBlank()) {
                        errMsg += ": $errBody"
                    }
                    if (code == 401 || code == 403) {
                        errMsg += " (Authentication failure - check if database rules require auth, or if you are using a simulated/guest user)"
                    }
                    Log.e(TAG, "Failed to update update config in RTDB: $errMsg")
                    errors.add(errMsg)
                }
            }
        } catch (e: Exception) {
            val exMsg = "RTDB Exception: ${e.localizedMessage ?: e.message}"
            Log.e(TAG, "Exception saving RTDB update config: ${e.message}", e)
            errors.add(exMsg)
        }

        // 2. Save to Firestore
        try {
            val idx = FIRESTORE_BASE_URL.indexOf("/documents")
            val firestoreUpdateUrl = if (idx != -1) {
                FIRESTORE_BASE_URL.substring(0, idx) + "/documents/configs/DarkStoreUpdate"
            } else {
                FIRESTORE_BASE_URL.replace("/apps", "/configs") + "/DarkStoreUpdate"
            }

            val fieldsMap = mapOf(
                "latestVersionCode" to mapOf("integerValue" to config.latestVersionCode.toString()),
                "latestVersionName" to mapOf("stringValue" to config.latestVersionName),
                "apkDownloadUrl" to mapOf("stringValue" to config.apkDownloadUrl),
                "updateTitle" to mapOf("stringValue" to config.updateTitle),
                "updateMessage" to mapOf("stringValue" to config.updateMessage),
                "forceUpdate" to mapOf("booleanValue" to config.forceUpdate)
            )
            val firestorePayload = mapOf("fields" to fieldsMap)

            val adapter = moshi.adapter(Map::class.java)
            val jsonStr = adapter.toJson(firestorePayload)
            val body = jsonStr.toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(firestoreUpdateUrl)
                .patch(body)
            if (activeToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $activeToken")
            }
            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Successfully updated update config in Firestore")
                    firestoreSuccess = true
                } else {
                    val code = response.code
                    val errBody = response.body?.string() ?: ""
                    var errMsg = "Firestore Response HTTP $code"
                    if (errBody.isNotBlank()) {
                        errMsg += ": $errBody"
                    }
                    Log.e(TAG, "Failed to update update config in Firestore: $errMsg")
                    errors.add(errMsg)
                }
            }
        } catch (e: Exception) {
            val exMsg = "Firestore Exception: ${e.localizedMessage ?: e.message}"
            Log.e(TAG, "Exception saving Firestore update config: ${e.message}", e)
            errors.add(exMsg)
        }

        val overallSuccess = rtdbSuccess || firestoreSuccess
        
        // Add user-friendly prompt if they are on a simulated account
        if (!overallSuccess && !isRealToken()) {
            errors.add("Active session is running in Sandbox Simulation / Local Guest mode. No real authorization token is available to push to live database. Go to developer settings to verify real credentials.")
        }

        val combinedErrors = if (errors.isNotEmpty()) errors.joinToString("\n• ") else null
        return Pair(overallSuccess, combinedErrors)
    }
}
