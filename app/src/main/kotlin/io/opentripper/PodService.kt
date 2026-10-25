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
                if (state != PodSession.State.STOPPED) AppState.status(when (state) {
                    PodSession.State.WAITING_PIN -> "Enter the PIN shown on the Pod"
                    PodSession.State.READY -> "Session ready — verify the Pod display before remembering it"
                    else -> "Pod: $state"
                })
            }, { reason -> onFailure(reason) })
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
                "SCAN" -> { session.stop(); targetAddress = null; AppState.status("Scanning for RE_DISP"); transport.scan() }
                "CONNECT" -> {
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
                    forwarding = intent.getBooleanExtra("enabled", false)
                    prefs.edit().putBoolean("forwarding", forwarding).apply()
                    refreshRoute()
                }
                "TIME" -> session.syncTime()

            }
        } catch (_: SecurityException) { onFailure("Bluetooth permission was revoked"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun refreshRoute() {
        val value = route.current(SystemClock.elapsedRealtime())
        if (value != AppState.navigation) { AppState.navigation = value; AppState.notifyChanged() }
        session.updateScreen(if (forwarding && value != null) TripperProtocol.navigation(value.maneuver, value.meters) else null,
            route.expiresAt)
    }
    override fun onReady(address: String) {
        session.start(prefs.getString("verified_address", null) == address)
    }
    override fun onWriteComplete(success: Boolean) { session.onWriteComplete(success) }
    override fun onFailure(reason: String) {
        session.stop(); transport.close(); AppState.status(reason)
    }
    override fun onEvent(message: String) { AppState.event(message) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session.stop(); transport.close(); route.clear()
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
