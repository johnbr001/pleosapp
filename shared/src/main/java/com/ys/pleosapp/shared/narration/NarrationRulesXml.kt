package com.ys.pleosapp.shared.narration

import com.ys.pleosapp.shared.drive.DriveEvent
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses the developer-editable rule set (`res/raw/narration_rules.xml`).
 *
 * Uses `javax.xml`, so the exact same code runs on Android and in local
 * JVM unit tests.
 *
 * ```xml
 * <narration-rules>
 *   <rule id="sharp_lane_change" event="SUDDEN_LANE_CHANGE"
 *         cooldownMs="8000" severity="FUN" minSpeedKmh="30">
 *     <line>OH! A sudden lane change! What a MOVE!</line>
 *   </rule>
 * </narration-rules>
 * ```
 *
 * The car app loads `res/raw/narration_rules.xml` through this parser, so
 * the file on disk is the single source of truth.
 */
object NarrationRulesXml {
    fun parse(input: InputStream): List<NarrationRule> =
        input.use {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(it)
            val nodes = doc.getElementsByTagName("rule")
            List(nodes.length) { i ->
                val el = nodes.item(i) as Element
                val lineNodes = el.getElementsByTagName("line")
                NarrationRule(
                    id = el.attr("id", "rule_$i"),
                    event = DriveEvent.valueOf(el.attr("event", "SMOOTH_CRUISE")),
                    minSpeedKmh = el.attr("minSpeedKmh", "0").toFloat(),
                    maxSpeedKmh = el.attr("maxSpeedKmh", Float.MAX_VALUE.toString()).toFloat(),
                    minRepeatCount = el.attr("minRepeatCount", "0").toInt(),
                    cooldownMs = el.attr("cooldownMs", "8000").toLong(),
                    severity = Severity.valueOf(el.attr("severity", "INFO")),
                    lines = List(lineNodes.length) { j ->
                        lineNodes.item(j).textContent.trim()
                    }.filter { line -> line.isNotEmpty() }
                )
            }
        }

    private fun Element.attr(name: String, default: String): String {
        val value = getAttribute(name)
        return if (value.isNullOrBlank()) default else value
    }
}
