package io.opentripper.core

import java.util.Locale
import kotlin.math.roundToInt

data class MapsNotification(val packageName: String, val key: String, val ongoing: Boolean, val lines: List<String>)
data class MapsManeuver(val maneuver: Maneuver, val meters: Int)

/** Experimental English text patterns only. Google Maps exposes no stable notification contract. */
object MapsParser {
    const val PACKAGE = "com.google.android.apps.maps"
    private val distance = Regex("^(?:in\\s+)?([0-9]+(?:\\.[0-9]+)?)\\s*(km|m|mi|ft)(?=$|[\\s,·])", RegexOption.IGNORE_CASE)
    private val rules = listOf(
        "(?:make a |make |take a )?u[- ]turn" to Maneuver.U_TURN,
        "(?:turn |take a )?sharp left" to Maneuver.SHARP_LEFT,
        "(?:turn |take a )?sharp right" to Maneuver.SHARP_RIGHT,
        "(?:turn |bear )?slight(?:ly)? left" to Maneuver.SLIGHT_LEFT,
        "(?:turn |bear )?slight(?:ly)? right" to Maneuver.SLIGHT_RIGHT,
        "keep left" to Maneuver.KEEP_LEFT, "keep right" to Maneuver.KEEP_RIGHT,
        "turn left" to Maneuver.LEFT, "turn right" to Maneuver.RIGHT,
        "(?:continue straight|head straight|go straight)" to Maneuver.STRAIGHT
    ).map { (pattern, maneuver) -> Regex("^(?:$pattern)(?=$|[ ,.:])", RegexOption.IGNORE_CASE) to maneuver }

    fun parse(notification: MapsNotification): MapsManeuver? {
        if (notification.packageName != PACKAGE || !notification.ongoing) return null
        val lines = notification.lines.map { it.trim().replace('\u00a0', ' ') }.filter { it.isNotEmpty() }.distinct()
        for (line in lines) {
            val match = distance.find(line) ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "km" -> 1000.0; "mi" -> 1609.344; "ft" -> 0.3048; else -> 1.0
            }
            val meters = value * multiplier
            if (!meters.isFinite() || meters !in 0.0..6_553_500.0) continue
            val suffix = line.substring(match.range.last + 1).trimStart(' ', ',', '·', ':', '–', '-')
            // Separate instruction lines are allowed only when the distance field contains nothing else.
            val candidates = if (suffix.isEmpty()) lines else listOf(suffix)
            for (instruction in candidates) {
                val maneuver = rules.firstOrNull { it.first.containsMatchIn(instruction) }?.second ?: continue
                return MapsManeuver(maneuver, meters.roundToInt())
            }
        }
        return null
    }
}

/** Tracks one active navigation notification. Unrelated Maps notifications cannot clear it. */
class MapsRoute {
    var key: String? = null; private set
    var value: MapsManeuver? = null; private set
    var expiresAt: Long = 0; private set
    fun post(notification: MapsNotification, now: Long) {
        if (notification.packageName != MapsParser.PACKAGE) return
        val parsed = MapsParser.parse(notification)
        if (parsed != null) { key = notification.key; value = parsed; expiresAt = now + 15_000 }
        else if (notification.key == key) clear()
    }
    fun remove(removedKey: String) { if (key == removedKey) clear() }
    fun current(now: Long): MapsManeuver? {
        if (now >= expiresAt) clear()
        return value
    }
    fun clear() { key = null; value = null; expiresAt = 0 }
}
