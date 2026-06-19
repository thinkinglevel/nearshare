package com.pcmobilelink.nearshare.ui

import android.graphics.Typeface

object AppTypeface {
    val regular: Typeface by lazy {
        loadSystemTypeface("/system/fonts/DroidSans.ttf")
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
    }

    val bold: Typeface by lazy {
        loadSystemTypeface("/system/fonts/DroidSans-Bold.ttf")
            ?: Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun getTypeface(family: String, style: Int): Typeface {
        return if (style == Typeface.BOLD || family.contains("medium", ignoreCase = true)) {
            bold
        } else {
            regular
        }
    }

    private fun loadSystemTypeface(path: String): Typeface? {
        return try {
            Typeface.createFromFile(path)
        } catch (e: Exception) {
            null
        }
    }
}
