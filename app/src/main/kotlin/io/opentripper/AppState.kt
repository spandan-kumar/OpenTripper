package io.opentripper

import io.opentripper.core.MapsManeuver
import io.opentripper.core.MapsNotification
import io.opentripper.core.PodProbe
import io.opentripper.core.PodSession
import io.opentripper.core.GuidedRun

/** In-process UI state, accessed on main looper only. No notification contents go to disk or network. */
object AppState {
    var status = "Stopped"; private set
    var devices: List<Pair<String, String>> = emptyList(); private set
    var navigation: MapsManeuver? = null
    var observerConnected = false
    var sessionState = PodSession.State.STOPPED
    var screenMessage = "No screen is being forwarded"
    var completedProbe: PodProbe? = null
    var completedProbeAt: String? = null
    var roundaboutsAllowed = false
    var guidedRun: GuidedRun? = null
    var guidedRunId: String? = null
    var guidedChoosingPod = false
    var requestMapsSnapshot: (() -> Unit)? = null
    var onMapsPost: ((MapsNotification) -> Unit)? = null
    var onMapsRemove: ((String) -> Unit)? = null
    var onMapsDisconnect: (() -> Unit)? = null
    val listeners = mutableSetOf<() -> Unit>()
    private val events = ArrayDeque<String>()
    fun status(text: String) { status = text; event(text) }
    fun devices(value: List<Pair<String, String>>) { devices = value; notifyChanged() }
    fun event(text: String) { events.addLast(text); while (events.size > 12) events.removeFirst(); notifyChanged() }
    fun eventText() = events.joinToString("\n")
    fun notifyChanged() { listeners.toList().forEach { it() } }
}
