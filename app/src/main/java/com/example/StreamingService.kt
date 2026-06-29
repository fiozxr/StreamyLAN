package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        private const val NOTIFICATION_ID = 4210
        private const val CHANNEL_ID = "lan_stream_channel"
    }

    private var server: SimpleHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        val port = ServerManager.serverPort.value
        val ip = NetworkUtils.getLocalIpAddress(this) ?: "127.0.0.1"

        // Stop existing server if any
        server?.stop()

        // Create new server instance
        server = SimpleHttpServer(this, port) {
            ServerManager.sharedFiles.value
        }
        server?.start()

        ServerManager.onServerStarted(ip, port)
        DiscoveryManager.startReceiver(this, port)

        val notification = buildNotification("Running", "Active at http://$ip:$port")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                Log.e("StreamingService", "Failed to start foreground service with type, falling back", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        ServerManager.onServerStopped()
        DiscoveryManager.stopReceiver()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LAN Stream Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of the local streaming server"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LAN Stream Server ($status)")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online) // Standard system streaming icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Server",
                stopPendingIntent
            )
            .build()
    }
}
