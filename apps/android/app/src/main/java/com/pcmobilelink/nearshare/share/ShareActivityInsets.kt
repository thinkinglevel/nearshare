package com.pcmobilelink.nearshare.share

object ShareActivityInsets {
    data class Padding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    fun contentPadding(base: Padding, systemBars: Padding): Padding {
        return Padding(
            left = base.left + systemBars.left,
            top = base.top + systemBars.top,
            right = base.right + systemBars.right,
            bottom = base.bottom + systemBars.bottom,
        )
    }
}
