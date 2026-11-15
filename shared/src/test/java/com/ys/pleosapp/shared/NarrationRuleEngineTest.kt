package com.ys.pleosapp.shared

import com.ys.pleosapp.shared.drive.DriveEvent
import com.ys.pleosapp.shared.narration.NarrationRuleEngine
import com.ys.pleosapp.shared.narration.NarrationRulesXml
import com.ys.pleosapp.shared.narration.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Mirrors `shared/src/main/res/raw/narration_rules.xml`: if the shipped file
 * changes, update [RULES_XML] here too so the contract stays pinned.
 */
class NarrationRuleEngineTest {
    private lateinit var engine: NarrationRuleEngine

    @Before
    fun setUp() {
        engine = NarrationRuleEngine(
            NarrationRulesXml.parse(ByteArrayInputStream(RULES_XML.toByteArray()))
        )
    }

    @Test
    fun suddenLaneChangeCallsTheMove() {
        val narration = engine.pick(listOf(DriveEvent.SUDDEN_LANE_CHANGE), 62f, 1, 0L)
        assertEquals("OH! A sudden lane change! What a MOVE!", narration?.text)
        assertEquals(Severity.FUN, narration?.severity)
    }

    @Test
    fun cooldownSuppressesImmediateRepeat() {
        engine.pick(listOf(DriveEvent.SUDDEN_LANE_CHANGE), 62f, 1, 0L)
        assertNull(engine.pick(listOf(DriveEvent.SUDDEN_LANE_CHANGE), 62f, 2, 1_000L))
    }

    @Test
    fun linesRoundRobinAfterCooldown() {
        engine.pick(listOf(DriveEvent.SUDDEN_LANE_CHANGE), 62f, 1, 0L)
        val second = engine.pick(listOf(DriveEvent.SUDDEN_LANE_CHANGE), 62f, 1, 9_000L)
        assertEquals("Unbelievable! They thread the needle between lanes!", second?.text)
    }

    @Test
    fun repeatedSharpMovesEscalateToWarningFirst() {
        // Both rules match; the WARNING escalation is first in file order.
        val narration = engine.pick(
            listOf(DriveEvent.SUDDEN_LANE_CHANGE, DriveEvent.REPEATED_SHARP_MOVES),
            62f, 3, 0L
        )
        assertEquals(Severity.WARNING, narration?.severity)
        assertEquals(
            "Whoa, that's one sharp move too many! Let's calm down, champ.",
            narration?.text
        )
    }

    @Test
    fun repeatCountGatesEscalation() {
        // Only 2 sharp moves: the escalation rule must not fire.
        val narration = engine.pick(
            listOf(DriveEvent.SUDDEN_LANE_CHANGE, DriveEvent.REPEATED_SHARP_MOVES),
            62f, 2, 0L
        )
        assertEquals("sharp_lane_change", narration?.ruleId)
    }

    @Test
    fun tripStartBeatsLaunchOffTheLine() {
        // Pulling away fires TRIP_START and RAPID_ACCEL together;
        // the green-light opener wins because it is earlier in the file.
        val narration = engine.pick(
            listOf(DriveEvent.TRIP_START, DriveEvent.RAPID_ACCEL), 22f, 0, 0L
        )
        assertEquals("trip_start", narration?.ruleId)
        assertEquals("And we're GREEN GREEN GREEN! The drive is underway!", narration?.text)
    }

    @Test
    fun speedWindowIsRespected() {
        // Lane-change call needs >= 30 km/h.
        assertNull(engine.pick(listOf(DriveEvent.SUDDEN_LANE_CHANGE), 20f, 1, 0L))
    }

    companion object {
        // Keep in sync with res/raw/narration_rules.xml.
        const val RULES_XML = """<narration-rules>
  <rule id="too_much" event="REPEATED_SHARP_MOVES" minRepeatCount="3" cooldownMs="30000" severity="WARNING">
    <line>Whoa, that's one sharp move too many! Let's calm down, champ.</line>
    <line>Okay, that is starting to look dangerous. Smooth it out, please!</line>
  </rule>
  <rule id="trip_start" event="TRIP_START" cooldownMs="60000" severity="FUN">
    <line>And we're GREEN GREEN GREEN! The drive is underway!</line>
  </rule>
  <rule id="sharp_lane_change" event="SUDDEN_LANE_CHANGE" minSpeedKmh="30" cooldownMs="8000" severity="FUN">
    <line>OH! A sudden lane change! What a MOVE!</line>
    <line>Unbelievable! They thread the needle between lanes!</line>
    <line>And the crowd goes wild — another daring lane change!</line>
  </rule>
  <rule id="hard_brake" event="HARD_BRAKE" cooldownMs="15000" severity="WARNING">
    <line>Ouch! That was a HARD brake! Keep a safe distance, driver!</line>
  </rule>
  <rule id="speeding" event="SPEEDING" minSpeedKmh="100" cooldownMs="30000" severity="WARNING">
    <line>Speeding past the limit! Even race cars respect the pit lane speed!</line>
  </rule>
  <rule id="rapid_accel" event="RAPID_ACCEL" cooldownMs="12000" severity="FUN">
    <line>And they're OFF! What a launch off the line!</line>
  </rule>
  <rule id="nav_turn_ahead" event="NAV_TURN_AHEAD" cooldownMs="20000" severity="INFO">
    <line>Heads up, a turn is coming up ahead. Set yourself up nicely.</line>
  </rule>
  <rule id="smooth_cruise" event="SMOOTH_CRUISE" minSpeedKmh="40" maxSpeedKmh="90" cooldownMs="45000" severity="INFO">
    <line>Textbook cruising — smooth, steady, beautiful to watch.</line>
  </rule>
  <rule id="arriving" event="NAV_ARRIVING" cooldownMs="60000" severity="FUN">
    <line>And across the line! What a finish! Welcome to your destination!</line>
  </rule>
</narration-rules>"""
    }
}
