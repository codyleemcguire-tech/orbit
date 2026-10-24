package com.orbit.player

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

// Copies a profile from one device to another over the home network, with no account or
// cloud. The sending device shows a 4-digit PIN and answers "who's sending?" broadcasts;
// the receiving device finds it, asks for the PIN and downloads the profile.
object ProfileTransfer {

    const val discoveryPort = 47821
    private const val ask = "ORBIT-FIND-PROFILE"
    private const val reply = "ORBIT-PROFILE"

    data class Sender(val device: String, val profile: String, val host: String, val port: Int)

    fun deviceName(context: Context): String =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    // The sending side. Serves the profile to whoever gives the right PIN; five wrong PINs
    // and it stops. onSent runs (on a background thread) after a device has downloaded it.
    class Server(
        private val context: Context,
        private val payload: String,
        private val profileName: String,
        private val onSent: (String) -> Unit
    ) {
        val pin = (1000..9999).random().toString()
        private var tcp: ServerSocket? = null
        private var udp: DatagramSocket? = null
        private var lock: WifiManager.MulticastLock? = null
        @Volatile private var wrongPins = 0
        @Volatile private var running = false

        // Returns "ip:port" for typing in by hand, or null when there's no network.
        fun start(): String? {
            val ip = localAddress() ?: return null
            running = true
            val server = ServerSocket(0)
            tcp = server
            thread(name = "orbit-profile-send") {
                while (running) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    thread { handle(client) }
                }
            }
            // Receiving broadcasts needs the Wi-Fi multicast lock on most TVs.
            lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("orbit-profile")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            thread(name = "orbit-profile-discovery") {
                try {
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(discoveryPort))
                    }
                    udp = socket
                    val buffer = ByteArray(256)
                    while (running) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        if (String(packet.data, 0, packet.length) != ask) continue
                        val message = "$reply|${deviceName(context)}|$profileName|${server.localPort}".toByteArray()
                        socket.send(DatagramPacket(message, message.size, packet.address, packet.port))
                    }
                } catch (_: Exception) {
                }
            }
            return "$ip:${server.localPort}"
        }

        fun stop() {
            running = false
            try { tcp?.close() } catch (_: Exception) {}
            try { udp?.close() } catch (_: Exception) {}
            try { lock?.release() } catch (_: Exception) {}
        }

        private fun handle(client: Socket) {
            client.use {
                try {
                    client.soTimeout = 15000
                    val input = BufferedInputStream(client.getInputStream())
                    val request = readLine(input) ?: return
                    var from = client.inetAddress.hostAddress.orEmpty()
                    while (true) {
                        val header = readLine(input) ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("X-Orbit-Device:", true)) from = header.substringAfter(':').trim()
                    }
                    val path = request.split(' ').getOrNull(1).orEmpty()
                    val given = path.substringAfter("pin=", "").take(4)
                    when {
                        !path.startsWith("/profile") -> respond(client, 404, "Not found")
                        wrongPins >= 5 -> respond(client, 403, "Too many wrong PINs")
                        given != pin -> {
                            wrongPins++
                            respond(client, 401, "Wrong PIN")
                            if (wrongPins >= 5) stop()
                        }
                        else -> {
                            respond(client, 200, payload, "application/json")
                            onSent(from)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun respond(client: Socket, status: Int, body: String, type: String = "text/plain") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = when (status) { 200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; else -> "Not Found" }
            client.getOutputStream().apply {
                write(("HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes)
                flush()
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (line.isEmpty()) null else line.toString()
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                line.append(c.toChar())
                if (line.length > 4096) return null
            }
        }
    }

    // The receiving side: devices on the network that are sending a profile right now.
    // Takes about two seconds. Call off the main thread.
    fun discover(context: Context): List<Sender> {
        val found = linkedMapOf<String, Sender>()
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("orbit-profile-find")?.apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val message = ask.toByteArray()
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                val until = System.currentTimeMillis() + 2200
                var nextSend = 0L
                while (System.currentTimeMillis() < until) {
                    // Ask a few times in case a packet is lost.
                    if (System.currentTimeMillis() >= nextSend) {
                        targets.forEach {
                            try { socket.send(DatagramPacket(message, message.size, it, discoveryPort)) } catch (_: Exception) {}
                        }
                        nextSend = System.currentTimeMillis() + 700
                    }
                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val parts = String(packet.data, 0, packet.length).split('|')
                    if (parts.size != 4 || parts[0] != reply) continue
                    val host = packet.address.hostAddress ?: continue
                    val port = parts[3].toIntOrNull() ?: continue
                    found["$host:$port"] = Sender(parts[1], parts[2], host, port)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    class WrongPin : Exception()

    // Downloads the profile; throws WrongPin when the sender says the PIN is wrong. A plain
    // socket rather than HttpURLConnection: Orbit blocks cleartext HTTP, and this is the one
    // place that talks plain HTTP, to a device on the home network.
    fun fetch(context: Context, host: String, port: Int, pin: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 20000
            socket.getOutputStream().apply {
                write(("GET /profile?pin=$pin HTTP/1.0\r\nHost: $host\r\n" +
                    "X-Orbit-Device: ${deviceName(context)}\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }
            val response = socket.getInputStream().readBytes()
            val split = (0 until response.size - 3).firstOrNull {
                response[it] == '\r'.code.toByte() && response[it + 1] == '\n'.code.toByte() &&
                    response[it + 2] == '\r'.code.toByte() && response[it + 3] == '\n'.code.toByte()
            } ?: throw IllegalStateException("The other device sent something unexpected.")
            val status = String(response, 0, split).lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
            val body = String(response, split + 4, response.size - split - 4, Charsets.UTF_8)
            when (status) {
                200 -> return body
                401 -> throw WrongPin()
                403 -> throw IllegalStateException("The other device stopped after too many wrong PINs.")
                else -> throw IllegalStateException("The other device answered $status.")
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    } catch (_: Exception) {
        emptyList()
    }

    private fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
