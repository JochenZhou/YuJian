package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabThemeTest {

    @Test
    fun mainTabTitlesFollowXianxiaAndModernThemes() {
        assertEquals("玉简首页", Theme.XIUXIAN.mainTabTitle(MainTab.OVERVIEW))
        assertEquals("传音与法相", Theme.XIUXIAN.mainTabTitle(MainTab.PRACTICE))
        assertEquals("互联与天机", Theme.XIUXIAN.mainTabTitle(MainTab.SYSTEM))

        assertEquals("首页", Theme.DEFAULT.mainTabTitle(MainTab.OVERVIEW))
        assertEquals("通知与外观", Theme.DEFAULT.mainTabTitle(MainTab.PRACTICE))
        assertEquals("互联与系统", Theme.DEFAULT.mainTabTitle(MainTab.SYSTEM))
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

    @Test
    fun minecraftThemeParsesAliasesAndUsesBlockyBadgeCopy() {
        assertEquals(9, Theme.entries.size)
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("minecraft"))
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("mc"))
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("pixel"))
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("block"))
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("minecraft_pixel"))
        assertEquals("minecraft", Theme.MINECRAFT.storageId)
        assertEquals(0f, Theme.MINECRAFT.bannerCornerRadiusDp)
        assertFalse(Theme.MINECRAFT.isXianxia)
        assertFalse(Theme.MINECRAFT.isLightBanner)
        assertEquals("[成就]", Theme.MINECRAFT.levelBadgeText(BannerLevel.INFO))
        assertEquals("[苦力怕!]", Theme.MINECRAFT.levelBadgeText(BannerLevel.WARN))
        assertEquals("[下界警报]", Theme.MINECRAFT.levelBadgeText(BannerLevel.ERROR))
        assertTrue(Theme.MINECRAFT.appTitle.contains("CRAFT"))
        assertTrue(Theme.MINECRAFT.testBannerText.isNotBlank())
        assertTrue(Theme.MINECRAFT.controlCardSubtitle.contains("9"))
        assertTrue(Theme.XIUXIAN.controlCardSubtitle.contains("9"))
    }
}
