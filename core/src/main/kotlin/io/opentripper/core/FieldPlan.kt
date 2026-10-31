package io.opentripper.core

/** All selectable tests ship together. Retry a group or continue only steps without photo evidence. */
object FieldPlan {
    enum class Group(val label: String) {
        ROUNDABOUTS("Roundabouts: exits 1–9"),
        JUNCTIONS("Other turns, forks, merges and ramps"),
        DISPLAY("Distance, approach emphasis and night mode"),
        MAPS_REPLAY("Maps parser replay: 3 sample directions"),
        BASELINE("Repeat the 7 already-tested screens")
    }
    val recommended = listOf(Group.ROUNDABOUTS, Group.JUNCTIONS, Group.DISPLAY, Group.MAPS_REPLAY)
    fun steps(groups: List<Group>, side: DrivingSide): List<GuidedRun.Step> = groups.distinct().flatMap { group ->
        fun step(id: String, title: String, kind: Maneuver, meters: Int = 100, emphasis: Boolean = false, night: Boolean = false) =
            GuidedRun.Step("${group.name.lowercase()}-$id", title,
                PodProbe(ManeuverCue(kind, drivingSide = side), meters, approachEmphasis = emphasis, night = night))
        when (group) {
            Group.ROUNDABOUTS -> (1..9).map { exit -> GuidedRun.Step("roundabouts-$exit", "Roundabout · exit $exit (${side.label})",
                PodProbe(ManeuverCue(Maneuver.ROUNDABOUT, exit, side), 100)) }
            Group.JUNCTIONS -> listOf(Maneuver.SHARP_RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.FORK_RIGHT,
                Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT, Maneuver.ON_RAMP_LEFT, Maneuver.ON_RAMP_RIGHT,
                Maneuver.OFF_RAMP_RIGHT, Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT, Maneuver.STRAIGHT, Maneuver.DESTINATION)
                .map { step(it.name.lowercase().replace('_', '-'), it.label, it) }
            Group.DISPLAY -> listOf(step("baseline", "Approach comparison · ordinary left at 100 m", Maneuver.LEFT)) +
                listOf(95, 70, 45, 20, 10).map { meters -> step("emphasis-$meters", "Experimental approach emphasis · $meters m", Maneuver.LEFT, meters, emphasis = true) } +
                listOf(999, 1000).map { meters -> step("distance-$meters", "Distance boundary · $meters m", Maneuver.LEFT, meters) } +
                step("night", "Night mode · ordinary left", Maneuver.LEFT, night = true)
            Group.MAPS_REPLAY -> listOf("Turn sharp left", "Slight left", "Keep left at the fork").mapIndexed { index, text ->
                val parsed = requireNotNull(MapsParser.parse(MapsNotification(MapsParser.PACKAGE, "fixture", true, listOf("100 m", text))))
                GuidedRun.Step("maps-replay-$index", "Sample Maps text (not live): $text", PodProbe(parsed.cue(side), parsed.meters))
            }
            Group.BASELINE -> GuidedRun.plan(side, false).map { it.copy(id = "baseline-${it.id}") }
        }
    }
    fun remaining(groups: List<Group>, side: DrivingSide, photographed: Set<String>) = steps(groups, side).filter { it.id !in photographed }
}
