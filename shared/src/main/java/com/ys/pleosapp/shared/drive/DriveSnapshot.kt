package com.ys.pleosapp.shared.drive

/**
 * Navigation status as reported by the Pleos NaviHelper SDK
 * (`NaviHelperEventListener`: `onRouteStarted`, `onDestinationArrived`, ...).
 */
data class NavState(
    val guidanceActive: Boolean = false,
    val distanceToTurnM: Int? = null,
    val arrived: Boolean = false
)

/**
 * One fused sample of everything the commentary engine reacts to:
 * driving movement (speed/bearing), car status and navigation status.
 */
data class DriveSnapshot(
    val timestampMs: Long,
    val speedKmh: Float,
    val bearingDeg: Float? = null,
    val doorsClosed: Boolean? = null,
    val nav: NavState = NavState()
)
