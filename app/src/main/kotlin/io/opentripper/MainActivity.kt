package io.opentripper

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import io.opentripper.core.PodSession

/** Ride is the everyday entry point; device setup and stationary tools have their own destinations. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var rideStatus: TextView
    private lateinit var navigation: TextView
    private lateinit var events: TextView
    private lateinit var devices: LinearLayout
    private lateinit var pin: EditText
    private lateinit var pinButton: Button
    private lateinit var forwardingToggle: Switch
    private lateinit var roundaboutToggle: Switch
    private lateinit var pages: List<ScrollView>
    private val tabs = mutableListOf<Button>()
    private var selectedTab = 0
    private var updating = false
    private var renderedDevices: List<Pair<String, String>>? = null
    private val redraw: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = AppUi.column(this).apply { setBackgroundColor(AppUi.background(context)) }
        val host = FrameLayout(this); root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val contents = List(3) { AppUi.column(this, padding = 24) }
        pages = contents.map { AppUi.scroll(this, it).also { scroll -> host.addView(scroll, FrameLayout.LayoutParams(-1, -1)) } }
        buildRide(contents[0]); buildPod(contents[1]); buildTools(contents[2])
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8))
            background = AppUi.shape(context, AppUi.color(context, R.color.surface), 0)
        }
        listOf("Ride" to R.drawable.ic_route, "Pod" to R.drawable.ic_pod, "Tools" to R.drawable.ic_tools).forEachIndexed { index, item ->
            val b = Button(this).apply {
                text = item.first; isAllCaps = false; textSize = 12f; minHeight = dp(64)
                setPadding(dp(8), dp(8), dp(8), dp(8)); compoundDrawablePadding = dp(4)
                setCompoundDrawablesWithIntrinsicBounds(0, item.second, 0, 0); stateListAnimator = null
                setOnClickListener { selectTab(index) }
            }
            bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }); tabs.add(b)
        }
        root.addView(bar); setContentView(root); AppUi.insets(this, root)
        selectTab(savedInstanceState?.getInt("tab") ?: 0)
    }
    private fun buildRide(content: LinearLayout) {
        AppUi.label(content, "OPENTRIPPER  /  HUNTER 350")
        AppUi.text(content, "Make the next\nturn clearer.", 34f, bold = true)
        AppUi.text(content, "Your Maps directions. Your Tripper Pod.", 16f, secondary = true)
        val hero = AppUi.card(content, dark = true)
        rideStatus = AppUi.text(hero, "POD OFFLINE", 12f, bold = true).apply {
            letterSpacing = .12f; setTextColor(AppUi.color(context, R.color.hero_muted))
        }
        hero.addView(PodIllustration(this), LinearLayout.LayoutParams(-1, dp(174)))
        AppUi.text(hero, "Small display. Clearer signals.", 19f, bold = true).apply { setTextColor(AppUi.color(context, R.color.hero_ink)) }
        AppUi.text(hero, "Distinct turns, approach cues and night mode.\nPod illustration · open Maps for live directions.", 13f).apply {
            setTextColor(AppUi.color(context, R.color.hero_muted)); (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
        val primary = AppUi.button(content, "Start Maps navigation  →", primary = true) { open(MapsNavigationActivity::class.java) }
        val hint = AppUi.text(content, "Set up while parked. Live Maps forwarding is experimental.", 13f, secondary = true)
        // Keep the everyday action ahead of decorative content on short displays and at large text sizes.
        if (resources.configuration.screenHeightDp < 760 || resources.configuration.fontScale > 1.3f) {
            content.removeView(primary); content.removeView(hint)
            val position = content.indexOfChild(hero)
            content.addView(primary, position); content.addView(hint, position + 1)
        }
        AppUi.label(content, "BEFORE YOU HEAD OUT")
        AppUi.linkRow(content, "Your Pod", "Connection, pairing and the phone clock") { selectTab(1) }
        AppUi.linkRow(content, "Test your display", "Guided checks, photos and saved reports") { open(FieldTestActivity::class.java) }
    }
    private fun buildPod(content: LinearLayout) {
        AppUi.label(content, "YOUR HARDWARE")
        AppUi.text(content, "Meet your Pod.", 32f, bold = true)
        AppUi.text(content, "For the original small Tripper display. Look for RE_DISP when connecting.", 15f, secondary = true)
        val connection = AppUi.card(content)
        AppUi.label(connection, "CONNECTION")
        status = AppUi.text(connection, "Not connected", 23f, bold = true)
        AppUi.button(connection, "Find my Pod", primary = true) { command("SCAN") }
        devices = AppUi.column(this, connection)
        pin = EditText(this).apply {
            hint = "PIN shown on your Pod"; contentDescription = "Pairing PIN shown on Pod"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false; connection.addView(this); AppUi.styleInput(this)
        }
        pinButton = AppUi.button(connection, "Pair with this PIN") { command("PIN") { putExtra("pin", pin.text.toString()) }; pin.text.clear() }
        AppUi.text(connection, "Keep the ignition on. Choose your Pod and enter its displayed PIN if asked.", 14f, secondary = true)
        AppUi.label(content, "PHONE SETUP")
        AppUi.button(content, "Allow Bluetooth access") { requestBluetoothPermissions() }
        AppUi.button(content, "Turn on Bluetooth") {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) requestBluetoothPermissions()
            else startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        AppUi.label(content, "DEVICE ACTIONS")
        AppUi.button(content, "Sync phone time · 24 hour") { command("TIME") }
        AppUi.button(content, "Remember this Pod") { command("REMEMBER") }
        AppUi.text(content, "Remember it after checking that the correct Pod responded.", 13f, secondary = true)
        AppUi.disclosure(content, "More connection options") { more ->
            AppUi.button(more, "Forget saved Pod", quiet = true) { command("FORGET") }
            AppUi.button(more, "Disconnect Pod", quiet = true, danger = true) { command("STOP") }
        }
        AppUi.text(content, "Tripper Dash is a different device and is unsupported.", 13f, secondary = true)
    }
    private fun buildTools(content: LinearLayout) {
        AppUi.label(content, "THE WORKBENCH")
        AppUi.text(content, "Explore your display.", 32f, bold = true)
        AppUi.text(content, "Stationary tools for understanding what your Pod can show.", 15f, secondary = true)
        AppUi.linkRow(content, "One-visit test kit", "Choose checks, capture photos and share one report") { open(FieldTestActivity::class.java) }
        AppUi.linkRow(content, "Maneuver lab", "Try turn shapes and rehearse a sample route") { open(ManeuverLabActivity::class.java) }
        AppUi.linkRow(content, "Quick guided test", "Seven baseline screens, camera access and reports") { open(GuidedTestActivity::class.java) }
        AppUi.label(content, "MAPS DIAGNOSTICS")
        navigation = AppUi.text(content, "Waiting for Maps", 15f)
        AppUi.disclosure(content, "Advanced Maps controls") { advanced ->
            AppUi.text(advanced, "English directions only. Unknown or stale instructions stop updates. Notification access is broad; OpenTripper filters for Maps.", 14f, secondary = true)
            AppUi.button(advanced, "Notification access settings") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            AppUi.button(advanced, "Start notification preview") { command("PREVIEW") }
            forwardingToggle = Switch(this).apply {
                text = "Forward Maps directions"; AppUi.styleToggle(this); advanced.addView(this)
                setOnCheckedChangeListener { _, enabled -> if (!updating) command("FORWARD") { putExtra("enabled", enabled) } }
            }
            roundaboutToggle = Switch(this).apply {
                text = "Experimental roundabout numbers"; AppUi.styleToggle(this); advanced.addView(this)
                setOnCheckedChangeListener { _, enabled -> if (!updating) command("ROUNDABOUTS") { putExtra("enabled", enabled) } }
            }
            AppUi.text(advanced, "Roundabout exit numbers use one common arrow shape. This opt-in lasts for the current session.", 13f, secondary = true)
            AppUi.button(advanced, "Stop connection", quiet = true, danger = true) { command("STOP") }
        }
        AppUi.disclosure(content, "Connection log") { body -> events = AppUi.text(body, "", 12f).apply { typeface = Typeface.MONOSPACE } }
        AppUi.text(content, "OpenTripper · ${BuildConfig.VERSION_NAME}\nMade for the original Tripper Pod.", 12f, secondary = true)
    }
    private fun selectTab(index: Int) {
        selectedTab = index.coerceIn(0, 2)
        pages.forEachIndexed { i, view -> view.visibility = if (i == selectedTab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, button ->
            button.isSelected = i == selectedTab
            button.background = AppUi.shape(this, AppUi.color(this, if (i == selectedTab) R.color.accent else R.color.surface), 18)
            val fg = AppUi.color(this, if (i == selectedTab) R.color.on_accent else R.color.muted)
            button.setTextColor(fg); button.compoundDrawableTintList = ColorStateList.valueOf(fg)
        }
    }
    @Deprecated("Native Activity back callback")
    override fun onBackPressed() { if (selectedTab != 0) selectTab(0) else super.onBackPressed() }
    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("tab", selectedTab); super.onSaveInstanceState(out) }
    private fun render() {
        status.text = if (AppState.status == "Stopped") "Not connected" else AppState.status
        rideStatus.text = when (AppState.sessionState) {
            PodSession.State.READY -> "POD CONNECTED"
            PodSession.State.STOPPED, PodSession.State.FAILED -> "POD OFFLINE"
            else -> "CONNECTING TO POD"
        }
        val needsPin = AppState.sessionState == PodSession.State.WAITING_PIN
        pin.visibility = if (needsPin) View.VISIBLE else View.GONE; pinButton.visibility = pin.visibility
        val side = runCatching { io.opentripper.core.DrivingSide.valueOf(getSharedPreferences("pod", MODE_PRIVATE).getString("driving_side", "LEFT")!!) }.getOrDefault(io.opentripper.core.DrivingSide.LEFT)
        navigation.text = (if (AppState.observerConnected) "Maps reader connected" else "Maps reader not connected") + "\n" +
            (AppState.navigation?.let { "${it.cue(side).label} · ${it.meters} m" } ?: "No recognized direction") + "\n" + AppState.screenMessage
        updating = true
        forwardingToggle.isChecked = getSharedPreferences("pod", MODE_PRIVATE).getBoolean("forwarding", false)
        roundaboutToggle.isChecked = AppState.roundaboutsAllowed; updating = false
        events.text = AppState.eventText().ifBlank { "Connection events will appear here." }
        if (renderedDevices != AppState.devices) {
            renderedDevices = AppState.devices.toList(); devices.removeAllViews()
            AppState.devices.forEach { (address, name) -> AppUi.button(devices, "Connect $name\n$address") { command("CONNECT") { putExtra("address", address) } } }
        }
    }
    private fun requestBluetoothPermissions() {
        val list = if (Build.VERSION.SDK_INT >= 31) mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }
    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        if (action == "STOP" && AppState.onMapsPost == null) return
        if (action != "STOP" && !BleTransport.hasPermissions(this)) { requestBluetoothPermissions(); return }
        val request = Intent(this, PodService::class.java).setAction(action).apply(extras)
        if (action == "STOP") startService(request) else startForegroundService(request)
    }
    private fun open(type: Class<out Activity>) { startActivity(Intent(this, type)) }
    private fun dp(value: Int) = AppUi.dp(this, value)
}

/** Decorative phone illustration, never a live route or a claim about the Pod's current pixels. */
private class PodIllustration(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val turnPath = Path()
    private val arrowPath = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = minOf(width / 2f, height / 2f) - AppUi.dp(context, 8)
        val x = width / 2f; val y = height / 2f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = AppUi.dp(context, 1).toFloat(); paint.color = Color.rgb(88, 111, 95)
        canvas.drawCircle(x, y, r, paint)
        paint.strokeWidth = AppUi.dp(context, 6).toFloat(); paint.color = Color.rgb(46, 65, 54)
        canvas.drawCircle(x, y, r - AppUi.dp(context, 7), paint)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(19, 32, 26); canvas.drawCircle(x, y, r - AppUi.dp(context, 11), paint)
        val path = turnPath.apply {
            reset()
            moveTo(x + r * .23f, y + r * .35f); lineTo(x + r * .23f, y - r * .12f)
            quadTo(x + r * .23f, y - r * .38f, x - r * .04f, y - r * .38f)
            lineTo(x - r * .36f, y - r * .38f)
        }
        paint.color = AppUi.accent(context); paint.style = Paint.Style.STROKE; paint.strokeWidth = r * .16f; paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        canvas.drawPath(arrowPath.apply { reset(); moveTo(x - r * .14f, y - r * .65f); lineTo(x - r * .48f, y - r * .38f); lineTo(x - r * .14f, y - r * .11f); close() }, paint)
        paint.color = Color.rgb(166, 188, 170); paint.textAlign = Paint.Align.CENTER; paint.textSize = r * .13f; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText("TRIPPER POD", x, y + r * .65f, paint)
    }
}
