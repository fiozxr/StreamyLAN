package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import java.util.Locale
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize ServerManager states and preferences
        ServerManager.init(applicationContext)
        
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    LanStreamApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// Custom Palette for Cyberpunk-Slate Theme
val CyberBg = Color(0xFF0B0F19)
val CyberCard = Color(0xFF111827)
val CyberPrimary = Color(0xFF3B82F6)
val CyberAccent = Color(0xFF10B981)
val CyberMuted = Color(0xFF9CA3AF)
val CyberBorder = Color(0xFF1F2937)

@Composable
fun LanStreamApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Observe State from global manager
    val isRunning by ServerManager.isServerRunning.collectAsState()
    val serverPort by ServerManager.serverPort.collectAsState()
    val serverUrl by ServerManager.serverUrl.collectAsState()
    val sharedFiles by ServerManager.sharedFiles.collectAsState()

    val activeTransfers by ServerManager.activeTransfers.collectAsState()
    val discoveredInstances by DiscoveryManager.discoveredInstances.collectAsState()
    val isScanning by DiscoveryManager.isScanning.collectAsState()
    val connectedDevices by ServerManager.connectedDevices.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var inputPort by remember { mutableStateOf(serverPort.toString()) }

    // Media video picker contract launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                ServerManager.addFile(context, uri)
            }
            Toast.makeText(context, "Added ${uris.size} files for sharing!", Toast.LENGTH_SHORT).show()
        }
    }

    // Dynamic Permission request for notifications on Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notifications recommended to keep server running in background", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasNotificationPermission) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = CyberBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card with beautiful title and dynamic status indicator
            AppHeader(isRunning = isRunning)

            // Central Status Dashboard
            StatusDashboard(
                isRunning = isRunning,
                serverUrl = serverUrl,
                port = serverPort,
                sharedFilesCount = sharedFiles.size,
                onStartClick = {
                    if (isRunning) {
                        ServerManager.stopServer(context)
                    } else {
                        ServerManager.startServer(context)
                    }
                },
                onConfigureClick = {
                    inputPort = serverPort.toString()
                    showSettingsDialog = true
                },
                onCopyClick = {
                    serverUrl?.let {
                        clipboardManager.setText(AnnotatedString(it))
                        Toast.makeText(context, "URL Copied to Clipboard!", Toast.LENGTH_SHORT).show()
                    }
                },
                onShareClick = {
                    serverUrl?.let { url ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "LAN Stream Hub")
                            putExtra(Intent.EXTRA_TEXT, "Stream media files from my Android device on: $url")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Stream URL"))
                    }
                }
            )

            // Real-time visual progress indicator for media files being transferred/accessed
            ActiveTransfersSection(activeTransfers = activeTransfers, connectedDevices = connectedDevices)

            // Tab-based Navigation for Media vs Device Control

            // Section Header and File Management List
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CyberCard),
                border = CardBorder()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Custom Tabs Navigation
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TabButton(
                            text = "Media Library",
                            selected = selectedTab == 0,
                            icon = Icons.Rounded.Folder,
                            badgeCount = sharedFiles.size,
                            onClick = { selectedTab = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        TabButton(
                            text = "Device Access",
                            selected = selectedTab == 1,
                            icon = Icons.Rounded.Devices,
                            badgeCount = connectedDevices.size,
                            onClick = { selectedTab = 1 },
                            modifier = Modifier.weight(1.1f)
                        )
                        TabButton(
                            text = "Discover LAN",
                            selected = selectedTab == 2,
                            icon = Icons.Rounded.Wifi,
                            badgeCount = discoveredInstances.size,
                            onClick = {
                                selectedTab = 2
                                DiscoveryManager.scanNetwork(context)
                            },
                            modifier = Modifier.weight(1.1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (selectedTab == 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Shared Media Files",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${sharedFiles.size} movies or videos active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyberMuted
                                )
                            }

                            Button(
                                onClick = { videoPickerLauncher.launch("video/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("add_media_button")
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Add File", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Media", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (sharedFiles.isEmpty()) {
                            EmptyStatePlaceholder(
                                onSelectFiles = { videoPickerLauncher.launch("video/*") }
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(
                                    items = sharedFiles,
                                    key = { _, file -> file.uriString }
                                ) { index, file ->
                                    MediaFileItem(
                                        index = index,
                                        file = file,
                                        serverUrl = serverUrl,
                                        onDeleteClick = {
                                            ServerManager.removeFile(context, file)
                                            Toast.makeText(context, "Removed file from sharing list", Toast.LENGTH_SHORT).show()
                                        },
                                        onCopyCommandClick = { command ->
                                            clipboardManager.setText(AnnotatedString(command))
                                            Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    } else if (selectedTab == 1) {
                        DeviceAccessList(
                            devices = connectedDevices,
                            onToggleBlock = { ip ->
                                ServerManager.toggleDeviceBlock(ip)
                                Toast.makeText(context, "Access permission updated for $ip", Toast.LENGTH_SHORT).show()
                            },
                            onClearHistory = {
                                ServerManager.clearDevices()
                                Toast.makeText(context, "Connection history cleared", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        LanDiscoverySection(
                            discoveredInstances = discoveredInstances,
                            isScanning = isScanning,
                            onScanClick = {
                                DiscoveryManager.scanNetwork(context)
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialog for changing server Port configuration
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text(
                    "Server Configurations",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Set the TCP port for the LAN server. Values between 1024 and 65535 are recommended.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberMuted
                    )
                    OutlinedTextField(
                        value = inputPort,
                        onValueChange = { inputPort = it.filter { char -> char.isDigit() } },
                        label = { Text("Server Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberPrimary,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("port_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val portNum = inputPort.toIntOrNull()
                        if (portNum != null && portNum in 1024..65535) {
                            ServerManager.setPort(context, portNum)
                            showSettingsDialog = false
                            Toast.makeText(context, "Port updated to $portNum", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid port. Must be between 1024 and 65535.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberAccent)
                ) {
                    Text("Apply Port")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel", color = CyberMuted)
                }
            },
            containerColor = CyberCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun AppHeader(isRunning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(CyberPrimary.copy(alpha = 0.4f), Color.Transparent)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = if (isRunning) CyberAccent else CyberPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "streamyLAN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    text = "High Performance LAN Streamer Hub",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberMuted
                )
            }
        }

        // Mini Badge
        Box(
            modifier = Modifier
                .background(
                    color = if (isRunning) CyberAccent.copy(alpha = 0.15f) else Color.DarkGray.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(99.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Pulse dot
                if (isRunning) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(CyberAccent.copy(alpha = alpha), CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.Gray, CircleShape)
                    )
                }

                Text(
                    text = if (isRunning) "SERVER ACTIVE" else "OFFLINE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning) CyberAccent else Color.Gray
                )
            }
        }
    }
}

@Composable
fun StatusDashboard(
    isRunning: Boolean,
    serverUrl: String?,
    port: Int,
    sharedFilesCount: Int,
    onStartClick: () -> Unit,
    onConfigureClick: () -> Unit,
    onCopyClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard),
        border = CardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pulse wave animation when running
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isRunning) {
                    val transition = rememberInfiniteTransition(label = "waves")
                    val scale1 by transition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "scale1"
                    )
                    val scale2 by transition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, delayMillis = 1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "scale2"
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = CyberAccent.copy(alpha = 1f - scale1),
                            radius = size.minDimension / 2 * scale1,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = CyberAccent.copy(alpha = 1f - scale2),
                            radius = size.minDimension / 2 * scale2,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // Inner Circle Button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = if (isRunning) {
                                    listOf(CyberAccent, CyberAccent.copy(alpha = 0.7f))
                                } else {
                                    listOf(CyberPrimary, CyberPrimary.copy(alpha = 0.7f))
                                }
                            )
                        )
                        .clickable { onStartClick() }
                        .testTag("server_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = "Toggle Server",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Address display card
            if (isRunning && serverUrl != null) {
                Text(
                    text = "Open this link on any computer/TV in your Wi-Fi:",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberMuted,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { onCopyClick() }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Language, contentDescription = "Web", tint = CyberPrimary, modifier = Modifier.size(16.dp))
                        Text(
                            text = serverUrl,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = CyberAccent
                        )
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = CyberMuted, modifier = Modifier.size(14.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons for running server
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onCopyClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(CyberBorder, CyberBorder)))
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Link")
                    }

                    Button(
                        onClick = onShareClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Link")
                    }
                }
            } else {
                Text(
                    text = "Server is Offline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap the button above to start the server. Make sure you are connected to local Wi-Fi or have a hotspot active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(color = CyberBorder, thickness = 1.dp)

            Spacer(modifier = Modifier.height(12.dp))

            // Footer of status dashboard: Port configurations
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Port", tint = CyberMuted, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Configured Port: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberMuted
                    )
                    Text(
                        text = port.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                TextButton(
                    onClick = onConfigureClick,
                    colors = ButtonDefaults.textButtonColors(contentColor = CyberPrimary)
                ) {
                    Text("Change Port", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun MediaFileItem(
    index: Int,
    file: SharedFile,
    serverUrl: String?,
    onDeleteClick: () -> Unit,
    onCopyCommandClick: (String) -> Unit
) {
    var expandedCommands by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.2f)),
        border = CardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Movie icon container
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Video",
                            tint = CyberPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = formatBytes(file.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberMuted
                            )
                            Box(
                                modifier = Modifier
                                    .background(CyberPrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = file.mimeType.substringAfter("/").uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (serverUrl != null) {
                        IconButton(
                            onClick = { expandedCommands = !expandedCommands }
                        ) {
                            Icon(
                                imageVector = if (expandedCommands) Icons.Rounded.Settings else Icons.Rounded.Info,
                                contentDescription = "CLI Commands",
                                tint = if (expandedCommands) CyberAccent else CyberMuted
                            )
                        }
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.testTag("delete_file_button_$index")
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.8f))
                    }
                }
            }

            // CLI Commands expansion panel
            AnimatedVisibility(
                visible = expandedCommands && serverUrl != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                val fullFileUrl = "$serverUrl/files/$index"
                val escapedName = "\"" + file.name.replace("\"", "\\\"") + "\""
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Run these commands on your terminal to download/stream:",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    CliCommandBlock(
                        label = "Fetch with cURL",
                        command = "curl -L -o $escapedName \"$fullFileUrl\"",
                        onCopyClick = onCopyCommandClick
                    )

                    CliCommandBlock(
                        label = "Fetch with wget",
                        command = "wget -O $escapedName \"$fullFileUrl\"",
                        onCopyClick = onCopyCommandClick
                    )

                    CliCommandBlock(
                        label = "Stream with VLC",
                        command = "vlc \"$fullFileUrl\"",
                        onCopyClick = onCopyCommandClick
                    )
                }
            }
        }
    }
}

@Composable
fun CliCommandBlock(
    label: String,
    command: String,
    onCopyClick: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberMuted))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF020408))
                .clickable { onCopyClick(command) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFF34D399),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = CyberMuted, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
fun EmptyStatePlaceholder(onSelectFiles: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onSelectFiles() }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudUpload,
            contentDescription = "Empty list",
            tint = CyberMuted,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "No videos shared yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Tap here to select video or movie files from your device gallery or local storage to start broadcasting.",
            style = MaterialTheme.typography.bodySmall,
            color = CyberMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun CardBorder() = BorderStroke(1.dp, CyberBorder)

fun formatBytes(bytes: Long, decimals: Int = 2): String {
    if (bytes <= 0) return "0 Bytes"
    val k = 1024
    val dm = if (decimals < 0) 0 else decimals
    val sizes = arrayOf("Bytes", "KB", "MB", "GB", "TB")
    val i = (Math.floor(Math.log(bytes.toDouble()) / Math.log(k.toDouble()))).toInt()
    val sizeIndex = if (i in sizes.indices) i else sizes.size - 1
    return String.format(Locale.US, "%.${dm}f %s", bytes / Math.pow(k.toDouble(), sizeIndex.toDouble()), sizes[sizeIndex])
}

@Composable
fun TabButton(
    text: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) CyberPrimary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (selected) CyberPrimary else CyberMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else CyberMuted
            )
            if (badgeCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = if (selected) CyberPrimary else CyberMuted.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceAccessList(
    devices: List<ConnectedDevice>,
    onToggleBlock: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Access Permissions & Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (devices.isNotEmpty()) {
                TextButton(
                    onClick = onClearHistory,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (devices.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    tint = CyberMuted,
                    modifier = Modifier.size(48.dp),
                    contentDescription = "Security"
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Network Dashboard Clear",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "When external devices in your local area network (LAN) stream or browse files, they will show up here. You can selectively block/allow access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberMuted,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(devices.size) { index ->
                    val device = devices[index]
                    DeviceItemCard(device = device, onToggleBlock = { onToggleBlock(device.ipAddress) })
                }
            }
        }
    }
}

@Composable
fun DeviceItemCard(
    device: ConnectedDevice,
    onToggleBlock: () -> Unit
) {
    val isBlocked = device.isBlocked
    val cardBorderColor = if (isBlocked) Color.Red.copy(alpha = 0.4f) else CyberBorder
    val cardBg = if (isBlocked) Color.Red.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.2f)
    
    val deviceIcon = when {
        device.displayName.contains("Android", ignoreCase = true) || device.displayName.contains("iPhone", ignoreCase = true) -> Icons.Rounded.Smartphone
        device.displayName.contains("Terminal", ignoreCase = true) -> Icons.Rounded.Terminal
        device.displayName.contains("PC", ignoreCase = true) -> Icons.Rounded.Computer
        device.displayName.contains("VLC", ignoreCase = true) -> Icons.Rounded.Tv
        else -> Icons.Rounded.Router
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (isBlocked) Color.Red.copy(alpha = 0.15f) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = deviceIcon,
                        contentDescription = device.displayName,
                        tint = if (isBlocked) Color.Red else CyberAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = device.ipAddress,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = if (isBlocked) Color.Red.copy(alpha = 0.8f) else Color.White
                        )
                        if (isBlocked) {
                            Box(
                                modifier = Modifier
                                    .background(Color.Red.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "BLOCKED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Red,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .background(CyberAccent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "AUTHORIZED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "Requests: ${device.requestCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberMuted
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberMuted
                        )
                        Text(
                            text = formatRelativeTime(device.lastActive),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onToggleBlock,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBlocked) CyberAccent else Color.Red.copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = if (isBlocked) "Unblock" else "Block",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 5000 -> "Just now"
        diff < 60000 -> "${diff / 1000}s ago"
        diff < 3600000 -> "${diff / 60000}m ago"
        else -> "Active today"
    }
}

@Composable
fun ActiveTransfersSection(
    activeTransfers: Map<String, FileTransferProgress>,
    connectedDevices: List<ConnectedDevice>
) {
    if (activeTransfers.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard),
        border = CardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapCalls,
                    contentDescription = null,
                    tint = CyberAccent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Live Network Streams",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            activeTransfers.values.forEach { transfer ->
                val deviceName = connectedDevices.firstOrNull { it.ipAddress == transfer.ipAddress }?.displayName ?: "Remote Client"
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = transfer.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Streaming to $deviceName (${transfer.ipAddress})",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        Text(
                            text = "${(transfer.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberAccent
                        )
                    }

                    LinearProgressIndicator(
                        progress = transfer.progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = CyberAccent,
                        trackColor = CyberBorder
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.totalBytes)}",
                            style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = CyberMuted)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(CyberAccent, CircleShape)
                            )
                            Text(
                                text = "Transmitting",
                                style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = CyberAccent)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LanDiscoverySection(
    discoveredInstances: List<DiscoveredInstance>,
    isScanning: Boolean,
    onScanClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Discovered LAN Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Detecting active streamyLAN servers on Wi-Fi",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberMuted
                )
            }
            
            Button(
                onClick = onScanClick,
                enabled = !isScanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberPrimary,
                    disabledContainerColor = CyberPrimary.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scanning...", style = MaterialTheme.typography.labelMedium)
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = "Scan", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan LAN", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (isScanning && discoveredInstances.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                RadarScannerAnimation()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Pinging subnet...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "Broadcasting UDP packets to discover other streamyLAN nodes on local subnet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        } else if (discoveredInstances.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SignalWifiStatusbarNull,
                    tint = CyberMuted,
                    modifier = Modifier.size(48.dp),
                    contentDescription = "No devices"
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No Devices Detected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Make sure other devices on this Wi-Fi have streamyLAN active, and their server is running. Tap Scan to search again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onScanClick,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Scan Now")
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(discoveredInstances.size) { index ->
                    val instance = discoveredInstances[index]
                    DiscoveredInstanceItem(instance = instance)
                }
            }
        }
    }
}

@Composable
fun DiscoveredInstanceItem(instance: DiscoveredInstance) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.2f)),
        border = CardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = CyberAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = instance.deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = instance.serverUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = CyberPrimary
                    )
                    
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .background(CyberAccent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${instance.activeFilesCount} Shared Media Files",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(instance.serverUrl))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open server url", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberAccent),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = "Connect",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun RadarScannerAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2.0f
            val center = size / 2.0f
            
            // Draw grid concentric circles
            drawCircle(color = CyberPrimary.copy(alpha = 0.15f), radius = radius, style = Stroke(width = 1.dp.toPx()))
            drawCircle(color = CyberPrimary.copy(alpha = 0.1f), radius = radius * 0.66f, style = Stroke(width = 1.dp.toPx()))
            drawCircle(color = CyberPrimary.copy(alpha = 0.05f), radius = radius * 0.33f, style = Stroke(width = 1.dp.toPx()))
            
            // Draw crosshairs
            drawLine(
                color = CyberPrimary.copy(alpha = 0.1f),
                start = androidx.compose.ui.geometry.Offset(center.width, 0f),
                end = androidx.compose.ui.geometry.Offset(center.width, size.height),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = CyberPrimary.copy(alpha = 0.1f),
                start = androidx.compose.ui.geometry.Offset(0f, center.height),
                end = androidx.compose.ui.geometry.Offset(size.width, center.height),
                strokeWidth = 1.dp.toPx()
            )
        }
        
        // Rotating radar sweep
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    rotate(rotation) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    CyberAccent.copy(alpha = 0.4f),
                                    Color.Transparent
                                )
                            ),
                            startAngle = 0f,
                            sweepAngle = 90f,
                            useCenter = true
                        )
                    }
                }
        )
        
        Icon(
            imageVector = Icons.Rounded.Wifi,
            contentDescription = null,
            tint = CyberAccent.copy(alpha = alpha),
            modifier = Modifier.size(36.dp)
        )
    }
}
