package io.opentripper.core

/** Tripper Pod only. Wire fields follow WazeTripper docs/PROTOCOL.md, not Tripper Dash. */
object TripperProtocol {
    const val SERVICE = "01ff0100-ba5e-f4ee-5ca1-eb1e5e4b1ce0"
    const val CHARACTERISTIC = "01ff0101-ba5e-f4ee-5ca1-eb1e5e4b1ce0"
    const val DEVICE_NAME = "RE_DISP"

    fun crc(data: ByteArray): Int {
        var result = 0xffff
        for (byte in data) {
            result = result xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) {
                result = if (result and 0x8000 != 0) (result shl 1) xor 0x1021 else result shl 1
                result = result and 0xffff
            }
        }
        return result
    }

    fun packet(vararg payload: Int): ByteArray {
        require(payload.size <= 18 && payload.all { it in 0..255 })
        val bytes = ByteArray(20)
        payload.forEachIndexed { i, value -> bytes[i] = value.toByte() }
        val crc = crc(bytes.copyOf(18))
        bytes[18] = (crc ushr 8).toByte()
        bytes[19] = crc.toByte()
        return bytes
    }

    fun isValid(bytes: ByteArray): Boolean = bytes.size == 20 &&
        crc(bytes.copyOf(18)) == ((bytes[18].toInt() and 0xff) shl 8 or (bytes[19].toInt() and 0xff))

    fun handshake(knownDevice: Boolean) = packet(0x21, if (knownDevice) 0 else 1)
    fun ping() = packet(3)
    fun pin(value: String): ByteArray {
        require(value.length in 1..6 && value.all { it in '0'..'9' }) { "Enter 1–6 PIN digits" }
        return packet(0x20, *value.map { it.code }.toIntArray())
    }

    fun time(hour24: Int, minute: Int, twelveHour: Boolean = false): ByteArray {
        require(hour24 in 0..23 && minute in 0..59)
        val hour = if (!twelveHour) hour24 else
            ((hour24 % 12).let { if (it == 0) 12 else it }) or (if (hour24 < 12) 0x40 else 0)
        return packet(0x50, hour, minute)
    }

    /** Saturates the wire distance; km are expressed in tenths, truncated as in upstream. */
    fun distance(meters: Int): IntArray {
        require(meters >= 0)
        val value = (if (meters <= 999) meters else meters / 100).coerceAtMost(0xffff)
        return intArrayOf(value ushr 8, value and 255, if (meters <= 999) 1 else 2)
    }

    fun navigation(maneuver: Maneuver, meters: Int, night: Boolean = false): ByteArray {
        val d = distance(meters)
        // Unknown next maneuver, radar and bottom line stay blank. No invented ETA.
        return packet(0x10, 0x11, maneuver.icon, d[0], d[1], d[2], if (night) 1 else 0,
            0xff, 0xff, 0xff, 0x41, 0xff, 0xff, 0xff)
    }
}

enum class Maneuver(val icon: Int) {
    LEFT(0x14), RIGHT(0x15), SHARP_LEFT(0x16), SHARP_RIGHT(0x17),
    SLIGHT_LEFT(0x18), SLIGHT_RIGHT(0x19), KEEP_LEFT(0x28), KEEP_RIGHT(0x27),
    STRAIGHT(0x09), U_TURN(0x3d)
}
