package io.opentripper

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import io.opentripper.core.DrivingSide
import io.opentripper.core.MapsParser
import io.opentripper.core.MapsRoute
import io.opentripper.core.PodSession

/** A deliberate entry into live Maps forwarding, with setup and source status in one place. */
class MapsNavigationActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var source: TextView
    private lateinit var start: Button
    private lateinit var pin: EditText
    private lateinit var sendPin: Button
    private lateinit var side: Spinner
    private lateinit var candidates: LinearLayout
    private var renderedCandidates: List<Pair<String, String>>? = null
    private var pendingStart = false
    private var externalPending = false
    private val redraw: () -> Unit = { render() }
    private val prefs by lazy { getSharedPreferences("pod", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = AppUi.page(this, "Maps to your Pod.", "Connect while parked, choose a route, then check your first direction.")
        if (intent.getBooleanExtra("field", false)) {
            AppUi.button(content, "Back to all checks", quiet = true) { finish() }
        }
        val connection = AppUi.card(content)
        AppUi.label(connection, "01  /  CONNECT")
        status = AppUi.text(connection, "Ready to connect", 21f, bold = true)
        start = AppUi.button(connection, "Connect my Pod", primary = true) {
            pendingStart = true; continueStart()
        }
        candidates = AppUi.column(this, connection)
        pin = EditText(this).apply {
            hint = "PIN shown on Pod"; contentDescription = "Pairing PIN shown on Pod"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false; connection.addView(this); AppUi.styleInput(this)
        }
        sendPin = AppUi.button(connection, "Pair with this PIN") { command("PIN") { putExtra("pin", pin.text.toString()) }; pin.text.clear() }
        AppUi.text(connection, "Keep the ignition on. Bluetooth and Maps notification access are requested when you connect.", 14f, secondary = true)
        val route = AppUi.card(content)
        AppUi.label(route, "02  /  CHOOSE YOUR ROUTE")
        AppUi.text(route, "Where are you heading?", 22f, bold = true)
        AppUi.text(route, "Start turn-by-turn navigation in Google Maps, then return here.", 14f, secondary = true)
        AppUi.button(route, "Open Google Maps  ↗") {
            val maps = packageManager.getLaunchIntentForPackage(MapsParser.PACKAGE)
            if (maps != null) startActivity(maps) else Toast.makeText(this, "Install Google Maps to use this mode", Toast.LENGTH_LONG).show()
        }
        val direction = AppUi.card(content, dark = true)
        AppUi.label(direction, "03  /  YOUR DIRECTION").setTextColor(AppUi.color(this, R.color.hero_muted))
        instruction = AppUi.text(direction, "Waiting for a direction", 29f, bold = true).apply {
            setTextColor(AppUi.color(context, R.color.hero_ink))
        }
        source = AppUi.text(direction, "", 14f).apply { setTextColor(AppUi.color(context, R.color.hero_muted)) }
        AppUi.text(content, "English directions only · experimental forwarding", 13f, secondary = true)
        AppUi.label(content, "TRAFFIC SIDE")
        side = Spinner(this).apply {
            contentDescription = "Maps traffic side"
            adapter = ArrayAdapter(this@MapsNavigationActivity, android.R.layout.simple_spinner_dropdown_item, DrivingSide.entries.map { it.label })
            setSelection(savedInstanceState?.getInt("selectedSide") ?: if (prefs.getString("driving_side", "LEFT") == "RIGHT") 1 else 0)
            content.addView(this); AppUi.styleSpinner(this)
        }
        AppUi.button(content, "Remember this Pod", quiet = true) { command("REMEMBER") }
        AppUi.text(content, "Remember it once you have checked the direction on the physical display.", 13f, secondary = true)
        AppUi.button(content, "Stop navigation", quiet = true, danger = true) { pendingStart = false; command("STOP") }
        AppUi.disclosure(content, "Access, privacy & connection options") { options ->
            AppUi.button(options, "Notification access settings") { openNotificationSettings() }
            AppUi.button(options, "Forget saved Pod", quiet = true) { command("FORGET") }
            AppUi.text(options, "Android grants broad notification access; OpenTripper filters for Google Maps. Normal navigation saves no notification text. The test kit saves wording only if you opt in.", 14f, secondary = true)
            AppUi.text(options, "Turn shapes and display modes have been photographed on your Hunter. Live Maps interpretation still needs validation. Roundabouts stay off in this mode. Old or unreadable directions stop screen updates; reconnect while parked if the Pod disconnects.", 14f, secondary = true)
        }
        pendingStart = savedInstanceState?.getBoolean("pendingStart") ?: false
        externalPending = savedInstanceState?.getBoolean("externalPending") ?: false
        render()
    }
    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); render() }
    override fun onResume() { super.onResume(); render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedSide", side.selectedItemPosition)
        outState.putBoolean("pendingStart", pendingStart); outState.putBoolean("externalPending", externalPending)
        super.onSaveInstanceState(outState)
    }
    private fun notificationAccess(): Boolean = if (Build.VERSION.SDK_INT >= 27)
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, MapsObserver::class.java))
    else androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    private fun openNotificationSettings() {
        externalPending = true
        startActivityForResult(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), NOTIFICATION_ACCESS)
    }
    private fun continueStart(requestNotifications: Boolean = true) {
        if (!pendingStart || externalPending) return
        val required = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (requestNotifications && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            externalPending = true; requestPermissions(missing.toTypedArray(), PERMISSIONS); return
        }
        if (!notificationAccess()) { openNotificationSettings(); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { pendingStart = false; status.text = "Bluetooth is unavailable"; return }
        try {
            if (!adapter.isEnabled) {
                externalPending = true; startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH); return
            }
        } catch (_: SecurityException) { pendingStart = false; status.text = "Bluetooth permission is needed"; return }
        prefs.edit().putString("driving_side", DrivingSide.entries[side.selectedItemPosition.coerceAtLeast(0)].name).apply()
        pendingStart = false
        // Roundabouts require their own physical validation and remain opt-in in the lab.
        command("MAPS_START")
    }
    @Deprecated("Native Activity permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS) return
        externalPending = false
        if (!BleTransport.hasPermissions(this)) {
            pendingStart = false; status.text = "Allow Bluetooth permission, then tap Connect again"; return
        }
        // Notification permission is optional; do not repeatedly request it after refusal.
        continueStart(requestNotifications = false)
    }
    @Deprecated("Native Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        externalPending = false
        when (requestCode) {
            NOTIFICATION_ACCESS -> if (notificationAccess()) continueStart(requestNotifications = false) else {
                pendingStart = false; status.text = "Enable OpenTripper notification access to read Maps directions"
            }
            ENABLE_BLUETOOTH -> if (resultCode == RESULT_OK) continueStart(requestNotifications = false) else {
                pendingStart = false; status.text = "Enable Bluetooth, then tap Connect"
            }
        }
    }
    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        if (action != "MAPS_START" && AppState.onMapsPost == null) return
        val actual = if (action == "MAPS_START" && intent.getBooleanExtra("field", false)) "FIELD_MAPS" else action
        if (actual == "FIELD_MAPS" && AppState.fieldSessionId == null) { status.text = "Return to the test kit and continue the saved session"; return }
        val request = Intent(this, PodService::class.java).setAction(actual).apply(extras)
        try { if (action == "MAPS_START") startForegroundService(request) else startService(request) }
        catch (_: SecurityException) { status.text = "Bluetooth permission is needed" }
    }
    private fun render() {
        if (!::start.isInitialized) return
        val waitingPin = AppState.sessionState == PodSession.State.WAITING_PIN
        pin.visibility = if (waitingPin) View.VISIBLE else View.GONE; sendPin.visibility = pin.visibility
        start.isEnabled = !AppState.mapsConnecting
        side.isEnabled = AppState.onMapsPost == null || !prefs.getBoolean("forwarding", false)
        status.text = if (AppState.sessionState == PodSession.State.STOPPED && AppState.onMapsPost == null) "Ready to connect" else AppState.status
        start.text = if (AppState.mapsConnecting) "Connecting…" else if (AppState.sessionState == PodSession.State.READY) "Reconnect my Pod" else "Connect my Pod"
        val selectedSide = runCatching { DrivingSide.valueOf(prefs.getString("driving_side", "LEFT")!!) }.getOrDefault(DrivingSide.LEFT)
        instruction.text = AppState.navigation?.let { "${it.cue(selectedSide).label}\n${it.meters} m" } ?: "Waiting for a direction"
        source.text = when {
            !notificationAccess() -> "Setup needed: allow OpenTripper notification access."
            !AppState.observerConnected -> "Waiting for Android to connect the Maps notification reader."
            AppState.onMapsPost == null -> "Tap Connect my Pod to start."
            else -> when (AppState.mapsStatus) {
                MapsRoute.Status.WAITING -> "Reader connected. Open Maps and start turn-by-turn navigation. ${AppState.screenMessage}"
                MapsRoute.Status.RECOGNIZED -> "Maps instruction recognized. ${AppState.screenMessage}"
                MapsRoute.Status.UNSUPPORTED -> "Maps sent a notification, but its direction/distance could not be read. No arrow is guessed."
                MapsRoute.Status.EXPIRED -> "No fresh recognized direction for 15 seconds. Screen updates stopped."
                MapsRoute.Status.REMOVED -> "Maps removed the navigation notification. Screen updates stopped."
            }
        }
        val choices = AppState.devices.takeIf { AppState.mapsChoosingPod } ?: emptyList()
        if (choices != renderedCandidates) {
            renderedCandidates = choices.toList(); candidates.removeAllViews()
            choices.forEach { (address, _) -> AppUi.button(candidates, "Use Pod $address") { command("MAPS_CONNECT") { putExtra("address", address) } } }
        }
    }
    companion object { private const val PERMISSIONS = 41; private const val NOTIFICATION_ACCESS = 42; private const val ENABLE_BLUETOOTH = 43 }
}
