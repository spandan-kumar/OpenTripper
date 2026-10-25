package io.opentripper.core

enum class DrivingSide(val label: String) { LEFT("Left-hand traffic"), RIGHT("Right-hand traffic") }

/** A semantic instruction, not an arbitrary raw icon number. Never invent a roundabout exit. */
data class ManeuverCue(
    val maneuver: Maneuver,
    val exit: Int? = null,
    val drivingSide: DrivingSide = DrivingSide.LEFT
) {
    init {
        require(if (maneuver == Maneuver.ROUNDABOUT) exit != null && exit in 1..9 else exit == null) {
            "Roundabouts need an explicit exit from 1 to 9; other maneuvers cannot have an exit"
        }
    }
    val label get() = if (maneuver == Maneuver.ROUNDABOUT) "Roundabout · exit $exit" else maneuver.label
    val experimental get() = maneuver == Maneuver.ROUNDABOUT
    val key get() = "${drivingSide.name}:${maneuver.name}:${exit ?: 0}"

    fun icon(small: Boolean = false): Int = when (maneuver) {
        Maneuver.ROUNDABOUT -> (if (drivingSide == DrivingSide.LEFT) 0x0b else 0x32) + (exit!! - 1)
        Maneuver.U_TURN -> if (drivingSide == DrivingSide.LEFT) 0x1a else 0x3d
        Maneuver.KEEP_LEFT -> if (small) 0x2b else maneuver.icon
        Maneuver.KEEP_RIGHT -> if (small) 0x2c else maneuver.icon
        Maneuver.OFF_RAMP_LEFT -> if (small) 0x2b else maneuver.icon
        Maneuver.OFF_RAMP_RIGHT -> if (small) 0x2c else maneuver.icon
        else -> maneuver.icon
    }
}

/** A bounded, explicitly requested stationary screen test. Expiry cannot resume Maps forwarding. */
data class PodProbe(
    val cue: ManeuverCue,
    val meters: Int,
    val next: ManeuverCue? = null,
    val night: Boolean = false,
    val approachEmphasis: Boolean = false
) {
    init { require(meters in 0..6_553_500) }
    val experimental get() = cue.experimental || next?.experimental == true || approachEmphasis
    val key get() = "${cue.key}|${next?.key ?: "none"}|$meters|$night|$approachEmphasis"
    fun packet() = TripperProtocol.navigation(cue, meters, next, night, approachEmphasis)
}
