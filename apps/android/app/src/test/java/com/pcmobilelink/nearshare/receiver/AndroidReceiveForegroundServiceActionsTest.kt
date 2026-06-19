package com.pcmobilelink.nearshare.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidReceiveForegroundServiceActionsTest {
    @Test
    fun manualReceiveActionIsStableForNotificationAndUiWiring() {
        assertEquals(
            "com.pcmobilelink.nearshare.receiver.START_MANUAL_RECEIVE",
            AndroidReceiveForegroundService.ACTION_START_MANUAL_RECEIVE,
        )
    }

    @Test
    fun alwaysOnActionIsStableForNotificationAndUiWiring() {
        assertEquals(
            "com.pcmobilelink.nearshare.receiver.START_ALWAYS_ON_RECEIVE",
            AndroidReceiveForegroundService.ACTION_START_ALWAYS_ON_RECEIVE,
        )
    }

    @Test
    fun stopActionIsStableForNotificationAndUiWiring() {
        assertEquals(
            "com.pcmobilelink.nearshare.receiver.STOP_RECEIVE",
            AndroidReceiveForegroundService.ACTION_STOP_RECEIVE,
        )
    }
}
