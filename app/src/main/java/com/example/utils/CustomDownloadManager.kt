package com.example.utils

import android.content.Context
import android.util.Log
import com.example.data.AppEntity
import com.example.data.DownloadEntity
import com.example.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

class CustomDownloadManager(
    private val context: Context,
    private val repository: AppRepository
) {
    companion object {
        private val parentJob = SupervisorJob()
        val downloadScope = CoroutineScope(Dispatchers.IO + parentJob)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .cookieJar(InMemoryCookieJar())
        .build()
    private val TAG = "CustomDownloadManager"

    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val activeCalls = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Call>()

    fun registerJob(id: String, job: kotlinx.coroutines.Job) {
        activeJobs[id] = job
        job.invokeOnCompletion { activeJobs.remove(id, job) }
    }

    fun cancelDownload(id: String) {
        activeJobs[id]?.cancel()
        try {
            activeCalls[id]?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling call for $id: ${e.message}")
        }
    }

    suspend fun prepareForNewDownload(id: String) {
        val oldCall = activeCalls[id]
        try {
            oldCall?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling old call in prepare: ${e.message}")
        }
        
        val oldJob = activeJobs[id]
        if (oldJob != null && oldJob.isActive) {
            oldJob.cancel()
            try {
                oldJob.join()
            } catch (e: Exception) {
                // Ignore
            }
        }
        activeCalls.remove(id)
        activeJobs.remove(id)
    }

    suspend fun startDownload(app: AppEntity) = withContext(Dispatchers.IO) {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val targetFolder = File(baseDir, "apks").apply { if (!exists()) mkdirs() }
        val targetFile = File(targetFolder, "${app.id}_${app.version}.apk")

        Log.d(TAG, "Starting download of ${app.name} from: ${app.apkUrl}")

        var download = DownloadEntity(
            id = app.id,
            name = app.name,
            packageName = app.packageName,
            status = "DOWNLOADING",
            progress = 0,
            size = app.size,
            localFilePath = targetFile.absolutePath
        )
        repository.insertDownload(download)

        if (app.apkUrl.startsWith("file://")) {
            try {
                val sourceFile = File(app.apkUrl.removePrefix("file://"))
                if (sourceFile.exists()) {
                    sourceFile.inputStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val completedDownload = download.copy(
                        status = "COMPLETED",
                        progress = 100,
                        localFilePath = targetFile.absolutePath
                    )
                    repository.insertDownload(completedDownload)
                    return@withContext
                } else {
                    throw Exception("Local source APK not found at: ${sourceFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local file copy download failing: ${e.message}", e)
                val failedDownload = download.copy(status = "FAILED")
                repository.insertDownload(failedDownload)
                return@withContext
            }
        }

        var activeCall: okhttp3.Call? = null
        try {
            var responseToProcess: okhttp3.Response? = null
            var downloadUrl = app.apkUrl
            val fileId = extractGoogleDriveFileId(app.apkUrl)

            if (fileId != null) {
                Log.d(TAG, "Google Drive link detected. File ID: $fileId")
                val initialUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                val initialRequest = Request.Builder()
                    .url(initialUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    .build()

                val call = client.newCall(initialRequest)
                activeCall = call
                activeCalls[app.id] = call

                val response = call.execute()
                val contentType = response.body?.contentType()?.toString() ?: ""
                val contentDisposition = response.headers["Content-Disposition"] ?: ""

                if (contentType.contains("text/html", ignoreCase = true) && !contentDisposition.contains("attachment", ignoreCase = true)) {
                    val htmlBody = response.body?.string() ?: ""
                    response.close()

                    val token = extractConfirmToken(htmlBody)

                    if (token != null) {
                        Log.d(TAG, "Extracted Google Drive confirmation token: $token")
                        downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId&confirm=$token"
                    } else {
                        when {
                            htmlBody.contains("quota", ignoreCase = true) || 
                            htmlBody.contains("exceeded", ignoreCase = true) || 
                            htmlBody.contains("limit", ignoreCase = true) -> {
                                throw Exception("Google Drive download quota exceeded for this file.")
                            }
                            htmlBody.contains("access", ignoreCase = true) || 
                            htmlBody.contains("permission", ignoreCase = true) || 
                            htmlBody.contains("sign in", ignoreCase = true) ||
                            htmlBody.contains("unauthorized", ignoreCase = true) -> {
                                throw Exception("Access denied. Ensure the Google Drive file is shared publicly ('Anyone with the link can view').")
                            }
                            htmlBody.contains("not exist", ignoreCase = true) || 
                            htmlBody.contains("not found", ignoreCase = true) ||
                            htmlBody.contains("404", ignoreCase = true) -> {
                                throw Exception("Google Drive file not found. Please check if the file was deleted.")
                            }
                            else -> {
                                throw Exception("Failed to retrieve download link from Google Drive. Ensure the file is shared publicly.")
                            }
                        }
                    }
                } else {
                    responseToProcess = response
                }
            }

            val responseToUse = if (responseToProcess != null) {
                responseToProcess
            } else {
                val directRequest = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    .build()
                val directCall = client.newCall(directRequest)
                activeCall = directCall
                activeCalls[app.id] = directCall
                directCall.execute()
            }

            responseToUse.use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Server returned code ${response.code}")
                }

                val body = response.body ?: throw Exception("Response body is empty")
                val isContentLengthProvided = body.contentLength() > 0
                var totalBytes = body.contentLength()
                if (!isContentLengthProvided) {
                    val parsedSize = try {
                        val clean = app.size.uppercase().trim()
                        val numberPart = clean.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
                        val multiplier = when {
                            clean.contains("GB") -> 1024L * 1024L * 1024L
                            clean.contains("MB") -> 1024L * 1024L
                            clean.contains("KB") -> 1024L
                            else -> 1024L * 1024L
                        }
                        (numberPart * multiplier).toLong()
                    } catch (e: Exception) {
                        0L
                    }
                    if (parsedSize > 0) {
                        totalBytes = parsedSize
                    }
                }

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesDownloaded = 0L
                val startTime = System.currentTimeMillis()
                
                var lastUpdateTime = startTime
                var lastSavedProgress = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!coroutineContext.isActive) {
                        throw kotlinx.coroutines.CancellationException("Download cancelled")
                    }
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesDownloaded += bytesRead

                    val currentTime = System.currentTimeMillis()
                    // Throttled 500ms intervals to prevent database and thread performance overheads
                    if (currentTime - lastUpdateTime >= 500L || totalBytesDownloaded >= totalBytes) {
                        lastUpdateTime = currentTime
                        
                        val progress = if (totalBytes > 0) {
                            ((totalBytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        // Prevent backward spikes or progress percentage jumps
                        if (progress >= lastSavedProgress) {
                            lastSavedProgress = progress

                            val elapsedTime = (currentTime - startTime) / 1000.0
                            val downloadSpeed = if (elapsedTime > 0) {
                                val speedKB = (totalBytesDownloaded / 1024.0) / elapsedTime
                                if (speedKB > 1024) {
                                    String.format(Locale.getDefault(), "%.1f MB/s", speedKB / 1024.0)
                                } else {
                                    String.format(Locale.getDefault(), "%.0f KB/s", speedKB)
                                }
                            } else {
                                "0 KB/s"
                            }

                            download = download.copy(
                                status = "DOWNLOADING",
                                progress = progress,
                                downloadedBytes = totalBytesDownloaded,
                                totalBytes = totalBytes,
                                downloadSpeed = downloadSpeed
                            )
                            repository.insertDownload(download)
                            NotificationHelper.showDownloadProgress(context, app.name, progress, app.id.hashCode())
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                if (isContentLengthProvided && totalBytes > 0 && totalBytesDownloaded < totalBytes) {
                    throw Exception("Download truncated: downloaded $totalBytesDownloaded of $totalBytes bytes")
                }

                // Verify package archive integrity immediately to fail fast and delete corrupt files
                val packageInfo = try {
                    context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
                } catch (pe: Exception) {
                    null
                }
                if (packageInfo == null) {
                    throw Exception("Problem parsing the package: APK file corrupted or incomplete.")
                }

                Log.d(TAG, "Completed download of ${app.name} successfully.")
                download = download.copy(
                    status = "DOWNLOADED",
                    progress = 100,
                    downloadSpeed = "Done"
                )
                repository.insertDownload(download)
                
                NotificationHelper.showDownloadCompleted(context, app.name, targetFile.absolutePath, app.id.hashCode())
                
                // Trigger auto-install layout
                withContext(Dispatchers.Main) {
                    ApkInstaller.installApk(context, targetFile)
                }
            }
        } catch (e: Exception) {
            val isCancelled = !coroutineContext.isActive || 
                e is kotlinx.coroutines.CancellationException || 
                e.message?.contains("canceled", ignoreCase = true) == true ||
                e.message?.contains("closed", ignoreCase = true) == true
            
            Log.e(TAG, "Failed downloading app: ${e.message}, isCancelled=$isCancelled", e)
            try {
                if (targetFile.exists()) {
                    targetFile.delete()
                    Log.d(TAG, "Deleted partially downloaded or faulty APK file: ${targetFile.absolutePath}")
                }
            } catch (delEx: Exception) {
                Log.e(TAG, "Exception while cleaning up faulty APK file: ${delEx.message}")
            }
            if (!isCancelled) {
                download = download.copy(
                    status = "FAILED",
                    downloadSpeed = "Failed: ${e.localizedMessage ?: "Unknown Error"}"
                )
                repository.insertDownload(download)
            } else {
                Log.d(TAG, "Download of ${app.name} was cancelled by user. Deleting download record from database.")
                try {
                    repository.deleteDownload(app.id)
                } catch (dbEx: Exception) {
                    Log.e(TAG, "Failed to delete download in cancelled catch block: ${dbEx.message}")
                }
            }
        } finally {
            activeCall?.let { activeCalls.remove(app.id, it) }
            coroutineContext[kotlinx.coroutines.Job]?.let { activeJobs.remove(app.id, it) }
        }
    }

    private fun extractGoogleDriveFileId(url: String): String? {
        if (!url.contains("google.com", ignoreCase = true)) {
            return null
        }
        val fileDRegex = "(?:drive|docs)\\.google\\.com/file/d/([^/\\s?#]+)".toRegex(RegexOption.IGNORE_CASE)
        val matchD = fileDRegex.find(url)
        if (matchD != null) {
            return matchD.groupValues[1]
        }
        
        val idRegex = "[?&]id=([^&\\s#]+)".toRegex(RegexOption.IGNORE_CASE)
        val matchId = idRegex.find(url)
        if (matchId != null) {
            return matchId.groupValues[1]
        }
        
        return null
    }

    private fun extractConfirmToken(htmlBody: String): String? {
        // Pattern 1: URL parameter confirm=XXXX
        val urlParamRegex = """confirm=([^"&'\s>]+)""".toRegex(RegexOption.IGNORE_CASE)
        val matchParam = urlParamRegex.find(htmlBody)
        if (matchParam != null) {
            return matchParam.groupValues[1]
        }

        // Pattern 2: Form hidden input <input type="hidden" name="confirm" value="XXXX">
        val inputTagRegex = """name="confirm"\s+value="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
        val matchInput = inputTagRegex.find(htmlBody)
        if (matchInput != null) {
            return matchInput.groupValues[1]
        }

        // Pattern 3: Form hidden input alternate order <input value="XXXX" type="hidden" name="confirm">
        val inputTagRegex2 = """value="([^"]+)"\s+name="confirm"""".toRegex(RegexOption.IGNORE_CASE)
        val matchInput2 = inputTagRegex2.find(htmlBody)
        if (matchInput2 != null) {
            return matchInput2.groupValues[1]
        }
        
        // Pattern 4: any token-like confirm string in form actions or scripts
        val confirmTokenRegex = """["']confirm["']\s*,\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        val matchScript = confirmTokenRegex.find(htmlBody)
        if (matchScript != null) {
            return matchScript.groupValues[1]
        }

        return null
    }

    suspend fun downloadSelfUpdate(
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val targetFolder = File(baseDir, "updates").apply { if (!exists()) mkdirs() }
        val targetFile = File(targetFolder, "DarkStore_update.apk")
        if (targetFile.exists()) {
            targetFile.delete()
        }

        var downloadUrl = apkUrl
        val fileId = extractGoogleDriveFileId(apkUrl)

        var responseToProcess: okhttp3.Response? = null
        var activeCall: okhttp3.Call? = null

        try {
            if (fileId != null) {
                Log.d("SelfUpdate", "Google Drive link detected. File ID: $fileId")
                val initialUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                val initialRequest = Request.Builder()
                    .url(initialUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    .build()

                val call = client.newCall(initialRequest)
                activeCall = call
                val response = call.execute()
                val contentType = response.body?.contentType()?.toString() ?: ""
                val contentDisposition = response.headers["Content-Disposition"] ?: ""

                if (contentType.contains("text/html", ignoreCase = true) && !contentDisposition.contains("attachment", ignoreCase = true)) {
                    val htmlBody = response.body?.string() ?: ""
                    response.close()

                    val token = extractConfirmToken(htmlBody)

                    if (token != null) {
                        Log.d("SelfUpdate", "Extracted Google Drive confirmation token: $token")
                        downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId&confirm=$token"
                    } else {
                        when {
                            htmlBody.contains("quota", ignoreCase = true) || 
                            htmlBody.contains("exceeded", ignoreCase = true) || 
                            htmlBody.contains("limit", ignoreCase = true) -> {
                                throw Exception("Google Drive download quota exceeded for this file.")
                            }
                            htmlBody.contains("access", ignoreCase = true) || 
                            htmlBody.contains("permission", ignoreCase = true) || 
                            htmlBody.contains("sign in", ignoreCase = true) ||
                            htmlBody.contains("unauthorized", ignoreCase = true) -> {
                                throw Exception("Access denied. Ensure the Google Drive file is shared publicly ('Anyone with the link can view').")
                            }
                            htmlBody.contains("not exist", ignoreCase = true) || 
                            htmlBody.contains("not found", ignoreCase = true) ||
                            htmlBody.contains("404", ignoreCase = true) -> {
                                throw Exception("Google Drive file not found. Please check if the file was deleted.")
                            }
                            else -> {
                                throw Exception("Failed to retrieve download link from Google Drive. Ensure the file is shared publicly.")
                            }
                        }
                    }
                } else {
                    responseToProcess = response
                }
            }

            val responseToUse = if (responseToProcess != null) {
                responseToProcess
            } else {
                val directRequest = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    .build()
                val directCall = client.newCall(directRequest)
                activeCall = directCall
                directCall.execute()
            }

            responseToUse.use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Server returned code ${response.code}")
                }

                val body = response.body ?: throw Exception("Response body is empty")
                val totalBytes = body.contentLength()

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesDownloaded = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesDownloaded += bytesRead

                    if (totalBytes > 0) {
                        val progress = ((totalBytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(progress)
                    } else {
                        onProgress(-1) // Indeterminate progress
                    }
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }

            targetFile
        } catch (e: Exception) {
            try {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
            } catch (ex: Exception) {
                // Ignore
            }
            throw e
        }
    }
}

class InMemoryCookieJar : CookieJar {
    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host.lowercase(Locale.US)
        val domainMap = cookieStore.getOrPut(host) { java.util.concurrent.ConcurrentHashMap() }
        for (cookie in cookies) {
            domainMap[cookie.name] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host.lowercase(Locale.US)
        val result = mutableListOf<Cookie>()
        
        // Share cookies between all google.com / googleapis.com subdomains for seamless warning page bypass
        val isGoogle = host.contains("google.com") || host.contains("googleapis.com")
        
        for ((storedHost, cookiesMap) in cookieStore) {
            if (isGoogle && (storedHost.contains("google.com") || storedHost.contains("googleapis.com"))) {
                result.addAll(cookiesMap.values)
            } else if (host == storedHost || host.endsWith(".$storedHost") || storedHost.endsWith(".$host")) {
                result.addAll(cookiesMap.values)
            }
        }
        
        // Filter out expired cookies
        val now = System.currentTimeMillis()
        return result.filter { it.expiresAt > now }
    }
}
