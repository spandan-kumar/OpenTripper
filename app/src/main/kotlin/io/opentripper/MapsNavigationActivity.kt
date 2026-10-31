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
        val pad = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(this).apply { addView(content) }; setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 12); content.addView(this)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; content.addView(this) }
        button("Back") { finish() }
        text("Maps → your Pod", 28f)
        text("While parked: connect the Pod, open Google Maps and start a route. Return here to check the recognized direction before riding. English instructions only.")
        status = text("Ready to connect", 20f)
        instruction = text("Waiting for a direction", 23f)
        source = text("")
        text("Traffic side")
        side = Spinner(this).apply {
            contentDescription = "Maps traffic side"
            adapter = ArrayAdapter(this@MapsNavigationActivity, android.R.layout.simple_spinner_dropdown_item, DrivingSide.entries.map { it.label })
            setSelection(if (prefs.getString("driving_side", "LEFT") == "RIGHT") 1 else 0)
            content.addView(this)
        }
        start = button("Connect Pod & forward Maps") {
            pendingStart = true; continueStart()
        }
        candidates = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        pin = EditText(this).apply {
            hint = "PIN shown on Pod"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false; content.addView(this)
        }
        sendPin = button("Send PIN") { command("PIN") { putExtra("pin", pin.text.toString()) }; pin.text.clear() }
        button("Open Google Maps / choose destination") {
            val maps = packageManager.getLaunchIntentForPackage(MapsParser.PACKAGE)
            if (maps != null) startActivity(maps) else Toast.makeText(this, "Install Google Maps to use this mode", Toast.LENGTH_LONG).show()
        }
        button("Remember Pod — I saw the correct direction") { command("REMEMBER") }
        button("Forget saved Pod") { command("FORGET") }
        button("Stop navigation connection") { pendingStart = false; command("STOP") }
        button("Notification access settings") { openNotificationSettings() }
        text("OpenTripper reads Google Maps notifications. Android grants broad notification access; the app filters for Maps and saves no notification text or route history. No location or camera access is needed on Android 12+ for this mode.", 14f)
        text("Your Hunter test verified seven screen commands. Maps interpretation is still experimental. Roundabouts stay disabled here until separately tested. Missing or old directions stop screen refresh; the Pod may disconnect, so reconnect while parked.", 14f)
        pendingStart = savedInstanceState?.getBoolean("pendingStart") ?: false
        externalPending = savedInstanceState?.getBoolean("externalPending") ?: false
        render()
    }
    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); render() }
    override fun onResume() { super.onResume(); render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
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
        val request = Intent(this, PodService::class.java).setAction(action).apply(extras)
        try { if (action == "MAPS_START") startForegroundService(request) else startService(request) }
        catch (_: SecurityException) { status.text = "Bluetooth permission is needed" }
    }
    private fun render() {
        if (!::start.isInitialized) return
        val waitingPin = AppState.sessionState == PodSession.State.WAITING_PIN
        pin.visibility = if (waitingPin) View.VISIBLE else View.GONE; sendPin.visibility = pin.visibility
        start.isEnabled = !AppState.mapsConnecting
        side.isEnabled = AppState.onMapsPost == null || !prefs.getBoolean("forwarding", false)
        status.text = AppState.status
        val selectedSide = runCatching { DrivingSide.valueOf(prefs.getString("driving_side", "LEFT")!!) }.getOrDefault(DrivingSide.LEFT)
        instruction.text = AppState.navigation?.let { "${it.cue(selectedSide).label}\n${it.meters} m" } ?: "Waiting for a direction"
        source.text = when {
            !notificationAccess() -> "Setup needed: allow OpenTripper notification access."
            !AppState.observerConnected -> "Waiting for Android to connect the Maps notification reader."
            AppState.onMapsPost == null -> "Tap Connect Pod & forward Maps to start."
            else -> when (AppState.mapsStatus) {
                MapsRoute.Status.WAITING -> "Reader connected. Open Maps and start turn-by-turn navigation."
                MapsRoute.Status.RECOGNIZED -> "Maps instruction recognized. ${AppState.screenMessage}"
                MapsRoute.Status.UNSUPPORTED -> "Maps sent a notification, but its direction/distance could not be read. No arrow is guessed."
                MapsRoute.Status.EXPIRED -> "No fresh recognized direction for 15 seconds. Screen updates stopped."
                MapsRoute.Status.REMOVED -> "Maps removed the navigation notification. Screen updates stopped."
            }
        }
        val choices = AppState.devices.takeIf { AppState.mapsChoosingPod } ?: emptyList()
        if (choices != renderedCandidates) {
            renderedCandidates = choices.toList(); candidates.removeAllViews()
            choices.forEach { (address, _) -> candidates.addView(Button(this).apply {
                text = "Use Pod $address"; setOnClickListener { command("MAPS_CONNECT") { putExtra("address", address) } }
            }) }
        }
    }
    companion object { private const val PERMISSIONS = 41; private const val NOTIFICATION_ACCESS = 42; private const val ENABLE_BLUETOOTH = 43 }
}
