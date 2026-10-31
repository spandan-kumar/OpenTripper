package io.opentripper.core

import org.junit.Assert.*
import org.junit.Test

class GuidedRunTest {
    private class Rig(experimental: Boolean = false) {
        var time = 1000L
        val run = GuidedRun(GuidedRun.plan(DrivingSide.LEFT, experimental), now = { time })
        fun advance(ms: Long) { time += ms; run.tick() }
        fun photoReady() {
            run.connected(); run.written(run.current!!.probe.packet()); advance(GuidedRun.SETTLE_MS)
            assertEquals(GuidedRun.Phase.PHOTO_REQUIRED, run.phase)
        }
    }
    @Test fun waitsForExactWriteThenSettlesBeforeAskingForPhoto() {
        val r = Rig()
        assertNull(r.run.screen)
        assertFalse(r.run.photo("left", "photo-left.jpg"))
        r.run.connected()
        r.run.written(TripperProtocol.ping())
        assertEquals(GuidedRun.Phase.SENDING, r.run.phase)
        r.run.written(r.run.current!!.probe.packet())
        assertEquals(GuidedRun.Phase.SETTLING, r.run.phase)
        r.advance(2999); assertEquals(GuidedRun.Phase.SETTLING, r.run.phase)
        r.advance(1); assertEquals(GuidedRun.Phase.PHOTO_REQUIRED, r.run.phase)
        assertNotNull(r.run.screen)
        r.run.written(r.run.current!!.probe.packet())
        assertEquals(GuidedRun.Phase.PHOTO_REQUIRED, r.run.phase)
        assertEquals(2, r.run.results[0].writes)
    }
    @Test fun acceptedPhotoAdvancesExactlyOneStepAndStaleResultsAreIgnored() {
        val r = Rig(); r.photoReady()
        assertFalse(r.run.photo("right", "photo-right.jpg"))
        assertFalse(r.run.photo("left", "../../bad.jpg"))
        assertTrue(r.run.photo("left", "photo-left.jpg"))
        assertEquals("photo-left.jpg", r.run.results[0].photo)
        assertEquals(GuidedRun.Phase.SENDING, r.run.phase)
        assertEquals("right", r.run.current!!.id)
        assertFalse(r.run.photo("left", "photo-left.jpg"))
        assertFalse(r.run.skip("right"))
    }
    @Test fun everyCheckpointNeedsPhotoOrExplicitSkipToComplete() {
        val r = Rig()
        r.run.steps.forEachIndexed { index, step ->
            r.photoReady()
            if (index == 1) assertTrue(r.run.skip(step.id))
            else assertTrue(r.run.photo(step.id, "photo-${step.id}.jpg"))
        }
        assertEquals(GuidedRun.Phase.COMPLETED, r.run.phase)
        assertEquals(6, r.run.results.count { it.photo != null })
        assertTrue(r.run.results[1].skipped)
        assertNull(r.run.screen)
        r.run.fail("late disconnect"); r.run.cancel()
        assertEquals(GuidedRun.Phase.COMPLETED, r.run.phase)
    }
    @Test fun missingWriteFailsWithoutClaimingPhotoEvidence() {
        val r = Rig(); r.run.connected(); r.advance(5000)
        assertEquals(GuidedRun.Phase.FAILED, r.run.phase)
        assertEquals(0, r.run.results[0].writes)
        assertNull(r.run.screen)
    }
    @Test fun photoTimeoutPreservesPartialEvidenceAndRejectsLatePhoto() {
        val r = Rig(); r.photoReady(); r.run.photo("left", "photo-left.jpg")
        r.photoReady(); r.time += GuidedRun.PHOTO_TIMEOUT_MS
        assertFalse(r.run.photo("right", "photo-right.jpg"))
        assertEquals(GuidedRun.Phase.FAILED, r.run.phase)
        assertEquals("photo-left.jpg", r.run.results[0].photo)
        assertNull(r.run.results[1].photo)
    }
    @Test fun connectionAndWholeRunAreBoundedAndCancellationIsFinal() {
        val r = Rig(); r.advance(GuidedRun.MAX_DURATION_MS)
        assertEquals(GuidedRun.Phase.FAILED, r.run.phase)
        val other = Rig(); other.photoReady(); other.run.cancel(); other.run.connected()
        assertFalse(other.run.skip("left"))
        assertEquals(GuidedRun.Phase.CANCELLED, other.run.phase)
        assertNull(other.run.screen)
    }
    @Test fun experimentalPlanRequiresExplicitChoiceAndPreservesTrafficSide() {
        for (side in DrivingSide.entries) {
            val standard = GuidedRun.plan(side, false)
            assertEquals(7, standard.size); assertFalse(standard.any { it.probe.experimental })
            val all = GuidedRun.plan(side, true)
            assertEquals(11, all.size)
            assertEquals(listOf(1, 2, 3), all.filter { it.probe.cue.maneuver == Maneuver.ROUNDABOUT }.map { it.probe.cue.exit })
            assertTrue(all.all { it.probe.cue.drivingSide == side })
            assertEquals(20, all.last().probe.meters)
            assertTrue(all.last().probe.approachEmphasis)
        }
    }
}
