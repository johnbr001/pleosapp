package com.ys.pleosapp.shared.drive

/** Discrete driving moments the commentary engine can narrate. */
enum class DriveEvent {
    TRIP_START,
    SMOOTH_CRUISE,
    SUDDEN_LANE_CHANGE,
    REPEATED_SHARP_MOVES,
    HARD_BRAKE,
    RAPID_ACCEL,
    SPEEDING,
    NAV_TURN_AHEAD,
    NAV_ARRIVING
}
