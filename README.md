# ⚡ streamyLAN - Mobile Media Hub & LAN Streamer

`streamyLAN` is a high-performance Android application built with **Kotlin** and **Jetpack Compose** that instantly transforms your mobile phone into a local area network (LAN) streaming server. It allows anyone on your home or office Wi-Fi network to browse and stream videos or movies directly in their web browsers, or download files instantly with command-line tools like `cURL` and `wget`.

---

## 🚀 Key Features

- **Local Web Server**: Instantly broadcast video content to any browser on the same network (smart TVs, laptops, tablets, PCs).
- **Access Management Dashboard**: Active monitoring of connected clients with the ability to selectively **grant** or **prevent/block** access via their local IP address.
- **Terminal Friendly**: Pre-configured copyable command-line patterns for downloading/streaming via `cURL`, `wget`, or VLC Media Player.
- **Adaptive Range Support**: Fully supports video scrubbing and seeks (via HTTP `Range: bytes` headers) for seamless, buffer-free playback in modern browser players or VLC.
- **Customizable Port Configuration**: Change the default TCP port (8080) to any open port between 1024 and 65535.
- **Background Play**: Runs as a foreground service with a status notification card to ensure stable playback even when the device is locked.

---

## 🛠️ How it Works

1. **Add Media Files**: Launch `streamyLAN` and tap the **"Add Media"** button to select videos or movies from your device storage.
2. **Start Server**: Tap the central pulse play/pause button. This launches an inline, low-overhead HTTP Server on your device's local IP address and selected port.
3. **Open Link on Other Devices**: Simply open the highlighted URL (e.g. `http://192.168.1.100:8080`) on any browser in the local network to play or download files.
4. **Access Control**: Monitor incoming connections in real-time under the **"Device Access"** tab. Block or unblock devices dynamically.

---

## 💻 CLI Commands Support

For terminal power-users, `streamyLAN` displays direct command lines to fetch files or play streams directly from any macOS, Linux, or Windows terminal.

### Fetching with cURL:
```bash
curl -L -o "your_movie.mp4" "http://<android_ip_address>:<port>/files/<file_id>"
```

### Fetching with wget:
```bash
wget -O "your_movie.mp4" "http://<android_ip_address>:<port>/files/<file_id>"
```

### Streaming directly with VLC:
```bash
vlc "http://<android_ip_address>:<port>/files/<file_id>"
```

---

## 🧱 Project Architecture & Tech Stack

- **Jetpack Compose**: 100% declarative UI with Material Design 3 guidelines, modern typography, and responsive grid layouts.
- **Kotlin Coroutines & Flow**: Used for non-blocking I/O file serving, background processes, and asynchronous state updates.
- **Multi-threaded Web Server**: Built on lightweight, robust socket structures to serve byte-range streams smoothly.
- **Android Foreground Service**: Prevents the system from reclaiming resources when serving large video files over LAN.
