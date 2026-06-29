package com.example

data class ConnectedDevice(
    val ipAddress: String,
    val userAgent: String,
    val lastActive: Long,
    val requestCount: Int = 1,
    val isBlocked: Boolean = false
) {
    val displayName: String
        get() {
            return when {
                userAgent.contains("Chrome", ignoreCase = true) && userAgent.contains("Android", ignoreCase = true) -> "Android Chrome Browser"
                userAgent.contains("Safari", ignoreCase = true) && userAgent.contains("iPhone", ignoreCase = true) -> "iPhone Safari Browser"
                userAgent.contains("curl", ignoreCase = true) -> "Terminal (cURL)"
                userAgent.contains("Wget", ignoreCase = true) -> "Terminal (Wget)"
                userAgent.contains("VLC", ignoreCase = true) -> "VLC Media Player"
                userAgent.contains("Windows", ignoreCase = true) -> "Windows PC Browser"
                userAgent.contains("Macintosh", ignoreCase = true) -> "Mac PC Browser"
                userAgent.contains("Linux", ignoreCase = true) -> "Linux PC Browser"
                else -> "Generic Web Browser"
            }
        }
}
