package com.ys.pleosapp.shared

import com.ys.pleosapp.shared.drive.DriveEvent
import com.ys.pleosapp.shared.drive.DriveEventDetector
import com.ys.pleosapp.shared.drive.DriveSnapshot
import com.ys.pleosapp.shared.drive.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriveEventDetectorTest {
    private lateinit var detector: DriveEventDetector

    @Before
    fun setUp() {
        detector = DriveEventDetector()
    }

    private fun snap(
        t: Long,
        speed: Float,
        bearing: Float? = 90f,
        nav: NavState = NavState()
    ) = DriveSnapshot(t, speed, bearing, true, nav)

    @Test
    fun tripStartFiresOnce() {
        // Parked: no trip start yet; the first moving sample starts the trip.
        assertTrue(DriveEvent.TRIP_START !in detector.update(snap(0L, 0f)))
        assertTrue(DriveEvent.TRIP_START in detector.update(snap(1_000L, 20f)))
        val again = detector.update(snap(2_000L, 25f))
        assertTrue(DriveEvent.TRIP_START !in again)
    }

    @Test
    fun suddenLaneChangeNeedsBearingJumpAndSpeed() {
        detector.update(snap(0L, 60f, 90f))
        val events = detector.update(snap(1_000L, 62f, 122f))
        assertTrue(DriveEvent.SUDDEN_LANE_CHANGE in events)
    }

    @Test
    fun slowSwerveIsNotALaneChange() {
        detector.update(snap(0L, 20f, 90f))
        val events = detector.update(snap(1_000L, 20f, 140f))
        assertTrue(DriveEvent.SUDDEN_LANE_CHANGE !in events)
    }

    @Test
    fun threeSharpMovesEscalate() {
        detector.update(snap(0L, 60f, 90f))
        detector.update(snap(1_000L, 60f, 120f))
        detector.update(snap(2_000L, 60f, 150f))
        val events = detector.update(snap(3_000L, 60f, 180f))
        assertTrue(DriveEvent.SUDDEN_LANE_CHANGE in events)
        assertTrue(DriveEvent.REPEATED_SHARP_MOVES in events)
        assertEquals(3, detector.repeatedSharpCount)
    }

    @Test
    fun hardBrakeOnBigFastDrop() {
        detector.update(snap(0L, 65f))
        val events = detector.update(snap(1_000L, 35f))
        assertTrue(DriveEvent.HARD_BRAKE in events)
    }

    @Test
    fun speedingFiresOnceUntilReset() {
        detector.update(snap(0L, 90f))
        assertTrue(DriveEvent.SPEEDING in detector.update(snap(1_000L, 105f)))
        assertTrue(DriveEvent.SPEEDING !in detector.update(snap(2_000L, 107f)))
        detector.update(snap(3_000L, 80f)) // drop out of the band resets the edge
        assertTrue(DriveEvent.SPEEDING in detector.update(snap(4_000L, 101f)))
    }

    @Test
    fun navTurnAheadFiresOnApproach() {
        detector.update(snap(0L, 50f, nav = NavState(guidanceActive = true, distanceToTurnM = 500)))
        val events = detector.update(
            snap(1_000L, 50f, nav = NavState(guidanceActive = true, distanceToTurnM = 250))
        )
        assertTrue(DriveEvent.NAV_TURN_AHEAD in events)
    }

    @Test
    fun resetClearsState() {
        detector.update(snap(0L, 60f, 90f))
        detector.update(snap(1_000L, 60f, 120f))
        detector.reset()
        assertEquals(0, detector.repeatedSharpCount)
        assertTrue(DriveEvent.TRIP_START in detector.update(snap(2_000L, 20f)))
    }
}
