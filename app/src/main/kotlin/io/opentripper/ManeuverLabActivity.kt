package io.opentripper

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import io.opentripper.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Offline, manual rehearsal. A preview is never presented as GPS navigation or a verified Pod rendering. */
class ManeuverLabActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pod", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private lateinit var manual: LinearLayout
    private lateinit var routePanel: LinearLayout
    private lateinit var maneuver: Spinner
    private lateinit var exit: Spinner
    private lateinit var next: Spinner
    private lateinit var side: Spinner
    private lateinit var distance: Spinner
    private lateinit var step: Spinner
    private lateinit var night: Switch
    private lateinit var emphasis: Switch
    private lateinit var experimental: Switch
    private lateinit var preview: TextView
    private lateinit var description: TextView
    private lateinit var packet: TextView
    private lateinit var connection: TextView
    private lateinit var routeDescription: TextView
    private lateinit var send: Button
    private lateinit var firmware: EditText
    private lateinit var notes: EditText
    private lateinit var observations: TextView
    private val recordButtons = mutableListOf<Button>()
    private val kinds = Maneuver.entries.toList()
    private val nextKinds = listOf<Maneuver?>(null) + kinds.filter { it != Maneuver.ROUNDABOUT }
    private val distances = listOf(1000, 500, 200, 100, 50, 20, 10, 0)
    private var route: OsrmRoute? = null
    private var routeName = "Bundled synthetic junctions"
    private var routeMode = false
    private var ready = false
    private val redraw: () -> Unit = { render() }
    private data class Retained(val route: OsrmRoute?, val name: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        button(content, "Back to connection") { finish() }
        text(content, "Maneuver lab", 30f)
        text(content, "Stationary tests and manual route rehearsal. No GPS guidance.")
        connection = text(content, "Connect your Pod to send a test", 16f)
        val mode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; content.addView(this) }
        val manualMode = RadioButton(this).apply { id = View.generateViewId(); text = "Manual icons"; mode.addView(this) }
        val structuredMode = RadioButton(this).apply { id = View.generateViewId(); text = "Route preview"; mode.addView(this) }

        preview = text(content, "", 28f).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = GradientDrawable().apply { setColor(Color.rgb(21, 30, 33)); cornerRadius = dp(24).toFloat() }
        }
        text(content, "Phone schematic · Pod graphics need a hardware check", 13f)
        description = text(content, "", 16f)

        manual = panel(content)
        maneuver = spinner(manual, "Current maneuver", kinds.map { it.label })
        maneuver.setSelection(kinds.indexOf(Maneuver.FORK_RIGHT))
        exit = spinner(manual, "Roundabout exit", (1..9).map { "Exit $it" })
        next = spinner(manual, "Following maneuver", nextKinds.map { it?.label ?: "None / unknown" })
        side = spinner(manual, "Traffic side", DrivingSide.entries.map { it.label })
        side.setSelection(if (prefs.getString("driving_side", "LEFT") == "RIGHT") 1 else 0)

        routePanel = panel(content)
        text(routePanel, "Structured route rehearsal", 22f)
        text(routePanel, "OSRM supplies maneuver types and exit numbers. Step through a route manually; distance below is a test value, not distance from your location.")
        button(routePanel, "Load junction demo") { loadDemo(); updateSteps(); render() }
        button(routePanel, "Import OSRM JSON") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            }, IMPORT_ROUTE)
        }
        routeDescription = text(routePanel, "")
        step = spinner(routePanel, "Selected route step", emptyList())
        val stepControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; routePanel.addView(this) }
        button(stepControls, "Previous") { step.setSelection((step.selectedItemPosition - 1).coerceAtLeast(0)) }
        button(stepControls, "Next step") {
            route?.let { step.setSelection((step.selectedItemPosition + 1).coerceAtMost(it.steps.lastIndex)) }
        }

        distance = spinner(content, "Test distance before maneuver", distances.map { "$it m" })
        distance.setSelection(2)
        night = toggle(content, "Night mode")
        emphasis = toggle(content, "Experimental approach emphasis")
        experimental = toggle(content, "Enable experimental icon tests")
        text(content, "Roundabout exit codes and approach emphasis still need hardware checks. Other icons also remain unverified on your particular Pod.", 14f)

        send = button(content, "Send to Pod for 20 seconds") { sendProbe() }
        button(content, "Stop screen test") {
            if (AppState.onMapsPost != null) startService(Intent(this, PodService::class.java).setAction("STOP_PROBE"))
        }
        text(content, "Sending a test pauses Maps forwarding. Ending it may let the Pod return to its clock by disconnecting; reconnect manually if needed.", 14f)
        packet = text(content, "", 12f).apply { typeface = Typeface.MONOSPACE; visibility = View.GONE }
        button(content, "Show / hide packet details") { packet.visibility = if (packet.visibility == View.GONE) View.VISIBLE else View.GONE }

        text(content, "What did your Pod show?", 22f)
        text(content, "Record an observation after Android confirms a test write. A successful write alone does not verify the icon.", 14f)
        firmware = EditText(this).apply {
            hint = "Pod firmware version (optional)"; inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true); setText(prefs.getString("lab_firmware", "")); content.addView(this)
        }
        notes = EditText(this).apply { hint = "Observation notes (optional)"; content.addView(this) }
        val recordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; content.addView(this) }
        listOf("Matches" to "matches", "Different" to "different", "Blank" to "blank").forEach { (label, outcome) ->
            recordButtons.add(button(recordRow, label) { record(outcome) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        observations = text(content, "")
        button(content, "Export observation report") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "OpenTripper-observations.json")
            }, EXPORT_REPORT)
        }
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? Retained
        if (retained != null) { route = retained.route; routeName = retained.name } else loadDemo()
        updateSteps()
        mode.setOnCheckedChangeListener { _, checked -> routeMode = checked == structuredMode.id; render() }
        mode.check(if (savedInstanceState?.getBoolean("routeMode") == true) structuredMode.id else manualMode.id)
        step.setSelection((savedInstanceState?.getInt("step") ?: 0).coerceIn(0, (route?.steps?.lastIndex ?: 0)))
        listOf(maneuver, exit, next, side, distance, step).forEach { picker ->
            picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (ready) {
                        prefs.edit().putString("driving_side", selectedSide().name).apply()
                        render()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        listOf(night, emphasis, experimental).forEach { it.setOnCheckedChangeListener { _, _ -> if (ready) render() } }
        ready = true
        render()
    }

    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); if (ready) render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("routeMode", routeMode); outState.putInt("step", step.selectedItemPosition)
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = Retained(route, routeName)

    private fun selectedSide() = DrivingSide.entries[side.selectedItemPosition.coerceAtLeast(0)]
    private fun selectedProbe(): PodProbe? {
        val meters = distances[distance.selectedItemPosition.coerceAtLeast(0)]
        return if (routeMode) {
            val steps = route?.steps ?: return null
            val index = step.selectedItemPosition.coerceAtLeast(0)
            val cue = steps.getOrNull(index)?.cue ?: return null
            PodProbe(cue, meters, steps.getOrNull(index + 1)?.cue, night.isChecked, emphasis.isChecked)
        } else {
            val kind = kinds[maneuver.selectedItemPosition.coerceAtLeast(0)]
            val cue = ManeuverCue(kind, if (kind == Maneuver.ROUNDABOUT) exit.selectedItemPosition + 1 else null, selectedSide())
            PodProbe(cue, meters, nextKinds[next.selectedItemPosition.coerceAtLeast(0)]?.let {
                ManeuverCue(it, drivingSide = selectedSide())
            }, night.isChecked, emphasis.isChecked)
        }
    }

    private fun render() {
        if (!ready) return
        manual.visibility = if (routeMode) View.GONE else View.VISIBLE
        routePanel.visibility = if (routeMode) View.VISIBLE else View.GONE
        exit.isEnabled = kinds[maneuver.selectedItemPosition.coerceAtLeast(0)] == Maneuver.ROUNDABOUT
        val test = selectedProbe()
        val selected = if (routeMode) route?.steps?.getOrNull(step.selectedItemPosition) else null
        connection.text = "${AppState.status}\n${AppState.screenMessage}"
        routeDescription.text = "$routeName\n${route?.steps?.size ?: 0} steps · first route alternative"
        if (test == null) {
            preview.text = "?\nUnsupported"
            description.text = selected?.unsupportedReason ?: "Import an OSRM route with steps=true"
            packet.text = "No screen packet is generated for this instruction"
        } else {
            preview.text = "${symbol(test.cue)}\n${test.cue.label}\n${test.meters} m"
            description.text = "${test.cue.drivingSide.label}\nFollowing: ${test.next?.label ?: "unknown / none"}" +
                (selected?.road?.takeIf { it.isNotBlank() }?.let { "\nRoad: $it" } ?: "") +
                (if (test.experimental) "\nExperimental output; compare against the physical Pod" else "")
            packet.text = test.packet().joinToString(" ") { "%02X".format(it.toInt() and 255) }
        }
        send.isEnabled = test != null && AppState.sessionState == PodSession.State.READY &&
            (!test.experimental || experimental.isChecked)
        recordButtons.forEach { it.isEnabled = test != null && test == AppState.completedProbe }
        observations.text = "${readObservations().length()} saved observations. Reports include your notes and phone model; no PIN, Pod address or route coordinates."
    }

    private fun sendProbe() {
        val test = selectedProbe() ?: return
        if (AppState.sessionState != PodSession.State.READY || !BleTransport.hasPermissions(this)) return
        startForegroundService(Intent(this, PodService::class.java).setAction("PROBE").apply {
            putExtra("maneuver", test.cue.maneuver.name); putExtra("exit", test.cue.exit ?: 0)
            putExtra("side", test.cue.drivingSide.name); putExtra("meters", test.meters)
            test.next?.let { putExtra("next", it.maneuver.name); putExtra("nextExit", it.exit ?: 0); putExtra("nextSide", it.drivingSide.name) }
            putExtra("night", test.night); putExtra("emphasis", test.approachEmphasis)
            putExtra("experimental", experimental.isChecked)
        })
    }

    private fun loadDemo() {
        route = OsrmRouteImporter.parse(assets.open("junction-demo.osrm.json").bufferedReader().use { it.readText() })
        routeName = "Bundled synthetic junctions · not a real route"
    }
    private fun updateSteps() {
        step.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            route?.steps?.mapIndexed { index, value -> "${index + 1}. ${value.label}" } ?: emptyList())
    }
    private fun readObservations() = runCatching { JSONObject(prefs.getString("observations", "{}")!!) }.getOrDefault(JSONObject())
    private fun record(outcome: String) {
        val test = selectedProbe()?.takeIf { it == AppState.completedProbe } ?: return
        val version = firmware.text.toString().trim().take(80)
        val record = JSONObject().put("at", Instant.now().toString()).put("firmware", version.ifBlank { "unknown" })
            .put("writeCompletedAt", AppState.completedProbeAt ?: JSONObject.NULL)
            .put("maneuver", test.cue.maneuver.name).put("exit", test.cue.exit ?: JSONObject.NULL)
            .put("drivingSide", test.cue.drivingSide.name).put("meters", test.meters)
            .put("nextManeuver", test.next?.maneuver?.name ?: JSONObject.NULL)
            .put("nextExit", test.next?.exit ?: JSONObject.NULL).put("nextDrivingSide", test.next?.drivingSide?.name ?: JSONObject.NULL)
            .put("night", test.night).put("approachEmphasis", test.approachEmphasis)
            .put("packetHex", test.packet().joinToString(" ") { "%02X".format(it.toInt() and 255) })
            .put("outcome", outcome).put("notes", notes.text.toString().take(1000))
        val all = readObservations()
        val key = "$version|${test.key}"
        if (!all.has(key) && all.length() >= 500) { toast("Report is full; export the existing observations first"); return }
        all.put(key, record)
        prefs.edit().putString("observations", all.toString()).putString("lab_firmware", version).apply()
        toast("Recorded: $outcome"); render()
    }
    private fun report(): String {
        val all = readObservations()
        val rows = JSONArray()
        all.keys().forEach { rows.put(all.getJSONObject(it)) }
        return JSONObject().put("schemaVersion", 1).put("appVersion", "0.5.0-experimental")
            .put("exportedAt", Instant.now().toString()).put("phoneModel", Build.MODEL)
            .put("androidApi", Build.VERSION.SDK_INT).put("evidence", "User-reported visual observations; not automatic hardware verification")
            .put("observations", rows).toString(2)
    }

    @Deprecated("Legacy Activity result API; native Activity has no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val export = if (requestCode == EXPORT_REPORT) report() else null
        val fallback = selectedSide()
        Thread {
            try {
                if (export != null) {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(export) }
                    runOnUiThread { if (!isDestroyed) toast("Observation report exported") }
                } else if (requestCode == IMPORT_ROUTE) {
                    val json = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= OsrmRouteImporter.MAX_BYTES) { "Route file must be under 1 MiB" }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    val imported = OsrmRouteImporter.parse(json, fallback)
                    runOnUiThread {
                        if (!isDestroyed) { route = imported; routeName = "Imported OSRM route · manual rehearsal"; updateSteps(); render() }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) toast(error.message?.take(200) ?: "Could not open file") }
            }
        }.start()
    }

    private fun symbol(cue: ManeuverCue): String = when (cue.maneuver) {
        Maneuver.ROUNDABOUT -> if (cue.drivingSide == DrivingSide.LEFT) "↻ ${cue.exit}" else "↺ ${cue.exit}"
        Maneuver.STRAIGHT, Maneuver.DEPART -> "↑"
        Maneuver.DESTINATION -> "⚑"
        Maneuver.U_TURN -> if (cue.drivingSide == DrivingSide.LEFT) "↷" else "↶"
        Maneuver.LEFT -> "↰"; Maneuver.RIGHT -> "↱"
        Maneuver.SHARP_LEFT -> "⤶"; Maneuver.SHARP_RIGHT -> "⤷"
        Maneuver.SLIGHT_LEFT -> "↖"; Maneuver.SLIGHT_RIGHT -> "↗"
        Maneuver.FORK_LEFT -> "Y ↖"; Maneuver.FORK_RIGHT -> "Y ↗"
        Maneuver.ON_RAMP_LEFT -> "↗ ↑"; Maneuver.ON_RAMP_RIGHT -> "↑ ↖"
        Maneuver.OFF_RAMP_LEFT -> "↖ ↑"; Maneuver.OFF_RAMP_RIGHT -> "↑ ↗"
        else -> if (cue.maneuver.name.endsWith("LEFT")) "←" else if (cue.maneuver.name.endsWith("RIGHT")) "→" else "↑"
    }
    private fun panel(parent: LinearLayout) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; parent.addView(this) }
    private fun text(parent: LinearLayout, value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setPadding(0, dp(8), 0, dp(8)); parent.addView(this)
    }
    private fun button(parent: LinearLayout, value: String, action: () -> Unit) = Button(this).apply {
        text = value; setOnClickListener { action() }; parent.addView(this)
    }
    private fun spinner(parent: LinearLayout, label: String, values: List<String>): Spinner {
        val title = text(parent, label, 14f)
        return Spinner(this).apply {
            id = View.generateViewId(); title.labelFor = id; contentDescription = label
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@ManeuverLabActivity, android.R.layout.simple_spinner_dropdown_item, values)
            parent.addView(this)
        }
    }
    private fun toggle(parent: LinearLayout, label: String) = Switch(this).apply { text = label; minHeight = dp(48); parent.addView(this) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    companion object { private const val IMPORT_ROUTE = 10; private const val EXPORT_REPORT = 11 }
}
