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
) : Closeable {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var serverSocket: SSLServerSocket? = null

    constructor(
        certificate: AndroidReceiveCertificate,
        sessionManager: AndroidReceiveSessionManager,
        listenAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
        listenPort: Int = 0,
    ) : this(
        certificate = certificate,
        requestHandler = { request -> sessionManager.handle(request) },
        listenAddress = listenAddress,
        listenPort = listenPort,
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
        return AndroidReceiveEndpointMetadata(
            host = preferredLocalIpv4Address(),
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
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { networkInterface -> networkInterface.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
                ?.hostAddress
                ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private companion object {
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val TAG = "NearShare"
    }
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
