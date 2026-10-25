package io.opentripper

import io.opentripper.core.MapsManeuver
import io.opentripper.core.MapsNotification

/** In-process UI state, accessed on main looper only. No notification contents go to disk or network. */
object AppState {
    var status = "Stopped"; private set
    var devices: List<Pair<String, String>> = emptyList(); private set
    var navigation: MapsManeuver? = null
    var observerConnected = false
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
