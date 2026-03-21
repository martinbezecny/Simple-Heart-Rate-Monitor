package com.martinbartin.simpleheartratemonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var bleScanner: BluetoothLeScanner? = null

    private lateinit var devicesArrayAdapter: ArrayAdapter<String>
    private val discoveredDevices = ArrayList<BluetoothDevice>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var devicesDialog: AlertDialog? = null
    private var isScanning = false
    private var lastConnectedDevice: BluetoothDevice? = null
    private var reconnectAttempt = 0
    private var userRequestedDisconnect = false

    private lateinit var txtHeartRate: TextView
    private lateinit var txtAverageHeartRate: TextView
    private lateinit var txtResetHint: TextView

    private lateinit var mainContainer: View
    private lateinit var btnTheme: Button
    private lateinit var btnConnect: Button
    private lateinit var btnIncrease: Button
    private lateinit var btnDecrease: Button

    private val bpmValues = mutableListOf<Int>()
    private lateinit var themePrefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentFontSizeSp = DEFAULT_FONT_SIZE_SP

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS_NAME = "ThemePrefs"
        private const val KEY_IS_DARK = "isDarkMode"
        private const val PREF_PERMISSIONS_ASKED = "permissionsAsked"
        private const val KEY_FONT_SIZE_SP = "fontSizeSp"
        private const val DEFAULT_FONT_SIZE_SP = 190f
        private const val SCAN_DURATION_MS = 15_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val MIN_FONT_SIZE_SP = 20f
        private const val MAX_FONT_SIZE_SP = 400f
        private const val FONT_STEP_SP = 10f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainContainer = findViewById(R.id.main_container)
        btnTheme = findViewById(R.id.btnTheme)
        btnConnect = findViewById(R.id.btnConnect)
        btnIncrease = findViewById(R.id.btnIncrease)
        btnDecrease = findViewById(R.id.btnDecrease)
        txtHeartRate = findViewById(R.id.txtHeartRate)
        txtAverageHeartRate = findViewById(R.id.txtAverageHeartRate)
        txtResetHint = findViewById(R.id.txtResetHint)

        themePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyColors(themePrefs.getBoolean(KEY_IS_DARK, false))

        // Restore saved font size
        currentFontSizeSp = themePrefs.getFloat(KEY_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
        txtHeartRate.textSize = currentFontSizeSp

        devicesArrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)

        btnTheme.setOnClickListener {
            val newMode = !themePrefs.getBoolean(KEY_IS_DARK, false)
            themePrefs.edit().putBoolean(KEY_IS_DARK, newMode).apply()
            applyColors(newMode)
        }

        setupUIListeners()
    }

    private fun applyColors(isDark: Boolean) {
        val bgColor = if (isDark) Color.BLACK else Color.WHITE
        val btnColor = if (isDark) Color.parseColor("#333333") else Color.BLACK
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val hintColor = if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#808080")

        mainContainer.setBackgroundColor(bgColor)

        listOf(btnConnect, btnIncrease, btnDecrease, btnTheme).forEach {
            it.backgroundTintList = ColorStateList.valueOf(btnColor)
            it.setTextColor(Color.WHITE)
        }

        txtHeartRate.setTextColor(textColor)
        txtAverageHeartRate.setTextColor(textColor)
        txtResetHint.setTextColor(hintColor)

        val windowController = WindowInsetsControllerCompat(window, window.decorView)
        windowController.isAppearanceLightStatusBars = !isDark
        window.statusBarColor = bgColor

        btnTheme.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        btnTheme.text = getString(if (isDark) R.string.theme_dark else R.string.theme_light)
    }

    private fun setupUIListeners() {
        btnConnect.setOnClickListener { checkPermissionsAndProceed() }

        btnDecrease.setOnClickListener {
            if (currentFontSizeSp > MIN_FONT_SIZE_SP) {
                currentFontSizeSp = (currentFontSizeSp - FONT_STEP_SP).coerceAtLeast(MIN_FONT_SIZE_SP)
                txtHeartRate.textSize = currentFontSizeSp
                themePrefs.edit().putFloat(KEY_FONT_SIZE_SP, currentFontSizeSp).apply()
            }
        }

        btnIncrease.setOnClickListener {
            if (currentFontSizeSp < MAX_FONT_SIZE_SP) {
                currentFontSizeSp = (currentFontSizeSp + FONT_STEP_SP).coerceAtMost(MAX_FONT_SIZE_SP)
                txtHeartRate.textSize = currentFontSizeSp
                themePrefs.edit().putFloat(KEY_FONT_SIZE_SP, currentFontSizeSp).apply()
            }
        }

        txtAverageHeartRate.setOnClickListener { resetAverageBPM() }
    }

    // ── Permissions ──────────────────────────────────────────────────────────────

    private fun getRequiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun checkPermissionsAndProceed() {
        val missingPermissions = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initiateBluetoothAction()
            return
        }

        // Check if any missing permission was permanently denied ("Don't ask again").
        // shouldShowRequestPermissionRationale returns false in two cases:
        //   1. Permission was never requested yet (first launch)
        //   2. User tapped "Don't ask again"
        // We distinguish them by checking if we've ever asked before.
        val permanentlyDenied = missingPermissions.any { permission ->
            !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
                    && themePrefs.getBoolean(PREF_PERMISSIONS_ASKED, false)
        }

        if (permanentlyDenied) {
            // System won't show the prompt anymore — guide user to Settings
            showOpenSettingsDialog()
        } else if (missingPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }) {
            // User denied before but didn't pick "Don't ask again" — show rationale then re-ask
            showPermissionRationaleDialog(missingPermissions.toTypedArray())
        } else {
            // First time asking
            requestPermissions(missingPermissions.toTypedArray())
        }
    }

    private fun requestPermissions(permissions: Array<String>) {
        // Remember that we've asked at least once
        themePrefs.edit().putBoolean(PREF_PERMISSIONS_ASKED, true).apply()
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    private fun showPermissionRationaleDialog(permissions: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_rationale_title))
            .setMessage(getString(R.string.permission_rationale_message))
            .setPositiveButton(getString(R.string.grant)) { _, _ ->
                requestPermissions(permissions)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showOpenSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_settings_title))
            .setMessage(getString(R.string.permission_settings_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                settingsLauncher.launch(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** When user comes back from Settings, re-check permissions automatically. */
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User is back from Settings — check if they granted the permissions
            val allGranted = getRequiredPermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                initiateBluetoothAction()
            }
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initiateBluetoothAction()
            } else {
                showToast(getString(R.string.permissions_denied))
            }
        }
    }

    // ── Bluetooth ────────────────────────────────────────────────────────────────

    private fun initiateBluetoothAction() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            showToast(getString(R.string.bluetooth_not_supported))
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showToast(getString(R.string.ble_not_supported))
            return
        }
        if (adapter.isEnabled) {
            startBleScan()
        } else {
            requestEnableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) return

        // Cancel any pending auto-reconnect
        userRequestedDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        bluetoothGatt?.close()
        bluetoothGatt = null
        lastConnectedDevice = null
        reconnectAttempt = 0

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            showToast(getString(R.string.bluetooth_required))
            return
        }

        devicesArrayAdapter.clear()
        discoveredDevices.clear()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(scanFilter), scanSettings, bleScanCallback)
        isScanning = true

        showDeviceListDialog()

        // Auto-stop scan after timeout
        mainHandler.postDelayed({
            stopBleScan()
        }, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (isScanning) {
            bleScanner?.stopScan(bleScanCallback)
            isScanning = false
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: return
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                runOnUiThread {
                    devicesArrayAdapter.add(deviceName)
                    devicesArrayAdapter.notifyDataSetChanged()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            runOnUiThread {
                showToast(getString(R.string.search_finished))
            }
        }
    }

    private fun showDeviceListDialog() {
        if (devicesDialog?.isShowing == true) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.dialog_title_select_device)
        builder.setAdapter(devicesArrayAdapter) { _, which ->
            stopBleScan()
            val device = discoveredDevices[which]
            connectDevice(device)
            devicesDialog?.dismiss()
        }
        builder.setOnDismissListener {
            stopBleScan()
        }
        devicesDialog = builder.create()
        devicesDialog?.show()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        bluetoothGatt?.close()
        lastConnectedDevice = device
        userRequestedDisconnect = false
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempt = 0
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    if (!userRequestedDisconnect && lastConnectedDevice != null) {
                        scheduleReconnect()
                    } else {
                        runOnUiThread {
                            txtHeartRate.text = getString(R.string.bpm_placeholder)
                            resetAverageBPM()
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(HEART_RATE_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID) ?: return

            gatt.setCharacteristicNotification(char, true)

            val desc = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_CHAR_UUID) {
                val heartRate = extractHeartRate(characteristic)
                runOnUiThread {
                    txtHeartRate.text = heartRate.toString()
                    updateAverageBPM(heartRate)
                }
            }
        }

        // API 33+ variant
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_CHAR_UUID) {
                val heartRate = extractHeartRateFromBytes(value)
                runOnUiThread {
                    txtHeartRate.text = heartRate.toString()
                    updateAverageBPM(heartRate)
                }
            }
        }
    }

    // ── Auto-Reconnect ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            runOnUiThread {
                txtHeartRate.text = getString(R.string.bpm_placeholder)
                resetAverageBPM()
                showToast(getString(R.string.reconnect_failed))
            }
            lastConnectedDevice = null
            reconnectAttempt = 0
            return
        }

        reconnectAttempt++
        val delay = RECONNECT_BASE_DELAY_MS * reconnectAttempt
        runOnUiThread {
            showToast(getString(R.string.reconnecting, reconnectAttempt, MAX_RECONNECT_ATTEMPTS))
        }

        mainHandler.postDelayed({
            lastConnectedDevice?.let { device ->
                bluetoothGatt?.close()
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            }
        }, delay)
    }

    // ── Heart Rate Parsing ───────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun extractHeartRate(characteristic: BluetoothGattCharacteristic): Int {
        val flag = characteristic.value[0].toInt()
        val format = if ((flag and 0x01) != 0) {
            BluetoothGattCharacteristic.FORMAT_UINT16
        } else {
            BluetoothGattCharacteristic.FORMAT_UINT8
        }
        return characteristic.getIntValue(format, 1)
    }

    private fun extractHeartRateFromBytes(value: ByteArray): Int {
        if (value.isEmpty()) return 0
        val flag = value[0].toInt()
        return if ((flag and 0x01) != 0) {
            // UINT16 format
            if (value.size >= 3) (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            else 0
        } else {
            // UINT8 format
            if (value.size >= 2) value[1].toInt() and 0xFF
            else 0
        }
    }

    // ── Average BPM ──────────────────────────────────────────────────────────────

    private fun updateAverageBPM(newBPM: Int) {
        bpmValues.add(newBPM)
        val average = bpmValues.average().toInt()
        txtAverageHeartRate.text = getString(R.string.average_bpm_format, average)
    }

    private fun resetAverageBPM() {
        bpmValues.clear()
        txtAverageHeartRate.text = getString(R.string.average_bpm_placeholder)
    }

    // ── Activity Result ──────────────────────────────────────────────────────────

    private val requestEnableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startBleScan()
            }
        }

    // ── Utilities ────────────────────────────────────────────────────────────────

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        userRequestedDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        stopBleScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        lastConnectedDevice = null
    }
}
