package dev.zktsw.androidsmtcbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TransportHub(
    private val context: Context,
    private val commandHandler: (RemoteCommand) -> Unit,
) : Closeable {
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private var wifiServer: ServerSocket? = null
    private var bluetoothServer: android.bluetooth.BluetoothServerSocket? = null
    private val running = AtomicBoolean(false)
    @Volatile private var config = BridgePreferences.load(context)
    @Volatile private var latest = MediaSnapshot()

    fun start(newConfig: BridgeConfig) {
        close()
        config = newConfig
        running.set(true)
        BridgeState.update {
            it.copy(
                wifiRunning = false,
                bluetoothRunning = false,
                wifiAddresses = localIpv4Addresses(newConfig.port),
                lastError = "",
            )
        }
        if (newConfig.wifiEnabled) startWifi(newConfig.port)
        if (newConfig.bluetoothEnabled) startBluetooth()
    }

    fun broadcast(snapshot: MediaSnapshot) {
        latest = snapshot
        val line = snapshot.toJson()
        clients.forEach { client ->
            if (!client.send(line)) removeClient(client)
        }
    }

    private fun startWifi(port: Int) {
        thread(name = "media-bridge-wifi", isDaemon = true) {
            try {
                val server = ServerSocket(port).also { wifiServer = it }
                BridgeState.update { it.copy(wifiRunning = true) }
                while (running.get()) {
                    val socket = server.accept()
                    attach(socket.getInputStream(), socket.getOutputStream(), socket)
                }
            } catch (error: Exception) {
                if (running.get()) reportError("Wi-Fi: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                BridgeState.update { it.copy(wifiRunning = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            reportError("请授予附近设备权限后再启用蓝牙")
            return
        }
        thread(name = "media-bridge-bluetooth", isDaemon = true) {
            try {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: error("设备不支持蓝牙")
                val server = adapter.listenUsingRfcommWithServiceRecord(
                    RFCOMM_SERVICE_NAME,
                    UUID.fromString(RFCOMM_SERVICE_UUID),
                ).also { bluetoothServer = it }
                BridgeState.update { it.copy(bluetoothRunning = true) }
                while (running.get()) {
                    val socket = server.accept()
                    attach(socket.inputStream, socket.outputStream, socket)
                }
            } catch (error: Exception) {
                if (running.get()) reportError("蓝牙: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                BridgeState.update { it.copy(bluetoothRunning = false) }
            }
        }
    }

    private fun attach(input: InputStream, output: OutputStream, resource: Closeable) {
        thread(name = "media-bridge-client", isDaemon = true) {
            var client: ClientConnection? = null
            try {
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
                val helloLine = reader.readLine() ?: return@thread
                val hello = JSONObject(helloLine)
                val accepted = hello.optString("type") == "hello" &&
                    hello.optInt("version") == PROTOCOL_VERSION &&
                    hello.optString("pin") == config.pin
                if (!accepted) {
                    writer.write(JSONObject().put("type", "error").put("message", "authentication failed").toString())
                    writer.newLine()
                    writer.flush()
                    return@thread
                }

                client = ClientConnection(writer, resource)
                clients += client
                updateClientCount()
                client.send(JSONObject().put("type", "hello").put("version", PROTOCOL_VERSION).put("accepted", true).toString())
                client.send(latest.toJson())

                while (running.get()) {
                    val line = reader.readLine() ?: break
                    RemoteCommand.fromJson(line)?.let(commandHandler)
                }
            } catch (_: Exception) {
                // A disconnected client is expected and should not become a persistent UI error.
            } finally {
                client?.let(::removeClient) ?: runCatching { resource.close() }
            }
        }
    }

    private fun removeClient(client: ClientConnection) {
        if (clients.remove(client)) {
            client.close()
            updateClientCount()
        }
    }

    private fun updateClientCount() {
        BridgeState.update { it.copy(connectedClients = clients.size) }
    }

    private fun reportError(message: String) {
        BridgeState.update { it.copy(lastError = message) }
    }

    override fun close() {
        running.set(false)
        runCatching { wifiServer?.close() }
        runCatching { bluetoothServer?.close() }
        wifiServer = null
        bluetoothServer = null
        clients.forEach { it.close() }
        clients.clear()
        BridgeState.update { it.copy(wifiRunning = false, bluetoothRunning = false, connectedClients = 0) }
    }

    private class ClientConnection(
        private val writer: BufferedWriter,
        private val resource: Closeable,
    ) : Closeable {
        @Synchronized
        fun send(line: String): Boolean = runCatching {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }.isSuccess

        override fun close() {
            runCatching { resource.close() }
        }
    }

    private fun localIpv4Addresses(port: Int): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .map { "${it.hostAddress}:$port" }
            .distinct()
    }.getOrDefault(emptyList())
}
