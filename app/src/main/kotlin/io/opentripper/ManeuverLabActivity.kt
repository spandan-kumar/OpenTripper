package io.opentripper

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
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
        content = AppUi.page(this, "Maneuver lab", "Try the Pod’s built-in screens while parked.")
        val session = AppUi.card(content)
        AppUi.label(session, "Pod connection")
        connection = text(session, "Connect your Pod to send a test", 16f)

        val previewCard = AppUi.card(content, dark = true)
        text(previewCard, "SCREEN REHEARSAL", 12f).apply {
            setTextColor(AppUi.color(this@ManeuverLabActivity, R.color.hero_muted)); letterSpacing = .12f
        }
        preview = text(previewCard, "", 30f).apply {
            gravity = Gravity.CENTER
            setTextColor(AppUi.color(this@ManeuverLabActivity, R.color.hero_ink))
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }
        text(previewCard, "Phone schematic · not the Pod’s actual rendering", 13f).apply {
            setTextColor(AppUi.color(this@ManeuverLabActivity, R.color.hero_muted))
        }
        description = text(content, "", 15f)

        AppUi.label(content, "Choose a test")
        val mode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; content.addView(this) }
        val manualMode = RadioButton(this).apply {
            id = View.generateViewId(); text = "Manual icons"; AppUi.styleToggle(this); mode.addView(this)
        }
        val structuredMode = RadioButton(this).apply {
            id = View.generateViewId(); text = "Route preview"; AppUi.styleToggle(this); mode.addView(this)
        }
        manual = panel(content)
        text(manual, "Build a maneuver", 22f)
        maneuver = spinner(manual, "Current maneuver", kinds.map { it.label })
        maneuver.setSelection(kinds.indexOf(Maneuver.FORK_RIGHT))
        exit = spinner(manual, "Roundabout exit", (1..9).map { "Exit $it" })
        next = spinner(manual, "Following maneuver", nextKinds.map { it?.label ?: "None / unknown" })
        side = spinner(manual, "Traffic side", DrivingSide.entries.map { it.label })
        side.setSelection(if (prefs.getString("driving_side", "LEFT") == "RIGHT") 1 else 0)

        routePanel = panel(content)
        text(routePanel, "Rehearse a route", 22f)
        text(routePanel, "Step through the junction demo or import an OSRM route. This is a manual rehearsal, without GPS guidance.", 14f)
        button(routePanel, "Load junction demo") { loadDemo(); updateSteps(); render() }
        button(routePanel, "Import OSRM JSON", quiet = true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            }, IMPORT_ROUTE)
        }
        routeDescription = text(routePanel, "", 14f)
        step = spinner(routePanel, "Selected route step", emptyList())
        val stepControls = AppUi.column(this, routePanel)
        button(stepControls, "Previous step", quiet = true) { step.setSelection((step.selectedItemPosition - 1).coerceAtLeast(0)) }
        button(stepControls, "Next step") {
            route?.let { step.setSelection((step.selectedItemPosition + 1).coerceAtMost(it.steps.lastIndex)) }
        }

        val appearance = AppUi.card(content)
        text(appearance, "Distance & appearance", 22f)
        distance = spinner(appearance, "Test distance before maneuver", distances.map { "$it m" })
        distance.setSelection(2)
        night = toggle(appearance, "Night mode")
        emphasis = toggle(appearance, "Approach emphasis")
        experimental = toggle(appearance, "Allow experimental screen commands")
        text(appearance, "Hunter photos confirm night mode, staged emphasis and exit numbers 1–9 for left-hand traffic. Roundabouts retain one common arrow shape. Experimental commands still require your opt-in.", 14f)

        send = button(content, "Send to Pod for 20 seconds", primary = true) { sendProbe() }
        button(content, "Stop screen test", quiet = true) {
            if (AppState.onMapsPost != null) startService(Intent(this, PodService::class.java).setAction("STOP_PROBE"))
        }
        text(content, "A test pauses Maps forwarding. Reconnect the Pod while parked if it returns to its clock afterward.", 14f)
        AppUi.disclosure(content, "Packet details") { details ->
            packet = text(details, "", 12f).apply { typeface = Typeface.MONOSPACE }
        }

        AppUi.label(content, "Save an observation")
        val evidence = AppUi.card(content)
        text(evidence, "What did the Pod show?", 22f)
        text(evidence, "After a confirmed write, record what you actually see. The app does not automatically grade the display.", 14f)
        firmware = EditText(this).apply {
            hint = "Pod firmware version (optional)"; inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true); setText(prefs.getString("lab_firmware", "")); evidence.addView(this); AppUi.styleInput(this)
        }
        notes = EditText(this).apply { hint = "Observation notes (optional)"; evidence.addView(this); AppUi.styleInput(this) }
        val recordRow = AppUi.column(this, evidence)
        listOf("Matches" to "matches", "Different" to "different", "Blank" to "blank").forEach { (label, outcome) ->
            recordButtons.add(button(recordRow, label) { record(outcome) })
        }
        observations = text(evidence, "", 14f)
        button(evidence, "Export observation report") {
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
        savedInstanceState?.let { saved ->
            listOf(maneuver, exit, next, side, distance).forEachIndexed { index, picker ->
                picker.setSelection(saved.getInt("picker-$index", picker.selectedItemPosition).coerceIn(0, (picker.count - 1).coerceAtLeast(0)))
            }
            night.isChecked = saved.getBoolean("night")
            emphasis.isChecked = saved.getBoolean("emphasis")
            experimental.isChecked = saved.getBoolean("experimental")
            firmware.setText(saved.getString("firmwareText", firmware.text.toString()))
            notes.setText(saved.getString("notesText", ""))
        }
        ready = true
        render()
    }

    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); if (ready) render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("routeMode", routeMode); outState.putInt("step", step.selectedItemPosition)
        listOf(maneuver, exit, next, side, distance).forEachIndexed { index, picker -> outState.putInt("picker-$index", picker.selectedItemPosition) }
        outState.putBoolean("night", night.isChecked); outState.putBoolean("emphasis", emphasis.isChecked)
        outState.putBoolean("experimental", experimental.isChecked)
        outState.putString("firmwareText", firmware.text.toString()); outState.putString("notesText", notes.text.toString())
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
        return JSONObject().put("schemaVersion", 1).put("appVersion", BuildConfig.VERSION_NAME)
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
    private fun panel(parent: LinearLayout) = AppUi.card(parent)
    private fun text(parent: LinearLayout, value: String, size: Float = 16f) =
        AppUi.text(parent, value, size, secondary = size <= 15f, bold = size >= 22f)
    private fun button(parent: LinearLayout, value: String, primary: Boolean = false, quiet: Boolean = false, action: () -> Unit) =
        AppUi.button(parent, value, primary = primary, quiet = quiet, action = action)
    private fun spinner(parent: LinearLayout, label: String, values: List<String>): Spinner {
        val title = text(parent, label, 14f)
        return Spinner(this).apply {
            id = View.generateViewId(); title.labelFor = id; contentDescription = label
            adapter = ArrayAdapter(this@ManeuverLabActivity, android.R.layout.simple_spinner_dropdown_item, values)
            parent.addView(this); AppUi.styleSpinner(this)
        }
    }
    private fun toggle(parent: LinearLayout, label: String) = Switch(this).apply {
        text = label; AppUi.styleToggle(this); parent.addView(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    companion object { private const val IMPORT_ROUTE = 10; private const val EXPORT_REPORT = 11 }
}
