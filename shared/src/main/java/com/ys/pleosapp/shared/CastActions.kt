package com.ys.pleosapp.shared

/**
 * Body actions for the cast template. `MessageTemplate` allows at most two
 * body actions (`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`) and throws on
 * the third `addAction`, which kills the app on the head unit — so the
 * selection below is the single place that decides, and it never returns
 * more than two.
 */
enum class CastAction {
    GRANT,
    MUTE,
    REPLAY,
    DEMO
}

fun selectActions(hasPermissions: Boolean, hasReplay: Boolean): List<CastAction> {
    if (!hasPermissions) return listOf(CastAction.GRANT, CastAction.DEMO)
    if (hasReplay) return listOf(CastAction.MUTE, CastAction.REPLAY)
    return listOf(CastAction.MUTE, CastAction.DEMO)
}

/**
 * Minimum interval between template refreshes. The host kills the app after
 * more than 5 templates in one flow
 * (`OverLimitFlowViolationException`), so per-tick updates (speed ticker,
 * demo-lap feeds ~1.4 s apart) must be coalesced: a refresh is only allowed
 * when visible content changed AND this long has passed since the last one.
 */
const val TEMPLATE_MIN_REFRESH_MS = 5000L

/**
 * Max auto refreshes per host flow (plus the free first render). The 5s
 * interval above only bounds the rate: a speed ticker plus a demo lap can
 * still spend the host's whole 5-template budget with no user interaction
 * in between, so the count is capped too. User gestures start a new host
 * flow; the screen resets this budget on each one.
 */
const val MAX_AUTO_TEMPLATE_REFRESHES = 3

fun shouldRefreshTemplate(
    lastHeadline: String?,
    lastStatus: String?,
    lastMuted: Boolean,
    lastHasReplay: Boolean,
    lastHasPermissions: Boolean,
    newHeadline: String,
    newStatus: String,
    newMuted: Boolean,
    newHasReplay: Boolean,
    newHasPermissions: Boolean,
    elapsedMs: Long,
    autoRefreshesUsed: Int
): Boolean {
    // Nothing rendered yet: the first template must always go out.
    if (lastHeadline == null || lastStatus == null) return true
    if (autoRefreshesUsed >= MAX_AUTO_TEMPLATE_REFRESHES) return false
    val changed = newHeadline != lastHeadline ||
        newStatus != lastStatus ||
        newMuted != lastMuted ||
        newHasReplay != lastHasReplay ||
        newHasPermissions != lastHasPermissions
    if (!changed) return false
    return elapsedMs >= TEMPLATE_MIN_REFRESH_MS
}
