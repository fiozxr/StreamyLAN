package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

class SimpleHttpServer(
    private val context: Context,
    private val port: Int,
    private val getFiles: () -> List<SharedFile>
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d("SimpleHttpServer", "Server started on port $port")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    executor.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e("SimpleHttpServer", "Server error", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("SimpleHttpServer", "Error closing server socket", e)
        }
        executor.shutdown()
    }

    private fun handleClient(socket: Socket) {
        try {
            val clientIp = socket.inetAddress.hostAddress ?: "Unknown"

            // Check if device is blocked before reading the entire request
            if (ServerManager.isDeviceBlocked(clientIp)) {
                Log.d("SimpleHttpServer", "Blocked request from: $clientIp")
                sendResponse(socket, "403 Forbidden", "text/plain", "Access Denied: You have been blocked by the streamer host.".toByteArray())
                return
            }

            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            
            val firstLine = reader.readLine() ?: return
            Log.d("SimpleHttpServer", "Request: $firstLine")
            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                sendResponse(socket, "400 Bad Request", "text/plain", "Bad Request".toByteArray())
                return
            }

            val method = parts[0]
            val rawPath = parts[1]

            // Read request headers to look for Range and User-Agent
            var rangeHeader: String? = null
            var userAgentHeader = "Unknown User-Agent"
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null || line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substring(6).trim()
                } else if (line.startsWith("User-Agent:", ignoreCase = true)) {
                    userAgentHeader = line.substring(11).trim()
                }
            }

            // Register device request
            ServerManager.registerDeviceRequest(clientIp, userAgentHeader)

            val path = URLDecoder.decode(rawPath, "UTF-8")

            if (method == "GET" || method == "HEAD") {
                when {
                    path == "/" || path == "/index.html" -> {
                        val hostIp = NetworkUtils.getLocalIpAddress(context) ?: "localhost"
                        val html = generateWebUi(hostIp, port, getFiles())
                        sendResponse(socket, "200 OK", "text/html; charset=utf-8", html.toByteArray())
                    }
                    path.startsWith("/files/") -> {
                        val fileIdString = path.substring(7)
                        serveFile(socket, fileIdString, rangeHeader, method)
                    }
                    else -> {
                        sendResponse(socket, "404 Not Found", "text/plain", "Not Found".toByteArray())
                    }
                }
            } else {
                sendResponse(socket, "405 Method Not Allowed", "text/plain", "Method Not Allowed".toByteArray())
            }
        } catch (e: Exception) {
            Log.e("SimpleHttpServer", "Error in handleClient", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun serveFile(socket: Socket, fileIdString: String, rangeHeader: String?, method: String) {
        val fileIndex = fileIdString.toIntOrNull()
        val files = getFiles()
        if (fileIndex == null || fileIndex !in files.indices) {
            sendResponse(socket, "404 Not Found", "text/plain", "File not found".toByteArray())
            return
        }

        val sharedFile = files[fileIndex]
        val uri = Uri.parse(sharedFile.uriString)

        var start: Long = 0
        var end: Long = sharedFile.size - 1

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeValue = rangeHeader.substring(6).trim()
            val hyphenIndex = rangeValue.indexOf('-')
            if (hyphenIndex != -1) {
                val startStr = rangeValue.substring(0, hyphenIndex).trim()
                val endStr = rangeValue.substring(hyphenIndex + 1).trim()
                
                if (startStr.isNotEmpty()) {
                    start = startStr.toLongOrNull() ?: 0
                }
                if (endStr.isNotEmpty()) {
                    end = endStr.toLongOrNull() ?: (sharedFile.size - 1)
                }
            }
        }

        // Clip to actual file size
        if (start < 0) start = 0
        if (end >= sharedFile.size) end = sharedFile.size - 1

        if (start > end) {
            try {
                val output = socket.getOutputStream()
                val header = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */${sharedFile.size}\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                output.write(header.toByteArray())
                output.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"
        val isRangeRequest = rangeHeader != null && rangeHeader.startsWith("bytes=")
        val contentLength = end - start + 1

        try {
            val output = socket.getOutputStream()
            val headerBuilder = StringBuilder()

            if (isRangeRequest) {
                headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
                headerBuilder.append("Content-Range: bytes $start-$end/${sharedFile.size}\r\n")
            } else {
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
            }

            headerBuilder.append("Content-Type: ${sharedFile.mimeType}\r\n")
            headerBuilder.append("Content-Length: $contentLength\r\n")
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Content-Disposition: inline; filename=\"${sharedFile.name}\"\r\n")
            headerBuilder.append("Connection: close\r\n")
            headerBuilder.append("\r\n")

            output.write(headerBuilder.toString().toByteArray())

            if (method == "GET") {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val fis = FileInputStream(pfd.fileDescriptor)
                    fis.channel.use { channel ->
                        channel.position(start)
                        val buffer = ByteArray(64 * 1024) // 64KB chunks
                        var bytesRemaining = contentLength
                        var bytesTransferred = 0L
                        var lastUpdate = 0L
                        while (bytesRemaining > 0) {
                            val bytesToRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                            val bytesRead = fis.read(buffer, 0, bytesToRead)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            bytesRemaining -= bytesRead
                            bytesTransferred += bytesRead

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdate > 300 || bytesRemaining <= 0) {
                                lastUpdate = currentTime
                                ServerManager.updateTransferProgress(
                                    ip = clientIp,
                                    fileId = fileIdString,
                                    fileName = sharedFile.name,
                                    bytesTransferred = bytesTransferred,
                                    totalBytes = contentLength
                                )
                            }
                        }
                    }
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e("SimpleHttpServer", "Error sending file response", e)
        } finally {
            ServerManager.removeTransfer(clientIp, fileIdString)
        }
    }

    private fun sendResponse(socket: Socket, status: String, contentType: String, content: ByteArray) {
        try {
            val output = socket.getOutputStream()
            val header = "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${content.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            output.write(header.toByteArray())
            output.write(content)
            output.flush()
        } catch (e: Exception) {
            Log.e("SimpleHttpServer", "Error in sendResponse", e)
        }
    }

    private fun generateWebUi(hostIp: String, port: Int, files: List<SharedFile>): String {
        val filesJson = StringBuilder().apply {
            append("[")
            files.forEachIndexed { index, file ->
                val sizeFormatted = formatFileSize(file.size)
                append("{")
                append("\"id\": $index,")
                append("\"name\": ${escapeJsonString(file.name)},")
                append("\"size\": \"$sizeFormatted\",")
                append("\"mimeType\": \"${file.mimeType}\",")
                append("\"url\": \"/files/$index\"")
                append("}")
                if (index < files.size - 1) append(",")
            }
            append("]")
        }.toString()

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>streamyLAN Hub - Local Media Streamer</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700;800&family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-color: #040308;
            --card-bg: rgba(18, 14, 32, 0.7);
            --text-color: #f3f4f6;
            --text-muted: #9ca3af;
            --primary: #a855f7;
            --primary-glow: rgba(168, 85, 247, 0.15);
            --accent: #10b981;
            --border-color: rgba(255, 255, 255, 0.08);
            --font-display: 'Space Grotesk', system-ui, sans-serif;
            --font-sans: 'Inter', system-ui, sans-serif;
        }
        body {
            font-family: var(--font-sans);
            background-color: var(--bg-color);
            background-image: 
                radial-gradient(circle at 10% 20%, rgba(168, 85, 247, 0.08) 0%, transparent 40%),
                radial-gradient(circle at 90% 80%, rgba(16, 185, 129, 0.05) 0%, transparent 40%);
            background-attachment: fixed;
            color: var(--text-color);
            margin: 0;
            padding: 0;
            display: flex;
            flex-direction: column;
            min-height: 100vh;
        }
        header {
            background-color: rgba(13, 10, 24, 0.8);
            backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--border-color);
            padding: 1.25rem 2rem;
            display: flex;
            align-items: center;
            justify-content: space-between;
            flex-wrap: wrap;
            gap: 1rem;
        }
        .header-brand {
            display: flex;
            flex-direction: column;
        }
        header h1 {
            margin: 0;
            font-family: var(--font-display);
            font-size: 1.75rem;
            font-weight: 800;
            letter-spacing: -0.5px;
            color: #ffffff;
            display: flex;
            align-items: center;
            gap: 0.5rem;
        }
        header h1 span {
            color: var(--primary);
            text-shadow: 0 0 15px rgba(168, 85, 247, 0.5);
        }
        header p {
            margin: 0.25rem 0 0 0;
            color: var(--text-muted);
            font-size: 0.85rem;
        }
        header p code {
            background-color: rgba(255, 255, 255, 0.06);
            padding: 0.15rem 0.4rem;
            border-radius: 4px;
            color: var(--primary);
            font-family: monospace;
            font-weight: 600;
        }
        .server-status {
            display: flex;
            align-items: center;
            gap: 0.5rem;
            background-color: rgba(16, 185, 129, 0.1);
            border: 1px solid rgba(16, 185, 129, 0.2);
            padding: 0.4rem 0.8rem;
            border-radius: 20px;
            font-size: 0.8rem;
            font-weight: 700;
            color: var(--accent);
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .status-dot {
            width: 8px;
            height: 8px;
            background-color: var(--accent);
            border-radius: 50%;
            box-shadow: 0 0 8px var(--accent);
            animation: pulse 2s infinite;
        }
        @keyframes pulse {
            0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }
            70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }
            100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
        }
        .container {
            max-width: 1200px;
            margin: 2rem auto;
            padding: 0 1.5rem;
            flex-grow: 1;
            width: 100%;
            box-sizing: border-box;
        }
        .main-layout {
            display: grid;
            grid-template-columns: 1fr;
            gap: 2rem;
        }
        @media (min-width: 992px) {
            .main-layout {
                grid-template-columns: 1.6fr 1fr;
            }
        }
        .player-card {
            background-color: var(--card-bg);
            backdrop-filter: blur(16px);
            border-radius: 16px;
            border: 1px solid var(--border-color);
            padding: 1.25rem;
            box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
            display: flex;
            flex-direction: column;
        }
        
        /* Netflix/VLC Premium Custom Video Player */
        .video-player-wrapper {
            position: relative;
            width: 100%;
            border-radius: 10px;
            background-color: #000000;
            overflow: hidden;
            aspect-ratio: 16 / 9;
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.8);
        }
        
        .video-player-wrapper video {
            width: 100%;
            height: 100%;
            display: block;
            object-fit: contain;
        }
        
        /* Loading Buffering Spinner */
        .player-spinner {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            z-index: 10;
            display: none;
            pointer-events: none;
        }
        
        .spinner-circle {
            width: 50px;
            height: 50px;
            border: 4px solid rgba(168, 85, 247, 0.2);
            border-top: 4px solid var(--primary);
            border-radius: 50%;
            animation: spin 1s linear infinite;
        }
        
        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }
        
        /* Large Centered Play/Pause Indicator Overlay */
        .center-play-btn {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%) scale(1);
            background: rgba(0, 0, 0, 0.6);
            backdrop-filter: blur(4px);
            border-radius: 50%;
            width: 64px;
            height: 64px;
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 8;
            opacity: 0;
            pointer-events: none;
            transition: all 0.3s ease;
        }
        
        .center-play-btn.flash {
            animation: playFlash 0.6s ease-out;
        }
        
        @keyframes playFlash {
            0% { opacity: 0; transform: translate(-50%, -50%) scale(0.8); }
            50% { opacity: 1; transform: translate(-50%, -50%) scale(1.1); }
            100% { opacity: 0; transform: translate(-50%, -50%) scale(1.2); }
        }
        
        /* Video Controls Panel Overlay */
        .video-controls-overlay {
            position: absolute;
            bottom: 0;
            left: 0;
            right: 0;
            background: linear-gradient(to top, rgba(0, 0, 0, 0.95) 0%, rgba(0, 0, 0, 0.5) 70%, transparent 100%);
            padding: 2.5rem 1.25rem 0.85rem 1.25rem;
            z-index: 6;
            display: flex;
            flex-direction: column;
            gap: 0.6rem;
            opacity: 0;
            transition: opacity 0.3s ease-in-out;
            pointer-events: auto;
        }
        
        /* Show controls on hover or activity */
        .video-player-wrapper:hover .video-controls-overlay,
        .video-player-wrapper.user-active .video-controls-overlay {
            opacity: 1;
        }
        
        /* Custom Timeline Seek Bar */
        .progress-bar-container {
            position: relative;
            width: 100%;
            height: 5px;
            border-radius: 3px;
            cursor: pointer;
            margin-bottom: 0.25rem;
            display: flex;
            align-items: center;
            transition: height 0.1s ease;
        }
        
        .progress-bar-rail {
            position: absolute;
            left: 0;
            right: 0;
            height: 100%;
            background-color: rgba(255, 255, 255, 0.15);
            border-radius: 3px;
        }
        
        .progress-bar-buffered {
            position: absolute;
            left: 0;
            height: 100%;
            background-color: rgba(255, 255, 255, 0.25);
            border-radius: 3px;
            width: 0%;
        }
        
        .progress-bar-fill {
            position: absolute;
            left: 0;
            height: 100%;
            background: linear-gradient(90deg, var(--primary), var(--accent));
            border-radius: 3px;
            width: 0%;
        }
        
        .progress-input {
            position: absolute;
            left: 0;
            width: 100%;
            height: 100%;
            opacity: 0;
            cursor: pointer;
            margin: 0;
            z-index: 5;
        }
        
        .progress-bar-container:hover {
            height: 8px;
        }
        
        /* Control Action Row */
        .controls-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        
        .controls-left, .controls-right {
            display: flex;
            align-items: center;
            gap: 1rem;
        }
        
        .control-btn {
            background: none;
            border: none;
            color: #ffffff;
            cursor: pointer;
            padding: 6px;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            transition: all 0.15s ease;
            background-color: rgba(255, 255, 255, 0);
        }
        
        .control-btn:hover {
            color: var(--primary);
            background-color: rgba(255, 255, 255, 0.08);
            transform: scale(1.1);
        }
        
        .control-btn svg {
            width: 20px;
            height: 20px;
            fill: currentColor;
        }
        
        /* Volume Controller */
        .volume-container {
            display: flex;
            align-items: center;
            gap: 0.4rem;
        }
        
        .volume-input {
            width: 0px;
            opacity: 0;
            height: 4px;
            border-radius: 2px;
            -webkit-appearance: none;
            background: rgba(255, 255, 255, 0.2);
            transition: width 0.25s ease-in-out, opacity 0.2s ease;
            cursor: pointer;
        }
        
        .volume-input::-webkit-slider-thumb {
            -webkit-appearance: none;
            width: 10px;
            height: 10px;
            border-radius: 50%;
            background: #ffffff;
            box-shadow: 0 0 5px rgba(0,0,0,0.5);
        }
        
        .volume-container:hover .volume-input,
        .volume-input:focus {
            width: 70px;
            opacity: 1;
        }
        
        .time-display {
            font-size: 0.8rem;
            color: #ffffff;
            font-family: monospace;
            font-weight: 500;
            letter-spacing: 0.5px;
            margin-left: 0.25rem;
            opacity: 0.9;
        }

        .video-info {
            margin-top: 1.25rem;
        }
        .video-title {
            font-family: var(--font-display);
            font-size: 1.35rem;
            font-weight: 700;
            margin: 0 0 0.5rem 0;
            color: #ffffff;
            word-break: break-all;
        }
        .video-meta {
            font-size: 0.85rem;
            color: var(--text-muted);
            display: flex;
            align-items: center;
            gap: 1rem;
        }
        
        /* Format Support Dynamic Notice */
        .format-tip-box {
            margin-top: 1rem;
            background-color: rgba(168, 85, 247, 0.05);
            border: 1px solid rgba(168, 85, 247, 0.1);
            border-radius: 10px;
            padding: 0.75rem 1rem;
            display: flex;
            align-items: flex-start;
            gap: 0.5rem;
        }
        .format-tip-icon {
            font-size: 1.1rem;
            line-height: 1;
        }
        .format-tip-text {
            font-size: 0.8rem;
            color: var(--text-muted);
            line-height: 1.4;
            margin: 0;
        }
        .format-tip-text strong {
            color: #ffffff;
        }
        
        .list-card {
            background-color: var(--card-bg);
            backdrop-filter: blur(16px);
            border-radius: 16px;
            border: 1px solid var(--border-color);
            padding: 1.5rem;
            box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
            display: flex;
            flex-direction: column;
            max-height: 620px;
        }
        .list-card h2 {
            margin: 0 0 1rem 0;
            font-family: var(--font-display);
            font-size: 1.4rem;
            font-weight: 700;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 0.75rem;
            color: #ffffff;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .file-list-container {
            overflow-y: auto;
            flex-grow: 1;
            padding-right: 0.25rem;
        }
        /* Custom scrollbar */
        .file-list-container::-webkit-scrollbar {
            width: 6px;
        }
        .file-list-container::-webkit-scrollbar-track {
            background: transparent;
        }
        .file-list-container::-webkit-scrollbar-thumb {
            background: var(--border-color);
            border-radius: 3px;
        }
        .file-list-container::-webkit-scrollbar-thumb:hover {
            background: rgba(255, 255, 255, 0.2);
        }
        
        .file-list {
            list-style: none;
            padding: 0;
            margin: 0;
            display: flex;
            flex-direction: column;
            gap: 0.75rem;
        }
        .file-item {
            display: flex;
            flex-direction: column;
            padding: 1rem;
            background-color: rgba(255, 255, 255, 0.02);
            border: 1px solid var(--border-color);
            border-radius: 10px;
            cursor: pointer;
            transition: all 0.2s ease-in-out;
        }
        .file-item:hover {
            border-color: var(--primary);
            background-color: rgba(168, 85, 247, 0.05);
            transform: translateY(-2px);
        }
        .file-item.active {
            border-color: var(--accent);
            background-color: rgba(16, 185, 129, 0.06);
            box-shadow: inset 0 0 12px rgba(16, 185, 129, 0.03);
        }
        .file-header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            gap: 1rem;
        }
        .file-name {
            font-weight: 600;
            font-size: 0.95rem;
            word-break: break-all;
            color: #ffffff;
        }
        .file-size {
            font-size: 0.8rem;
            color: var(--text-muted);
            white-space: nowrap;
        }
        .action-buttons {
            display: flex;
            gap: 0.5rem;
            margin-top: 0.75rem;
            flex-wrap: wrap;
        }
        .btn {
            display: inline-flex;
            align-items: center;
            gap: 0.35rem;
            padding: 0.45rem 0.9rem;
            border-radius: 6px;
            font-size: 0.8rem;
            font-weight: 600;
            text-decoration: none;
            cursor: pointer;
            border: none;
            transition: all 0.15s ease;
        }
        .btn-primary {
            background-color: var(--primary);
            color: #fff;
            box-shadow: 0 4px 12px rgba(168, 85, 247, 0.2);
        }
        .btn-primary:hover {
            background-color: #9333ea;
            box-shadow: 0 0 16px rgba(168, 85, 247, 0.4);
        }
        .btn-secondary {
            background-color: rgba(255, 255, 255, 0.06);
            color: #e5e7eb;
            border: 1px solid var(--border-color);
        }
        .btn-secondary:hover {
            background-color: rgba(255, 255, 255, 0.12);
            border-color: rgba(255, 255, 255, 0.2);
        }
        .cli-section {
            grid-column: 1 / -1;
            background-color: var(--card-bg);
            backdrop-filter: blur(16px);
            border-radius: 16px;
            border: 1px solid var(--border-color);
            padding: 1.5rem;
            box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
        }
        .cli-section h3 {
            margin: 0 0 1rem 0;
            font-family: var(--font-display);
            font-size: 1.2rem;
            color: #ffffff;
            display: flex;
            align-items: center;
            gap: 0.5rem;
        }
        .cli-grid {
            display: grid;
            grid-template-columns: 1fr;
            gap: 1rem;
        }
        @media (min-width: 768px) {
            .cli-grid {
                grid-template-columns: repeat(3, 1fr);
            }
        }
        .cli-box {
            background-color: rgba(0, 0, 0, 0.4);
            border: 1px solid var(--border-color);
            border-radius: 10px;
            padding: 1rem;
            display: flex;
            flex-direction: column;
            gap: 0.5rem;
        }
        .cli-label {
            font-size: 0.75rem;
            font-weight: 700;
            text-transform: uppercase;
            color: var(--primary);
            letter-spacing: 0.05em;
        }
        .cli-code-container {
            display: flex;
            background-color: rgba(0, 0, 0, 0.5);
            border-radius: 6px;
            overflow: hidden;
            border: 1px solid var(--border-color);
        }
        .cli-code {
            flex-grow: 1;
            padding: 0.6rem 0.8rem;
            font-family: 'Courier New', Courier, monospace;
            font-size: 0.85rem;
            color: var(--accent);
            overflow-x: auto;
            white-space: nowrap;
        }
        .cli-copy {
            background-color: rgba(255, 255, 255, 0.08);
            border: none;
            color: #e5e7eb;
            padding: 0.5rem 0.75rem;
            cursor: pointer;
            font-size: 0.75rem;
            font-weight: 600;
            transition: background-color 0.1s;
        }
        .cli-copy:hover {
            background-color: rgba(255, 255, 255, 0.15);
        }
        .empty-state {
            text-align: center;
            padding: 4rem 2rem;
            color: var(--text-muted);
            border: 2px dashed rgba(255, 255, 255, 0.1);
            border-radius: 16px;
            background-color: rgba(13, 10, 24, 0.4);
        }
        .empty-state-icon {
            font-size: 3rem;
            margin-bottom: 1rem;
        }
        .badge {
            background-color: rgba(168, 85, 247, 0.15);
            color: var(--primary);
            padding: 0.2rem 0.6rem;
            border-radius: 9999px;
            font-size: 0.75rem;
            font-weight: 600;
            border: 1px solid rgba(168, 85, 247, 0.1);
        }
        footer {
            text-align: center;
            padding: 2rem;
            color: var(--text-muted);
            font-size: 0.85rem;
            border-top: 1px solid var(--border-color);
            margin-top: auto;
            background-color: rgba(13, 10, 24, 0.8);
        }
    </style>
</head>
<body>
    <header>
        <div class="header-brand">
            <h1>streamy<span>LAN</span> Hub</h1>
            <p>Streaming from <strong>Android App</strong> at <code>$hostIp:$port</code></p>
        </div>
        <div class="server-status">
            <div class="status-dot"></div>
            Online
        </div>
    </header>
    <div class="container">
        ${
            if (files.isEmpty()) {
                """
                <div class="empty-state">
                    <div class="empty-state-icon">🎬</div>
                    <h2>No shared files yet</h2>
                    <p>Open the streamyLAN app on your Android device and tap "Add Media" to start streaming.</p>
                </div>
                """
            } else {
                """
                <div class="main-layout">
                    <!-- Player Column -->
                    <div class="player-card">
                        <!-- VLC/Netflix style premium player container -->
                        <div class="video-player-wrapper" id="video-wrapper">
                            <video id="video-player" playsinline></video>
                            
                            <!-- Buffering Loader -->
                            <div class="player-spinner" id="player-spinner">
                                <div class="spinner-circle"></div>
                            </div>
                            
                            <!-- Resume Notification -->
                            <div id="resume-toast" style="display: none; position: absolute; top: 16px; left: 50%; transform: translateX(-50%); background-color: rgba(16, 185, 129, 0.95); color: white; padding: 8px 16px; border-radius: 20px; font-size: 0.85rem; font-weight: bold; font-family: sans-serif; z-index: 10; box-shadow: 0 4px 12px rgba(0,0,0,0.5); pointer-events: none; transition: opacity 0.3s ease-in-out;">
                                <span id="resume-toast-text">Resumed playback</span>
                            </div>
                            
                            <!-- Large Center Play Button Overlay -->
                            <div class="center-play-btn" id="center-play-btn">
                                <svg viewBox="0 0 24 24" width="32" height="32"><path d="M8 5v14l11-7z" fill="white"/></svg>
                            </div>

                            <!-- Custom Controls Overlay Panel -->
                            <div class="video-controls-overlay" id="video-controls">
                                <!-- Seek Bar Slider -->
                                <div class="progress-bar-container">
                                    <div class="progress-bar-rail"></div>
                                    <div class="progress-bar-buffered" id="progress-buffered"></div>
                                    <div class="progress-bar-fill" id="progress-fill"></div>
                                    <input type="range" class="progress-input" id="progress-input" min="0" max="100" step="0.1" value="0">
                                </div>
                                
                                <!-- Buttons & Control Row -->
                                <div class="controls-row">
                                    <div class="controls-left">
                                        <!-- Play Button -->
                                        <button class="control-btn" id="ctrl-play" title="Play (Space)">
                                            <svg viewBox="0 0 24 24" class="icon-play"><path d="M8 5v14l11-7z"/></svg>
                                            <svg viewBox="0 0 24 24" class="icon-pause" style="display:none;"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>
                                        </button>
                                        
                                        <!-- Rewind 10s -->
                                        <button class="control-btn" id="ctrl-rewind" title="Rewind 10s (Left Arrow)">
                                            <svg viewBox="0 0 24 24"><path d="M12.5 3C7.25 3 3 7.25 3 12.5S7.25 22 12.5 22 22 17.75 22 12.5s-4.25-9.5-9.5-9.5zm-1 13.5v-8L7 12l4.5 4.5zm5 0v-8L12 12l4.5 4.5z"/></svg>
                                        </button>
                                        
                                        <!-- Fast Forward 10s -->
                                        <button class="control-btn" id="ctrl-forward" title="Forward 10s (Right Arrow)">
                                            <svg viewBox="0 0 24 24"><path d="M11.5 3C6.25 3 2 7.25 2 12.5S6.25 22 11.5 22 21 17.75 21 12.5 16.75 3 11.5 3zm1 13.5L8 12l4.5-4.5v9zm5 0L13 12l4.5-4.5v9z"/></svg>
                                        </button>
                                        
                                        <!-- Volume Controller -->
                                        <div class="volume-container">
                                            <button class="control-btn" id="ctrl-mute" title="Mute (M)">
                                                <svg viewBox="0 0 24 24" class="icon-vol-up"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>
                                                <svg viewBox="0 0 24 24" class="icon-vol-mute" style="display:none;"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.21.05-.42.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>
                                            </button>
                                            <input type="range" class="volume-input" id="volume-input" min="0" max="1" step="0.05" value="1">
                                        </div>

                                        <!-- Live time counter -->
                                        <span class="time-display" id="time-display">00:00 / 00:00</span>
                                    </div>
                                    
                                    <div class="controls-right">
                                        <!-- Playback Speed Control -->
                                        <div class="speed-control-container" style="position: relative; display: flex; align-items: center; margin-right: 4px;">
                                            <button class="control-btn" id="ctrl-speed" title="Playback Speed" style="font-size: 0.8rem; font-weight: bold; width: auto; padding: 4px 8px; border-radius: 4px; border: 1px solid rgba(255, 255, 255, 0.2); font-family: monospace;">1.0x</button>
                                            <select id="speed-select" style="position: absolute; top: 0; left: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer;">
                                                <option value="0.5">0.5x</option>
                                                <option value="0.75">0.75x</option>
                                                <option value="1.0" selected>1.0x</option>
                                                <option value="1.25">1.25x</option>
                                                <option value="1.5">1.5x</option>
                                                <option value="2.0">2.0x</option>
                                            </select>
                                        </div>

                                        <!-- Fullscreen Button -->
                                        <button class="control-btn" id="ctrl-fullscreen" title="Fullscreen (F)">
                                            <svg viewBox="0 0 24 24" class="icon-fs-enter"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>
                                            <svg viewBox="0 0 24 24" class="icon-fs-exit" style="display:none;"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/></svg>
                                        </button>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <div class="video-info">
                            <h2 class="video-title" id="current-title">Select a video to stream</h2>
                            <div class="video-meta">
                                <span class="badge" id="current-format">N/A</span>
                                <span id="current-size">0 MB</span>
                            </div>
                        </div>

                        <!-- Helpful HTML5 Codec Format Notice -->
                        <div class="format-tip-box">
                            <span class="format-tip-icon">💡</span>
                            <p class="format-tip-text">
                                <strong>Browser Format Tip:</strong> Standard formats like MP4 and WebM will play directly inline. If you have an unsupported format (like MKV/HEVC or Dolby DTS), simply click the <strong>Stream with VLC</strong> command button below. VLC will decode it flawlessly with zero buffering!
                            </p>
                        </div>
                    </div>

                    <!-- List Column -->
                    <div class="list-card">
                        <h2>Shared Media <span class="badge">${files.size}</span></h2>
                        <div class="file-list-container">
                            <ul class="file-list" id="file-list"></ul>
                        </div>
                    </div>

                    <!-- CLI Command Generator Section -->
                    <div class="cli-section" id="cli-section" style="display: none;">
                        <h3>💻 CLI Terminal Integration</h3>
                        <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0; margin-bottom: 1rem;">
                            Terminal power-user? Instantly download or play this video over the LAN using these ready-to-run terminal scripts:
                        </p>
                        <div class="cli-grid">
                            <div class="cli-box">
                                <div class="cli-label">Fetch with cURL</div>
                                <div class="cli-code-container">
                                    <div class="cli-code" id="code-curl">curl ...</div>
                                    <button class="cli-copy" onclick="copyCode('code-curl')">Copy</button>
                                </div>
                            </div>
                            <div class="cli-box">
                                <div class="cli-label">Fetch with wget</div>
                                <div class="cli-code-container">
                                    <div class="cli-code" id="code-wget">wget ...</div>
                                    <button class="cli-copy" onclick="copyCode('code-wget')">Copy</button>
                                </div>
                            </div>
                            <div class="cli-box">
                                <div class="cli-label">Stream in VLC</div>
                                <div class="cli-code-container">
                                    <div class="cli-code" id="code-vlc">vlc ...</div>
                                    <button class="cli-copy" onclick="copyCode('code-vlc')">Copy</button>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
                """
            }
        }
    </div>
    <footer>
        <p>⚡ streamyLAN Server running locally &middot; Ultra high performance inline video streamer</p>
    </footer>

    <script>
        const files = $filesJson;
        const baseUrl = window.location.origin;

        function escapeShellArg(arg) {
            return '"' + arg.replace(/"/g, '\\"') + '"';
        }

        function populateList() {
            const listEl = document.getElementById('file-list');
            if (!listEl) return;
            
            files.forEach((file) => {
                const li = document.createElement('li');
                li.className = 'file-item';
                li.id = 'file-' + file.id;
                li.onclick = () => playFile(file.id);

                li.innerHTML = '<div class="file-header">' +
                    '<span class="file-name">' + file.name + '</span>' +
                    '<span class="file-size">' + file.size + '</span>' +
                    '</div>' +
                    '<div class="action-buttons">' +
                    '<a href="' + file.url + '" download="' + file.name + '" class="btn btn-primary" onclick="event.stopPropagation();">⬇️ Download</a>' +
                    '<button class="btn btn-secondary" onclick="event.stopPropagation(); playFile(' + file.id + ');">▶️ Play Inline</button>' +
                    '</div>';
                listEl.appendChild(li);
            });
        }

        let playerInited = false;
        let lastPlayedId = null;

        function initCustomControls() {
            if (playerInited) return;
            playerInited = true;

            const video = document.getElementById('video-player');
            const videoWrapper = document.getElementById('video-wrapper');
            const playBtn = document.getElementById('ctrl-play');
            const rewindBtn = document.getElementById('ctrl-rewind');
            const forwardBtn = document.getElementById('ctrl-forward');
            const muteBtn = document.getElementById('ctrl-mute');
            const volumeInput = document.getElementById('volume-input');
            const progressInput = document.getElementById('progress-input');
            const progressFill = document.getElementById('progress-fill');
            const progressBuffered = document.getElementById('progress-buffered');
            const timeDisplay = document.getElementById('time-display');
            const fullscreenBtn = document.getElementById('ctrl-fullscreen');
            const spinner = document.getElementById('player-spinner');
            const centerPlayBtn = document.getElementById('center-play-btn');

            // Play / Pause toggler
            function togglePlay() {
                if (video.paused) {
                    video.play().catch(err => console.log('Playback error:', err));
                    showCenterFlash('play');
                } else {
                    video.pause();
                    showCenterFlash('pause');
                }
            }

            function showCenterFlash(type) {
                centerPlayBtn.classList.remove('flash');
                void centerPlayBtn.offsetWidth; // Trigger reflow
                
                if (type === 'play') {
                    centerPlayBtn.innerHTML = '<svg viewBox="0 0 24 24" width="32" height="32"><path d="M8 5v14l11-7z" fill="white"/></svg>';
                } else {
                    centerPlayBtn.innerHTML = '<svg viewBox="0 0 24 24" width="32" height="32"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z" fill="white"/></svg>';
                }
                centerPlayBtn.classList.add('flash');
            }

            video.addEventListener('play', () => {
                playBtn.querySelector('.icon-play').style.display = 'none';
                playBtn.querySelector('.icon-pause').style.display = 'block';
            });

            video.addEventListener('pause', () => {
                playBtn.querySelector('.icon-play').style.display = 'block';
                playBtn.querySelector('.icon-pause').style.display = 'none';
            });

            playBtn.addEventListener('click', togglePlay);
            video.addEventListener('click', togglePlay);

            // Time skips
            function skip(seconds) {
                video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + seconds));
            }

            rewindBtn.addEventListener('click', () => skip(-10));
            forwardBtn.addEventListener('click', () => skip(10));

            // Time format helper
            function formatTime(seconds) {
                if (isNaN(seconds) || seconds === Infinity) return "00:00";
                const h = Math.floor(seconds / 3600);
                const m = Math.floor((seconds % 3600) / 60);
                const s = Math.floor(seconds % 60);
                
                const mStr = m.toString().padStart(2, '0');
                const sStr = s.toString().padStart(2, '0');
                
                if (h > 0) {
                    return h.toString() + ':' + mStr + ':' + sStr;
                }
                return mStr + ':' + sStr;
            }

            // Update Progress fill & display
            function updateProgress() {
                if (!video.duration) return;
                const percent = (video.currentTime / video.duration) * 100;
                progressInput.value = percent;
                progressFill.style.width = percent + '%';
                
                timeDisplay.textContent = formatTime(video.currentTime) + ' / ' + formatTime(video.duration);
            }

            video.addEventListener('timeupdate', () => {
                updateProgress();
                if (lastPlayedId !== null && video.currentTime > 0) {
                    localStorage.setItem('streamyLAN_resume_' + lastPlayedId, video.currentTime);
                }
            });

            // Playback speed selector logic
            const speedSelect = document.getElementById('speed-select');
            const speedBtn = document.getElementById('ctrl-speed');
            if (speedSelect && speedBtn) {
                // Initialize default speed
                video.playbackRate = parseFloat(speedSelect.value || "1.0");
                speedBtn.textContent = video.playbackRate.toFixed(1) + 'x';

                speedSelect.addEventListener('change', () => {
                    const speed = parseFloat(speedSelect.value);
                    video.playbackRate = speed;
                    speedBtn.textContent = speed.toFixed(1) + 'x';
                });
            }

            // Dynamic Buffering Visuals
            function updateBuffered() {
                if (video.buffered.length > 0 && video.duration) {
                    const bufferedEnd = video.buffered.end(video.buffered.length - 1);
                    const percent = (bufferedEnd / video.duration) * 100;
                    progressBuffered.style.width = percent + '%';
                }
            }
            video.addEventListener('progress', updateBuffered);

            // Timeline Seek Action
            function handleSeek() {
                if (!video.duration) return;
                const seekTime = (progressInput.value / 100) * video.duration;
                video.currentTime = seekTime;
            }

            progressInput.addEventListener('input', () => {
                progressFill.style.width = progressInput.value + '%';
            });
            progressInput.addEventListener('change', handleSeek);

            // Audio & Mute Control
            function handleVolume() {
                video.volume = volumeInput.value;
                video.muted = (volumeInput.value == 0);
                updateVolumeIcon();
            }

            function updateVolumeIcon() {
                const isMuted = video.muted || video.volume === 0;
                if (isMuted) {
                    muteBtn.querySelector('.icon-vol-up').style.display = 'none';
                    muteBtn.querySelector('.icon-vol-mute').style.display = 'block';
                } else {
                    muteBtn.querySelector('.icon-vol-up').style.display = 'block';
                    muteBtn.querySelector('.icon-vol-mute').style.display = 'none';
                }
            }

            muteBtn.addEventListener('click', () => {
                video.muted = !video.muted;
                updateVolumeIcon();
            });

            volumeInput.addEventListener('input', handleVolume);

            // Fullscreen trigger
            function toggleFullscreen() {
                if (!document.fullscreenElement) {
                    videoWrapper.requestFullscreen().catch(err => {
                        console.log('Error triggering full-screen:', err);
                    });
                } else {
                    document.exitFullscreen();
                }
            }

            fullscreenBtn.addEventListener('click', toggleFullscreen);
            videoWrapper.addEventListener('dblclick', toggleFullscreen);

            document.addEventListener('fullscreenchange', () => {
                if (document.fullscreenElement) {
                    fullscreenBtn.querySelector('.icon-fs-enter').style.display = 'none';
                    fullscreenBtn.querySelector('.icon-fs-exit').style.display = 'block';
                } else {
                    fullscreenBtn.querySelector('.icon-fs-enter').style.display = 'block';
                    fullscreenBtn.querySelector('.icon-fs-exit').style.display = 'none';
                }
            });

            // Buffering events for loader spinner
            video.addEventListener('waiting', () => { spinner.style.display = 'block'; });
            video.addEventListener('playing', () => { spinner.style.display = 'none'; });
            video.addEventListener('seeking', () => { spinner.style.display = 'block'; });
            video.addEventListener('seeked', () => { spinner.style.display = 'none'; });

            // Inactivity Controls Hide
            let idleTimer;
            function resetIdleTimer() {
                videoWrapper.classList.add('user-active');
                clearTimeout(idleTimer);
                idleTimer = setTimeout(() => {
                    videoWrapper.classList.remove('user-active');
                }, 2500);
            }

            videoWrapper.addEventListener('mousemove', resetIdleTimer);
            videoWrapper.addEventListener('touchstart', resetIdleTimer);

            // Power key binds
            document.addEventListener('keydown', (e) => {
                if (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA') return;

                switch (e.key.toLowerCase()) {
                    case ' ':
                        e.preventDefault();
                        togglePlay();
                        break;
                    case 'arrowleft':
                        e.preventDefault();
                        skip(-10);
                        break;
                    case 'arrowright':
                        e.preventDefault();
                        skip(10);
                        break;
                    case 'f':
                        toggleFullscreen();
                        break;
                    case 'm':
                        video.muted = !video.muted;
                        updateVolumeIcon();
                        break;
                    case 'arrowup':
                        e.preventDefault();
                        volumeInput.value = Math.min(1, parseFloat(volumeInput.value) + 0.05);
                        handleVolume();
                        break;
                    case 'arrowdown':
                        e.preventDefault();
                        volumeInput.value = Math.max(0, parseFloat(volumeInput.value) - 0.05);
                        handleVolume();
                        break;
                }
            });
        }

        function showResumeToast(time) {
            const toast = document.getElementById('resume-toast');
            const toastText = document.getElementById('resume-toast-text');
            if (!toast || !toastText) return;

            const m = Math.floor(time / 60);
            const s = Math.floor(time % 60);
            const timeStr = m.toString().padStart(2, '0') + ':' + s.toString().padStart(2, '0');

            toastText.textContent = "Resumed playback from " + timeStr;
            toast.style.display = 'block';
            toast.style.opacity = '1';

            setTimeout(() => {
                toast.style.opacity = '0';
                setTimeout(() => {
                    toast.style.display = 'none';
                }, 300);
            }, 3000);
        }

        function playFile(id) {
            const file = files.find(f => f.id === id);
            if (!file) return;

            const player = document.getElementById('video-player');

            // Save progress of current video before switching
            if (lastPlayedId !== null && player && player.currentTime > 0) {
                localStorage.setItem('streamyLAN_resume_' + lastPlayedId, player.currentTime);
            }
            
            lastPlayedId = id;

            // Mark Item Active
            document.querySelectorAll('.file-item').forEach(el => el.classList.remove('active'));
            const activeItem = document.getElementById('file-' + id);
            if (activeItem) activeItem.classList.add('active');

            // Set Video Source
            player.src = file.url;
            player.load();

            // Try to resume from stored position
            const resumeTime = localStorage.getItem('streamyLAN_resume_' + id);
            if (resumeTime) {
                const parsedTime = parseFloat(resumeTime);
                if (parsedTime > 1) { // Only resume if played past first second
                    player.currentTime = parsedTime;
                    showResumeToast(parsedTime);
                }
            }

            player.play().catch(err => console.log('Autoplay blocked, click play to stream'));

            // Init custom overlay controllers
            initCustomControls();

            // Set details label
            document.getElementById('current-title').textContent = file.name;
            document.getElementById('current-format').textContent = file.mimeType;
            document.getElementById('current-size').textContent = file.size;

            // Display terminal panel
            document.getElementById('cli-section').style.display = 'block';
            
            // Build full access absolute url
            const fullUrl = baseUrl + file.url;
            
            // Generate command text
            document.getElementById('code-curl').textContent = "curl -L -o " + escapeShellArg(file.name) + " " + escapeShellArg(fullUrl);
            document.getElementById('code-wget').textContent = "wget -O " + escapeShellArg(file.name) + " " + escapeShellArg(fullUrl);
            document.getElementById('code-vlc').textContent = "vlc " + escapeShellArg(fullUrl);
        }

        function copyCode(id) {
            const text = document.getElementById(id).textContent;
            navigator.clipboard.writeText(text).then(() => {
                alert('Command copied to clipboard!');
            }).catch(err => {
                console.error('Failed to copy text: ', err);
            });
        }

        // Autoplay first item if list is populated
        if (files.length > 0) {
            populateList();
            playFile(0);
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.toDouble())).toInt()
        val index = if (digitGroups in units.indices) digitGroups else units.size - 1
        return String.format(Locale.US, "%.2f %s", size / Math.pow(1024.toDouble(), index.toDouble()), units[index])
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder()
        for (i in 0 until s.length) {
            val ch = s[i]
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code in 0..31 || ch.code in 127..159) {
                        val ss = Integer.toHexString(ch.code)
                        sb.append("\\u")
                        for (k in 0 until 4 - ss.length) {
                            sb.append('0')
                        }
                        sb.append(ss.uppercase(Locale.US))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return "\"" + sb.toString() + "\""
    }
}
