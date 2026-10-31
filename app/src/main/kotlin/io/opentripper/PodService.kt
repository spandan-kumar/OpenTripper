package io.opentripper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import io.opentripper.core.*
import java.time.LocalTime
import java.time.Instant

class PodService : Service(), BleTransport.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var transport: BleTransport
    private lateinit var session: PodSession
    private val route = MapsRoute()
    private var targetAddress: String? = null
    private val prefs by lazy { getSharedPreferences("pod", MODE_PRIVATE) }
    private var forwarding = false
    private var probe: PodProbe? = null
    private var probeUntil = 0L
    private var guided: GuidedRun? = null
    private var guidedId: String? = null
    private val guidedEvents = ArrayDeque<String>()
    private var guidedSignature = ""
    private var discoveryScheduled = false
    private var mapsConnecting = false
        set(value) { field = value; AppState.mapsConnecting = value }
    private val awaitingPod get() = activeGuided?.phase == GuidedRun.Phase.CONNECTING || mapsConnecting
    private var discoveryToken = 0
    private var selectingPod = false
        set(value) {
            field = value
            AppState.guidedChoosingPod = value && activeGuided != null
            AppState.mapsChoosingPod = value && mapsConnecting
        }
    private var destroying = false
    private var testWakeLock: PowerManager.WakeLock? = null
    private val reports by lazy { GuidedReportStore(this) }
    private val activeGuided get() = guided?.takeUnless { it.terminal }
    private val ticker = object : Runnable {
        override fun run() {
            try {
                if (!BleTransport.hasPermissions(this@PodService)) {
                    onFailure("Bluetooth permission was revoked"); stopSelf(); return
                }
                activeGuided?.let { run ->
                    if (session.state == PodSession.State.READY) run.connected()
                    run.tick(); publishGuided()
                }
                if (guided?.terminal == true) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
                }
                refreshRoute()
                session.tick()
                handler.postDelayed(this, if (session.state == PodSession.State.HANDSHAKE) 50 else 1000)
            } catch (_: SecurityException) { onFailure("Bluetooth permission was revoked"); stopSelf() }
        }
    }
    override fun onCreate() {
        super.onCreate()
        forwarding = prefs.getBoolean("forwarding", false)
        transport = BleTransport(this, this)
        session = PodSession(SystemClock::elapsedRealtime,
            { LocalTime.now().let { TripperProtocol.time(it.hour, it.minute) } },
            { bytes -> try { transport.write(bytes) } catch (_: SecurityException) { onFailure("Bluetooth permission was revoked") } },
            { state ->
                AppState.sessionState = state
                if (state != PodSession.State.STOPPED) AppState.status(when (state) {
                    PodSession.State.WAITING_PIN -> "Enter the PIN shown on the Pod"
                    PodSession.State.READY -> "Session ready — verify the Pod display before remembering it"
                    else -> "Pod: $state"
                })
            }, { reason -> onFailure(reason) }, { bytes ->
                activeGuided?.written(bytes)
                publishGuided()
                probe?.let { test ->
                    if (bytes.contentEquals(test.packet()) && AppState.completedProbe != test) {
                        AppState.completedProbe = test
                        AppState.completedProbeAt = java.time.Instant.now().toString()
                        AppState.event("Test write completed. Check the Pod visually; Android cannot confirm its graphics.")
                    }
                }
            })
        AppState.onMapsPost = { note -> route.post(note, SystemClock.elapsedRealtime()); refreshRoute() }
        AppState.onMapsRemove = { key -> route.remove(key); refreshRoute() }
        AppState.onMapsDisconnect = { route.clear(); refreshRoute() }
        AppState.requestMapsSnapshot?.invoke()
        handler.post(ticker)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            cancelGuided()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (!BleTransport.hasPermissions(this)) {
            AppState.status("Bluetooth permissions required"); stopSelf(); return START_NOT_STICKY
        }
        try {
            startForegroundNotification()
            if (intent?.action in setOf("SCAN", "CONNECT", "FORWARD", "PROBE", "STOP_PROBE", "MAPS_START")) cancelGuided()
            // A later manual action takes ownership of the service after a completed run.
            if (intent?.action?.startsWith("GUIDE_") != true && guided?.terminal == true) {
                guided = null; guidedId = null
            }
            if (intent?.action in setOf("SCAN", "CONNECT", "PROBE", "GUIDE_START")) {
                mapsConnecting = false; selectingPod = false; discoveryToken++
            }
            when (intent?.action) {
                "MAPS_START" -> startMaps()
                "MAPS_CONNECT" -> intent.getStringExtra("address")?.let { address ->
                    if (mapsConnecting && selectingPod && AppState.devices.any { it.first == address }) connectDiscovered(address)
                }
                "GUIDE_START" -> startGuided(intent.getBooleanExtra("experimental", false))
                "GUIDE_CONNECT" -> intent.getStringExtra("address")?.let { address ->
                    if (activeGuided?.phase == GuidedRun.Phase.CONNECTING && selectingPod &&
                        AppState.devices.any { it.first == address }) connectDiscovered(address)
                }
                "GUIDE_CANCEL" -> { cancelGuided(); refreshRoute() }
                "GUIDE_PHOTO", "GUIDE_SKIP" -> {
                    val run = activeGuided
                    val stepId = intent.getStringExtra("step")
                    if (run != null && intent.getStringExtra("run") == guidedId && stepId != null) {
                        val accepted = if (intent.action == "GUIDE_SKIP") run.skip(stepId) else {
                            val name = intent.getStringExtra("photo") ?: ""
                            val valid = runCatching {
                                val file = reports.photoFile(guidedId!!, name)
                                file.length() in 1..20_000_000 && file.inputStream().use {
                                    it.read() == 0xff && it.read() == 0xd8 && it.read() == 0xff
                                }
                            }.getOrDefault(false)
                            valid && run.photo(stepId, name)
                        }
                        if (accepted) { guidedEvent(if (intent.action == "GUIDE_SKIP") "Photo skipped by user: $stepId" else "Photo accepted: $stepId"); publishGuided(); refreshRoute() }
                    }
                }
                "SCAN" -> { clearProbe(clearEvidence = true); session.stop(); targetAddress = null; AppState.status("Scanning for RE_DISP"); transport.scan() }
                "CONNECT" -> {
                    clearProbe(clearEvidence = true)
                    session.stop()
                    val address = intent.getStringExtra("address") ?: return START_NOT_STICKY
                    targetAddress = address
                    AppState.status("Starting two-way BLE connection")
                    transport.connect(address)
                }
                "PIN" -> try { session.submitPin(intent.getStringExtra("pin") ?: "") }
                    catch (error: IllegalArgumentException) { AppState.event(error.message ?: "Invalid PIN") }
                    catch (error: IllegalStateException) { AppState.event(error.message ?: "Not waiting for PIN") }
                "REMEMBER" -> if (session.state == PodSession.State.READY && targetAddress != null) {
                    prefs.edit().putString("verified_address", targetAddress).apply()
                    AppState.event("Saved user-verified Pod for resume handshake; PIN is never stored")
                }
                "FORGET" -> { prefs.edit().remove("verified_address").apply(); AppState.event("Forgot saved Pod") }
                "FORWARD" -> {
                    clearProbe()
                    forwarding = intent.getBooleanExtra("enabled", false)
                    prefs.edit().putBoolean("forwarding", forwarding).apply()
                    refreshRoute()
                }
                "TIME" -> session.syncTime()
                "ROUNDABOUTS" -> {
                    AppState.roundaboutsAllowed = intent.getBooleanExtra("enabled", false)
                    refreshRoute()
                }
                "PROBE" -> {
                    if (session.state != PodSession.State.READY) {
                        AppState.event("Connect and complete PIN entry before sending a screen test")
                    } else try {
                        val side = DrivingSide.valueOf(intent.getStringExtra("side") ?: "LEFT")
                        val kind = Maneuver.valueOf(intent.getStringExtra("maneuver") ?: "")
                        val nextKind = intent.getStringExtra("next")?.let(Maneuver::valueOf)
                        val nextSide = DrivingSide.valueOf(intent.getStringExtra("nextSide") ?: side.name)
                        val test = PodProbe(ManeuverCue(kind, intent.getIntExtra("exit", 0).takeIf { it > 0 }, side),
                            intent.getIntExtra("meters", 100), nextKind?.let {
                                ManeuverCue(it, intent.getIntExtra("nextExit", 0).takeIf { e -> e > 0 }, nextSide)
                            },
                            intent.getBooleanExtra("night", false), intent.getBooleanExtra("emphasis", false))
                        require(!test.experimental || intent.getBooleanExtra("experimental", false)) {
                            "Enable experimental tests before sending roundabout or approach-intensity codes"
                        }
                        // A manual test must never be replaced by a Maps notification or resume it at expiry.
                        forwarding = false
                        prefs.edit().putBoolean("forwarding", false).apply()
                        AppState.completedProbe = null
                        AppState.completedProbeAt = null
                        probe = test
                        probeUntil = SystemClock.elapsedRealtime() + 20_000
                        AppState.event("Stationary screen test requested for 20 seconds: ${test.cue.label}")
                        refreshRoute()
                    } catch (error: IllegalArgumentException) {
                        AppState.event(error.message ?: "Invalid screen test")
                    }
                }
                "STOP_PROBE" -> { clearProbe(); refreshRoute() }

            }
        } catch (_: SecurityException) { onFailure("Bluetooth permission was revoked"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun refreshRoute() {
        val now = SystemClock.elapsedRealtime()
        val value = route.current(now)
        if (AppState.mapsStatus != route.status) { AppState.mapsStatus = route.status; AppState.notifyChanged() }
        if (value != AppState.navigation) { AppState.navigation = value; AppState.notifyChanged() }
        if (probe != null && now >= probeUntil) {
            probe = null
            AppState.event("Screen test ended; Maps forwarding remains paused")
        }
        val side = runCatching { DrivingSide.valueOf(prefs.getString("driving_side", "LEFT")!!) }.getOrDefault(DrivingSide.LEFT)
        val cue = value?.cue(side)
        val test = probe
        val automated = activeGuided
        val automatedScreen = automated?.screen
        val message: String
        val packet: ByteArray?
        val validUntil: Long
        when {
            automatedScreen != null -> {
                message = "Guided test ${automated.index + 1}/${automated.steps.size}: ${automated.current!!.title}"
                packet = automatedScreen.packet(); validUntil = automated.deadline
            }
            automated != null -> {
                message = "Guided test is connecting to your Pod"
                packet = null; validUntil = 0
            }
            test != null -> {
                message = "Stationary test: ${test.cue.label} · ${((probeUntil - now + 999) / 1000)} s left"
                packet = test.packet(); validUntil = probeUntil
            }
            forwarding && cue != null && session.state != PodSession.State.READY -> {
                message = "Maps instruction waiting for Pod connection"
                packet = null; validUntil = 0
            }
            forwarding && cue != null && (!cue.experimental || AppState.roundaboutsAllowed) -> {
                message = "Maps forwarding: ${cue.label}"
                packet = TripperProtocol.navigation(cue, value.meters); validUntil = route.expiresAt
            }
            else -> {
                message = if (forwarding && cue?.experimental == true)
                    "${cue.label} recognized; experimental roundabout output is disabled"
                else "No screen is being forwarded"
                packet = null; validUntil = 0
            }
        }
        if (AppState.screenMessage != message) { AppState.screenMessage = message; AppState.notifyChanged() }
        session.updateScreen(packet, validUntil)
    }
    private fun clearProbe(clearEvidence: Boolean = false) {
        probe = null; probeUntil = 0
        if (clearEvidence) { AppState.completedProbe = null; AppState.completedProbeAt = null }
    }
    private fun startGuided(experimental: Boolean) {
        cancelGuided(); clearProbe(clearEvidence = true)
        forwarding = false; prefs.edit().putBoolean("forwarding", false).apply()
        val side = runCatching { DrivingSide.valueOf(prefs.getString("driving_side", "LEFT")!!) }.getOrDefault(DrivingSide.LEFT)
        guidedId = reports.create()
        guided = GuidedRun(GuidedRun.plan(side, experimental), SystemClock::elapsedRealtime)
        AppState.guidedRun = guided; AppState.guidedRunId = guidedId
        guidedEvents.clear(); guidedSignature = ""; discoveryScheduled = false; selectingPod = false; discoveryToken++
        guidedEvent("Stationary guided test started; experimental=$experimental")
        val power = getSystemService(PowerManager::class.java)
        testWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenTripper:guided-test").apply {
            acquire(GuidedRun.MAX_DURATION_MS + 30_000)
        }
        publishGuided()
        if (activeGuided == null) return
        if (targetAddress != null && session.state in setOf(PodSession.State.READY, PodSession.State.WAITING_PIN, PodSession.State.HANDSHAKE)) {
            if (session.state == PodSession.State.READY) guided?.connected()
        } else {
            session.stop(); targetAddress = null
            AppState.status("Looking for your Pod; turn the Hunter ignition on now")
            transport.scan(30_000)
        }
        publishGuided(); refreshRoute()
    }
    private fun startMaps() {
        AppState.roundaboutsAllowed = false
        clearProbe(clearEvidence = true)
        forwarding = true; prefs.edit().putBoolean("forwarding", true).apply()
        discoveryToken++; discoveryScheduled = false; selectingPod = false
        if (targetAddress != null && session.state in setOf(PodSession.State.READY, PodSession.State.WAITING_PIN, PodSession.State.HANDSHAKE)) {
            mapsConnecting = false
        } else {
            session.stop(); targetAddress = null; mapsConnecting = true
            AppState.status("Looking for your Pod; turn the Hunter ignition on")
            transport.scan(30_000)
        }
        AppState.requestMapsSnapshot?.invoke()
        refreshRoute(); AppState.notifyChanged(); startForegroundNotification()
    }
    private fun connectDiscovered(address: String) {
        if (!awaitingPod || targetAddress != null) return
        selectingPod = false; targetAddress = address
        guidedEvent("Pod selected; starting BLE connection")
        AppState.status("Connecting to Pod; PIN entry will appear here if needed")
        transport.connect(address)
    }
    override fun onDeviceFound(address: String) {
        if (!awaitingPod || targetAddress != null) return
        val saved = prefs.getString("verified_address", null)
        if (saved != null) {
            if (address == saved) connectDiscovered(address)
            return
        }
        if (!discoveryScheduled) {
            discoveryScheduled = true
            val token = discoveryToken
            handler.postDelayed({
                if (discoveryToken == token && awaitingPod && targetAddress == null) {
                    if (AppState.devices.size == 1) connectDiscovered(AppState.devices.single().first)
                    else if (AppState.devices.size > 1) {
                        selectingPod = true; AppState.status("More than one Pod found; select yours to continue")
                    }
                }
            }, 3000)
        } else if (selectingPod) AppState.notifyChanged()
    }
    override fun onScanFinished() {
        if (!awaitingPod || targetAddress != null) return
        if (selectingPod && AppState.devices.isNotEmpty()) return
        onFailure(if (prefs.getString("verified_address", null) != null)
            "Saved Pod not found. Cycle the ignition and try again, or forget the saved Pod."
            else "No Pod found. Turn the Hunter ignition off/on, then start the test again.")
    }
    private fun guidedEvent(message: String) {
        guidedEvents.addLast("${Instant.now()} $message")
        while (guidedEvents.size > 80) guidedEvents.removeFirst()
    }
    private fun publishGuided() {
        val run = guided ?: return
        val id = guidedId ?: return
        val signature = "${run.phase}:${run.index}:${run.results.sumOf { it.writes }}:${run.reason}"
        if (signature == guidedSignature) return
        val phaseChanged = !guidedSignature.startsWith("${run.phase}:${run.index}:")
        guidedSignature = signature
        try { reports.save(id, run, guidedEvents.toList()) }
        catch (_: java.io.IOException) { run.fail("Could not save test evidence; check phone storage") }
        if (run.terminal) {
            releaseTestWakeLock(); selectingPod = false
            // Invalidate scan and connection callbacks when a bounded run ends.
            session.stop(); targetAddress = null; transport.close()
        }
        AppState.notifyChanged()
        if (phaseChanged && !destroying) startForegroundNotification()
    }
    private fun cancelGuided() {
        activeGuided?.cancel(); publishGuided(); releaseTestWakeLock()
    }
    private fun releaseTestWakeLock() {
        testWakeLock?.let { if (it.isHeld) it.release() }; testWakeLock = null
    }
    override fun onReady(address: String) {
        mapsConnecting = false; selectingPod = false
        session.start(prefs.getString("verified_address", null) == address)
    }
    override fun onWriteComplete(success: Boolean) { session.onWriteComplete(success) }
    override fun onFailure(reason: String) {
        activeGuided?.fail(reason); guidedEvent(reason); publishGuided()
        mapsConnecting = false; selectingPod = false; discoveryToken++
        clearProbe(); session.stop(); targetAddress = null; transport.close(); AppState.status(reason)
    }
    override fun onEvent(message: String) {
        if (activeGuided != null) guidedEvent(message)
        AppState.event(message)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroying = true
        activeGuided?.fail("Connection service stopped; partial report saved"); publishGuided(); releaseTestWakeLock()
        handler.removeCallbacksAndMessages(null)
        mapsConnecting = false; selectingPod = false; AppState.mapsStatus = MapsRoute.Status.WAITING
        session.stop(); transport.close(); route.clear()
        clearProbe(); AppState.roundaboutsAllowed = false
        AppState.screenMessage = "No screen is being forwarded"
        AppState.onMapsPost = null; AppState.onMapsRemove = null; AppState.onMapsDisconnect = null
        AppState.navigation = null; AppState.status("Stopped")
        super.onDestroy()
    }
    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("pod", "Tripper connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, if (activeGuided != null) GuidedTestActivity::class.java else if (forwarding) MapsNavigationActivity::class.java else MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, PodService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val detail = activeGuided?.let { if (it.phase == GuidedRun.Phase.PHOTO_REQUIRED) "Photo needed: ${it.current?.title}" else "Guided Pod test: ${it.phase}" }
            ?: "Experimental Tripper Pod connection"
        val note = Notification.Builder(this, "pod").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenTripper active").setContentText(detail)
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, note)
    }
}
