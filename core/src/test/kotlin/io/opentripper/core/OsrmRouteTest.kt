package io.opentripper.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsrmRouteTest {
    private fun fixture() = javaClass.getResource("/junction-demo.osrm.json")!!.readText()
    private fun single(type: String, modifier: String? = null, exit: Any? = null, side: String? = "left"): String {
        val maneuver = JSONObject().put("type", type)
        modifier?.let { maneuver.put("modifier", it) }; exit?.let { maneuver.put("exit", it) }
        val step = JSONObject().put("maneuver", maneuver).put("distance", 450).put("name", "Test Road")
        side?.let { step.put("driving_side", it) }
        val leg = JSONObject().put("steps", JSONArray().put(step))
        val route = JSONObject().put("distance", 450).put("duration", 90).put("legs", JSONArray().put(leg))
        return JSONObject().put("code", "Ok").put("routes", JSONArray().put(route)).toString()
    }
    @Test fun importsRepresentativeJunctionsWithoutLosingUnsupportedSteps() {
        val route = OsrmRouteImporter.parse(fixture())
        assertEquals(10, route.steps.size)
        assertEquals(Maneuver.FORK_RIGHT, route.steps[2].cue?.maneuver)
        assertEquals(Maneuver.ON_RAMP_RIGHT, route.steps[3].cue?.maneuver)
        assertEquals(Maneuver.OFF_RAMP_LEFT, route.steps[4].cue?.maneuver)
        assertEquals(ManeuverCue(Maneuver.ROUNDABOUT, 3), route.steps[5].cue)
        assertNull(route.steps[8].cue)
        assertTrue(route.steps[8].unsupportedReason!!.contains("Unsupported"))
        assertEquals(325.0, route.steps[2].distanceAfterMeters, 0.0) // Not distance to current maneuver.
        assertEquals(Maneuver.DESTINATION, route.steps[9].cue?.maneuver)
    }
    @Test fun importsAnActualOsrmResponseUsingDocumentedBerlinCoordinates() {
        val json = javaClass.getResource("/osrm-berlin-capture.json")!!.readText()
        val route = OsrmRouteImporter.parse(json)
        assertEquals(4, route.steps.size)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.SLIGHT_RIGHT, Maneuver.RIGHT, Maneuver.DESTINATION),
            route.steps.map { it.cue?.maneuver })
        assertTrue(route.steps.all { it.cue?.drivingSide == DrivingSide.RIGHT })
    }
    @Test fun trafficSideFromRouteOverridesFallback() {
        val route = OsrmRouteImporter.parse(single("roundabout", exit = 3, side = "right"), DrivingSide.LEFT)
        assertEquals(0x34, route.steps.single().cue!!.icon())
        val fallback = OsrmRouteImporter.parse(single("roundabout", exit = 3, side = null), DrivingSide.LEFT)
        assertEquals(0x0d, fallback.steps.single().cue!!.icon())
    }
    @Test fun missingInvalidOrOutOfRangeRoundaboutExitNeverBecomesStraight() {
        for (exit in listOf(null, 0, 10, 2.5, "third")) {
            assertNull(OsrmRouteImporter.parse(single("roundabout", exit = exit)).steps.single().cue)
        }
    }
    @Test fun unrecognizedManeuverAndModifierStayUnsupported() {
        for (json in listOf(single("turn", "banana"), single("fork", "straight"), single("mystery", "left"))) {
            assertNull(OsrmRouteImporter.parse(json).steps.single().cue)
        }
    }
    @Test fun mapsAngleRampAndMergeSemanticsExplicitly() {
        for ((type, modifier, expected) in listOf(
            Triple("turn", "sharp left", Maneuver.SHARP_LEFT), Triple("continue", "slight right", Maneuver.SLIGHT_RIGHT),
            Triple("off ramp", "slight left", Maneuver.OFF_RAMP_LEFT), Triple("merge", "right", Maneuver.MERGE_RIGHT),
            Triple("fork", "slight left", Maneuver.FORK_LEFT))) {
            assertEquals(expected, OsrmRouteImporter.parse(single(type, modifier)).steps.single().cue?.maneuver)
        }
    }
    @Test fun rejectsMalformedFailedMissingAndOversizedRoutes() {
        listOf("not json", "{}", "{\"code\":\"NoRoute\"}", "{\"code\":\"Ok\",\"routes\":[]}",
            single("depart").replace("\"steps\"", "\"geometry\""), " ".repeat(OsrmRouteImporter.MAX_BYTES + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { OsrmRouteImporter.parse(it) }
        }
    }
    @Test fun rejectsNegativeDistancesAndInvalidTrafficSide() {
        assertThrows(IllegalArgumentException::class.java) {
            OsrmRouteImporter.parse(single("turn", "left").replace("450", "-1"))
        }
        assertThrows(IllegalArgumentException::class.java) { OsrmRouteImporter.parse(single("turn", "left", side = "middle")) }
    }
    @Test fun preservesLegBoundariesAndSelectsFirstAlternative() {
        val root = JSONObject(single("depart"))
        val first = root.getJSONArray("routes").getJSONObject(0)
        first.getJSONArray("legs").put(JSONObject().put("steps", JSONArray().put(
            JSONObject().put("distance", 0).put("maneuver", JSONObject().put("type", "arrive")))))
        root.getJSONArray("routes").put(JSONObject(single("turn", "right")).getJSONArray("routes").getJSONObject(0))
        val steps = OsrmRouteImporter.parse(root.toString()).steps
        assertEquals(listOf(Maneuver.DEPART, Maneuver.DESTINATION), steps.map { it.cue?.maneuver })
    }
}
