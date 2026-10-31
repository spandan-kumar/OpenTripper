package io.opentripper.core

/** A stationary test only. Transmission success never implies a correct visual rendering. */
class GuidedRun(val steps: List<Step>, private val now: () -> Long, val durationMs: Long = MAX_DURATION_MS) {
    data class Step(val id: String, val title: String, val probe: PodProbe)
    data class Result(var writes: Int = 0, var firstWriteAt: Long? = null,
        var photo: String? = null, var skipped: Boolean = false)
    enum class Phase { CONNECTING, SENDING, SETTLING, PHOTO_REQUIRED, COMPLETED, FAILED, CANCELLED }
    val results = steps.map { Result() }
    val startedAt = now()
    val deadline = startedAt + durationMs
    var phase = Phase.CONNECTING; private set
    var index = 0; private set
    var reason: String? = null; private set
    private var stepStarted = startedAt
    private var photoRequestedAt = 0L
    val terminal get() = phase in setOf(Phase.COMPLETED, Phase.FAILED, Phase.CANCELLED)
    val current get() = steps.getOrNull(index)
    val screen get() = current?.probe?.takeIf { phase in setOf(Phase.SENDING, Phase.SETTLING, Phase.PHOTO_REQUIRED) }

    init { require(durationMs in 5000..1_200_000); require(steps.isNotEmpty() && steps.size <= 64 && steps.map { it.id }.distinct().size == steps.size) }
    fun connected() {
        if (phase != Phase.CONNECTING) return
        phase = Phase.SENDING; stepStarted = now()
    }
    fun written(packet: ByteArray) {
        val probe = screen ?: return
        if (!packet.contentEquals(probe.packet())) return
        val result = results[index]
        result.writes++
        if (result.firstWriteAt == null) {
            result.firstWriteAt = now(); phase = Phase.SETTLING
        }
    }
    fun tick() {
        if (terminal) return
        when {
            now() >= deadline -> fail("Test reached its time limit")
            phase == Phase.SENDING && now() - stepStarted >= 5000 -> fail("No successful screen write was confirmed")
            phase == Phase.SETTLING && now() - results[index].firstWriteAt!! >= SETTLE_MS -> {
                phase = Phase.PHOTO_REQUIRED; photoRequestedAt = now()
            }
            phase == Phase.PHOTO_REQUIRED && now() - photoRequestedAt >= PHOTO_TIMEOUT_MS -> fail("Photo wait timed out; partial report saved")
        }
    }
    fun photo(stepId: String, fileName: String): Boolean {
        tick()
        if (phase != Phase.PHOTO_REQUIRED || current?.id != stepId ||
            !fileName.matches(Regex("photo-[a-z0-9-]+\\.jpg"))) return false
        results[index].photo = fileName; advance(); return true
    }
    fun skip(stepId: String): Boolean {
        tick()
        if (phase != Phase.PHOTO_REQUIRED || current?.id != stepId) return false
        results[index].skipped = true; advance(); return true
    }
    private fun advance() {
        index++
        if (index == steps.size) phase = Phase.COMPLETED else { phase = Phase.SENDING; stepStarted = now() }
    }
    fun fail(message: String) { if (!terminal) { reason = message; phase = Phase.FAILED } }
    fun cancel() { if (!terminal) { reason = "Stopped by user"; phase = Phase.CANCELLED } }

    companion object {
        const val MAX_DURATION_MS = 600_000L
        const val SETTLE_MS = 3000L
        const val PHOTO_TIMEOUT_MS = 120_000L
        fun plan(side: DrivingSide, experimental: Boolean): List<Step> {
            fun step(id: String, title: String, kind: Maneuver, next: Maneuver? = null) = Step(id, title,
                PodProbe(ManeuverCue(kind, drivingSide = side), 100,
                    next?.let { ManeuverCue(it, drivingSide = side) }))
            val result = mutableListOf(
                step("left", "Ordinary left turn", Maneuver.LEFT),
                step("right", "Ordinary right turn", Maneuver.RIGHT),
                step("sharp-left", "Sharp left: compare with the ordinary turn", Maneuver.SHARP_LEFT),
                step("slight-left", "Slight left: compare the angle", Maneuver.SLIGHT_LEFT),
                step("fork-left", "Left at a fork", Maneuver.FORK_LEFT),
                step("exit-next", "Exit left, followed by a right turn", Maneuver.OFF_RAMP_LEFT, Maneuver.RIGHT),
                step("uturn", "U-turn for ${side.label.lowercase()}", Maneuver.U_TURN)
            )
            if (experimental) {
                for (exit in 1..3) result.add(Step("roundabout-$exit", "Experimental roundabout: exit $exit",
                    PodProbe(ManeuverCue(Maneuver.ROUNDABOUT, exit, side), 100)))
                result.add(Step("near-turn", "Experimental approach emphasis at 20 m",
                    PodProbe(ManeuverCue(Maneuver.LEFT, drivingSide = side), 20, approachEmphasis = true)))
            }
            return result
        }
    }
}
