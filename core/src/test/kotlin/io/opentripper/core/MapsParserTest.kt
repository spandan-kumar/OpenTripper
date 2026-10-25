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
        assertEquals(Maneuver.KEEP_RIGHT, MapsParser.parse(note("100 m", "Keep right at the fork"))?.maneuver)
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
}
