package io.opentripper.core

import org.junit.Assert.*
import org.junit.Test

class FieldPlanTest {
    @Test fun recommendedPlanExcludesAlreadyPhotographedBaselineAndHasUniqueValidCommands() {
        val steps = FieldPlan.steps(FieldPlan.recommended, DrivingSide.LEFT)
        assertEquals(33, steps.size)
        assertFalse(steps.any { it.id.startsWith("baseline-") })
        assertEquals(steps.size, steps.map { it.id }.distinct().size)
        assertTrue(steps.all { TripperProtocol.isValid(it.probe.packet()) })
        assertEquals(40, FieldPlan.steps(FieldPlan.Group.entries, DrivingSide.LEFT).size)
    }
    @Test fun roundaboutsCoverAllExitsForOnlyTheChosenTrafficSide() {
        for (side in DrivingSide.entries) {
            val steps = FieldPlan.steps(listOf(FieldPlan.Group.ROUNDABOUTS), side)
            assertEquals((1..9).toList(), steps.map { it.probe.cue.exit })
            assertTrue(steps.all { it.probe.experimental && it.probe.cue.drivingSide == side })
        }
    }
    @Test fun displayTestsKeepDistanceBoundaryEmphasisAndNightModeSeparate() {
        val steps = FieldPlan.steps(listOf(FieldPlan.Group.DISPLAY), DrivingSide.LEFT)
        assertEquals(listOf(95, 70, 45, 20, 10), steps.filter { it.probe.approachEmphasis }.map { it.probe.meters })
        assertEquals(listOf(999, 1000), steps.filter { it.id.contains("distance-") }.map { it.probe.meters })
        assertEquals(1, steps.count { it.probe.night })
        assertFalse(steps.first().probe.approachEmphasis)
    }
    @Test fun retryingAGroupAndContinuingMissingPhotosDoNotRequireAnotherBuild() {
        val groups = listOf(FieldPlan.Group.ROUNDABOUTS, FieldPlan.Group.MAPS_REPLAY)
        val original = FieldPlan.steps(groups, DrivingSide.LEFT)
        val remaining = FieldPlan.remaining(groups, DrivingSide.LEFT, original.take(4).map { it.id }.toSet())
        assertEquals(original.drop(4), remaining)
        assertEquals(3, FieldPlan.steps(listOf(FieldPlan.Group.MAPS_REPLAY), DrivingSide.LEFT).size)
        assertTrue(FieldPlan.remaining(groups, DrivingSide.LEFT, original.map { it.id }.toSet()).isEmpty())
        assertEquals(original, FieldPlan.steps(groups + groups, DrivingSide.LEFT))
    }
    @Test fun replayCommandsComeFromTheParserAndAreLabeledAsSamples() {
        val replay = FieldPlan.steps(listOf(FieldPlan.Group.MAPS_REPLAY), DrivingSide.LEFT)
        assertEquals(listOf(Maneuver.SHARP_LEFT, Maneuver.SLIGHT_LEFT, Maneuver.FORK_LEFT), replay.map { it.probe.cue.maneuver })
        assertTrue(replay.all { it.title.startsWith("Sample Maps text (not live)") })
    }
    @Test fun expandedRunStaysBoundedAndRetainsPhotosBeforeFailure() {
        var time = 0L
        val steps = FieldPlan.steps(FieldPlan.recommended, DrivingSide.LEFT)
        val run = GuidedRun(steps, { time }, 1_200_000)
        run.connected(); run.written(steps.first().probe.packet()); time = 3000; run.tick()
        assertTrue(run.photo(steps.first().id, "photo-${steps.first().id}.jpg"))
        time = 1_200_000; run.tick()
        assertEquals(GuidedRun.Phase.FAILED, run.phase)
        assertNotNull(run.results.first().photo)
        assertNull(run.screen)
    }
}
