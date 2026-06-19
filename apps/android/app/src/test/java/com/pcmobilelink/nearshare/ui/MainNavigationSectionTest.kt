package com.pcmobilelink.nearshare.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationSectionTest {
    @Test
    fun bottomNavigationOrderStartsWithDashboardAndEndsWithSettings() {
        assertEquals(
            listOf(
                MainNavigationSection.Dashboard,
                MainNavigationSection.Transfer,
                MainNavigationSection.Settings,
            ),
            MainNavigationSection.bottomNavigationOrder(),
        )
    }

    @Test
    fun defaultSectionIsDashboard() {
        assertEquals(MainNavigationSection.Dashboard, MainNavigationSection.defaultSection())
    }

    @Test
    fun bottomNavigationLabelsAreUserFacing() {
        assertEquals("Dashboard", MainNavigationSection.Dashboard.label)
        assertEquals("Transfer", MainNavigationSection.Transfer.label)
        assertEquals("Settings", MainNavigationSection.Settings.label)
    }
}
