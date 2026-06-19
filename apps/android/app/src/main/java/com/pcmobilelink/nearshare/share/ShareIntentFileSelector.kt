package com.pcmobilelink.nearshare.share

import android.content.Intent
import android.net.Uri
import android.os.Build

object ShareIntentFileSelector {
    fun acceptedFileCount(action: String?, streamCount: Int): Int {
        if (streamCount <= 0) {
            return 0
        }

        return when (action) {
            Intent.ACTION_SEND -> 1
            Intent.ACTION_SEND_MULTIPLE -> streamCount
            else -> 0
        }
    }

    fun selectedFileUris(intent: Intent?): List<Uri> {
        if (intent == null) {
            return emptyList()
        }

        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(singleStream(intent))
            Intent.ACTION_SEND_MULTIPLE -> multipleStreams(intent)
            else -> emptyList()
        }
        val acceptedCount = acceptedFileCount(intent.action, streams.size)
        return streams.take(acceptedCount)
    }

    fun selectedSingleFileUri(intent: Intent?): Uri? {
        return selectedFileUris(intent).singleOrNull()
    }

    private fun singleStream(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun multipleStreams(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}
