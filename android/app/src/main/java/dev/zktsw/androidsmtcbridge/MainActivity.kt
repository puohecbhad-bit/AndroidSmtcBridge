package dev.zktsw.androidsmtcbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.BitmapFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { MediaBridgeTheme { BridgeScreen() } }
    }
}

@Composable
private fun MediaBridgeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = (LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colors,
        shapes = androidx.compose.material3.Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by BridgeState.state.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(BridgePreferences.load(context)) }
    var portText by remember(config.port) { mutableStateOf(config.port.toString()) }
    var pinText by remember(config.pin) { mutableStateOf(config.pin) }

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.all { it }) MediaBridgeService.reload(context)
    }
    val discoverable = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        MediaBridgeService.reload(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun persist(next: BridgeConfig) {
        config = next
        BridgePreferences.save(context, next)
        MediaBridgeService.reload(context)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Media Bridge", fontWeight = FontWeight.Bold)
                        Text("Android → Windows SMTC", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { MediaBridgeService.reload(context) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusHero(state)
            NowPlayingCard(state.media)

            ExpressiveCard(title = "连接方式", icon = Icons.Rounded.Router) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = config.wifiEnabled,
                        onClick = { persist(config.copy(wifiEnabled = !config.wifiEnabled)) },
                        label = { Text("Wi-Fi") },
                        leadingIcon = { Icon(Icons.Rounded.Router, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = config.bluetoothEnabled,
                        onClick = {
                            val enabled = !config.bluetoothEnabled
                            persist(config.copy(bluetoothEnabled = enabled))
                            if (enabled && Build.VERSION.SDK_INT >= 31) {
                                bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                            }
                        },
                        label = { Text("蓝牙") },
                        leadingIcon = { Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp)) },
                    )
                }

                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        portText = value.filter(Char::isDigit).take(5)
                        value.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { persist(config.copy(port = it)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Wi-Fi 端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )

                if (config.bluetoothEnabled) {
                    Button(onClick = {
                        discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        })
                    }) {
                        Icon(Icons.Rounded.Bluetooth, null)
                        Spacer(Modifier.size(8.dp))
                        Text("让电脑发现此手机（5 分钟）")
                    }
                }

                AnimatedVisibility(state.wifiAddresses.isNotEmpty() && config.wifiEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Windows 可连接：", style = MaterialTheme.typography.labelLarge)
                        state.wifiAddresses.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            ExpressiveCard(title = "配对 PIN", icon = Icons.Rounded.ContentCopy) {
                OutlinedTextField(
                    value = pinText,
                    onValueChange = { value ->
                        pinText = value.filter(Char::isDigit).take(6)
                        if (pinText.length == 6) persist(config.copy(pin = pinText))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("6 位 PIN") },
                    trailingIcon = {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(config.pin)) }) {
                            Icon(Icons.Rounded.ContentCopy, "复制 PIN")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                Text("PIN 用于阻止同一局域网中的陌生客户端接管播放；连接未加密，请仅在可信网络使用。", style = MaterialTheme.typography.bodySmall)
            }

            ExpressiveCard(title = "媒体权限", icon = Icons.Rounded.NotificationsActive) {
                Text(if (state.listenerConnected) "通知使用权已开启" else "需要通知使用权才能读取其他应用的 MediaSession。")
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Icon(Icons.Rounded.NotificationsActive, null)
                    Spacer(Modifier.size(8.dp))
                    Text("打开通知使用权设置")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatusHero(state: BridgeUiState) {
    val healthy = state.listenerConnected && (state.wifiRunning || state.bluetoothRunning)
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (healthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(topStart = 42.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 42.dp),
    ) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .72f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, Modifier.size(30.dp))
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(healthy, label = "bridge-status") { ready ->
                    Text(if (ready) "桥接服务已就绪" else "还需要完成设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text("${state.connectedClients} 个 Windows 客户端 · Wi-Fi ${onOff(state.wifiRunning)} · 蓝牙 ${onOff(state.bluetoothRunning)}")
                if (state.lastError.isNotBlank()) Text(state.lastError, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NowPlayingCard(media: MediaSnapshot) {
    val art = remember(media.artBase64) {
        runCatching {
            Base64.decode(media.artBase64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()
    }
    ExpressiveCard(title = "正在同步", icon = Icons.Rounded.MusicNote) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                Image(art.asImageBitmap(), null, Modifier.size(82.dp).clip(RoundedCornerShape(24.dp)))
            } else {
                Box(
                    Modifier.size(82.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.MusicNote, null, Modifier.size(38.dp)) }
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(media.title.ifBlank { "暂无媒体" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(media.artist.ifBlank { media.appName.ifBlank { "等待播放器" } }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (media.album.isNotBlank()) Text(media.album, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ExpressiveCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Icon(icon, null, Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

private fun onOff(value: Boolean) = if (value) "开" else "关"
