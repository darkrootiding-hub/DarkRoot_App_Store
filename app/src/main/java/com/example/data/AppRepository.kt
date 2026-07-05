package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AppRepository(private val appDao: AppDao) {

    val allApps: Flow<List<AppEntity>> = appDao.getApps()
    val allDownloads: Flow<List<DownloadEntity>> = appDao.getDownloads()

    suspend fun refreshApps() {
        withContext(Dispatchers.IO) {
            try {
                Log.d("AppRepository", "Refreshing apps from Firebase database...")
                val firebaseApps = FirebaseService.fetchApps()
                
                // Get local apps mapping to handle deletion checks
                val currentLocalApps = appDao.getAppsList()
                val firebaseIds = firebaseApps.map { it.id }.toSet()
                
                for (localApp in currentLocalApps) {
                    if (localApp.id !in firebaseIds) {
                        appDao.deleteApp(localApp)
                    }
                }

                if (firebaseApps.isNotEmpty()) {
                    appDao.insertApps(firebaseApps)
                    Log.d("AppRepository", "Successfully synced ${firebaseApps.size} apps from Firebase database.")
                } else {
                    Log.d("AppRepository", "No apps found on Firebase. Clearing local DB index.")
                    appDao.clearAllApps()
                }
            } catch (e: Exception) {
                Log.e("AppRepository", "Failed to refresh online apps: ${e.message}", e)
            }
        }
    }

    suspend fun getAppById(id: String): AppEntity? = withContext(Dispatchers.IO) {
        appDao.getAppById(id)
    }

    suspend fun saveApp(app: AppEntity): Boolean = withContext(Dispatchers.IO) {
        val success = FirebaseService.saveApp(app)
        if (success) {
            appDao.insertApps(listOf(app))
        }
        success
    }

    suspend fun deleteApp(id: String): Boolean = withContext(Dispatchers.IO) {
        val success = FirebaseService.deleteApp(id)
        if (success) {
            val localApp = appDao.getAppById(id)
            if (localApp != null) {
                appDao.deleteApp(localApp)
            }
        }
        success
    }

    // Download flow methods
    suspend fun insertDownload(download: DownloadEntity) = withContext(Dispatchers.IO) {
        appDao.insertDownload(download)
    }

    suspend fun getDownloadById(id: String): DownloadEntity? = withContext(Dispatchers.IO) {
        appDao.getDownloadById(id)
    }

    suspend fun getDownloadByPackageName(packageName: String): DownloadEntity? = withContext(Dispatchers.IO) {
        appDao.getDownloadByPackageName(packageName)
    }

    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        appDao.deleteDownloadById(id)
    }
}
