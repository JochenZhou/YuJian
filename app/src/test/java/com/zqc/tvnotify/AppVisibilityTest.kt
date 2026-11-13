package com.zqc.tvnotify

import org.junit.Assert.*
import org.junit.Test

class AppVisibilityTest {
    @Test fun `service-only process stays hidden until an activity is visible`() {
        val changes = mutableListOf<Boolean>()
        val state = AppVisibility { changes += it }
        assertFalse(state.visible)
        assertTrue(changes.isEmpty())
        val activity = Any()
        state.started(activity)
        state.stopped(activity, false)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `internal navigation does not suspend output`() {
        val changes = mutableListOf<Boolean>()
        val state = AppVisibility { changes += it }
        val main = Any()
        val player = Any()
        state.started(main)
        state.started(player)
        state.stopped(main, false)
        assertEquals(listOf(true), changes)
        assertTrue(state.visible)
        state.stopped(player, false)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `configuration replacement has no intermediate background event`() {
        val changes = mutableListOf<Boolean>()
        val state = AppVisibility { changes += it }
        val old = Any()
        val replacement = Any()
        state.started(old)
        state.stopped(old, true)
        state.started(replacement)
        assertEquals(listOf(true), changes)
        state.stopped(replacement, false)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `duplicate or unknown callbacks do not unbalance visibility`() {
        val changes = mutableListOf<Boolean>()
        val state = AppVisibility { changes += it }
        val activity = Any()
        state.started(activity)
        state.started(activity)
        state.stopped(Any(), false)
        assertTrue(state.visible)
        state.stopped(activity, false)
        state.stopped(activity, false)
        assertEquals(listOf(true, false), changes)
    }
}
