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
                        while (bytesRemaining > 0) {
                            val bytesToRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                            val bytesRead = fis.read(buffer, 0, bytesToRead)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            bytesRemaining -= bytesRead
                        }
                    }
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e("SimpleHttpServer", "Error sending file response", e)
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
    <title>LAN Stream - Mobile Media Hub</title>
    <style>
        :root {
            --bg-color: #0b0f19;
            --card-bg: #111827;
            --text-color: #f3f4f6;
            --text-muted: #9ca3af;
            --primary: #3b82f6;
            --primary-glow: rgba(59, 130, 246, 0.15);
            --accent: #10b981;
            --border-color: #1f2937;
        }
        body {
            font-family: system-ui, -apple-system, sans-serif;
            background-color: var(--bg-color);
            color: var(--text-color);
            margin: 0;
            padding: 0;
            display: flex;
            flex-direction: column;
            min-height: 100vh;
        }
        header {
            background-color: var(--card-bg);
            border-bottom: 1px solid var(--border-color);
            padding: 1.5rem;
            text-align: center;
        }
        header h1 {
            margin: 0;
            font-size: 2rem;
            font-weight: 800;
            background: linear-gradient(135deg, #3b82f6, #10b981);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 0.5rem;
        }
        header p {
            margin: 0.5rem 0 0 0;
            color: var(--text-muted);
            font-size: 0.95rem;
        }
        .container {
            max-width: 1100px;
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
        @media (min-width: 768px) {
            .main-layout {
                grid-template-columns: 3fr 2fr;
            }
        }
        .player-card {
            background-color: var(--card-bg);
            border-radius: 16px;
            border: 1px solid var(--border-color);
            padding: 1.25rem;
            box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);
        }
        video {
            width: 100%;
            border-radius: 10px;
            background-color: #000;
            max-height: 480px;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
        }
        .video-info {
            margin-top: 1.25rem;
        }
        .video-title {
            font-size: 1.3rem;
            font-weight: 700;
            margin: 0 0 0.5rem 0;
            color: #ffffff;
            word-break: break-all;
        }
        .video-meta {
            font-size: 0.85rem;
            color: var(--text-muted);
            display: flex;
            gap: 1rem;
        }
        .list-card {
            background-color: var(--card-bg);
            border-radius: 16px;
            border: 1px solid var(--border-color);
            padding: 1.5rem;
            box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);
            display: flex;
            flex-direction: column;
            max-height: 700px;
        }
        .list-card h2 {
            margin: 0 0 1rem 0;
            font-size: 1.4rem;
            font-weight: 700;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 0.75rem;
            color: #ffffff;
        }
        .file-list-container {
            overflow-y: auto;
            flex-grow: 1;
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
            background-color: var(--primary-glow);
            transform: translateY(-2px);
        }
        .file-item.active {
            border-color: var(--accent);
            background-color: rgba(16, 185, 129, 0.08);
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
            border-radius: 8px;
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
        }
        .btn-primary:hover {
            background-color: #2563eb;
            box-shadow: 0 0 12px rgba(59, 130, 246, 0.3);
        }
        .btn-secondary {
            background-color: #1f2937;
            color: #e5e7eb;
            border: 1px solid var(--border-color);
        }
        .btn-secondary:hover {
            background-color: #374151;
        }
        .cli-section {
            grid-column: 1 / -1;
            background-color: var(--card-bg);
            border-radius: 16px;
            border: 1px solid var(--border-color);
            padding: 1.5rem;
            box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);
        }
        .cli-section h3 {
            margin: 0 0 1rem 0;
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
            background-color: #070a13;
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
            background-color: #020408;
            border-radius: 6px;
            overflow: hidden;
            border: 1px solid #111827;
        }
        .cli-code {
            flex-grow: 1;
            padding: 0.6rem 0.8rem;
            font-family: 'Courier New', Courier, monospace;
            font-size: 0.85rem;
            color: #34d399;
            overflow-x: auto;
            white-space: nowrap;
        }
        .cli-copy {
            background-color: #1f2937;
            border: none;
            color: #e5e7eb;
            padding: 0.5rem 0.75rem;
            cursor: pointer;
            font-size: 0.75rem;
            font-weight: 600;
            transition: background-color 0.1s;
        }
        .cli-copy:hover {
            background-color: #374151;
        }
        .empty-state {
            text-align: center;
            padding: 4rem 2rem;
            color: var(--text-muted);
            border: 2px dashed var(--border-color);
            border-radius: 12px;
        }
        .empty-state-icon {
            font-size: 3rem;
            margin-bottom: 1rem;
        }
        .badge {
            background-color: rgba(59, 130, 246, 0.15);
            color: var(--primary);
            padding: 0.2rem 0.6rem;
            border-radius: 9999px;
            font-size: 0.75rem;
            font-weight: 600;
        }
        footer {
            text-align: center;
            padding: 2rem;
            color: var(--text-muted);
            font-size: 0.85rem;
            border-top: 1px solid var(--border-color);
            margin-top: auto;
            background-color: var(--card-bg);
        }
    </style>
</head>
<body>
    <header>
        <h1>⚡ LAN Stream</h1>
        <p>Streaming from <strong>Android App</strong> at <code>$hostIp:$port</code></p>
    </header>
    <div class="container">
        ${
            if (files.isEmpty()) {
                """
                <div class="empty-state">
                    <div class="empty-state-icon">🎬</div>
                    <h2>No shared files yet</h2>
                    <p>Open the LAN Stream app on your Android device and tap "Add Media Files" to start streaming.</p>
                </div>
                """
            } else {
                """
                <div class="main-layout">
                    <!-- Player Column -->
                    <div class="player-card">
                        <video id="video-player" controls autoplay playsinline></video>
                        <div class="video-info">
                            <h2 class="video-title" id="current-title">Select a video to stream</h2>
                            <div class="video-meta">
                                <span class="badge" id="current-format">N/A</span>
                                <span id="current-size">0 MB</span>
                            </div>
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
                        <h3>💻 CLI Sharing Commands</h3>
                        <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0; margin-bottom: 1rem;">
                            You can easily fetch, download, or stream this video directly on your terminal using these pre-configured commands.
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
                                <div class="cli-label">Stream with VLC</div>
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
        <p>⚡ LAN Stream Server runs locally &middot; Fully client-side peer delivery</p>
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

                li.innerHTML = `
                    <div class="file-header">
                        <span class="file-name">${'$'}{file.name}</span>
                        <span class="file-size">${'$'}{file.size}</span>
                    </div>
                    <div class="action-buttons">
                        <a href="${'$'}{file.url}" download="${'$'}{file.name}" class="btn btn-primary" onclick="event.stopPropagation();">
                            ⬇️ Download
                        </a>
                        <button class="btn btn-secondary" onclick="event.stopPropagation(); playFile(${'$'}{file.id});">
                            ▶️ Play Inline
                        </button>
                    </div>
                `;
                listEl.appendChild(li);
            });
        }

        function playFile(id) {
            const file = files.find(f => f.id === id);
            if (!file) return;

            // Update Active Class
            document.querySelectorAll('.file-item').forEach(el => el.classList.remove('active'));
            const activeItem = document.getElementById('file-' + id);
            if (activeItem) activeItem.classList.add('active');

            // Update Player
            const player = document.getElementById('video-player');
            player.src = file.url;
            player.load();
            player.play().catch(err => console.log('Autoplay blocked, waiting for interaction'));

            // Update Details
            document.getElementById('current-title').textContent = file.name;
            document.getElementById('current-format').textContent = file.mimeType;
            document.getElementById('current-size').textContent = file.size;

            // Show CLI commands
            document.getElementById('cli-section').style.display = 'block';
            
            // Build absolute file URL
            const fullUrl = baseUrl + file.url;
            
            // Update CLI codes
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

        // Initialize
        if (files.length > 0) {
            populateList();
            // Play first file automatically
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
