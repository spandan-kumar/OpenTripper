package io.opentripper

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.content.FileProvider
import io.opentripper.core.*
import java.util.UUID

/** One installation, one outing, individually retryable groups, one evidence ZIP. */
class FieldTestActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var selection: TextView
    private lateinit var selectionDetail: TextView
    private lateinit var configuration: LinearLayout
    private lateinit var savedSession: LinearLayout
    private lateinit var groups: LinearLayout
    private lateinit var start: Button
    private lateinit var continueButton: Button
    private lateinit var photo: Button
    private lateinit var skip: Button
    private lateinit var stopChecks: Button
    private lateinit var finishButton: Button
    private lateinit var share: Button
    private lateinit var maps: Button
    private lateinit var livePhoto: Button
    private lateinit var pin: EditText
    private lateinit var sendPin: Button
    private lateinit var textOptIn: CheckBox
    private lateinit var side: Spinner
    private lateinit var retryGroup: Spinner
    private lateinit var retry: Button
    private lateinit var devices: LinearLayout
    private val boxes = linkedMapOf<FieldPlan.Group, CheckBox>()
    private val store by lazy { FieldReportStore(this) }
    private val prefs by lazy { getSharedPreferences("pod", MODE_PRIVATE) }
    private var pending: Intent? = null
    private var choices: List<Pair<String, String>>? = null
    private var busySharing = false
    private var openMapsWhenReady = false
    private var lastPhotoPrompt = ""
    private val redraw: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val content = AppUi.page(this, "Pod check kit", "One session. Every screen. One report.")
        val session = AppUi.card(content)
        AppUi.label(session, "Current session")
        status = AppUi.text(session, "Ready", 24f, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(AppUi.ink(context))
            session.addView(this, LinearLayout.LayoutParams(-1, AppUi.dp(context, 8)).apply { bottomMargin = AppUi.dp(context, 16) })
        }
        detail = AppUi.text(session, "", 15f, secondary = true)
        photo = AppUi.button(session, "Photograph this screen", primary = true) { openCheckPhoto() }
        skip = AppUi.button(session, "Skip this photo", quiet = true) { AppState.guidedRun?.current?.let { step -> command(Intent().setAction("GUIDE_SKIP").putExtra("run", AppState.guidedRunId).putExtra("step", step.id)) } }
        pin = EditText(this).apply {
            hint = "PIN shown on Pod"; contentDescription = "PIN shown on Pod"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false; AppUi.styleInput(this); session.addView(this)
        }
        sendPin = AppUi.button(session, "Pair with Pod", primary = true) { command(Intent().setAction("PIN").putExtra("pin", pin.text.toString())); pin.text.clear() }
        devices = AppUi.column(this, session)
        stopChecks = AppUi.button(session, "Stop checks & save progress", quiet = true, danger = true) { command(Intent().setAction("GUIDE_CANCEL")) }
        livePhoto = AppUi.button(session, "Photograph live navigation", primary = true) {
            val id = AppState.fieldSessionId ?: return@button
            val token = "maps-live-${UUID.randomUUID()}"
            AppState.fieldLivePhotoName = "photo-$token.jpg"
            startActivityForResult(Intent(this, PodCameraActivity::class.java).putExtra("live", true).putExtra("run", id).putExtra("step", token), LIVE_PHOTO)
        }

        configuration = AppUi.card(content)
        AppUi.label(configuration, "Start a new check")
        selection = AppUi.text(configuration, "33 photos · 4 groups", 21f, bold = true)
        selectionDetail = AppUi.text(configuration, "", 14f, secondary = true)
        start = AppUi.button(configuration, "Start selected checks", primary = true) {
            val selected = boxes.filterValues { it.isChecked }.keys
            setup(Intent().setAction("FIELD_START").putStringArrayListExtra("groups", ArrayList(selected.map { it.name })).putExtra("includeText", textOptIn.isChecked))
        }
        AppUi.disclosure(configuration, "Choose checks & preferences") { options ->
            groups = AppUi.column(this, options)
            FieldPlan.Group.entries.forEach { group -> boxes[group] = CheckBox(this).apply {
                text = "${group.label} · ${FieldPlan.steps(listOf(group), DrivingSide.LEFT).size} photos"
                id = when (group) {
                    FieldPlan.Group.ROUNDABOUTS -> R.id.field_group_roundabouts
                    FieldPlan.Group.JUNCTIONS -> R.id.field_group_junctions
                    FieldPlan.Group.DISPLAY -> R.id.field_group_display
                    FieldPlan.Group.MAPS_REPLAY -> R.id.field_group_maps_replay
                    FieldPlan.Group.BASELINE -> R.id.field_group_baseline
                }
                isChecked = group in FieldPlan.recommended; AppUi.styleToggle(this); groups.addView(this)
                setOnCheckedChangeListener { _, _ -> updateSelection() }
            } }
            AppUi.label(options, "Traffic side")
            side = Spinner(this).apply {
                id = R.id.field_side; contentDescription = "Field test traffic side"
                adapter = ArrayAdapter(this@FieldTestActivity, android.R.layout.simple_spinner_dropdown_item, DrivingSide.entries.map { it.label })
                setSelection(if (prefs.getString("driving_side", "LEFT") == "RIGHT") 1 else 0)
                AppUi.styleSpinner(this); options.addView(this)
            }
            textOptIn = CheckBox(this).apply {
                text = "Save Maps wording for debugging"
                contentDescription = "Save Maps instruction text for debugging. May include street and place names."
                id = R.id.field_text_optin; AppUi.styleToggle(this); options.addView(this)
            }
            AppUi.text(options, "Optional wording may contain street or place names. It stays on this phone and is included when you share the report.", 13f, secondary = true)
        }

        savedSession = AppUi.card(content)
        AppUi.label(savedSession, "Saved evidence")
        AppUi.text(savedSession, "Continue where you left off", 21f, bold = true)
        AppUi.text(savedSession, "Earlier attempts and photos stay in the same bundle.", 14f, secondary = true)
        share = AppUi.button(savedSession, "Share report & photos", primary = true) { shareReport() }
        continueButton = AppUi.button(savedSession, "Continue missing photos") {
            store.latestId()?.let { setup(Intent().setAction("FIELD_CONTINUE").putExtra("suite", it)) }
        }
        maps = AppUi.button(savedSession, "Continue with live Google Maps") {
            val id = store.latestId() ?: return@button
            openMapsWhenReady = true
            if (AppState.fieldSessionId != id) setup(Intent().setAction("FIELD_CONTINUE").putExtra("suite", id).putExtra("liveOnly", true))
            else command(Intent().setAction("FIELD_MAPS"))
        }
        finishButton = AppUi.button(savedSession, "Finish session & save", quiet = true) { command(Intent().setAction("FIELD_FINISH")) }
        AppUi.disclosure(savedSession, "Retry a group") { retryOptions ->
            retryGroup = Spinner(this).apply {
                id = R.id.field_retry; contentDescription = "Retry field test group"
                adapter = ArrayAdapter(this@FieldTestActivity, android.R.layout.simple_spinner_dropdown_item, FieldPlan.Group.entries.map { it.label })
                AppUi.styleSpinner(this); retryOptions.addView(this)
            }
            retry = AppUi.button(retryOptions, "Retry selected group") {
                store.latestId()?.let { setup(Intent().setAction("FIELD_RETRY").putExtra("suite", it).putExtra("group", FieldPlan.Group.entries[retryGroup.selectedItemPosition].name)) }
            }
        }
        AppUi.disclosure(content, "About this check kit") { help ->
            AppUi.text(help, "Accept each requested photo to advance. You can stop and continue missing photos later. Photos and reports stay on this phone until you choose Share.", 14f, secondary = true)
            AppUi.text(help, "Maps sample replay uses example directions. Live Maps needs a real route. Completing a run saves evidence; it does not automatically judge the display.", 14f, secondary = true)
            AppUi.button(help, "Remember Pod — I saw it respond") { command(Intent().setAction("REMEMBER")) }
        }
        @Suppress("DEPRECATION")
        pending = savedInstanceState?.getParcelable("pending")
        openMapsWhenReady = savedInstanceState?.getBoolean("openMapsWhenReady") ?: false
        updateSelection()
        render()
    }
    private fun updateSelection() {
        val selected = boxes.filterValues { it.isChecked }.keys.toList()
        val steps = FieldPlan.steps(selected, DrivingSide.LEFT)
        selection.text = "${steps.size} photos · ${selected.size} groups"
        selectionDetail.text = "Park the Hunter and turn the ignition on. " +
            (if (steps.any { it.probe.experimental }) "Includes experimental roundabout or approach checks. " else "") +
            "Allow 10–15 minutes for the full kit."
        if (::start.isInitialized) start.isEnabled = selected.isNotEmpty() && AppState.fieldSessionId == null && AppState.guidedRun?.terminal != false
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("pending", pending); outState.putBoolean("openMapsWhenReady", openMapsWhenReady)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    private fun setup(request: Intent) {
        pending = request
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) needed.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        else needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS) else launchPending()
    }
    private fun launchPending() {
        val request = pending ?: return
        if (!BleTransport.hasPermissions(this) || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pending = null; status.text = "Allow camera and Bluetooth permissions, then tap Start or Continue again"; return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { pending = null; status.text = "Bluetooth is unavailable on this phone"; return }
        try {
            if (!adapter.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH); return }
        } catch (_: SecurityException) { pending = null; status.text = "Allow Bluetooth access and try again"; return }
        pending = null
        prefs.edit().putString("driving_side", DrivingSide.entries[side.selectedItemPosition.coerceAtLeast(0)].name).apply()
        command(request)
    }
    @Deprecated("Native Activity permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS) launchPending()
    }
    private fun command(request: Intent) {
        request.setClass(this, PodService::class.java)
        try { if (AppState.onMapsPost == null) startForegroundService(request) else startService(request) }
        catch (_: SecurityException) { status.text = "Bluetooth permission or an enabled Bluetooth connection is needed" }
    }
    private fun render() {
        if (!::share.isInitialized) return
        val report = store.latestForDisplay()
        val own = AppState.fieldSessionId != null
        val run = AppState.guidedRun.takeIf { own }
        val activeChecks = run?.terminal == false
        val live = own && AppState.fieldPhase == "LIVE_MAPS"
        status.text = when {
            activeChecks -> "Screen ${run!!.index + 1} of ${run.steps.size} · " + when (run.phase) {
                GuidedRun.Phase.CONNECTING -> "Connecting"
                GuidedRun.Phase.SENDING -> "Sending"
                GuidedRun.Phase.SETTLING -> "Hold steady"
                GuidedRun.Phase.PHOTO_REQUIRED -> "Photo needed"
                else -> "In progress"
            }
            live -> "Live Maps check · ${AppState.status}"
            own -> "Partial checks saved · retry a group or continue with Maps"
            report != null -> "Saved session · ${report.optString("phase").lowercase()}"
            else -> "Ready when you are"
        }
        progress.visibility = if (run != null) View.VISIBLE else View.GONE
        progress.max = run?.steps?.size ?: 33
        progress.progress = run?.index ?: 0
        progress.contentDescription = "${progress.progress} of ${progress.max} screen checks completed"
        configuration.visibility = if (own) View.GONE else View.VISIBLE
        savedSession.visibility = if (report != null || own) View.VISIBLE else View.GONE
        detail.text = if (activeChecks) "${run!!.current?.title}\n${AppState.status}" else if (live)
            "${AppState.navigation?.let { "${it.maneuver.label} · ${it.meters} m" } ?: "Open Maps and start a route while parked"}\n${AppState.mapsStatus} · ${AppState.screenMessage}"
        else "${report?.optJSONArray("runs")?.length() ?: 0} saved screen runs · ${report?.optJSONArray("livePhotos")?.length() ?: 0} live photos. Earlier attempts stay in the bundle."
        photo.visibility = if (activeChecks && run?.phase == GuidedRun.Phase.PHOTO_REQUIRED) View.VISIBLE else View.GONE
        if (photo.visibility == View.VISIBLE) {
            val key = "${AppState.guidedRunId}:${run?.index}"
            if (lastPhotoPrompt != key) { lastPhotoPrompt = key; photo.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
        }
        skip.visibility = photo.visibility; stopChecks.visibility = if (activeChecks) View.VISIBLE else View.GONE
        val waiting = own && AppState.sessionState == PodSession.State.WAITING_PIN
        pin.visibility = if (waiting) View.VISIBLE else View.GONE; sendPin.visibility = pin.visibility
        start.isEnabled = !own && AppState.guidedRun?.terminal != false && boxes.values.any { it.isChecked }
        boxes.values.forEach { it.isEnabled = !own }; side.isEnabled = !own; textOptIn.isEnabled = !own
        continueButton.isEnabled = report != null && !activeChecks
        retry.isEnabled = report != null && !activeChecks
        maps.isEnabled = report != null && !activeChecks
        livePhoto.visibility = if (live) View.VISIBLE else View.GONE
        livePhoto.isEnabled = (report?.optJSONArray("livePhotos")?.length() ?: 0) < 20
        finishButton.isEnabled = own
        share.isEnabled = report != null && !own && !busySharing
        if (live && openMapsWhenReady) {
            openMapsWhenReady = false
            startActivity(Intent(this, MapsNavigationActivity::class.java).putExtra("field", true))
        }
        val available = AppState.devices.takeIf { own && (AppState.guidedChoosingPod || AppState.mapsChoosingPod) } ?: emptyList()
        if (available != choices) {
            choices = available.toList(); devices.removeAllViews()
            available.forEach { (address, _) -> AppUi.button(devices, "Use Pod $address") {
                command(Intent().setAction(if (AppState.guidedChoosingPod) "GUIDE_CONNECT" else "MAPS_CONNECT").putExtra("address", address))
            } }
        }
    }
    private fun openCheckPhoto() {
        val run = AppState.guidedRun ?: return; val step = run.current ?: return
        startActivityForResult(Intent(this, PodCameraActivity::class.java).putExtra("run", AppState.guidedRunId).putExtra("step", step.id).putExtra("title", step.title), CHECK_PHOTO)
    }
    @Deprecated("Native Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) launchPending() else { pending = null; status.text = "Enable Bluetooth, then start or continue" }
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == CHECK_PHOTO) command(Intent().setAction("GUIDE_PHOTO").putExtra("run", data.getStringExtra("run"))
            .putExtra("step", data.getStringExtra("step")).putExtra("photo", data.getStringExtra("photo")))
        if (requestCode == LIVE_PHOTO) command(Intent().setAction("FIELD_PHOTO").putExtra("suite", data.getStringExtra("run"))
            .putExtra("photo", data.getStringExtra("photo")).putExtra("capturedAt", data.getStringExtra("capturedAt")))
    }
    private fun shareReport() {
        val id = store.latestId() ?: return
        if (AppState.fieldSessionId != null) return
        busySharing = true; render()
        Thread {
            try {
                val uri = FileProvider.getUriForFile(this, "io.opentripper.reports", store.archive(id))
                runOnUiThread { if (!isDestroyed) {
                    busySharing = false; render()
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .apply { clipData = ClipData.newRawUri("Field test evidence", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share all Pod checks"))
                } }
            } catch (_: Exception) { runOnUiThread { if (!isDestroyed) { busySharing = false; render(); status.text = "Report could not be prepared; check storage" } } }
        }.start()
    }
    companion object { private const val PERMISSIONS = 51; private const val CHECK_PHOTO = 52; private const val LIVE_PHOTO = 53; private const val ENABLE_BLUETOOTH = 54 }
}
