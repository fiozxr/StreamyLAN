package com.example

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredInstance(
    val deviceName: String,
    val serverUrl: String,
    val activeFilesCount: Int,
    val ipAddress: String,
    val lastSeen: Long = System.currentTimeMillis()
)

object DiscoveryManager {
    private const val DISCOVERY_PORT = 8888
    private const val MSG_DISCOVER = "STREAMY_LAN_DISCOVER"
    private const val MSG_ALIVE_PREFIX = "STREAMY_LAN_ALIVE"

    private val _discoveredInstances = MutableStateFlow<List<DiscoveredInstance>>(emptyList())
    val discoveredInstances = _discoveredInstances.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private var receiverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Starts the UDP listener to respond to other devices scanning the network
    fun startReceiver(context: Context, serverPort: Int) {
        stopReceiver()
        receiverJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(DISCOVERY_PORT)
                socket.reuseAddress = true
                val buffer = ByteArray(1024)
                Log.d("DiscoveryManager", "UDP Discovery Receiver started on port $DISCOVERY_PORT")
                
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length).trim()
                    if (message == MSG_DISCOVER) {
                        val senderIp = packet.address.hostAddress ?: continue
                        val localIp = NetworkUtils.getLocalIpAddress(context)
                        
                        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} ${Build.MODEL}"
                        val serverUrl = "http://$localIp:$serverPort"
                        val filesCount = ServerManager.sharedFiles.value.size
                        
                        val response = "$MSG_ALIVE_PREFIX|$deviceName|$serverUrl|$filesCount"
                        val responseData = response.toByteArray()
                        
                        val responsePacket = DatagramPacket(
                            responseData,
                            responseData.size,
                            packet.address,
                            packet.port
                        )
                        socket.send(responsePacket)
                        Log.d("DiscoveryManager", "Responded to discovery from $senderIp:${packet.port} with: $response")
                    }
                }
            } catch (e: Exception) {
                Log.e("DiscoveryManager", "UDP Receiver error or stopped", e)
            } finally {
                socket?.close()
            }
        }
    }

    fun stopReceiver() {
        receiverJob?.cancel()
        receiverJob = null
    }

    // Sends a UDP broadcast to scan the network for other instances
    fun scanNetwork(context: Context) {
        if (_isScanning.value) return
        _isScanning.value = true
        _discoveredInstances.value = emptyList() // Clear previous

        scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket() // ephemeral port
                socket.broadcast = true
                socket.soTimeout = 2000 // Wait up to 2 seconds for responses
                
                val requestData = MSG_DISCOVER.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val requestPacket = DatagramPacket(
                    requestData,
                    requestData.size,
                    broadcastAddress,
                    DISCOVERY_PORT
                )
                
                socket.send(requestPacket)
                Log.d("DiscoveryManager", "Sent discovery broadcast packet to port $DISCOVERY_PORT")
                
                val localIp = NetworkUtils.getLocalIpAddress(context)
                val buffer = ByteArray(1024)
                val startTime = System.currentTimeMillis()
                val scanDuration = 2000
                
                val foundList = mutableListOf<DiscoveredInstance>()
                
                while (System.currentTimeMillis() - startTime < scanDuration) {
                    try {
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(responsePacket)
                        
                        val responseText = String(responsePacket.data, 0, responsePacket.length).trim()
                        val senderIp = responsePacket.address.hostAddress ?: continue
                        
                        // Skip if it is our own instance
                        if (senderIp == localIp) {
                            continue
                        }
                        
                        if (responseText.startsWith(MSG_ALIVE_PREFIX)) {
                            val parts = responseText.split("|")
                            if (parts.size >= 4) {
                                val deviceName = parts[1]
                                val serverUrl = parts[2]
                                val activeFilesCount = parts[3].toIntOrNull() ?: 0
                                
                                val instance = DiscoveredInstance(
                                    deviceName = deviceName,
                                    serverUrl = serverUrl,
                                    activeFilesCount = activeFilesCount,
                                    ipAddress = senderIp
                                )
                                
                                if (foundList.none { it.serverUrl == serverUrl }) {
                                    foundList.add(instance)
                                    _discoveredInstances.value = foundList.toList()
                                    Log.d("DiscoveryManager", "Discovered server: $deviceName at $serverUrl")
                                }
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        Log.e("DiscoveryManager", "Error receiving response packet", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("DiscoveryManager", "Scan error", e)
            } finally {
                socket?.close()
                _isScanning.value = false
            }
        }
    }
}
