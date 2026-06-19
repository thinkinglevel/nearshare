package com.pcmobilelink.nearshare.share

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.pcmobilelink.nearshare.R
import com.pcmobilelink.nearshare.storage.PairedPcRecord

object PairedPcShareTargets {
    const val CATEGORY_PAIRED_PC = "com.pcmobilelink.nearshare.category.PAIRED_PC_SHARE_TARGET"

    fun publish(context: Context, records: List<PairedPcRecord>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }

        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = records.take(8).map { record ->
            ShortcutInfo.Builder(context, shortcutId(record.pcDeviceId))
                .setShortLabel(record.pcName.take(24).ifBlank { "Paired device" })
                .setLongLabel("Send to ${record.pcName}")
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setCategories(setOf(CATEGORY_PAIRED_PC))
                .setIntent(
                    Intent(context, ShareActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .setType("*/*")
                        .putExtra(ShareActivity.EXTRA_TARGET_PC_DEVICE_ID, record.pcDeviceId),
                )
                .build()
        }
        manager.dynamicShortcuts = shortcuts
    }

    fun shortcutId(pcDeviceId: String): String {
        return "paired-pc-${pcDeviceId.lowercase()}"
    }
}
