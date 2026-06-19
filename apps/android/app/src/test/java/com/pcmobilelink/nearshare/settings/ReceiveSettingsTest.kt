package com.pcmobilelink.nearshare.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveSettingsTest {
    @Test
    fun defaultSettingsUseDownloadsAndDisableAlwaysOn() {
        val settings = ReceiveSettings.defaultSettings()

        assertEquals(ReceiveFolder.DefaultDownloads, settings.receiveFolder)
        assertEquals("Downloads", settings.receiveFolder.displayName)
        assertFalse(settings.alwaysOnReceiveEnabled)
        assertTrue(settings.transferSoundsEnabled)
    }

    @Test
    fun customFolderUsesProvidedDisplayNameWhenPresent() {
        val folder = ReceiveFolder.CustomTree(
            uri = "content://com.android.externalstorage.documents/tree/primary%3ANearShare",
            displayName = "NearShare",
        )

        assertEquals("NearShare", folder.displayName)
    }

    @Test
    fun customFolderFallsBackToFolderWhenDisplayNameIsBlank() {
        val folder = ReceiveFolder.CustomTree(
            uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            displayName = "   ",
        )

        assertEquals("Selected folder", folder.displayName)
    }

    @Test
    fun bootRestoreIsRequestedOnlyWhenAlwaysOnIsEnabled() {
        assertEquals(BootRestoreAction.StartReceiver, ReceiveSettings(alwaysOnReceiveEnabled = true).bootRestoreAction())
        assertEquals(BootRestoreAction.DoNothing, ReceiveSettings(alwaysOnReceiveEnabled = false).bootRestoreAction())
    }

    @Test
    fun bootFallbackMessageIsHonestAboutAndroidRestrictions() {
        val message = BootRestoreAction.ShowResumeNotification.userMessage

        assertTrue(message.contains("Tap"))
        assertTrue(message.contains("resume"))
        assertTrue(message.contains("Android"))
    }
}
