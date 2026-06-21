package com.pcmobilelink.nearshare.pairing

import android.util.Log
import com.pcmobilelink.nearshare.discovery.NearShareDiscoveryProtocol
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class AndroidPairingOfferDiscoveryResponder(
    private val shortCode: String,
    private val deviceName: String,
    private val pairingUri: String,
    private val listenAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val listenPort: Int = NearShareDiscoveryProtocol.DISCOVERY_PORT,
    private val diagnostics: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Pairing offer discovery is already running." }
        val datagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(listenAddress, listenPort))
        }
        socket = datagramSocket
        val message = "Pairing offer discovery bound to ${listenAddress.hostAddress}:$listenPort code=${maskCode(shortCode)}"
        Log.i(TAG, message)
        diagnostics(message)
        executor.execute { receiveLoop(datagramSocket) }
    }

    override fun close() {
        running.set(false)
        socket?.close()
        socket = null
        executor.shutdownNow()
    }

    private fun receiveLoop(datagramSocket: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                datagramSocket.receive(packet)
                val response = responseBytes(
                    requestBytes = packet.data.copyOf(packet.length),
                    remoteHost = packet.address.hostAddress ?: "unknown",
                ) ?: continue
                datagramSocket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: SocketException) {
                if (running.get()) {
                    diagnostics("Pairing offer discovery socket closed unexpectedly.")
                    running.set(false)
                }
                return
            } catch (exception: Exception) {
                Log.w(TAG, "Pairing offer discovery loop failed", exception)
                diagnostics("Pairing offer discovery loop failed: ${exception.message ?: exception.javaClass.simpleName}")
                if (!running.get()) return
            }
        }
    }

    private fun responseBytes(requestBytes: ByteArray, remoteHost: String): ByteArray? {
        return runCatching {
            val request = JSONObject(requestBytes.toString(Charsets.UTF_8))
            if (request.optString("type") != REQUEST_TYPE) {
                return null
            }

            val requestedCode = PairingShortCode.normalize(request.optString("shortCode"))
            val activeCode = PairingShortCode.normalize(shortCode)
            if (requestedCode != activeCode) {
                diagnostics(
                    "Pairing offer discovery ignored request from $remoteHost code=${maskCode(requestedCode)} expected=${maskCode(activeCode)}",
                )
                return null
            }

            diagnostics("Pairing offer discovery matched request from $remoteHost code=${maskCode(activeCode)}")
            JSONObject()
                .put("type", RESPONSE_TYPE)
                .put("shortCode", activeCode)
                .put("deviceName", deviceName)
                .put("pairingUri", pairingUri)
                .put("serverTimeUnixSeconds", System.currentTimeMillis() / 1000L)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }.onFailure { exception ->
            Log.w(TAG, "Could not parse pairing offer discovery request", exception)
            diagnostics("Could not parse pairing offer discovery request: ${exception.message ?: exception.javaClass.simpleName}")
        }.getOrNull()
    }

    companion object {
        const val REQUEST_TYPE = "nearshare.pairing.discovery.request.v1"
        const val RESPONSE_TYPE = "nearshare.pairing.discovery.response.v1"
        private const val MAX_DATAGRAM_BYTES = 8192
        private const val TAG = "NearShare"

        private fun maskCode(code: String): String {
            val normalized = PairingShortCode.normalize(code)
            if (normalized.isBlank()) {
                return "<blank>"
            }
            return normalized.take(3).padEnd(normalized.length.coerceAtLeast(3), '*')
        }
    }
}
