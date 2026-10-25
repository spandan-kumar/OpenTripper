package io.opentripper.core

/** Single-threaded state machine. The Android adapter calls this only on its main looper. */
class PodSession(
    private val now: () -> Long,
    private val clockPacket: () -> ByteArray,
    private val write: (ByteArray) -> Unit,
    private val changed: (State) -> Unit,
    private val failed: (String) -> Unit
) {
    enum class State { STOPPED, WAITING_PIN, HANDSHAKE, READY, FAILED }
    private data class Step(val bytes: ByteArray, val pauseAfter: Long = 0)
    var state = State.STOPPED; private set
    private val queue = ArrayDeque<Step>()
    private var pending: Step? = null
    private var deadline = 0L
    private var earliest = 0L
    private var lastWrite = 0L
    private var screen: ByteArray? = null
    private var screenUntil = 0L
    private var lastScreen = Long.MIN_VALUE / 2

    fun start(knownDevice: Boolean) {
        stop()
        lastWrite = now()
        earliest = now()
        setState(if (knownDevice) State.HANDSHAKE else State.WAITING_PIN)
        queue.add(Step(TripperProtocol.handshake(knownDevice), if (knownDevice) 200 else 0))
        if (knownDevice) {
            queue.add(Step(clockPacket(), 150))
            queue.add(Step(TripperProtocol.ping()))
            queue.add(Step(TripperProtocol.ping(), 300))
        }
        tick()
    }

    fun submitPin(pin: String) {
        check(state == State.WAITING_PIN) { "Pod is not waiting for a PIN" }
        val packet = TripperProtocol.pin(pin)
        setState(State.HANDSHAKE)
        queue.add(Step(packet))
        tick()
    }

    /** TTL uses monotonic milliseconds. Null stops screen refresh; the Pod may then disconnect. */
    fun updateScreen(packet: ByteArray?, validUntil: Long = 0) {
        require(packet == null || (TripperProtocol.isValid(packet) && packet[0] == 0x10.toByte()))
        if (screen?.contentEquals(packet ?: byteArrayOf()) != true) lastScreen = Long.MIN_VALUE / 2
        screen = packet?.copyOf()
        screenUntil = validUntil
        tick()
    }

    fun syncTime() {
        if (state == State.READY && queue.isEmpty()) queue.add(Step(clockPacket()))
        tick()
    }

    fun onWriteComplete(success: Boolean) {
        val step = pending ?: return
        pending = null
        if (!success) return fail("GATT write failed")
        earliest = now() + step.pauseAfter
        tick()
    }

    fun tick() {
        if (state in listOf(State.STOPPED, State.FAILED)) return
        val time = now()
        if (pending != null) {
            if (time >= deadline) fail("GATT write callback timed out")
            return
        }
        if (time < earliest) return
        if (queue.isEmpty() && state == State.HANDSHAKE) setState(State.READY)
        if (queue.isEmpty()) {
            val activeScreen = screen?.takeIf { state == State.READY && time < screenUntil }
            if (activeScreen != null && time - lastScreen >= 2000) {
                queue.add(Step(activeScreen))
                lastScreen = time
            } else if (time - lastWrite >= 2000) queue.add(Step(TripperProtocol.ping()))
        }
        if (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            pending = next
            deadline = time + 3000
            lastWrite = time
            write(next.bytes.copyOf())
        }
    }

    fun stop() {
        queue.clear(); pending = null; screen = null
        screenUntil = 0; lastScreen = Long.MIN_VALUE / 2
        setState(State.STOPPED)
    }

    private fun fail(reason: String) {
        queue.clear(); pending = null; screen = null
        setState(State.FAILED)
        failed(reason)
    }
    private fun setState(value: State) { state = value; changed(value) }
}
