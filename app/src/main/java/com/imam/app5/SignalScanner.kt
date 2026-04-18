package com.imam.app5

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import kotlin.math.abs

data class RadioSnapshot(
    val wifiCloseness: Double = 0.0,
    val bleCloseness: Double = 0.0,
    val cellInstability: Double = 0.0,
    val strongestWifiRssi: Int = -100,
    val strongestBleRssi: Int = -127,
    val strongestCellDbm: Int = -140,
    val wifiCount: Int = 0,
    val bleCount: Int = 0
)

class SignalScanner(
    context: Context,
    private val onUpdate: (RadioSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val bleDevices = mutableMapOf<String, Pair<Int, Long>>()

    private var running = false
    private var strongestWifiRssi = -100
    private var strongestCellDbm = -140
    private var previousCellDbm = -140
    private var cellInstability = 0.0
    private var wifiCount = 0
    private var receiverRegistered = false

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshWifi()
            emit()
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val key = result.device.address ?: result.device.name ?: return
            bleDevices[key] = result.rssi to SystemClock.elapsedRealtime()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val now = SystemClock.elapsedRealtime()
            results.forEach { result ->
                val key = result.device.address ?: result.device.name ?: return@forEach
                bleDevices[key] = result.rssi to now
            }
        }
    }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!running) {
                return
            }
            refreshWifi()
            refreshCell()
            emit()
            handler.postDelayed(this, 3500L)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

        if (!receiverRegistered) {
            appContext.registerReceiver(
                wifiReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )
            receiverRegistered = true
        }

        try {
            bluetoothManager.adapter?.bluetoothLeScanner?.startScan(bleCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }

        handler.post(sampleRunnable)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)

        try {
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(bleCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }

        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(wifiReceiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshWifi() {
        runCatching { wifiManager.startScan() }

        val results = runCatching { wifiManager.scanResults.orEmpty() }.getOrDefault(emptyList())
        wifiCount = results.size
        strongestWifiRssi = results.maxOfOrNull { it.level }
            ?: runCatching { wifiManager.connectionInfo.rssi }.getOrDefault(-100)
    }

    @SuppressLint("MissingPermission")
    private fun refreshCell() {
        val cellInfos = runCatching { telephonyManager.allCellInfo.orEmpty() }.getOrDefault(emptyList())
        val dbmValues = cellInfos.mapNotNull { info ->
            runCatching { info.cellSignalStrength.dbm }.getOrNull()
        }.filter { it > -140 }

        strongestCellDbm = dbmValues.maxOrNull() ?: -140

        cellInstability = if (previousCellDbm > -140 && strongestCellDbm > -140) {
            (((abs(strongestCellDbm - previousCellDbm) - 2).coerceAtLeast(0)).toDouble() / 18.0)
                .coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        previousCellDbm = strongestCellDbm
    }

    private fun emit() {
        val now = SystemClock.elapsedRealtime()
        val freshBle = bleDevices.filterValues { (_, seenAt) -> now - seenAt <= 8000L }
        bleDevices.clear()
        bleDevices.putAll(freshBle)

        val strongestBleRssi = freshBle.values.maxOfOrNull { it.first } ?: -127
        val bleCount = freshBle.size

        onUpdate(
            RadioSnapshot(
                wifiCloseness = normalize(strongestWifiRssi, -90, -40),
                bleCloseness = normalize(strongestBleRssi, -100, -45),
                cellInstability = cellInstability,
                strongestWifiRssi = strongestWifiRssi,
                strongestBleRssi = strongestBleRssi,
                strongestCellDbm = strongestCellDbm,
                wifiCount = wifiCount,
                bleCount = bleCount
            )
        )
    }

    private fun normalize(value: Int, min: Int, max: Int): Double {
        return ((value - min).toDouble() / (max - min).toDouble()).coerceIn(0.0, 1.0)
    }
}
