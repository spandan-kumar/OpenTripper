package io.opentripper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import io.opentripper.core.*
import java.time.LocalTime

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
    private val ticker = object : Runnable {
        override fun run() {
            try {
                if (!BleTransport.hasPermissions(this@PodService)) {
                    onFailure("Bluetooth permission was revoked"); stopSelf(); return
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
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (!BleTransport.hasPermissions(this)) {
            AppState.status("Bluetooth permissions required"); stopSelf(); return START_NOT_STICKY
        }
        try {
            startForegroundNotification()
            when (intent?.action) {
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
        if (value != AppState.navigation) { AppState.navigation = value; AppState.notifyChanged() }
        if (probe != null && now >= probeUntil) {
            probe = null
            AppState.event("Screen test ended; Maps forwarding remains paused")
        }
        val side = runCatching { DrivingSide.valueOf(prefs.getString("driving_side", "LEFT")!!) }.getOrDefault(DrivingSide.LEFT)
        val cue = value?.cue(side)
        val test = probe
        val message: String
        val packet: ByteArray?
        val validUntil: Long
        when {
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
    override fun onReady(address: String) {
        session.start(prefs.getString("verified_address", null) == address)
    }
    override fun onWriteComplete(success: Boolean) { session.onWriteComplete(success) }
    override fun onFailure(reason: String) {
        clearProbe(); session.stop(); transport.close(); AppState.status(reason)
    }
    override fun onEvent(message: String) { AppState.event(message) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, PodService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, "pod").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenTripper active").setContentText("Experimental Tripper Pod connection")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, note)
    }
}
