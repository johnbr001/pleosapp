package com.ys.pleosapp.shared.drive

import kotlin.math.abs

/**
 * Derives [DriveEvent]s from a stream of [DriveSnapshot]s with fixed
 * thresholds, so commentary decisions are fully deterministic.
 *
 * All tuning knobs are constructor parameters; the defaults below are the
 * app's shipped behavior.
 */
class DriveEventDetector(
    private val laneChangeBearingDeg: Float = 25f,
    private val laneChangeWindowMs: Long = 3_000L,
    private val laneChangeMinSpeedKmh: Float = 30f,
    private val sharpRepeatCount: Int = 3,
    private val sharpRepeatWindowMs: Long = 60_000L,
    private val hardBrakeDropKmh: Float = 25f,
    private val hardBrakeWindowMs: Long = 2_000L,
    private val rapidAccelGainKmh: Float = 20f,
    private val rapidAccelWindowMs: Long = 3_000L,
    private val speedingKmh: Float = 100f,
    private val cruiseMinKmh: Float = 40f,
    private val cruiseMaxKmh: Float = 90f,
    private val cruiseSteadyKmh: Float = 3f,
    private val cruiseHoldMs: Long = 20_000L,
    private val turnAheadM: Int = 300
) {
    private var prev: DriveSnapshot? = null
    private var tripStarted = false
    private var speedingActive = false
    private var turnAheadActive = false
    private var arrivedNotified = false
    private var cruiseSinceMs: Long? = null
    private var cruiseNotified = false
    private val sharpMoveTimes = ArrayDeque<Long>()

    /** Consecutive sharp moves inside [sharpRepeatWindowMs]; drives the "calm down" escalation. */
    var repeatedSharpCount: Int = 0
        private set

    fun update(snapshot: DriveSnapshot): List<DriveEvent> {
        val events = mutableListOf<DriveEvent>()
        val previous = prev
        prev = snapshot

        if (!tripStarted && snapshot.speedKmh > 5f) {
            tripStarted = true
            events += DriveEvent.TRIP_START
        }

        if (previous != null) {
            val dtMs = snapshot.timestampMs - previous.timestampMs
            if (dtMs > 0) {
                val dSpeed = snapshot.speedKmh - previous.speedKmh
                if (dSpeed <= -hardBrakeDropKmh && dtMs <= hardBrakeWindowMs) {
                    events += DriveEvent.HARD_BRAKE
                }
                if (dSpeed >= rapidAccelGainKmh && dtMs <= rapidAccelWindowMs) {
                    events += DriveEvent.RAPID_ACCEL
                }
                val b0 = previous.bearingDeg
                val b1 = snapshot.bearingDeg
                if (b0 != null && b1 != null &&
                    snapshot.speedKmh >= laneChangeMinSpeedKmh &&
                    dtMs <= laneChangeWindowMs &&
                    abs(angleDiffDeg(b0, b1)) >= laneChangeBearingDeg
                ) {
                    events += DriveEvent.SUDDEN_LANE_CHANGE
                    sharpMoveTimes.addLast(snapshot.timestampMs)
                }
            }
        }

        sharpMoveTimes.removeAll { snapshot.timestampMs - it > sharpRepeatWindowMs }
        repeatedSharpCount = sharpMoveTimes.size
        if (repeatedSharpCount >= sharpRepeatCount &&
            DriveEvent.SUDDEN_LANE_CHANGE in events
        ) {
            events += DriveEvent.REPEATED_SHARP_MOVES
        }

        if (snapshot.speedKmh >= speedingKmh) {
            if (!speedingActive) {
                speedingActive = true
                events += DriveEvent.SPEEDING
            }
        } else if (snapshot.speedKmh < speedingKmh - 5f) {
            speedingActive = false
        }

        val turnClose = snapshot.nav.guidanceActive &&
            snapshot.nav.distanceToTurnM != null &&
            snapshot.nav.distanceToTurnM <= turnAheadM
        if (turnClose && !turnAheadActive) events += DriveEvent.NAV_TURN_AHEAD
        turnAheadActive = turnClose

        if (snapshot.nav.arrived && !arrivedNotified) {
            arrivedNotified = true
            events += DriveEvent.NAV_ARRIVING
        }
        if (!snapshot.nav.arrived) arrivedNotified = false

        val cruising = snapshot.speedKmh in cruiseMinKmh..cruiseMaxKmh &&
            (previous == null || abs(snapshot.speedKmh - previous.speedKmh) <= cruiseSteadyKmh)
        if (cruising) {
            val since = cruiseSinceMs ?: snapshot.timestampMs.also { cruiseSinceMs = it }
            if (!cruiseNotified && snapshot.timestampMs - since >= cruiseHoldMs) {
                cruiseNotified = true
                events += DriveEvent.SMOOTH_CRUISE
            }
        } else {
            cruiseSinceMs = null
            cruiseNotified = false
        }

        return events
    }

    fun reset() {
        prev = null
        tripStarted = false
        speedingActive = false
        turnAheadActive = false
        arrivedNotified = false
        cruiseSinceMs = null
        cruiseNotified = false
        sharpMoveTimes.clear()
        repeatedSharpCount = 0
    }

    companion object {
        fun angleDiffDeg(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}
