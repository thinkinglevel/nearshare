package com.pcmobilelink.nearshare.pairing

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingErrorMessageTest {
    @Test
    fun timeoutMessageExplainsFirewallAndNetworkReachability() {
        val message = PairingErrorMessage.from(
            SocketTimeoutException("failed to connect to /192.168.29.58 (port 50371) from /192.168.29.214 after 5000ms"),
        )

        assertEquals(
            "Could not reach the Windows PC. Make sure both devices are on the same Wi-Fi or hotspot, NearShare is open on Windows, and Windows Firewall allows NearShare on this network.",
            message,
        )
    }

    @Test
    fun unknownErrorKeepsConciseMessage() {
        val message = PairingErrorMessage.from(IllegalStateException("Bad pairing token"))

        assertEquals("Bad pairing token", message)
    }
}
