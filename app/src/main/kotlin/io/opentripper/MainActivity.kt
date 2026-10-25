package io.opentripper

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var navigation: TextView
    private lateinit var events: TextView
    private lateinit var devices: LinearLayout
    private lateinit var pin: EditText
    private lateinit var forwardingToggle: Switch
    private lateinit var roundaboutToggle: Switch
    private var updating = false
    private var renderedDevices: List<Pair<String, String>>? = null
    private val redraw: () -> Unit = { render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt(); setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(content) }; setContentView(scroll)
        // Respect system bars on Android 15's enforced edge-to-edge layout.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; content.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; content.addView(this) }
        text("OpenTripper", 30f)
        text("Experimental • Royal Enfield Tripper Pod (RE_DISP). Tripper Dash is unsupported.")
        status = text("Stopped", 19f)
        button("Start automatic Pod test") { startActivity(Intent(this, GuidedTestActivity::class.java).putExtra("start", true)) }
        button("Guided test, camera & reports") { startActivity(Intent(this, GuidedTestActivity::class.java)) }
        button("Maneuver lab & route preview") { startActivity(Intent(this, ManeuverLabActivity::class.java)) }
        button("Grant Bluetooth permissions") { requestBluetoothPermissions() }
        button("Enable Bluetooth") {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                requestBluetoothPermissions()
            else startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        button("Scan for Pod") { command("SCAN") }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        pin = EditText(this).apply {
            hint = "PIN shown on Pod (1–6 digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false; content.addView(this)
        }
        button("Send PIN") { command("PIN") { putExtra("pin", pin.text.toString()) }; pin.text.clear() }
        button("Remember Pod — I verified its display") { command("REMEMBER") }
        button("Forget saved Pod") { command("FORGET") }
        button("Sync phone time (24h)") { command("TIME") }
        text("Google Maps observer", 22f)
        text("English instructions only. Notification formats can change. Check the preview before enabling forwarding. " +
            "Unknown or stale text stops screen refresh; the Pod may disconnect after about 5 seconds. " +
            "Notification access is broad, but this app filters for Google Maps and keeps no notification history.")
        button("Open notification access settings") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button("Start Maps preview") { command("PREVIEW") }
        navigation = text("No recognized maneuver")
        forwardingToggle = Switch(this).apply {
            text = "Forward experimental Maps maneuvers"
            isChecked = getSharedPreferences("pod", MODE_PRIVATE).getBoolean("forwarding", false)
            setOnCheckedChangeListener { _, enabled -> if (!updating) command("FORWARD") { putExtra("enabled", enabled) } }
            content.addView(this)
        }
        roundaboutToggle = Switch(this).apply {
            text = "Allow experimental roundabout icons this session"
            isChecked = AppState.roundaboutsAllowed
            setOnCheckedChangeListener { _, enabled -> if (!updating) command("ROUNDABOUTS") { putExtra("enabled", enabled) } }
            content.addView(this)
        }
        button("Stop connection") { command("STOP") }
        events = text("")
    }
    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    private fun render() {
        status.text = AppState.status
        navigation.text = (if (AppState.observerConnected) "Observer connected. " else "Observer disconnected. ") +
            (AppState.navigation?.let { "${it.cue(io.opentripper.core.DrivingSide.LEFT).label} in ${it.meters} m" }
                ?: "No recognized maneuver; unsupported text never guesses an arrow") + "\n" + AppState.screenMessage
        updating = true
        forwardingToggle.isChecked = getSharedPreferences("pod", MODE_PRIVATE).getBoolean("forwarding", false)
        roundaboutToggle.isChecked = AppState.roundaboutsAllowed
        updating = false
        events.text = AppState.eventText()
        if (renderedDevices != AppState.devices) {
            renderedDevices = AppState.devices.toList(); devices.removeAllViews()
            AppState.devices.forEach { (address, name) ->
                devices.addView(Button(this).apply {
                    text = "Connect $name ($address)"
                    setOnClickListener { command("CONNECT") { putExtra("address", address) } }
                })
            }
        }
    }
    private fun requestBluetoothPermissions() {
        val list = if (Build.VERSION.SDK_INT >= 31)
            mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }
    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        if (action == "STOP" && AppState.onMapsPost == null) return
        if (action != "STOP" && !BleTransport.hasPermissions(this)) {
            Toast.makeText(this, "Grant Bluetooth permissions first, then retry", Toast.LENGTH_LONG).show(); return
        }
        val intent = Intent(this, PodService::class.java).setAction(action).apply(extras)
        if (action == "STOP") startService(intent) else startForegroundService(intent)
    }
}
