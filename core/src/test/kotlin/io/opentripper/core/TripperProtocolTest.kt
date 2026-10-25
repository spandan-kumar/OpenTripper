package io.opentripper.core

import org.junit.Assert.*
import org.junit.Test

class TripperProtocolTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    @Test fun crcHasIndependentStandardCheckValue() {
        assertEquals(0x29b1, TripperProtocol.crc("123456789".toByteArray()))
    }
    @Test fun publishedFixedPacketsMatchExactly() {
        assertArrayEquals(hex("21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7"), TripperProtocol.handshake(false))
        assertArrayEquals(hex("21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45"), TripperProtocol.handshake(true))
        assertArrayEquals(hex("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9"), TripperProtocol.ping())
    }
    @Test fun everySingleBitCorruptionIsRejectedIncludingCrc() {
        val original = TripperProtocol.navigation(Maneuver.LEFT, 250)
        assertTrue(TripperProtocol.isValid(original))
        for (i in original.indices) for (bit in 0..7) {
            val corrupt = original.copyOf(); corrupt[i] = (corrupt[i].toInt() xor (1 shl bit)).toByte()
            assertFalse("byte $i bit $bit", TripperProtocol.isValid(corrupt))
        }
        assertFalse(TripperProtocol.isValid(original.copyOf(19)))
        assertFalse(TripperProtocol.isValid(original.copyOf(21)))
    }
    @Test fun pinUsesAsciiAndPreservesLeadingZeroes() {
        val pin = TripperProtocol.pin("001234")
        assertEquals(0x20, pin[0].toInt())
        assertEquals("001234", pin.copyOfRange(1, 7).toString(Charsets.US_ASCII))
        assertTrue(pin.copyOfRange(7, 18).all { it == 0.toByte() })
        assertTrue(TripperProtocol.isValid(pin))
    }
    @Test fun invalidPinsFailRatherThanTruncate() {
        listOf("", "1234567", "12 34", "１２３", "abc").forEach { pin ->
            assertThrows(IllegalArgumentException::class.java) { TripperProtocol.pin(pin) }
        }
    }
    @Test fun timeUsesSetTimeEncodingIncludingMidnightAndNoon() {
        assertEquals(0x4c, TripperProtocol.time(0, 5, true)[1].toInt())
        assertEquals(12, TripperProtocol.time(12, 0, true)[1].toInt())
        assertEquals(11, TripperProtocol.time(23, 59, true)[1].toInt())
        assertEquals(23, TripperProtocol.time(23, 59)[1].toInt())
        assertThrows(IllegalArgumentException::class.java) { TripperProtocol.time(24, 0) }
    }
    @Test fun distanceBoundariesAndSaturationFollowWireUnits() {
        assertArrayEquals(intArrayOf(3, 231, 1), TripperProtocol.distance(999))
        assertArrayEquals(intArrayOf(0, 10, 2), TripperProtocol.distance(1000))
        assertArrayEquals(intArrayOf(0, 229, 2), TripperProtocol.distance(22999))
        assertArrayEquals(intArrayOf(255, 255, 2), TripperProtocol.distance(Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { TripperProtocol.distance(-1) }
    }
    @Test fun navigationLeavesUnknownFieldsBlank() {
        val p = TripperProtocol.navigation(Maneuver.RIGHT, 999, true)
        assertArrayEquals(hex("10 11 15 03 E7 01 01 FF FF FF 41 FF FF FF 00 00 00 00"), p.copyOf(18))
        assertTrue(TripperProtocol.isValid(p))
    }
}
