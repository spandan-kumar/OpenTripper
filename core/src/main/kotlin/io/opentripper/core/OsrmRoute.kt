package io.opentripper.core

import org.json.JSONObject

data class RouteStep(
    val cue: ManeuverCue?, val road: String, val distanceAfterMeters: Double, val unsupportedReason: String?
) {
    val label get() = cue?.label ?: "Unsupported instruction"
}
data class OsrmRoute(val distanceMeters: Double, val durationSeconds: Double, val steps: List<RouteStep>)

/** Imports the first OSRM alternative. This does not perform GPS guidance or calculate a route. */
object OsrmRouteImporter {
    const val MAX_BYTES = 1_048_576
    fun parse(json: String, fallbackSide: DrivingSide = DrivingSide.LEFT): OsrmRoute {
        require(json.length <= MAX_BYTES) { "Route file must be under 1 MiB" }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Route file must be under 1 MiB" }
        try {
            val root = JSONObject(json)
            require(root.optString("code") == "Ok") { "OSRM did not return a successful route" }
            val routes = root.optJSONArray("routes")
            require(routes != null && routes.length() > 0) { "No routes in this file" }
            val route = routes.getJSONObject(0)
            val legs = route.optJSONArray("legs")
            require(legs != null && legs.length() in 1..100) { "Route legs are missing or too numerous" }
            val steps = mutableListOf<RouteStep>()
            for (legIndex in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(legIndex).optJSONArray("steps")
                require(legSteps != null && legSteps.length() > 0) { "No steps: export OSRM with steps=true" }
                require(steps.size + legSteps.length() <= 5000) { "Route has too many steps" }
                for (stepIndex in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(stepIndex)
                    val maneuver = step.getJSONObject("maneuver")
                    val side = when (step.optString("driving_side")) {
                        "left" -> DrivingSide.LEFT; "right" -> DrivingSide.RIGHT
                        "" -> fallbackSide
                        else -> throw IllegalArgumentException("Invalid driving_side")
                    }
                    val type = maneuver.optString("type").lowercase(java.util.Locale.ROOT)
                    val modifier = maneuver.optString("modifier").lowercase(java.util.Locale.ROOT)
                    val exit = maneuver.opt("exit") as? Number
                    val cue = map(type, modifier, exit?.toDouble(), side)
                    val reason = if (cue != null) null else when (type) {
                        "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" ->
                            "Roundabout needs an explicit exit from 1 to 9; none is guessed"
                        else -> "Unsupported OSRM maneuver: ${type.take(60)} / ${modifier.take(40)}"
                    }
                    steps.add(RouteStep(cue, step.optString("name").take(200),
                        nonnegative(step, "distance"), reason))
                }
            }
            return OsrmRoute(nonnegative(route, "distance"), nonnegative(route, "duration"), steps)
        } catch (error: org.json.JSONException) {
            throw IllegalArgumentException("Invalid OSRM route JSON: ${error.message?.take(160)}", error)
        }
    }

    private fun nonnegative(json: JSONObject, key: String): Double = json.getDouble(key).also {
        require(it.isFinite() && it >= 0) { "$key must be a finite nonnegative number" }
    }

    private fun map(type: String, modifier: String, exit: Double?, side: DrivingSide): ManeuverCue? {
        if (type in setOf("roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary")) {
            return if (exit != null && exit.isFinite() && exit in 1.0..9.0 && exit == exit.toInt().toDouble())
                ManeuverCue(Maneuver.ROUNDABOUT, exit.toInt(), side) else null
        }
        val turn = when (modifier) {
            "left" -> Maneuver.LEFT; "right" -> Maneuver.RIGHT
            "sharp left" -> Maneuver.SHARP_LEFT; "sharp right" -> Maneuver.SHARP_RIGHT
            "slight left" -> Maneuver.SLIGHT_LEFT; "slight right" -> Maneuver.SLIGHT_RIGHT
            "straight" -> Maneuver.STRAIGHT; "uturn" -> Maneuver.U_TURN
            else -> null
        }
        val left = modifier in setOf("left", "slight left", "sharp left")
        val right = modifier in setOf("right", "slight right", "sharp right")
        val maneuver = when (type) {
            "depart" -> Maneuver.DEPART; "arrive" -> Maneuver.DESTINATION
            "turn", "end of road", "continue", "new name" -> turn
            "fork" -> if (left) Maneuver.FORK_LEFT else if (right) Maneuver.FORK_RIGHT else null
            "on ramp" -> if (left) Maneuver.ON_RAMP_LEFT else if (right) Maneuver.ON_RAMP_RIGHT else null
            "off ramp" -> if (left) Maneuver.OFF_RAMP_LEFT else if (right) Maneuver.OFF_RAMP_RIGHT else null
            "merge" -> if (left) Maneuver.MERGE_LEFT else if (right) Maneuver.MERGE_RIGHT
                else if (modifier == "straight") Maneuver.MERGE else null
            else -> null
        }
        return maneuver?.let { ManeuverCue(it, drivingSide = side) }
    }
}
