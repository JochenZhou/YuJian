package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabThemeTest {

    @Test
    fun mainTabTitlesFollowXianxiaAndModernThemes() {
        assertEquals("灵台总览", Theme.XIUXIAN.mainTabTitle(MainTab.OVERVIEW))
        assertEquals("演法调阵", Theme.XIUXIAN.mainTabTitle(MainTab.PRACTICE))
        assertEquals("符钥天机", Theme.XIUXIAN.mainTabTitle(MainTab.SYSTEM))

        assertEquals("总览与互联", Theme.DEFAULT.mainTabTitle(MainTab.OVERVIEW))
        assertEquals("演练与调控", Theme.DEFAULT.mainTabTitle(MainTab.PRACTICE))
        assertEquals("系统与运维", Theme.DEFAULT.mainTabTitle(MainTab.SYSTEM))
    }

    @Test
    fun sectionTitlesAreNonEmptyAcrossAllThemes() {
        for (theme in Theme.entries) {
            for (tab in MainTab.entries) {
                assertTrue(theme.mainTabTitle(tab).isNotBlank())
            }
            assertTrue(theme.quickActionsTitle.isNotBlank())
            assertTrue(theme.practiceCardTitle.isNotBlank())
            assertTrue(theme.practiceCardSubtitle.isNotBlank())
            assertTrue(theme.practiceCardHint.isNotBlank())
            assertTrue(theme.controlCardTitle.isNotBlank())
            assertTrue(theme.controlCardSubtitle.isNotBlank())
            assertTrue(theme.controlCardHint.isNotBlank())
            assertTrue(theme.systemCardTitle.isNotBlank())
            assertTrue(theme.systemCardSubtitle.isNotBlank())
            assertTrue(theme.keepAliveCardTitle.isNotBlank())
        }
    }
}
