package com.pcmobilelink.nearshare.receiver

import android.content.Context
import android.util.Log
import com.pcmobilelink.nearshare.discovery.NearShareDiscoveryProtocol
import com.pcmobilelink.nearshare.diagnostics.NearShareDiagnostics
import com.pcmobilelink.nearshare.storage.PairedPcStore
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class AndroidReceiveDiscoveryResponder(
    context: Context,
    private val pairedPcStore: PairedPcStore,
    private val endpoint: AndroidReceiveEndpointMetadata,
    private val listenAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val listenPort: Int = NearShareDiscoveryProtocol.DISCOVERY_PORT,
) : Closeable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Android receive discovery is already running." }
        val datagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(listenAddress, listenPort))
        }
        socket = datagramSocket
        Log.i(TAG, "Android receive discovery responder bound to ${listenAddress.hostAddress}:$listenPort for endpoint ${endpoint.host}:${endpoint.port}")
        NearShareDiagnostics.info(appContext, "Receive discovery bound port=$listenPort advertised=${endpoint.host}:${endpoint.port}")
        executor.execute { receiveLoop(datagramSocket) }
    }

    override fun close() {
        Log.i(TAG, "Android receive discovery responder closing")
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
                Log.i(TAG, "Android receive discovery request received from ${packet.address.hostAddress}:${packet.port} bytes=${packet.length}")
                NearShareDiagnostics.info(appContext, "Receive discovery request from ${packet.address.hostAddress}:${packet.port}")
                val response = responseBytes(packet.data.copyOf(packet.length), packet.address) ?: continue
                datagramSocket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                Log.i(TAG, "Android receive discovery response sent to ${packet.address.hostAddress}:${packet.port} bytes=${response.size}")
            } catch (_: SocketException) {
                if (running.get()) {
                    Log.w(TAG, "Android receive discovery socket closed unexpectedly")
                    running.set(false)
                }
                return
            } catch (exception: Exception) {
                Log.w(TAG, "Android receive discovery loop failed", exception)
                if (!running.get()) return
            }
        }
    }

    private fun responseBytes(requestBytes: ByteArray, requesterAddress: InetAddress): ByteArray? {
        return runCatching {
            val request = JSONObject(requestBytes.toString(Charsets.UTF_8))
            if (request.optString("type") != REQUEST_TYPE) {
                Log.i(TAG, "Ignoring discovery request with unexpected type=${request.optString("type")}")
                return null
            }

            val pairedDeviceId = request.optString("pairedDeviceId")
            if (pairedDeviceId.isBlank()) {
                Log.w(TAG, "Ignoring discovery request without pairedDeviceId")
                return null
            }

            val pairedPc = pairedPcStore.loadAll().firstOrNull { record ->
                record.pcDeviceId.equals(pairedDeviceId, ignoreCase = true)
            } ?: run {
                Log.w(TAG, "Ignoring discovery request for unknown pairedDeviceId=$pairedDeviceId")
                return null
            }

            val responseHost = endpointHostForRequester(requesterAddress)
            Log.i(TAG, "Preparing discovery response for pairedDeviceId=${pairedPc.pcDeviceId} endpoint=$responseHost:${endpoint.port}")
            NearShareDiagnostics.info(
                appContext,
                "Receive discovery response requester=${requesterAddress.hostAddress} host=$responseHost port=${endpoint.port}",
            )
            JSONObject()
                .put("type", RESPONSE_TYPE)
                .put("pairedDeviceId", pairedPc.pcDeviceId)
                .put("deviceName", android.os.Build.MODEL.ifBlank { "Android device" })
                .put("receiveTlsCertificateSha256", endpoint.tlsCertificateSha256)
                .put(
                    "endpoint",
                    JSONObject()
                        .put("host", responseHost)
                        .put("port", endpoint.port),
                )
                .put("serverTimeUnixSeconds", System.currentTimeMillis() / 1000L)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }.onFailure { exception ->
            Log.w(TAG, "Could not parse Android receive discovery request", exception)
        }.getOrNull()
    }

    private fun endpointHostForRequester(requesterAddress: InetAddress): String {
        if (requesterAddress !is Inet4Address) {
            return endpoint.host
        }

        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
                .flatMap { networkInterface -> networkInterface.interfaceAddresses.toList() }
                .firstNotNullOfOrNull { interfaceAddress ->
                    val localAddress = interfaceAddress.address as? Inet4Address ?: return@firstNotNullOfOrNull null
                    if (localAddress.isLoopbackAddress || localAddress.isLinkLocalAddress) {
                        return@firstNotNullOfOrNull null
                    }
                    if (isSameIpv4Subnet(localAddress, requesterAddress, interfaceAddress.networkPrefixLength.toInt())) {
                        localAddress.hostAddress
                    } else {
                        null
                    }
                }
        }.getOrNull() ?: endpoint.host
    }

    private fun isSameIpv4Subnet(localAddress: Inet4Address, remoteAddress: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 0..32) {
            return false
        }
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (localAddress.toInt() and mask) == (remoteAddress.toInt() and mask)
    }

    private fun Inet4Address.toInt(): Int {
        return address.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) }
    }

    companion object {
        const val REQUEST_TYPE = "nearshare.android-receive.discovery.request.v1"
        const val RESPONSE_TYPE = "nearshare.android-receive.discovery.response.v1"
        private const val MAX_DATAGRAM_BYTES = 8192
        private const val TAG = "NearShare"
    }
}
