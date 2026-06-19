package com.pcmobilelink.nearshare.ui

enum class MainNavigationSection(val label: String) {
    Dashboard("Dashboard"),
    Transfer("Transfer"),
    Settings("Settings"),
    ;

    companion object {
        fun defaultSection(): MainNavigationSection = Dashboard

        fun bottomNavigationOrder(): List<MainNavigationSection> = listOf(Dashboard, Transfer, Settings)
    }
}
