package com.pcmobilelink.nearshare.storage

import android.content.Context
import java.util.UUID

class AndroidDeviceIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("nearshare_device_identity", Context.MODE_PRIVATE)

    fun devicePublicKey(): String {
        val existing = preferences.getString(KEY_DEVICE_PUBLIC_KEY, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val created = "nearshare-android-device:${UUID.randomUUID()}"
        preferences.edit().putString(KEY_DEVICE_PUBLIC_KEY, created).apply()
        return created
    }

    private companion object {
        private const val KEY_DEVICE_PUBLIC_KEY = "devicePublicKey"
    }
}
