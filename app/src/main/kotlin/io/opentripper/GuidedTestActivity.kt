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
import android.provider.Settings
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.content.FileProvider
import io.opentripper.core.DrivingSide
import io.opentripper.core.GuidedRun

class GuidedTestActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var progress: ProgressBar
    private lateinit var start: Button
    private lateinit var photo: Button
    private lateinit var skip: Button
    private lateinit var share: Button
    private lateinit var stop: Button
    private lateinit var pin: EditText
    private lateinit var pinButton: Button
    private lateinit var experimental: Switch
    private lateinit var side: Spinner
    private lateinit var candidates: LinearLayout
    private lateinit var configuration: LinearLayout
    private var renderedCandidates: List<Pair<String, String>>? = null
    private var pendingStart = false
    private var autoStart = false
    private var lastPhotoPrompt = ""
    private val prefs by lazy { getSharedPreferences("pod", MODE_PRIVATE) }
    private val reports by lazy { GuidedReportStore(this) }
    private val redraw: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val content = AppUi.page(this, "Quick Pod check", "Seven familiar turns, photographed one at a time.")
        val session = AppUi.card(content)
        AppUi.label(session, "Current check")
        status = AppUi.text(session, "Ready", 24f, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(AppUi.ink(context))
            session.addView(this, LinearLayout.LayoutParams(-1, AppUi.dp(context, 8)).apply { bottomMargin = AppUi.dp(context, 16) })
        }
        instruction = AppUi.text(session, "Park the Hunter and turn the ignition on. Allow 2–4 minutes. First-time pairing needs the PIN shown on your Pod.", 16f, secondary = true)
        photo = AppUi.button(session, "Photograph the Pod", primary = true) { openPhoto() }
        skip = AppUi.button(session, "Skip this photo", quiet = true) {
            AppState.guidedRun?.current?.let { step -> command("GUIDE_SKIP") {
                putExtra("run", AppState.guidedRunId); putExtra("step", step.id)
            } }
        }
        pin = EditText(this).apply {
            hint = "PIN shown on Pod"; contentDescription = "PIN shown on Pod"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false; AppUi.styleInput(this); session.addView(this)
        }
        pinButton = AppUi.button(session, "Pair & continue", primary = true) {
            command("PIN") { putExtra("pin", pin.text.toString()) }; pin.text.clear()
        }
        candidates = AppUi.column(this, session)
        stop = AppUi.button(session, "Stop & save progress", quiet = true, danger = true) { command("GUIDE_CANCEL") }

        configuration = AppUi.card(content)
        AppUi.label(configuration, "Before you start")
        AppUi.text(configuration, "A photo at every turn", 21f, bold = true)
        AppUi.text(configuration, "The app holds a direction on the Pod until you accept its photo. Keep the bike parked throughout.", 14f, secondary = true)
        AppUi.label(configuration, "Traffic side")
        side = Spinner(this).apply {
            contentDescription = "Guided test traffic side"
            adapter = ArrayAdapter(this@GuidedTestActivity, android.R.layout.simple_spinner_dropdown_item, DrivingSide.entries.map { it.label })
            setSelection(savedInstanceState?.getInt("selectedSide") ?: if (prefs.getString("driving_side", "LEFT") == "RIGHT") 1 else 0)
            AppUi.styleSpinner(this); configuration.addView(this)
        }
        experimental = Switch(this).apply {
            text = "Add experimental checks"; contentDescription = "Add experimental roundabout and approach-highlighting checks"
            isChecked = savedInstanceState?.getBoolean("experimentalChecks") ?: false
            AppUi.styleToggle(this); configuration.addView(this)
        }
        AppUi.text(configuration, "Adds roundabout exits 1–3 and approach highlighting: 11 photos in total.", 13f, secondary = true)
        start = AppUi.button(configuration, "Start quick check", primary = true) { startPressed() }
        AppUi.button(configuration, "Check camera permission", quiet = true) {
            startActivityForResult(Intent(this, PodCameraActivity::class.java).putExtra("check", true), CAMERA_CHECK)
        }
        AppUi.label(content, "Saved report")
        share = AppUi.button(content, "Share report & photos") { shareReport() }
        AppUi.disclosure(content, "Help & permissions") { help ->
            AppUi.text(help, "Photos and reports stay on this phone until you choose Share. Frame the Pod only. The report records what was sent and photographed; it does not automatically judge the display.", 14f, secondary = true)
            AppUi.button(help, "Remember Pod — I saw it respond") { command("REMEMBER") }
            AppUi.button(help, "Open app permissions", quiet = true) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        }
        pendingStart = savedInstanceState?.getBoolean("pendingStart") ?: false
        autoStart = savedInstanceState == null && intent.getBooleanExtra("start", false)
        render()
    }
    override fun onPostResume() {
        super.onPostResume()
        if (autoStart) { autoStart = false; startPressed() }
    }
    override fun onStart() { super.onStart(); AppState.listeners.add(redraw); render() }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingStart", pendingStart)
        outState.putInt("selectedSide", side.selectedItemPosition)
        outState.putBoolean("experimentalChecks", experimental.isChecked)
        super.onSaveInstanceState(outState)
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        else { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
    }
    private fun startPressed() {
        if (AppState.guidedRun?.terminal == false) return
        pendingStart = true
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS); return }
        continueStart()
    }
    private fun continueStart() {
        if (!pendingStart) return
        if (requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pendingStart = false; status.text = "Camera and Bluetooth permissions are needed. Tap Start to retry, or open app permissions."; return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { pendingStart = false; status.text = "Bluetooth is unavailable on this phone"; return }
        try {
            if (!adapter.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH); return }
        } catch (_: SecurityException) { pendingStart = false; status.text = "Bluetooth permission was revoked"; return }
        pendingStart = false
        prefs.edit().putString("driving_side", DrivingSide.entries[side.selectedItemPosition.coerceAtLeast(0)].name).apply()
        command("GUIDE_START") { putExtra("experimental", experimental.isChecked) }
    }
    @Deprecated("Native Activity permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) continueStart()
    }
    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        if (action != "GUIDE_START" && AppState.onMapsPost == null) return
        val intent = Intent(this, PodService::class.java).setAction(action).apply(extras)
        try { if (action == "GUIDE_START") startForegroundService(intent) else startService(intent) }
        catch (_: SecurityException) { status.text = "Bluetooth permission is needed to continue" }
    }
    private fun render() {
        val run = AppState.guidedRun
        val active = run != null && !run.terminal
        start.isEnabled = !active; side.isEnabled = !active; experimental.isEnabled = !active
        configuration.visibility = if (active) View.GONE else View.VISIBLE
        stop.visibility = if (active) View.VISIBLE else View.GONE
        photo.visibility = if (run?.phase == GuidedRun.Phase.PHOTO_REQUIRED) View.VISIBLE else View.GONE
        skip.visibility = photo.visibility
        val needsPin = active && AppState.sessionState == io.opentripper.core.PodSession.State.WAITING_PIN
        pin.visibility = if (needsPin) View.VISIBLE else View.GONE; pinButton.visibility = pin.visibility
        progress.max = run?.steps?.size ?: 7; progress.progress = run?.index ?: 0
        progress.contentDescription = "${progress.progress} of ${progress.max} screen checkpoints completed"
        share.isEnabled = !active && reports.latestId() != null
        if (run != null) {
            status.text = when (run.phase) {
                GuidedRun.Phase.CONNECTING -> if (needsPin) "Enter the PIN shown on your Pod" else AppState.status
                GuidedRun.Phase.SENDING -> "Sending test ${run.index + 1} of ${run.steps.size}"
                GuidedRun.Phase.SETTLING -> "Hold steady; waiting for the Pod screen"
                GuidedRun.Phase.PHOTO_REQUIRED -> "Photo needed · ${run.index + 1} of ${run.steps.size}"
                GuidedRun.Phase.COMPLETED -> "Test complete · report saved"
                GuidedRun.Phase.FAILED -> "Test stopped · ${run.reason}"
                GuidedRun.Phase.CANCELLED -> "Stopped · partial report saved"
            }
            instruction.text = if (run.phase == GuidedRun.Phase.CONNECTING)
                "Keep the Hunter parked with ignition on. The first screen test starts after connection and PIN entry."
            else run.current?.takeIf { active }?.let { "${it.title}\n${it.probe.meters} m · ${it.probe.cue.drivingSide.label}" }
                ?: "${run.results.count { it.photo != null }} photos saved; ${run.results.count { it.skipped }} skipped. Share the ZIP for review."
            if (run.phase == GuidedRun.Phase.PHOTO_REQUIRED) {
                instruction.append("\nPhotograph the Pod within two minutes. The same screen stays active while the camera is open.")
                val prompt = "${AppState.guidedRunId}:${run.index}"
                if (lastPhotoPrompt != prompt) { lastPhotoPrompt = prompt; photo.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            }
        } else {
            val latest = reports.latestForDisplay()
            status.text = latest?.let { "Last run: ${it.optString("phase").lowercase()}" } ?: "Ready to start"
        }
        val choices = AppState.devices.takeIf { AppState.guidedChoosingPod } ?: emptyList()
        if (choices != renderedCandidates) {
            renderedCandidates = choices.toList(); candidates.removeAllViews()
            choices.forEach { (address, _) -> AppUi.button(candidates, "Use Pod $address") {
                command("GUIDE_CONNECT") { putExtra("address", address) }
            } }
        }
    }
    private fun openPhoto() {
        val run = AppState.guidedRun ?: return
        val step = run.current ?: return
        if (run.phase != GuidedRun.Phase.PHOTO_REQUIRED) return
        startActivityForResult(Intent(this, PodCameraActivity::class.java).apply {
            putExtra("run", AppState.guidedRunId); putExtra("step", step.id); putExtra("title", step.title)
        }, TAKE_PHOTO)
    }
    @Deprecated("Native Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ENABLE_BLUETOOTH -> if (resultCode == RESULT_OK) continueStart() else { pendingStart = false; status.text = "Enable Bluetooth, then press Start" }
            TAKE_PHOTO -> if (resultCode == RESULT_OK && data != null) command("GUIDE_PHOTO") {
                putExtra("run", data.getStringExtra("run")); putExtra("step", data.getStringExtra("step")); putExtra("photo", data.getStringExtra("photo"))
            }
        }
    }
    private fun shareReport() {
        val id = reports.latestId() ?: return
        if (AppState.guidedRun?.terminal == false) return
        reports.latestForDisplay()
        share.isEnabled = false
        Thread {
            try {
                val file = reports.archive(id)
                val uri = FileProvider.getUriForFile(this, "io.opentripper.reports", file)
                runOnUiThread { if (!isDestroyed) {
                    share.isEnabled = true
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("OpenTripper test report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share Pod test report"))
                } }
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) { share.isEnabled = true; status.text = "Could not prepare the report; check phone storage" } }
            }
        }.start()
    }
    companion object { private const val REQUEST_PERMISSIONS = 21; private const val ENABLE_BLUETOOTH = 22; private const val TAKE_PHOTO = 23; private const val CAMERA_CHECK = 24 }
}
