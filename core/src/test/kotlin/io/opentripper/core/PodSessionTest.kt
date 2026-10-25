package io.opentripper.core

import org.junit.Assert.*
import org.junit.Test

class PodSessionTest {
    private class Rig {
        var time = 0L
        val sent = mutableListOf<ByteArray>()
        val failures = mutableListOf<String>()
        val session = PodSession({ time }, { TripperProtocol.time(13, 42) }, { sent.add(it) }, {}, { failures.add(it) })
        fun advance(ms: Long) { time += ms; session.tick() }
        fun complete() { session.onWriteComplete(true) }
        fun readyNew() { session.start(false); complete(); session.submitPin("123456"); complete() }
    }
    @Test fun resumeSequenceHonorsWriteCallbacksAndDelays() {
        val r = Rig(); r.session.start(true)
        assertEquals(1, r.sent.size)
        r.advance(1000); assertEquals(1, r.sent.size) // No callback, no second write.
        r.complete(); r.advance(199); assertEquals(1, r.sent.size)
        r.advance(1); assertEquals(0x50, r.sent.last()[0].toInt())
        r.complete(); r.advance(149); assertEquals(2, r.sent.size)
        r.advance(1); assertEquals(3, r.sent.size)
        r.complete(); assertEquals(4, r.sent.size) // Second ping is serialized, no extra delay.
        r.complete(); r.advance(299); assertEquals(PodSession.State.HANDSHAKE, r.session.state)
        r.advance(1); assertEquals(PodSession.State.READY, r.session.state)
        assertTrue(r.failures.isEmpty())
    }
    @Test fun newPairingKeepsAliveWhileWaitingForUser() {
        val r = Rig(); r.session.start(false); r.complete()
        assertEquals(PodSession.State.WAITING_PIN, r.session.state)
        r.advance(1999); assertEquals(1, r.sent.size)
        r.advance(1); assertArrayEquals(TripperProtocol.ping(), r.sent.last())
        r.complete(); r.session.submitPin("001234")
        assertArrayEquals(TripperProtocol.pin("001234"), r.sent.last())
        assertEquals(PodSession.State.HANDSHAKE, r.session.state)
        r.complete(); assertEquals(PodSession.State.READY, r.session.state)
    }
    @Test fun badPinDoesNotAdvanceHandshake() {
        val r = Rig(); r.session.start(false); r.complete()
        assertThrows(IllegalArgumentException::class.java) { r.session.submitPin("letters") }
        assertEquals(PodSession.State.WAITING_PIN, r.session.state)
        assertEquals(1, r.sent.size)
    }
    @Test fun missingWriteCallbackFailsBeforeFirmwareWatchdog() {
        val r = Rig(); r.session.start(true); r.advance(3000)
        assertEquals(PodSession.State.FAILED, r.session.state)
        assertEquals(1, r.failures.size)
        r.advance(10000); assertEquals(1, r.sent.size)
    }
    @Test fun gattErrorStopsQueueInsteadOfPretendingConnected() {
        val r = Rig(); r.session.start(true); r.session.onWriteComplete(false); r.advance(2000)
        assertEquals(PodSession.State.FAILED, r.session.state)
        assertEquals(1, r.sent.size)
    }
    @Test fun repeatedScreenRefreshAndExpiryAreIndependentOfPing() {
        val r = Rig(); r.readyNew()
        val screen = TripperProtocol.navigation(Maneuver.LEFT, 250)
        r.session.updateScreen(screen, 4500); r.complete()
        val count = r.sent.size
        r.advance(1999); assertEquals(count, r.sent.size)
        r.advance(1); assertArrayEquals(screen, r.sent.last()); r.complete()
        r.advance(2000); assertArrayEquals(screen, r.sent.last()); r.complete()
        r.advance(2000); assertArrayEquals(TripperProtocol.ping(), r.sent.last())
    }
    @Test fun stopCancelsQueuedWritesAndPendingCallbacks() {
        val r = Rig(); r.session.start(true); r.session.stop(); r.complete(); r.advance(5000)
        assertEquals(PodSession.State.STOPPED, r.session.state)
        assertEquals(1, r.sent.size)
    }
    @Test fun changedScreenWaitsForInFlightWriteAndUsesLatestValue() {
        val r = Rig(); r.readyNew()
        r.session.updateScreen(TripperProtocol.navigation(Maneuver.LEFT, 100), 10000)
        val count = r.sent.size
        r.session.updateScreen(TripperProtocol.navigation(Maneuver.RIGHT, 50), 10000)
        assertEquals(count, r.sent.size)
        r.complete()
        assertEquals(count + 1, r.sent.size)
        assertEquals(Maneuver.RIGHT.icon, r.sent.last()[2].toInt())
    }
    @Test fun clearingScreenDoesNotInventClockCommand() {
        val r = Rig(); r.readyNew()
        r.session.updateScreen(TripperProtocol.navigation(Maneuver.LEFT, 100), 10000); r.complete()
        r.session.updateScreen(null); r.advance(2000)
        assertArrayEquals(TripperProtocol.ping(), r.sent.last())
    }
    @Test fun successfulWritesAreReportedOnlyAfterCallbackAndNeverOnFailure() {
        val completed = mutableListOf<ByteArray>()
        val session = PodSession({ 0 }, { TripperProtocol.time(12, 0) }, {}, {}, {}, { completed.add(it) })
        session.start(false)
        assertTrue(completed.isEmpty())
        session.onWriteComplete(true)
        assertArrayEquals(TripperProtocol.handshake(false), completed.single())
        session.submitPin("123456"); session.onWriteComplete(false)
        assertEquals(1, completed.size)
    }
}
