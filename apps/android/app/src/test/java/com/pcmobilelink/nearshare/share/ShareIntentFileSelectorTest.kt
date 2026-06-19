package com.pcmobilelink.nearshare.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntentFileSelectorTest {
    @Test
    fun acceptedFileCountReturnsOneForActionSendWithOneStream() {
        assertEquals(1, ShareIntentFileSelector.acceptedFileCount(Intent.ACTION_SEND, streamCount = 1))
    }

    @Test
    fun acceptedFileCountReturnsAllStreamsForActionSendMultiple() {
        assertEquals(3, ShareIntentFileSelector.acceptedFileCount(Intent.ACTION_SEND_MULTIPLE, streamCount = 3))
    }

    @Test
    fun acceptedFileCountRejectsMissingStreams() {
        assertEquals(0, ShareIntentFileSelector.acceptedFileCount(Intent.ACTION_SEND, streamCount = 0))
        assertEquals(0, ShareIntentFileSelector.acceptedFileCount(Intent.ACTION_SEND_MULTIPLE, streamCount = 0))
    }

    @Test
    fun acceptedFileCountRejectsUnsupportedActions() {
        assertEquals(0, ShareIntentFileSelector.acceptedFileCount(Intent.ACTION_VIEW, streamCount = 1))
    }
}
