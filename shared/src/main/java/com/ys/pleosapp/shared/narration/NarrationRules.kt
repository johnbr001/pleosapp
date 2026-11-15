package com.ys.pleosapp.shared.narration

import com.ys.pleosapp.shared.drive.DriveEvent

/** Comic delivery with a safety lane: warnings always win the wording. */
enum class Severity { FUN, INFO, WARNING }

/**
 * One deterministic commentary rule. The first rule in file order whose
 * conditions all hold wins, so developers reorder/re-tune behavior by
 * editing `res/xml/narration_rules.xml` only.
 */
data class NarrationRule(
    val id: String,
    val event: DriveEvent,
    val minSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = Float.MAX_VALUE,
    val minRepeatCount: Int = 0,
    val cooldownMs: Long = 8_000L,
    val severity: Severity = Severity.INFO,
    val lines: List<String> = emptyList()
)

/** A fired commentary line ready to display and speak. */
data class Narration(
    val text: String,
    val severity: Severity,
    val ruleId: String
)

/**
 * Deterministic picker over [rules]: first match wins, per-rule cooldowns
 * stop spam, and each rule round-robins its own lines so repeats vary.
 */
class NarrationRuleEngine(rules: List<NarrationRule>) {
    private val rules: List<NarrationRule> = rules.filter { it.lines.isNotEmpty() }
    private val lastFiredMs = mutableMapOf<String, Long>()
    private val lineIndex = mutableMapOf<String, Int>()

    fun pick(
        events: List<DriveEvent>,
        speedKmh: Float,
        repeatCount: Int,
        nowMs: Long
    ): Narration? {
        if (events.isEmpty()) return null
        for (rule in rules) {
            if (rule.event !in events) continue
            if (speedKmh < rule.minSpeedKmh || speedKmh > rule.maxSpeedKmh) continue
            if (repeatCount < rule.minRepeatCount) continue
            val last = lastFiredMs[rule.id]
            if (last != null && nowMs - last < rule.cooldownMs) continue
            val index = (lineIndex[rule.id] ?: 0) % rule.lines.size
            lineIndex[rule.id] = index + 1
            lastFiredMs[rule.id] = nowMs
            return Narration(rule.lines[index], rule.severity, rule.id)
        }
        return null
    }

    fun reset() {
        lastFiredMs.clear()
        lineIndex.clear()
    }
}
