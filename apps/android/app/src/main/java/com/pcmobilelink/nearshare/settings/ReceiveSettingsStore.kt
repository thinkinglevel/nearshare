package com.pcmobilelink.nearshare.settings

import android.content.Context

class ReceiveSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("nearshare_receive_settings", Context.MODE_PRIVATE)

    fun load(): ReceiveSettings {
        val folderUri = preferences.getString(KEY_RECEIVE_FOLDER_URI, null)?.takeIf { it.isNotBlank() }
        val folderName = preferences.getString(KEY_RECEIVE_FOLDER_NAME, null).orEmpty()
        return ReceiveSettings(
            receiveFolder = if (folderUri == null) {
                ReceiveFolder.DefaultDownloads
            } else {
                ReceiveFolder.CustomTree(uri = folderUri, displayName = folderName)
            },
            alwaysOnReceiveEnabled = preferences.getBoolean(KEY_ALWAYS_ON_RECEIVE_ENABLED, false),
            transferSoundsEnabled = preferences.getBoolean(KEY_TRANSFER_SOUNDS_ENABLED, true),
        )
    }

    fun save(settings: ReceiveSettings) {
        val editor = preferences.edit()
            .putBoolean(KEY_ALWAYS_ON_RECEIVE_ENABLED, settings.alwaysOnReceiveEnabled)
            .putBoolean(KEY_TRANSFER_SOUNDS_ENABLED, settings.transferSoundsEnabled)
        when (val folder = settings.receiveFolder) {
            ReceiveFolder.DefaultDownloads -> editor
                .remove(KEY_RECEIVE_FOLDER_URI)
                .remove(KEY_RECEIVE_FOLDER_NAME)
            is ReceiveFolder.CustomTree -> editor
                .putString(KEY_RECEIVE_FOLDER_URI, folder.uri)
                .putString(KEY_RECEIVE_FOLDER_NAME, folder.displayName)
        }
        editor.apply()
    }

    fun setAlwaysOnReceiveEnabled(enabled: Boolean) {
        save(load().copy(alwaysOnReceiveEnabled = enabled))
    }

    fun setReceiveFolder(folder: ReceiveFolder) {
        save(load().copy(receiveFolder = folder))
    }

    fun setTransferSoundsEnabled(enabled: Boolean) {
        save(load().copy(transferSoundsEnabled = enabled))
    }

    private companion object {
        private const val KEY_RECEIVE_FOLDER_URI = "receiveFolderUri"
        private const val KEY_RECEIVE_FOLDER_NAME = "receiveFolderName"
        private const val KEY_ALWAYS_ON_RECEIVE_ENABLED = "alwaysOnReceiveEnabled"
        private const val KEY_TRANSFER_SOUNDS_ENABLED = "transferSoundsEnabled"
    }
}
