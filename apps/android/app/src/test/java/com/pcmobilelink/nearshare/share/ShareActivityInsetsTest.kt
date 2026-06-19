package com.pcmobilelink.nearshare.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareActivityInsetsTest {
    @Test
    fun contentPaddingAddsEverySystemBarInsetToBasePadding() {
        val padding = ShareActivityInsets.contentPadding(
            base = ShareActivityInsets.Padding(left = 24, top = 32, right = 24, bottom = 32),
            systemBars = ShareActivityInsets.Padding(left = 7, top = 44, right = 9, bottom = 28),
        )

        assertEquals(31, padding.left)
        assertEquals(76, padding.top)
        assertEquals(33, padding.right)
        assertEquals(60, padding.bottom)
    }
}
