package com.example.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class AppDao(private val context: Context) {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // File pointers
    private val appsFile = File(context.filesDir, "cached_apps.json")
    private val downloadsFile = File(context.filesDir, "active_downloads.json")
    private val noticesFile = File(context.filesDir, "cached_notices.json")

    // Moshi list adapters
    private val appsListAdapter = moshi.adapter<List<AppEntity>>(
        Types.newParameterizedType(List::class.java, AppEntity::class.java)
    )
    private val downloadsListAdapter = moshi.adapter<List<DownloadEntity>>(
        Types.newParameterizedType(List::class.java, DownloadEntity::class.java)
    )
    private val noticesListAdapter = moshi.adapter<List<NoticeEntity>>(
        Types.newParameterizedType(List::class.java, NoticeEntity::class.java)
    )

    // Flow states
    private val _appsFlow = MutableStateFlow<List<AppEntity>>(emptyList())
    val appsFlow: StateFlow<List<AppEntity>> = _appsFlow.asStateFlow()

    private val _downloadsFlow = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadEntity>> = _downloadsFlow.asStateFlow()

    private val _noticesFlow = MutableStateFlow<List<NoticeEntity>>(emptyList())
    val noticesFlow: StateFlow<List<NoticeEntity>> = _noticesFlow.asStateFlow()

    init {
        loadCachedApps()
        loadCachedDownloads()
        loadCachedNotices()
    }

    private fun loadCachedApps() {
        try {
            if (appsFile.exists()) {
                val json = appsFile.readText()
                val list = appsListAdapter.fromJson(json)
                if (list != null) {
                    _appsFlow.value = list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCachedApps() {
        try {
            val json = appsListAdapter.toJson(_appsFlow.value)
            appsFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCachedDownloads() {
        try {
            if (downloadsFile.exists()) {
                val json = downloadsFile.readText()
                val list = downloadsListAdapter.fromJson(json)
                if (list != null) {
                    _downloadsFlow.value = list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCachedDownloads() {
        try {
            val json = downloadsListAdapter.toJson(_downloadsFlow.value)
            downloadsFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ----------------------------------------------------
    // APP REPOSITORY BRIDGE INTERFACES
    // ----------------------------------------------------

    fun getApps(): StateFlow<List<AppEntity>> = appsFlow

    fun getAppsList(): List<AppEntity> = _appsFlow.value

    fun getAppById(id: String): AppEntity? {
        return _appsFlow.value.find { it.id == id }
    }

    fun insertApps(apps: List<AppEntity>) {
        val current = _appsFlow.value.toMutableList()
        for (newApp in apps) {
            val index = current.indexOfFirst { it.id == newApp.id }
            if (index != -1) {
                current[index] = newApp
            } else {
                current.add(newApp)
            }
        }
        _appsFlow.value = current
        saveCachedApps()
    }

    fun deleteApp(app: AppEntity) {
        val current = _appsFlow.value.toMutableList()
        current.removeAll { it.id == app.id }
        _appsFlow.value = current
        saveCachedApps()
    }

    fun clearAllApps() {
        _appsFlow.value = emptyList()
        saveCachedApps()
    }

    // Downloads
    fun getDownloads(): StateFlow<List<DownloadEntity>> = downloadsFlow

    fun getDownloadById(id: String): DownloadEntity? {
        return _downloadsFlow.value.find { it.id == id }
    }

    fun getDownloadByPackageName(packageName: String): DownloadEntity? {
        return _downloadsFlow.value.find { it.packageName == packageName }
    }

    fun insertDownload(download: DownloadEntity) {
        val current = _downloadsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == download.id }
        if (index != -1) {
            current[index] = download
        } else {
            current.add(download)
        }
        _downloadsFlow.value = current
        saveCachedDownloads()
    }

    fun deleteDownloadById(id: String) {
        val current = _downloadsFlow.value.toMutableList()
        current.removeAll { it.id == id }
        _downloadsFlow.value = current
        saveCachedDownloads()
    }

    // Notices persistent methods
    fun getNotices(): StateFlow<List<NoticeEntity>> = noticesFlow

    fun getNoticesList(): List<NoticeEntity> = _noticesFlow.value

    fun insertNotices(notices: List<NoticeEntity>) {
        _noticesFlow.value = notices
        try {
            val json = noticesListAdapter.toJson(notices)
            noticesFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadCachedNotices() {
        try {
            if (noticesFile.exists()) {
                val json = noticesFile.readText()
                val list = noticesListAdapter.fromJson(json)
                if (list != null) {
                    _noticesFlow.value = list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
