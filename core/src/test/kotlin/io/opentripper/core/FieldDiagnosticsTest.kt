package io.opentripper.core

import org.junit.Assert.*
import org.junit.Test

class FieldDiagnosticsTest {
    @Test fun defaultDiagnosticsExcludeNotificationKeyAndInstructionText() {
        val note = MapsNotification(MapsParser.PACKAGE, "private-notification-key", true, listOf("100 m", "Turn left onto Private Street"))
        val event = FieldDiagnostics.maps(note, false)!!
        assertEquals("LEFT", event.getString("maneuver"))
        assertEquals(100, event.getInt("meters"))
        assertFalse(event.has("text"))
        assertFalse(event.toString().contains("Private Street"))
        assertFalse(event.toString().contains("private-notification-key"))
    }
    @Test fun explicitTextCaptureIsBoundedAndMarksSnapshots() {
        val note = MapsNotification(MapsParser.PACKAGE, "ignored", true, List(20) { "x".repeat(2000) }, snapshot = true)
        val event = FieldDiagnostics.maps(note, true)!!
        assertTrue(event.getBoolean("snapshot"))
        assertFalse(event.getBoolean("recognized"))
        assertEquals(8, event.getJSONArray("text").length())
        assertEquals(512, event.getJSONArray("text").getString(0).length)
    }
    @Test fun otherAppsAreExcludedEvenWhenTextCaptureIsEnabled() {
        assertNull(FieldDiagnostics.maps(MapsNotification("another.app", "key", true, listOf("private message")), true))
    }
}
