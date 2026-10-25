package io.opentripper.core

import org.junit.Assert.*
import org.junit.Test

class ManeuverCueTest {
    @Test fun forksRampsAndTurnsKeepDistinctWireIcons() {
        val types = listOf(Maneuver.LEFT, Maneuver.SLIGHT_LEFT, Maneuver.SHARP_LEFT,
            Maneuver.FORK_LEFT, Maneuver.MERGE_LEFT, Maneuver.ON_RAMP_LEFT, Maneuver.OFF_RAMP_LEFT)
        val codes = types.map { TripperProtocol.navigation(ManeuverCue(it), 200)[2].toInt() and 255 }
        assertEquals(listOf(0x14, 0x18, 0x16, 0x06, 0x04, 0x1e, 0x2e), codes)
        assertEquals(types.size, codes.distinct().size)
    }
    @Test fun roundaboutsHaveExplicitExitAndTrafficSide() {
        for (exit in 1..9) {
            assertEquals(0x0a + exit, ManeuverCue(Maneuver.ROUNDABOUT, exit, DrivingSide.LEFT).icon())
            assertEquals(0x31 + exit, ManeuverCue(Maneuver.ROUNDABOUT, exit, DrivingSide.RIGHT).icon())
        }
        listOf(null, 0, 10, -1).forEach { exit ->
            assertThrows(IllegalArgumentException::class.java) { ManeuverCue(Maneuver.ROUNDABOUT, exit) }
        }
        assertThrows(IllegalArgumentException::class.java) { ManeuverCue(Maneuver.LEFT, 2) }
    }
    @Test fun nextManeuverUsesSmallArrowMappingRatherThanRepeatingLargeIcon() {
        val p = TripperProtocol.navigation(ManeuverCue(Maneuver.FORK_RIGHT), 250,
            next = ManeuverCue(Maneuver.OFF_RAMP_LEFT))
        assertEquals(0x05, p[2].toInt())
        assertEquals(0x2b, p[7].toInt())
        assertEquals(0xff, TripperProtocol.navigation(Maneuver.LEFT, 250)[7].toInt() and 255)
        assertTrue(TripperProtocol.isValid(p))
    }
    @Test fun uTurnsFollowTrafficSide() {
        assertEquals(0x1a, ManeuverCue(Maneuver.U_TURN, drivingSide = DrivingSide.LEFT).icon())
        assertEquals(0x3d, ManeuverCue(Maneuver.U_TURN, drivingSide = DrivingSide.RIGHT).icon())
    }
    @Test fun approachIntensityIsOptInAndKeepsNightBitSeparate() {
        val cue = ManeuverCue(Maneuver.LEFT)
        assertEquals(0, TripperProtocol.navigation(cue, 10)[6].toInt())
        listOf(10 to 0x50, 11 to 0x40, 20 to 0x40, 21 to 0x30, 45 to 0x30,
            46 to 0x20, 70 to 0x20, 71 to 0x10, 95 to 0x10, 96 to 0).forEach { (distance, expected) ->
            assertEquals(expected, TripperProtocol.navigation(cue, distance, approachEmphasis = true)[6].toInt())
            assertEquals(expected or 1, TripperProtocol.navigation(cue, distance, night = true, approachEmphasis = true)[6].toInt())
        }
    }
    @Test fun everyCatalogFrameHasValidChecksumAndUnknownBottomFields() {
        for (side in DrivingSide.entries) for (kind in Maneuver.entries) {
            val cue = ManeuverCue(kind, if (kind == Maneuver.ROUNDABOUT) 3 else null, side)
            val p = PodProbe(cue, 1000, ManeuverCue(Maneuver.KEEP_RIGHT, drivingSide = side)).packet()
            assertTrue(TripperProtocol.isValid(p))
            for (index in listOf(8, 9, 11, 12, 13)) assertEquals(255, p[index].toInt() and 255)
        }
    }
    @Test fun probesRejectUnrepresentableDistancesAndMarkExperimentalFeatures() {
        assertThrows(IllegalArgumentException::class.java) { PodProbe(ManeuverCue(Maneuver.LEFT), -1) }
        assertThrows(IllegalArgumentException::class.java) { PodProbe(ManeuverCue(Maneuver.LEFT), 6_553_501) }
        assertFalse(PodProbe(ManeuverCue(Maneuver.LEFT), 200).experimental)
        assertTrue(PodProbe(ManeuverCue(Maneuver.ROUNDABOUT, 3), 200).experimental)
        assertTrue(PodProbe(ManeuverCue(Maneuver.LEFT), 200, approachEmphasis = true).experimental)
    }
}
