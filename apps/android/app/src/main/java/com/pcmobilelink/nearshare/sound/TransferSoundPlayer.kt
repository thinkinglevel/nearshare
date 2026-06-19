package com.pcmobilelink.nearshare.sound

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.pcmobilelink.nearshare.settings.ReceiveSettingsStore

enum class TransferSoundResult {
    Success,
    Failure,
}

class TransferSoundPlayer(private val context: Context) {
    fun play(result: TransferSoundResult) {
        if (!ReceiveSettingsStore(context.applicationContext).load().transferSoundsEnabled) {
            return
        }

        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT)
            tone.startTone(toneType(result), TONE_DURATION_MILLIS)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, TONE_DURATION_MILLIS + RELEASE_DELAY_MILLIS)
        }
    }

    private fun toneType(result: TransferSoundResult): Int {
        return when (result) {
            TransferSoundResult.Success -> ToneGenerator.TONE_PROP_ACK
            TransferSoundResult.Failure -> ToneGenerator.TONE_PROP_NACK
        }
    }

    private companion object {
        private const val VOLUME_PERCENT = 70
        private const val TONE_DURATION_MILLIS = 180
        private const val RELEASE_DELAY_MILLIS = 80L
    }
}
