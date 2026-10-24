package com.orbit.player

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

// A tiny web server that runs only while the "Add from phone" QR code is on screen.
// The phone opens http://<tv-ip>:<port>/<token>, pastes an addon URL and submits it;
// onSubmit receives the pasted text and returns the message shown on the phone. Requests
// without the random token get 404, so only whoever scanned the code can add addons.
class PhoneLinkServer(
    private val heading: String = "Paste an addon's manifest URL (or stremio:// link). It's added to Orbit on your TV.",
    private val placeholder: String = "https://…/manifest.json",
    private val button: String = "Add to Orbit",
    private val onSubmit: (String) -> String
) {

    val token: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    private var socket: ServerSocket? = null

    // Starts listening and returns the address for the QR code, or null without Wi-Fi.
    fun start(): String? {
        val ip = localIpAddress() ?: return null
        val server = ServerSocket(0)
        socket = server
        thread(name = "orbit-phone-link") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread { handle(client) }
            }
        }
        return "http://$ip:${server.localPort}/$token"
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun handle(client: Socket) {
        client.use {
            try {
                client.soTimeout = 15000
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input) ?: return
                var contentLength = 0
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty().substringBefore('?')

                when {
                    path != "/$token" -> respond(client, 404, "text/plain", "Not found")
                    method == "GET" -> respond(client, 200, "text/html; charset=utf-8", page(null))
                    method == "POST" -> {
                        // Room for a large collections export, not just a URL.
                        val body = ByteArray(contentLength.coerceIn(0, 2 * 1024 * 1024))
                        var read = 0
                        while (read < body.size) {
                            val n = input.read(body, read, body.size - read)
                            if (n < 0) break
                            read += n
                        }
                        val form = String(body, 0, read)
                        val url = form.split('&')
                            .firstOrNull { it.startsWith("url=") }
                            ?.let { URLDecoder.decode(it.substring(4), "UTF-8") }
                            .orEmpty()
                        val message = if (url.isBlank()) "Paste something first." else onSubmit(url)
                        respond(client, 200, "text/html; charset=utf-8", page(message))
                    }
                    else -> respond(client, 405, "text/plain", "Method not allowed")
                }
            } catch (_: Exception) {
                // A dropped phone connection just ends that request.
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("UTF-8")
            if (b == '\n'.code) return line.toString("UTF-8").trimEnd('\r')
            line.write(b)
            if (line.size() > 8192) return null
        }
    }

    private fun respond(client: Socket, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val reason = mapOf(200 to "OK", 404 to "Not Found", 405 to "Method Not Allowed")[status]
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun page(message: String?): String {
        val note = message?.let {
            "<p class=\"note\">${it.replace("&", "&amp;").replace("<", "&lt;")}</p>"
        }.orEmpty()
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Orbit</title><style>
body{margin:0;padding:24px 18px;background:#0c1630;color:#fff;font:17px system-ui,sans-serif}
h1{letter-spacing:.3em;font-size:26px;margin:0 0 4px}p{color:#c3cadc;line-height:1.45}
textarea{box-sizing:border-box;width:100%;min-height:120px;padding:12px;border-radius:12px;
border:2px solid #2d3755;background:#141a2c;color:#fff;font:15px ui-monospace,monospace}
button{margin-top:14px;width:100%;padding:15px;border:0;border-radius:12px;background:#5a7dff;
color:#fff;font-weight:700;font-size:17px}.note{background:#1b2a55;border-radius:12px;padding:12px;color:#fff}
</style></head><body><h1>ORBIT</h1><p>${escape(heading)}</p>
$note<form method="post"><textarea name="url" placeholder="${escape(placeholder)}" autofocus></textarea>
<button type="submit">${escape(button)}</button></form></body></html>"""
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    // The stick's address on the home network (Wi-Fi or Ethernet).
    private fun localIpAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
}
