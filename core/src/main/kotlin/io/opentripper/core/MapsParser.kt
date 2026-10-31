package io.opentripper.core

import java.util.Locale
import kotlin.math.roundToInt

data class MapsNotification(val packageName: String, val key: String, val ongoing: Boolean, val lines: List<String>)
data class MapsManeuver(val maneuver: Maneuver, val meters: Int, val roundaboutExit: Int? = null) {
    fun cue(side: DrivingSide) = ManeuverCue(maneuver, roundaboutExit, side)
}

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
        "(?:keep|bear) left at (?:the |a )?fork" to Maneuver.FORK_LEFT,
        "(?:keep|bear) right at (?:the |a )?fork" to Maneuver.FORK_RIGHT,
        "(?:take (?:the )?)?(?:left fork|fork left)" to Maneuver.FORK_LEFT,
        "(?:take (?:the )?)?(?:right fork|fork right)" to Maneuver.FORK_RIGHT,
        "(?:take (?:the )?)?(?:exit|off[- ]ramp) (?:on (?:the )?)?left" to Maneuver.OFF_RAMP_LEFT,
        "(?:take (?:the )?)?(?:exit|off[- ]ramp) (?:on (?:the )?)?right" to Maneuver.OFF_RAMP_RIGHT,
        "(?:take (?:the )?)?(?:ramp|on[- ]ramp) (?:on (?:the )?)?left" to Maneuver.ON_RAMP_LEFT,
        "(?:take (?:the )?)?(?:ramp|on[- ]ramp) (?:on (?:the )?)?right" to Maneuver.ON_RAMP_RIGHT,
        "merge left" to Maneuver.MERGE_LEFT, "merge right" to Maneuver.MERGE_RIGHT,
        "keep left" to Maneuver.KEEP_LEFT, "keep right" to Maneuver.KEEP_RIGHT,
        "turn left" to Maneuver.LEFT, "turn right" to Maneuver.RIGHT,
        "(?:continue straight|head straight|go straight)" to Maneuver.STRAIGHT
    ).map { (pattern, maneuver) -> Regex("^(?:$pattern)(?=$|[ ,.:])", RegexOption.IGNORE_CASE) to maneuver }

    private val exitNames = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
    private val exitPattern = Regex("\\b(?:take|use) (?:the )?(1st|2nd|3rd|[4-9]th|${exitNames.joinToString("|")}) exit\\b", RegexOption.IGNORE_CASE)
    private val roundaboutWord = Regex("\\b(?:roundabout|rotary)\\b", RegexOption.IGNORE_CASE)
    private fun roundaboutExit(instruction: String): Int? {
        val lower = instruction.lowercase(Locale.ROOT)
        val roundabout = Regex("^(?:(?:at|enter) (?:the |a )?roundabout\\b|(?:take|use) (?:the )?.+ exit (?:at|on) (?:the |a )?roundabout\\b)")
        if (!roundabout.containsMatchIn(lower)) return null
        val matches = exitPattern.findAll(lower).toList()
        if (matches.size != 1) return null
        val value = matches.single().groupValues[1]
        return value.first().digitToIntOrNull() ?: (exitNames.indexOf(value) + 1).takeIf { it > 0 }
    }

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
            val roundabouts = candidates.filter { roundaboutWord.containsMatchIn(it) }
            if (roundabouts.isNotEmpty()) {
                val exits = roundabouts.mapNotNull(::roundaboutExit).distinct()
                if (exits.size == 1) return MapsManeuver(Maneuver.ROUNDABOUT, meters.roundToInt(), exits.single())
                // A generic title must not turn an unclear roundabout into an ordinary left/right arrow.
                continue
            }
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
    enum class Status { WAITING, RECOGNIZED, UNSUPPORTED, EXPIRED, REMOVED }
    var status = Status.WAITING; private set
    var key: String? = null; private set
    var value: MapsManeuver? = null; private set
    var expiresAt: Long = 0; private set
    fun post(notification: MapsNotification, now: Long) {
        if (notification.packageName != MapsParser.PACKAGE) return
        val parsed = MapsParser.parse(notification)
        if (parsed != null) {
            key = notification.key; value = parsed; expiresAt = now + 15_000; status = Status.RECOGNIZED
        } else if (notification.key == key || (key == null && notification.ongoing)) {
            clear(); key = notification.key; status = Status.UNSUPPORTED
        }
    }
    fun remove(removedKey: String) { if (key == removedKey) { clear(); status = Status.REMOVED } }
    fun current(now: Long): MapsManeuver? {
        if (value != null && now >= expiresAt) { clear(); status = Status.EXPIRED }
        return value
    }
    fun clear() { key = null; value = null; expiresAt = 0; status = Status.WAITING }
}
