package com.ys.pleosapp.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastActionsTest {
    @Test
    fun neverMoreThanTwoActions() {
        for (perms in listOf(false, true)) {
            for (replay in listOf(false, true)) {
                assertTrue(selectActions(perms, replay).size <= 2)
            }
        }
    }

    @Test
    fun missingPermissionsOffersGrantAndDemo() {
        assertEquals(
            listOf(CastAction.GRANT, CastAction.DEMO),
            selectActions(hasPermissions = false, hasReplay = false)
        )
    }

    @Test
    fun replayReplacesDemoOnceThereIsACall() {
        assertEquals(
            listOf(CastAction.MUTE, CastAction.DEMO),
            selectActions(hasPermissions = true, hasReplay = false)
        )
        assertEquals(
            listOf(CastAction.MUTE, CastAction.REPLAY),
            selectActions(hasPermissions = true, hasReplay = true)
        )
    }

    @Test
    fun firstRenderIsAlwaysAllowed() {
        assertTrue(
            shouldRefreshTemplate(
                lastHeadline = null,
                lastStatus = null,
                lastMuted = false,
                lastHasReplay = false,
                lastHasPermissions = false,
                newHeadline = "hello",
                newStatus = "world",
                newMuted = false,
                newHasReplay = false,
                newHasPermissions = true,
                elapsedMs = 0L,
                autoRefreshesUsed = 0
            )
        )
    }

    @Test
    fun firstRenderIgnoresSpentBudget() {
        assertTrue(
            shouldRefreshTemplate(
                lastHeadline = null,
                lastStatus = null,
                lastMuted = false,
                lastHasReplay = false,
                lastHasPermissions = false,
                newHeadline = "hello",
                newStatus = "world",
                newMuted = false,
                newHasReplay = false,
                newHasPermissions = true,
                elapsedMs = 0L,
                autoRefreshesUsed = MAX_AUTO_TEMPLATE_REFRESHES + 5
            )
        )
    }

    @Test
    fun unchangedContentNeverRefreshes() {
        assertFalse(
            shouldRefreshTemplate(
                lastHeadline = "same",
                lastStatus = "same",
                lastMuted = false,
                lastHasReplay = true,
                lastHasPermissions = true,
                newHeadline = "same",
                newStatus = "same",
                newMuted = false,
                newHasReplay = true,
                newHasPermissions = true,
                elapsedMs = TEMPLATE_MIN_REFRESH_MS * 10,
                autoRefreshesUsed = 0
            )
        )
    }

    @Test
    fun changedContentIsThrottledUntilIntervalPasses() {
        assertFalse(
            shouldRefreshTemplate(
                lastHeadline = "old",
                lastStatus = "old",
                lastMuted = false,
                lastHasReplay = false,
                lastHasPermissions = true,
                newHeadline = "OH! A sudden lane change!",
                newStatus = "new",
                newMuted = false,
                newHasReplay = true,
                newHasPermissions = true,
                elapsedMs = 0L,
                autoRefreshesUsed = 0
            )
        )
        assertFalse(
            shouldRefreshTemplate(
                lastHeadline = "old",
                lastStatus = "old",
                lastMuted = false,
                lastHasReplay = false,
                lastHasPermissions = true,
                newHeadline = "OH! A sudden lane change!",
                newStatus = "new",
                newMuted = false,
                newHasReplay = true,
                newHasPermissions = true,
                elapsedMs = TEMPLATE_MIN_REFRESH_MS - 1,
                autoRefreshesUsed = 0
            )
        )
        assertTrue(
            shouldRefreshTemplate(
                lastHeadline = "old",
                lastStatus = "old",
                lastMuted = false,
                lastHasReplay = false,
                lastHasPermissions = true,
                newHeadline = "OH! A sudden lane change!",
                newStatus = "new",
                newMuted = false,
                newHasReplay = true,
                newHasPermissions = true,
                elapsedMs = TEMPLATE_MIN_REFRESH_MS,
                autoRefreshesUsed = 0
            )
        )
    }

    @Test
    fun spentBudgetBlocksChangedContent() {
        assertFalse(
            shouldRefreshTemplate(
                lastHeadline = "old",
                lastStatus = "old",
                lastMuted = false,
                lastHasReplay = false,
                lastHasPermissions = true,
                newHeadline = "OH! A sudden lane change!",
                newStatus = "new",
                newMuted = false,
                newHasReplay = true,
                newHasPermissions = true,
                elapsedMs = TEMPLATE_MIN_REFRESH_MS * 10,
                autoRefreshesUsed = MAX_AUTO_TEMPLATE_REFRESHES
            )
        )
    }

    @Test
    fun lastBudgetedRefreshIsStillAllowed() {
        assertTrue(
            shouldRefreshTemplate(
                lastHeadline = "old",
                lastStatus = "old",
                lastMuted = false,
                lastHasReplay = false,
                lastHasPermissions = true,
                newHeadline = "OH! A sudden lane change!",
                newStatus = "new",
                newMuted = false,
                newHasReplay = true,
                newHasPermissions = true,
                elapsedMs = TEMPLATE_MIN_REFRESH_MS,
                autoRefreshesUsed = MAX_AUTO_TEMPLATE_REFRESHES - 1
            )
        )
    }
}
