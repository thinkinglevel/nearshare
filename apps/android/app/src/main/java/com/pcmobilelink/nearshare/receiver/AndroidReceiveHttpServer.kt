package com.pcmobilelink.nearshare.receiver

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class AndroidReceiveHttpServer(
    private val certificate: AndroidReceiveCertificate,
    private val requestHandler: (ReceiveHttpRequest) -> ReceiveHttpResponse,
    private val listenAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val listenPort: Int = 0,
    private val diagnostics: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var serverSocket: SSLServerSocket? = null

    constructor(
        certificate: AndroidReceiveCertificate,
        sessionManager: AndroidReceiveSessionManager,
        listenAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
        listenPort: Int = 0,
        diagnostics: (String) -> Unit = {},
    ) : this(
        certificate = certificate,
        requestHandler = { request -> sessionManager.handle(request) },
        listenAddress = listenAddress,
        listenPort = listenPort,
        diagnostics = diagnostics,
    )

    fun start(): AndroidReceiveEndpointMetadata {
        check(running.compareAndSet(false, true)) { "NearShare receiver is already running." }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(certificate.keyManagers, null, null)
        val socket = sslContext.serverSocketFactory.createServerSocket(listenPort, 50, listenAddress) as SSLServerSocket
        socket.enabledProtocols = socket.enabledProtocols.filter { protocol -> protocol != "SSLv3" }.toTypedArray()
        serverSocket = socket
        Log.i(TAG, "Android receive HTTPS server bound to ${listenAddress.hostAddress}:${socket.localPort}")
        executor.execute { acceptLoop(socket) }
        val advertisedHost = preferredLocalIpv4Address()
        return AndroidReceiveEndpointMetadata(
            host = advertisedHost,
            port = socket.localPort,
            tlsCertificateSha256 = certificate.tlsCertificateSha256,
        )
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept() as SSLSocket
                Log.i(TAG, "Android receive HTTPS client accepted from ${client.inetAddress.hostAddress}:${client.port}")
                executor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (running.get()) {
                    Log.w(TAG, "Android receive HTTPS socket closed unexpectedly")
                    AndroidReceiveEndpointRegistry.markStopped()
                }
                return
            } catch (exception: Exception) {
                Log.w(TAG, "Android receive HTTPS accept failed", exception)
                if (!running.get()) return
            }
        }
    }

    private fun handleClient(client: SSLSocket) {
        client.use { socket ->
            try {
                socket.useClientMode = false
                socket.startHandshake()
                val request = readRequest(socket)
                val response = requestHandler(request)
                writeResponse(socket, response)
            } catch (exception: Exception) {
                Log.w(TAG, "Android receive HTTPS client handling failed", exception)
                writeResponse(
                    socket,
                    ReceiveHttpResponse(
                        statusCode = 400,
                        bodyText = "{\"status\":\"bad_request\",\"message\":\"${escapeJson(exception.message ?: "Invalid request")}\"}",
                    ),
                )
            }
        }
    }

    private fun readRequest(socket: SSLSocket): ReceiveHttpRequest {
        val input = BufferedInputStream(socket.inputStream)
        val headerBytes = readHeaderBlock(input)
        val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw EOFException("Missing HTTP request line.")
        val parts = requestLine.split(' ', limit = 3)
        require(parts.size >= 2) { "Invalid HTTP request line." }
        val method = parts[0].uppercase(Locale.US)
        val pathAndQuery = parts[1]
        val headers = linkedMapOf<String, String>()
        lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
            }
        }
        val length = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value
            ?.toIntOrNull()
            ?: 0
        require(length >= 0) { "Content-Length cannot be negative." }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) throw EOFException("Request body ended before Content-Length bytes were read.")
            offset += read
        }
        return ReceiveHttpRequest(method, pathAndQuery, headers, body)
    }

    private fun readHeaderBlock(input: BufferedInputStream): ByteArray {
        val bytes = ArrayList<Byte>(1024)
        var previous3 = -1
        var previous2 = -1
        var previous1 = -1
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("Connection closed before HTTP headers completed.")
            bytes.add(value.toByte())
            if (previous3 == '\r'.code && previous2 == '\n'.code && previous1 == '\r'.code && value == '\n'.code) {
                return bytes.toByteArray()
            }
            require(bytes.size <= MAX_HEADER_BYTES) { "HTTP headers are too large." }
            previous3 = previous2
            previous2 = previous1
            previous1 = value
        }
    }

    private fun writeResponse(socket: SSLSocket, response: ReceiveHttpResponse) {
        val output = BufferedOutputStream(socket.outputStream)
        val body = response.bodyText.toByteArray(StandardCharsets.UTF_8)
        val reason = when (response.statusCode) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val headers = "HTTP/1.1 ${response.statusCode} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
        AndroidReceiveEndpointRegistry.markStopped()
    }

    private fun preferredLocalIpv4Address(): String {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { address -> scoreAddressCandidate(networkInterface, address) }
                }
                .sortedWith(
                    compareByDescending<AddressCandidate> { it.score }
                        .thenBy { it.interfaceName }
                        .thenBy { it.host },
                )

            val selected = candidates.firstOrNull()
            val message = if (selected == null) {
                "Android receive advertised IPv4 selected=127.0.0.1 candidates=<none>"
            } else {
                "Android receive advertised IPv4 selected=${selected.host} candidates=" +
                    candidates.joinToString("; ") { candidate -> candidate.describe() }
            }
            Log.i(TAG, message)
            diagnostics(message)
            selected?.host ?: "127.0.0.1"
        } catch (exception: Exception) {
            val message = "Android receive advertised IPv4 selection failed: ${exception.message ?: exception.javaClass.simpleName}"
            Log.w(TAG, message, exception)
            diagnostics(message)
            "127.0.0.1"
        }
    }

    private fun scoreAddressCandidate(networkInterface: NetworkInterface, address: Inet4Address): AddressCandidate? {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) {
            return null
        }

        val name = networkInterface.name.orEmpty()
        val normalizedName = name.lowercase(Locale.US)
        val reasons = mutableListOf<String>()
        var score = 0

        if (isPrivateIpv4(address)) {
            score += 80
            reasons += "private"
        } else {
            score -= 30
            reasons += "non-private"
        }

        if (isWifiOrHotspotInterface(normalizedName)) {
            score += 60
            reasons += "wifi"
        }

        if (isCellularInterface(normalizedName)) {
            score -= 100
            reasons += "cellular"
        }

        if (runCatching { networkInterface.isPointToPoint }.getOrDefault(false)) {
            score -= 20
            reasons += "point-to-point"
        }

        return AddressCandidate(
            interfaceName = name.ifBlank { "<unknown>" },
            host = address.hostAddress ?: return null,
            score = score,
            reasons = reasons,
        )
    }

    private fun isPrivateIpv4(address: Inet4Address): Boolean {
        val octets = address.address.map { value -> value.toInt() and 0xff }
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun isWifiOrHotspotInterface(name: String): Boolean {
        return name.startsWith("wlan") ||
            name.startsWith("swlan") ||
            name.startsWith("ap") ||
            name.startsWith("softap") ||
            name.startsWith("p2p")
    }

    private fun isCellularInterface(name: String): Boolean {
        return name.startsWith("rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("pdp") ||
            name.startsWith("wwan") ||
            name.contains("cell")
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private companion object {
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val TAG = "NearShare"
    }
}

private data class AddressCandidate(
    val interfaceName: String,
    val host: String,
    val score: Int,
    val reasons: List<String>,
) {
    fun describe(): String = "$interfaceName/$host score=$score ${reasons.joinToString("+")}"
}

data class AndroidReceiveEndpointMetadata(
    val host: String,
    val port: Int,
    val tlsCertificateSha256: String,
)

object AndroidReceiveEndpointRegistry {
    @Volatile
    private var current: AndroidReceiveEndpointMetadata? = null

    fun markRunning(endpoint: AndroidReceiveEndpointMetadata) {
        current = endpoint
    }

    fun markStopped() {
        current = null
    }

    fun currentEndpoint(): AndroidReceiveEndpointMetadata? = current
}
