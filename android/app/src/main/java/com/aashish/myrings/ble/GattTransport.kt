package com.aashish.myrings.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import com.aashish.myrings.protocol.Gatt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class BleException(msg: String) : Exception(msg)

data class ScanHit(val address: String, val name: String?, val rssi: Int)

/** Android GATT implementation of [RingTransport]. Callers must hold BLUETOOTH_CONNECT/SCAN permissions. */
@SuppressLint("MissingPermission")
class GattTransport private constructor(
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    override val incoming: Channel<ByteArray>,
    private val cb: Callback,
) : RingTransport {

    private val writeMutex = Mutex()

    override suspend fun write(data: ByteArray) = writeMutex.withLock {
        val d = CompletableDeferred<Int>()
        cb.writeDone = d
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(writeChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeChar.value = data
                gatt.writeCharacteristic(writeChar)
            }
        }
        if (!ok) throw BleException("write rejected by the Bluetooth stack")
        val status = withTimeoutOrNull(8000) { d.await() } ?: throw BleException("write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("write failed (GATT status $status)")
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        incoming.close()
    }

    internal class Callback(val incoming: Channel<ByteArray>) : BluetoothGattCallback() {
        val connected = CompletableDeferred<Int>()
        var services = CompletableDeferred<Int>()
        var mtu = CompletableDeferred<Int>()
        @Volatile var descDone = CompletableDeferred<Int>()
        @Volatile var writeDone = CompletableDeferred<Int>()
        @Volatile var disconnected = false

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) connected.complete(status)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = true
                connected.complete(-1)
                services.complete(-1); mtu.complete(-1); descDone.complete(-1); writeDone.complete(-1)
                incoming.close()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { services.complete(status) }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { mtu.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descDone.complete(status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeDone.complete(status) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value)
        }
        @Deprecated("pre-API 33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") incoming.trySend(c.value ?: return)
        }
    }

    companion object {
        private val adapter get() = BluetoothAdapter.getDefaultAdapter()

        fun bluetoothOn(): Boolean = adapter?.isEnabled == true

        /** Scan for Oura rings (filtered on the Oura GATT service) until [timeoutMs] or until [stopWhen] matches. */
        suspend fun scan(ctx: Context, timeoutMs: Long = 10_000, stopWhen: (ScanHit) -> Boolean = { false }): List<ScanHit> {
            val scanner = adapter?.bluetoothLeScanner ?: throw BleException("Bluetooth is off")
            val hits = LinkedHashMap<String, ScanHit>()
            val found = CompletableDeferred<Unit>()
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val h = ScanHit(r.device.address, r.scanRecord?.deviceName ?: r.device.name, r.rssi)
                    hits[h.address] = h
                    if (stopWhen(h)) found.complete(Unit)
                }
                override fun onScanFailed(errorCode: Int) { found.completeExceptionally(BleException("scan failed ($errorCode)")) }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Gatt.SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, cb)
            try { withTimeoutOrNull(timeoutMs) { found.await() } } finally { runCatching { scanner.stopScan(cb) } }
            return hits.values.sortedByDescending { it.rssi }
        }

        /** Bond with the device if needed (the ring encrypts its GATT attributes). */
        suspend fun ensureBonded(ctx: Context, device: BluetoothDevice, timeoutMs: Long = 45_000) {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return
            suspendCancellableCoroutine<Unit> { cont ->
                val recv = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (d?.address != device.address) return
                        when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> { runCatching { ctx.unregisterReceiver(this) }; if (cont.isActive) cont.resume(Unit) }
                            BluetoothDevice.BOND_NONE -> {
                                if (i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING) {
                                    runCatching { ctx.unregisterReceiver(this) }
                                    if (cont.isActive) cont.resumeWith(Result.failure(BleException("Bluetooth pairing was refused by the ring")))
                                }
                            }
                        }
                    }
                }
                ctx.registerReceiver(recv, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(recv) } }
                if (!device.createBond()) {
                    runCatching { ctx.unregisterReceiver(recv) }
                    cont.resumeWith(Result.failure(BleException("could not start Bluetooth pairing")))
                }
            }
        }

        /** Connect, discover services and enable notifications. */
        suspend fun connect(ctx: Context, address: String, bond: Boolean = true): GattTransport {
            val device = adapter?.getRemoteDevice(address) ?: throw BleException("Bluetooth is off")
            if (bond) withTimeout(50_000) { ensureBonded(ctx, device) }
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            val cb = Callback(incoming)
            val gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(ctx, false, cb)
            try {
                val st = withTimeoutOrNull(15_000) { cb.connected.await() } ?: throw BleException("connection timed out — is the ring in range and not held by another phone/PC?")
                if (st != 0) throw BleException("connection failed (status $st)")
                if (!gatt.discoverServices()) throw BleException("service discovery failed to start")
                val ss = withTimeoutOrNull(15_000) { cb.services.await() } ?: throw BleException("service discovery timed out")
                if (ss != 0) throw BleException("service discovery failed (status $ss)")
                gatt.requestMtu(247)
                withTimeoutOrNull(3_000) { cb.mtu.await() }

                val service = gatt.getService(Gatt.SERVICE) ?: throw BleException("Oura service not found on this device")
                val write = service.getCharacteristic(Gatt.WRITE) ?: throw BleException("Oura write characteristic not found")
                val notifiers = service.characteristics.filter {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
                for (c in notifiers) subscribe(gatt, cb, c)
                return GattTransport(gatt, write, incoming, cb)
            } catch (e: Throwable) {
                runCatching { gatt.disconnect() }; runCatching { gatt.close() }
                throw e
            }
        }

        private suspend fun subscribe(gatt: BluetoothGatt, cb: Callback, c: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(Gatt.CCCD) ?: return
            val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            var lastStatus = -1
            // Writing the CCCD on an encrypted attribute makes Android start encryption; retry while it settles.
            repeat(4) {
                cb.descDone = CompletableDeferred()
                val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                else @Suppress("DEPRECATION") run { cccd.value = value; gatt.writeDescriptor(cccd) }
                if (ok) {
                    lastStatus = withTimeoutOrNull(8_000) { cb.descDone.await() } ?: -2
                    if (lastStatus == BluetoothGatt.GATT_SUCCESS) return
                }
                if (cb.disconnected) throw BleException("ring disconnected while enabling notifications")
                delay(1500)
            }
            throw BleException(
                if (lastStatus == 5 || lastStatus == 15 || lastStatus == 8)
                    "the ring requires encryption — pair it with this phone first (reset the ring, then add it here)"
                else "could not enable notifications (status $lastStatus)"
            )
        }
    }
}
