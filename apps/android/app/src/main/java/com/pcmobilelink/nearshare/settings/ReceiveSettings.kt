package com.pcmobilelink.nearshare.settings

sealed class ReceiveFolder {
    abstract val displayName: String

    data object DefaultDownloads : ReceiveFolder() {
        override val displayName: String = "Downloads"
    }

    class CustomTree(
        val uri: String,
        displayName: String,
    ) : ReceiveFolder() {
        private val rawDisplayName = displayName

        override val displayName: String = rawDisplayName.trim().ifBlank { "Selected folder" }

        override fun equals(other: Any?): Boolean {
            return other is CustomTree
                && uri == other.uri
                && rawDisplayName == other.rawDisplayName
        }

        override fun hashCode(): Int {
            var result = uri.hashCode()
            result = 31 * result + rawDisplayName.hashCode()
            return result
        }

        override fun toString(): String {
            return "CustomTree(uri=$uri, displayName=$rawDisplayName)"
        }
    }
}

data class ReceiveSettings(
    val receiveFolder: ReceiveFolder = ReceiveFolder.DefaultDownloads,
    val alwaysOnReceiveEnabled: Boolean = false,
    val transferSoundsEnabled: Boolean = true,
) {
    fun bootRestoreAction(): BootRestoreAction {
        return if (alwaysOnReceiveEnabled) BootRestoreAction.StartReceiver else BootRestoreAction.DoNothing
    }

    companion object {
        fun defaultSettings(): ReceiveSettings = ReceiveSettings()
    }
}

enum class BootRestoreAction(val userMessage: String) {
    DoNothing("NearShare receiving is off."),
    StartReceiver("NearShare will try to resume Always On receiving after Android finishes booting."),
    ShowResumeNotification("Android may require user action after restart. Tap the notification to resume NearShare receiving."),
}
