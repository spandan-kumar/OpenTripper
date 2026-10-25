package io.opentripper

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.opentripper.core.TripperProtocol
import java.util.UUID

/** All mutable transport state is confined to main. Callback generations reject old connection events. */
@SuppressLint("MissingPermission") // Entry points check permissions; service also handles revocation.
class BleTransport(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onReady(address: String)
        fun onWriteComplete(success: Boolean)
        fun onFailure(reason: String)
        fun onEvent(message: String)
        fun onDeviceFound(address: String) {}
        fun onScanFinished() {}
    }
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private var generation = 0
    private var scanCallback: ScanCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var client: BluetoothGatt? = null
    private var server: BluetoothGattServer? = null
    private var remote: BluetoothGattCharacteristic? = null
    private var address: String? = null
    private var writePending = false
    private var transportReady = false
    private val found = linkedMapOf<String, String>()
    private val serviceUuid = UUID.fromString(TripperProtocol.SERVICE)
    private val characteristicUuid = UUID.fromString(TripperProtocol.CHARACTERISTIC)

    fun scan(durationMillis: Long = 15_000) {
        close()
        if (!checkAvailable()) return
        val token = generation
        found.clear(); AppState.devices(emptyList())
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onMain {
                    if (generation != token) return@onMain
                    // Use the advertisement name; the Pod may omit its service UUID in advertisements.
                    if (result.scanRecord?.deviceName == TripperProtocol.DEVICE_NAME) {
                        val isNew = !found.containsKey(result.device.address)
                        found[result.device.address] = TripperProtocol.DEVICE_NAME
                        AppState.devices(found.map { it.key to it.value })
                        if (isNew) listener.onDeviceFound(result.device.address)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) { onMain {
                if (generation == token) fail("BLE scan failed ($errorCode)")
            } }
        }
        scanCallback = callback
        scanner = adapter?.bluetoothLeScanner
        if (scanner == null) return fail("BLE scanner unavailable")
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        listener.onEvent("Scanning for RE_DISP; turn ignition on now")
        handler.postDelayed({
            if (generation == token) {
                stopScan(); listener.onScanFinished()
                if (generation == token) listener.onEvent("Scan finished; select a Pod, or cycle ignition and scan again")
            }
        }, durationMillis.coerceIn(5000, 30_000))
    }

    fun connect(target: String) {
        close()
        if (!checkAvailable()) return
        if (!BluetoothAdapter.checkBluetoothAddress(target)) return fail("Invalid Bluetooth address")
        address = target
        val token = generation
        val device = adapter!!.getRemoteDevice(target)
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                onMain {
                    if (generation != token || client !== gatt) return@onMain
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        fail("Pod disconnected (GATT $status); rescan to reconnect")
                    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                        listener.onEvent("BLE connected; discovering UART service")
                        if (!gatt.discoverServices()) fail("Service discovery could not start")
                    }
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { onMain {
                if (generation != token || client !== gatt) return@onMain
                if (status != BluetoothGatt.GATT_SUCCESS) return@onMain fail("Service discovery failed ($status)")
                val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
                val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                if (characteristic == null || characteristic.properties and writable == 0)
                    return@onMain fail("Pod UART characteristic missing or not writable")
                remote = characteristic
                handler.postDelayed({
                    if (generation == token) { transportReady = true; listener.onReady(target) }
                }, 200)
            } }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                onMain {
                    if (generation != token || client !== gatt || characteristic.uuid != characteristicUuid || !writePending) return@onMain
                    writePending = false
                    listener.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }
        val localCallback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) { onMain {
                if (generation != token) return@onMain
                if (status != BluetoothGatt.GATT_SUCCESS) return@onMain fail("Local UART service registration failed ($status)")
                listener.onEvent("Local GATT reply server ready; connecting to Pod")
                client = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (client == null) fail("GATT connection could not start")
            } }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
                responseNeeded: Boolean, offset: Int, value: ByteArray) {
                val copy = value.copyOf()
                onMain {
                    if (generation != token) return@onMain
                    val status = when {
                        device.address != address -> BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                        characteristic.uuid != characteristicUuid || preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                        copy.size != 20 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                        !TripperProtocol.isValid(copy) -> BluetoothGatt.GATT_FAILURE
                        else -> BluetoothGatt.GATT_SUCCESS
                    }
                    if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
                    // Never log the raw payload: a reply could contain pairing information.
                    listener.onEvent(if (status == BluetoothGatt.GATT_SUCCESS)
                        "Pod reply received (CRC valid); reply meaning is undocumented"
                        else "Pod reply rejected (GATT $status)")
                }
            }
            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                characteristic: BluetoothGattCharacteristic) { onMain {
                if (generation == token) server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            } }
            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) { onMain {
                if (generation == token) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            } }
        }
        server = manager?.openGattServer(context, localCallback)
        if (server == null) return fail("Phone cannot open a local GATT server")
        val localService = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        localService.addCharacteristic(BluetoothGattCharacteristic(characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        if (server?.addService(localService) != true) return fail("Local UART registration could not start")
        // No advertising / CCCD subscription: the Pod writes replies to our local server on the link.
        handler.postDelayed({ if (generation == token && !transportReady) fail("Pod connection timed out") }, 20_000)
    }

    fun write(bytes: ByteArray) {
        if (!hasPermissions(context)) return listener.onWriteComplete(false)
        val gatt = client
        val characteristic = remote
        if (gatt == null || characteristic == null || writePending || !TripperProtocol.isValid(bytes))
            return listener.onWriteComplete(false)
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writePending = true
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!accepted) { writePending = false; listener.onWriteComplete(false) }
    }

    fun close() {
        generation++
        stopScan()
        try { client?.disconnect(); client?.close(); server?.close() } catch (_: SecurityException) { }
        client = null; server = null; remote = null; address = null
        writePending = false; transportReady = false
        handler.removeCallbacksAndMessages(null)
    }
    private fun onMain(block: () -> Unit) {
        handler.post {
            try { block() } catch (_: SecurityException) { fail("Bluetooth permission was revoked") }
        }
    }
    private fun stopScan() {
        try { scanCallback?.let { scanner?.stopScan(it) } } catch (_: SecurityException) { }
        scanCallback = null; scanner = null
    }
    private fun checkAvailable(): Boolean {
        if (!hasPermissions(context)) { listener.onFailure("Bluetooth permissions required"); return false }
        if (adapter?.isEnabled != true) { listener.onFailure("Enable Bluetooth first"); return false }
        return true
    }
    private fun fail(message: String) { close(); listener.onFailure(message) }
    companion object {
        fun hasPermissions(context: Context): Boolean {
            val permissions = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            return permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }
    }
}
