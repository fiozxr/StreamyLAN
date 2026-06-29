package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServerManager {
    private const val PREFS_NAME = "lan_stream_prefs"
    private const val KEY_FILES = "shared_files_list"
    private const val KEY_PORT = "server_port"

    private val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles = _sharedFiles.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort = _serverPort.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl = _serverUrl.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices = _connectedDevices.asStateFlow()

    fun registerDeviceRequest(ip: String, userAgent: String) {
        val currentList = _connectedDevices.value
        val existingIndex = currentList.indexOfFirst { it.ipAddress == ip }
        if (existingIndex != -1) {
            val existingDevice = currentList[existingIndex]
            val updatedDevice = existingDevice.copy(
                userAgent = userAgent,
                lastActive = System.currentTimeMillis(),
                requestCount = existingDevice.requestCount + 1
            )
            _connectedDevices.value = currentList.toMutableList().apply {
                set(existingIndex, updatedDevice)
            }
        } else {
            val newDevice = ConnectedDevice(
                ipAddress = ip,
                userAgent = userAgent,
                lastActive = System.currentTimeMillis()
            )
            _connectedDevices.value = currentList + newDevice
        }
    }

    fun toggleDeviceBlock(ip: String) {
        val currentList = _connectedDevices.value
        val existingIndex = currentList.indexOfFirst { it.ipAddress == ip }
        if (existingIndex != -1) {
            val existingDevice = currentList[existingIndex]
            val updatedDevice = existingDevice.copy(isBlocked = !existingDevice.isBlocked)
            _connectedDevices.value = currentList.toMutableList().apply {
                set(existingIndex, updatedDevice)
            }
        } else {
            val newDevice = ConnectedDevice(
                ipAddress = ip,
                userAgent = "Unknown Device",
                lastActive = System.currentTimeMillis(),
                requestCount = 0,
                isBlocked = true
            )
            _connectedDevices.value = currentList + newDevice
        }
    }

    fun isDeviceBlocked(ip: String): Boolean {
        return _connectedDevices.value.firstOrNull { it.ipAddress == ip }?.isBlocked ?: false
    }

    fun clearDevices() {
        _connectedDevices.value = emptyList()
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_PORT, 8080)
        _serverPort.value = port

        val serializedList = prefs.getStringSet(KEY_FILES, null)
        if (serializedList != null) {
            val list = serializedList.mapNotNull { SharedFile.fromSerializedString(it) }
            _sharedFiles.value = list
        }
    }

    fun setPort(context: Context, port: Int) {
        _serverPort.value = port
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_PORT, port).apply()
        
        if (_isServerRunning.value) {
            startServer(context) // Restarting
        }
    }

    fun addFile(context: Context, uri: Uri) {
        // Persist permissions if possible to allow accessing this file again later
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            // Document picker may not return persistable URI permission in some flows, that's fine
            e.printStackTrace()
        }

        val newFile = SharedFile.fromUri(context, uri) ?: return
        if (_sharedFiles.value.any { it.uriString == newFile.uriString }) return

        val updatedList = _sharedFiles.value + newFile
        _sharedFiles.value = updatedList
        saveFiles(context, updatedList)
    }

    fun removeFile(context: Context, file: SharedFile) {
        val updatedList = _sharedFiles.value.filter { it.uriString != file.uriString }
        _sharedFiles.value = updatedList
        saveFiles(context, updatedList)
    }

    private fun saveFiles(context: Context, list: List<SharedFile>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serializedSet = list.map { it.toSerializedString() }.toSet()
        prefs.edit().putStringSet(KEY_FILES, serializedSet).apply()
    }

    fun startServer(context: Context) {
        val intent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopServer(context: Context) {
        val intent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun onServerStarted(ip: String, port: Int) {
        _isServerRunning.value = true
        _serverUrl.value = "http://$ip:$port"
    }

    fun onServerStopped() {
        _isServerRunning.value = false
        _serverUrl.value = null
        _activeTransfers.value = emptyMap()
    }

    private val _activeTransfers = MutableStateFlow<Map<String, FileTransferProgress>>(emptyMap())
    val activeTransfers = _activeTransfers.asStateFlow()

    fun updateTransferProgress(ip: String, fileId: String, fileName: String, bytesTransferred: Long, totalBytes: Long) {
        val key = "${ip}_${fileId}"
        val currentMap = _activeTransfers.value.toMutableMap()
        if (bytesTransferred >= totalBytes) {
            currentMap.remove(key)
        } else {
            currentMap[key] = FileTransferProgress(
                ipAddress = ip,
                fileName = fileName,
                fileId = fileId,
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes,
                lastActive = System.currentTimeMillis()
            )
        }
        _activeTransfers.value = currentMap
    }

    fun removeTransfer(ip: String, fileId: String) {
        val key = "${ip}_${fileId}"
        val currentMap = _activeTransfers.value.toMutableMap()
        currentMap.remove(key)
        _activeTransfers.value = currentMap
    }
}

data class FileTransferProgress(
    val ipAddress: String,
    val fileName: String,
    val fileId: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val lastActive: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
}
