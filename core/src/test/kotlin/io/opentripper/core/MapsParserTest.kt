package io.opentripper.core

import org.junit.Assert.*
import org.junit.Test

class MapsParserTest {
    private fun note(vararg lines: String, key: String = "nav", ongoing: Boolean = true,
        pkg: String = MapsParser.PACKAGE) = MapsNotification(pkg, key, ongoing, lines.toList())
    @Test fun parsesSingleAndSeparateDistanceInstructionFields() {
        assertEquals(MapsManeuver(Maneuver.LEFT, 200), MapsParser.parse(note("In 200 m, Turn left onto Main Street")))
        assertEquals(MapsManeuver(Maneuver.RIGHT, 1200), MapsParser.parse(note("1.2 km", "Turn right onto NH48")))
    }
    @Test fun metricAndImperialDistancesConvert() {
        assertEquals(152, MapsParser.parse(note("500 ft", "Keep left"))?.meters)
        assertEquals(805, MapsParser.parse(note("0.5 mi · Continue straight"))?.meters)
        assertEquals(300, MapsParser.parse(note("300\u00a0m", "Make a U-turn"))?.meters)
    }
    @Test fun sharpSlightAndKeepAreNotCollapsedIntoGenericTurn() {
        assertEquals(Maneuver.SHARP_RIGHT, MapsParser.parse(note("100 m", "Turn sharp right"))?.maneuver)
        assertEquals(Maneuver.SLIGHT_LEFT, MapsParser.parse(note("100 m", "Slight left onto A Road"))?.maneuver)
        assertEquals(Maneuver.FORK_RIGHT, MapsParser.parse(note("100 m", "Keep right at the fork"))?.maneuver)
    }
    @Test fun roundaboutsRequireAnExplicitExitRatherThanCollapsingIntoATurn() {
        for (text in listOf("At the roundabout, take the 3rd exit", "Enter the roundabout and take the third exit",
            "Take the 3rd exit at the roundabout")) {
            val result = MapsParser.parse(note("100 m", text))!!
            assertEquals(Maneuver.ROUNDABOUT, result.maneuver)
            assertEquals(3, result.roundaboutExit)
            assertEquals(0x0d, result.cue(DrivingSide.LEFT).icon())
        }
        for (text in listOf("At the roundabout turn left", "At the roundabout take the 10th exit",
            "At the roundabout take the 1st exit then take the 2nd exit")) {
            assertNull(MapsParser.parse(note("100 m", text)))
        }
    }
    @Test fun rampsExitsAndMergesRetainTheirOwnManeuverTypes() {
        assertEquals(Maneuver.ON_RAMP_RIGHT, MapsParser.parse(note("100 m", "Take the ramp on the right"))?.maneuver)
        assertEquals(Maneuver.OFF_RAMP_LEFT, MapsParser.parse(note("100 m", "Take the exit on the left"))?.maneuver)
        assertEquals(Maneuver.MERGE_RIGHT, MapsParser.parse(note("100 m", "Merge right"))?.maneuver)
        assertNull(MapsParser.parse(note("100 m", "Take the flyover"))) // No direction can be inferred.
    }
    @Test fun explicitRoundaboutExitTakesPriorityOverGenericNotificationTitle() {
        val parsed = MapsParser.parse(note("100 m", "Turn left", "At the roundabout, take the third exit"))!!
        assertEquals(Maneuver.ROUNDABOUT, parsed.maneuver)
        assertEquals(3, parsed.roundaboutExit)
    }
    @Test fun vagueAndConflictingRoundaboutsNeverFallBackToOrdinaryTurns() {
        assertNull(MapsParser.parse(note("100 m", "Turn left at the roundabout")))
        assertNull(MapsParser.parse(note("100 m", "Turn left", "At the roundabout, turn left")))
        assertNull(MapsParser.parse(note("100 m", "At the roundabout take the 1st exit", "At the roundabout take the 3rd exit")))
    }
    @Test fun rejectsOtherPackagesAndNonOngoingNotifications() {
        assertNull(MapsParser.parse(note("100 m", "Turn left", pkg = "com.example.fake")))
        assertNull(MapsParser.parse(note("100 m", "Turn left", ongoing = false)))
    }
    @Test fun rejectsEtaTripTotalsAndMissingDistance() {
        listOf(note("Turn left"), note("12 min · 2 km", "Turn left"), note("ETA 13:40", "Turn left"),
            note("2 km remaining", "Turn left"), note("100 m", "Left Lane Street"),
            note("100 m", "Turn leftwards"), note("100 m", "Take the roundabout"),
            note("0,5 km", "Turn left"), note("100 m", "Tournez à gauche")).forEach {
            assertNull(it.toString(), MapsParser.parse(it))
        }
    }
    @Test fun rejectsOutOfRangeAndAmbiguousUnits() {
        listOf("-10 m", "999999999999 km", "100 meters", "100miles").forEach {
            assertNull(MapsParser.parse(note(it, "Turn right")))
        }
    }
    @Test fun unrelatedRemovalAndOtherMapsUpdatesDoNotCancelNavigation() {
        val route = MapsRoute(); route.post(note("100 m", "Turn left"), 100)
        route.remove("other"); route.post(note("Restaurant nearby", key = "other"), 200)
        assertEquals(Maneuver.LEFT, route.current(300)?.maneuver)
        route.remove("nav"); assertNull(route.current(301))
    }
    @Test fun sameNotificationBecomingUnrecognizedClearsOldInstruction() {
        val route = MapsRoute(); route.post(note("100 m", "Turn left"), 0)
        route.post(note("You have arrived"), 100)
        assertNull(route.current(100))
    }
    @Test fun staleRouteExpiresAndCanBeRefreshed() {
        val route = MapsRoute(); route.post(note("100 m", "Turn left"), 100)
        assertNotNull(route.current(15099)); assertNull(route.current(15100))
        route.post(note("50 m", "Turn left"), 16000)
        assertEquals(50, route.current(16001)?.meters)
        route.clear(); assertNull(route.current(16002))
    }
    @Test fun sourceStatusDistinguishesNoRouteUnsupportedExpiredAndRemoved() {
        val route = MapsRoute()
        assertEquals(MapsRoute.Status.WAITING, route.status)
        route.post(note("Rerouting…"), 0)
        assertEquals(MapsRoute.Status.UNSUPPORTED, route.status)
        assertNull(route.current(100))
        assertEquals(MapsRoute.Status.UNSUPPORTED, route.status)
        route.post(note("100 m", "Turn left"), 200)
        assertEquals(MapsRoute.Status.RECOGNIZED, route.status)
        assertNull(route.current(15200))
        assertEquals(MapsRoute.Status.EXPIRED, route.status)
        route.post(note("50 m", "Turn right"), 16000)
        route.remove("nav")
        assertEquals(MapsRoute.Status.REMOVED, route.status)
        route.clear(); assertEquals(MapsRoute.Status.WAITING, route.status)
    }
    @Test fun irrelevantPostsCannotChangeSourceStatusOrRetainStaleArrows() {
        val route = MapsRoute()
        route.post(note("100 m", "Turn left", pkg = "other.app"), 0)
        route.post(note("Nearby places", ongoing = false), 0)
        assertEquals(MapsRoute.Status.WAITING, route.status)
        route.post(note("100 m", "Turn left"), 100)
        route.post(note("Nearby places", key = "other"), 200)
        assertEquals(MapsRoute.Status.RECOGNIZED, route.status)
        assertEquals(Maneuver.LEFT, route.current(300)?.maneuver)
        route.post(note("Rerouting…"), 400)
        assertNull(route.current(400))
        assertEquals(MapsRoute.Status.UNSUPPORTED, route.status)
        route.remove("other"); assertEquals(MapsRoute.Status.UNSUPPORTED, route.status)
    }

    @Test fun parsedDirectionsSelectTheSevenPhotographedHunterIconCodes() {
        val cases = listOf("Turn left" to 0x14, "Turn right" to 0x15,
            "Turn sharp left" to 0x16, "Slight left" to 0x18,
            "Keep left at the fork" to 0x06, "Take the exit on the left" to 0x2e,
            "Make a U-turn" to 0x1a)
        for ((instruction, icon) in cases) {
            val parsed = MapsParser.parse(note("100 m", instruction))!!
            val packet = TripperProtocol.navigation(parsed.cue(DrivingSide.LEFT), parsed.meters)
            assertEquals(instruction, icon, packet[2].toInt() and 255)
            assertTrue(TripperProtocol.isValid(packet))
        }
    }

}
