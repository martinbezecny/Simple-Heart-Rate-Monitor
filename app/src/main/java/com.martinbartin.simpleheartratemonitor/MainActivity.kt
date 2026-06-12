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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var bleScanner: BluetoothLeScanner? = null

    private lateinit var devicesArrayAdapter: ArrayAdapter<String>
    private val discoveredDevices = ArrayList<BluetoothDevice>()

    // All mutable state below is confined to the main thread. Bluetooth callbacks
    // arrive on binder threads and hop to mainHandler before touching any of it.
    private var bluetoothGatt: BluetoothGatt? = null
    private var devicesSheet: BottomSheetDialog? = null
    private var isScanning = false
    private var lastConnectedDevice: BluetoothDevice? = null
    private var lastConnectedLabel: String? = null
    private var reconnectAttempt = 0
    private var userRequestedDisconnect = false
    private var reconnectRunnable: Runnable? = null

    private lateinit var mainContainer: View
    private lateinit var statusDot: View
    private lateinit var txtStatus: TextView
    private lateinit var imgHeart: ImageView
    private lateinit var txtHeartRate: TextView
    private lateinit var txtBpmLabel: TextView
    private lateinit var txtAverageHeartRate: TextView
    private lateinit var txtResetHint: TextView
    private lateinit var btnTheme: MaterialButton
    private lateinit var btnConnect: Button
    private lateinit var btnIncrease: Button
    private lateinit var btnDecrease: Button

    // Running average; no need to keep every sample.
    private var bpmSum = 0L
    private var bpmCount = 0

    // Heartbeat animation, paced by the latest reading.
    private var currentBpm = 0
    private var heartbeatRunning = false
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatRunning) return
            beatOnce()
            val intervalMs =
                if (currentBpm in 25..250) (60_000L / currentBpm) else 1_000L
            mainHandler.postDelayed(this, intervalMs.coerceAtLeast(300L))
        }
    }

    private lateinit var themePrefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { onScanTimeout() }
    private var currentFontSizeSp = DEFAULT_FONT_SIZE_SP

    companion object {
        private const val TAG = "HeartRateMonitor"
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS_NAME = "ThemePrefs"
        private const val KEY_IS_DARK = "isDarkMode"
        private const val KEY_FONT_SIZE_SP = "fontSizeSp"
        private const val DEFAULT_FONT_SIZE_SP = 190f
        private const val SCAN_DURATION_MS = 15_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val MIN_FONT_SIZE_SP = 20f
        private const val MAX_FONT_SIZE_SP = 400f
        private const val FONT_STEP_SP = 10f
        private const val HEART_DIM_ALPHA = 0.35f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isDark = themePrefs.getBoolean(KEY_IS_DARK, false)
        // Pin the whole app — dialogs and sheets included — to the in-app theme
        // choice, so the system dark-mode setting can't make them disagree.
        applyNightMode(isDark)

        setContentView(R.layout.activity_main)

        mainContainer = findViewById(R.id.main_container)
        statusDot = findViewById(R.id.statusDot)
        txtStatus = findViewById(R.id.txtStatus)
        imgHeart = findViewById(R.id.imgHeart)
        txtHeartRate = findViewById(R.id.txtHeartRate)
        txtBpmLabel = findViewById(R.id.txtBpmLabel)
        txtAverageHeartRate = findViewById(R.id.txtAverageHeartRate)
        txtResetHint = findViewById(R.id.txtResetHint)
        btnTheme = findViewById(R.id.btnTheme)
        btnConnect = findViewById(R.id.btnConnect)
        btnIncrease = findViewById(R.id.btnIncrease)
        btnDecrease = findViewById(R.id.btnDecrease)

        applyColors(isDark)
        imgHeart.alpha = HEART_DIM_ALPHA
        showStatus(getString(R.string.status_not_connected), R.color.status_idle)

        // Restore saved font size
        currentFontSizeSp = themePrefs.getFloat(KEY_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
        txtHeartRate.textSize = currentFontSizeSp

        devicesArrayAdapter = ArrayAdapter(this, R.layout.item_device)

        btnTheme.setOnClickListener {
            val newMode = !themePrefs.getBoolean(KEY_IS_DARK, false)
            themePrefs.edit().putBoolean(KEY_IS_DARK, newMode).apply()
            applyNightMode(newMode)
            applyColors(newMode)
        }

        setupUIListeners()

        // React immediately if Bluetooth is switched off while we're scanning or connected.
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun applyNightMode(isDark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Repaints the screen from the color resources. After [applyNightMode] the
     * resources resolve to the matching day/night palette, so this stays the
     * single repaint pass for both themes.
     */
    private fun applyColors(isDark: Boolean) {
        fun color(res: Int) = ContextCompat.getColor(this, res)
        fun tint(res: Int) = ColorStateList.valueOf(color(res))

        val ink = color(R.color.ink)
        val subtle = color(R.color.text_subtle)

        mainContainer.setBackgroundColor(color(R.color.bg))

        txtHeartRate.setTextColor(ink)
        txtBpmLabel.setTextColor(subtle)
        txtAverageHeartRate.setTextColor(ink)
        txtAverageHeartRate.backgroundTintList = tint(R.color.chip_bg)
        txtResetHint.setTextColor(subtle)
        txtStatus.setTextColor(subtle)
        imgHeart.imageTintList = tint(R.color.heart)

        btnConnect.backgroundTintList = tint(R.color.button_bg)
        btnConnect.setTextColor(color(R.color.button_fg))

        listOf(btnIncrease, btnDecrease).forEach {
            it.backgroundTintList = tint(R.color.chip_bg)
            it.setTextColor(ink)
        }
        btnTheme.backgroundTintList = tint(R.color.chip_bg)
        btnTheme.iconTint = tint(R.color.ink)
        btnTheme.setIconResource(if (isDark) R.drawable.ic_moon else R.drawable.ic_sun)

        val windowController = WindowInsetsControllerCompat(window, window.decorView)
        windowController.isAppearanceLightStatusBars = !isDark
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Deprecated and a no-op under Android 15's enforced edge-to-edge;
            // still needed for older versions.
            @Suppress("DEPRECATION")
            window.statusBarColor = color(R.color.bg)
        }
    }

    private fun showStatus(text: CharSequence, dotColorRes: Int) {
        txtStatus.text = text
        statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))
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

    // ── Heartbeat animation ──────────────────────────────────────────────────────

    private fun beatOnce() {
        imgHeart.animate().scaleX(1.14f).scaleY(1.14f).setDuration(110)
            .withEndAction {
                imgHeart.animate().scaleX(1f).scaleY(1f).setDuration(190).start()
            }
            .start()
    }

    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        imgHeart.alpha = 1f
        mainHandler.post(heartbeatRunnable)
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        imgHeart.animate().cancel()
        imgHeart.scaleX = 1f
        imgHeart.scaleY = 1f
        imgHeart.alpha = HEART_DIM_ALPHA
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

        when {
            missingPermissions.isEmpty() -> initiateBluetoothAction()
            // Denied before, but the system is still willing to ask — explain first.
            missingPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) } ->
                showPermissionRationaleDialog(missingPermissions.toTypedArray())
            else -> permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            when {
                results.values.all { it } -> initiateBluetoothAction()
                // Denied just now AND the system refuses to ask again ("Don't ask again"):
                // only Settings can help from here.
                results.any { (permission, granted) ->
                    !granted && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
                } -> showOpenSettingsDialog()
                else -> showToast(getString(R.string.permissions_denied))
            }
        }

    private fun showPermissionRationaleDialog(permissions: Array<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.permission_rationale_title))
            .setMessage(getString(R.string.permission_rationale_message))
            .setPositiveButton(getString(R.string.grant)) { _, _ ->
                permissionLauncher.launch(permissions)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showOpenSettingsDialog() {
        MaterialAlertDialogBuilder(this)
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

    /** When the user comes back from any Settings screen, re-check and continue automatically. */
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val allGranted = getRequiredPermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                initiateBluetoothAction()
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
        if (!adapter.isEnabled) {
            requestEnableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        // On Android 6–11 the system delivers no BLE scan results unless
        // Location is switched on, even with the permission granted.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            showLocationDialog()
            return
        }
        startBleScan()
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        }
    }

    private fun showLocationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.location_needed_title))
            .setMessage(getString(R.string.location_needed_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) return

        // Starting a new search means abandoning the current connection.
        userRequestedDisconnect = true
        cancelReconnect()
        closeGatt()
        lastConnectedDevice = null
        lastConnectedLabel = null
        reconnectAttempt = 0
        resetDisplay()
        setKeepScreenOn(false)

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
        showStatus(getString(R.string.searching_for_devices), R.color.status_busy)

        showDeviceSheet()

        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        // stopScan throws if Bluetooth was switched off mid-scan.
        runCatching { bleScanner?.stopScan(bleScanCallback) }
            .onFailure { Log.w(TAG, "stopScan failed", it) }
    }

    /** Scan window elapsed: show the result in the sheet instead of leaving it ambiguous. */
    private fun onScanTimeout() {
        stopBleScan()
        val sheet = devicesSheet
        if (sheet?.isShowing != true) {
            showStatus(getString(R.string.status_not_connected), R.color.status_idle)
            return
        }
        sheet.findViewById<View>(R.id.sheetProgress)?.visibility = View.GONE
        if (discoveredDevices.isEmpty()) {
            sheet.findViewById<View>(R.id.sheetEmpty)?.visibility = View.VISIBLE
        }
        showStatus(getString(R.string.status_not_connected), R.color.status_idle)
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Fall back to the advertised name, then the address, so nameless
            // straps still show up (the scan filter guarantees they're HR devices).
            val label = result.scanRecord?.deviceName ?: device.name ?: device.address
            mainHandler.post {
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    devicesArrayAdapter.add(label)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed, errorCode=$errorCode")
            mainHandler.post {
                isScanning = false
                mainHandler.removeCallbacks(scanTimeoutRunnable)
                devicesSheet?.dismiss()
                showStatus(getString(R.string.status_not_connected), R.color.status_idle)
                showToast(getString(R.string.scan_failed, errorCode))
            }
        }
    }

    private fun showDeviceSheet() {
        if (devicesSheet?.isShowing == true) return
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(R.layout.sheet_device_picker)
        sheet.findViewById<ListView>(R.id.sheetList)?.apply {
            adapter = devicesArrayAdapter
            setOnItemClickListener { _, _, which, _ ->
                stopBleScan()
                connectDevice(discoveredDevices[which])
                sheet.dismiss()
            }
        }
        sheet.setOnDismissListener {
            stopBleScan()
            // Only fall back to idle if dismissing didn't lead into a connection.
            if (lastConnectedDevice == null) {
                showStatus(getString(R.string.status_not_connected), R.color.status_idle)
            }
        }
        devicesSheet = sheet
        sheet.show()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        closeGatt()
        lastConnectedDevice = device
        lastConnectedLabel = device.name ?: device.address
        userRequestedDisconnect = false
        reconnectAttempt = 0
        showStatus(getString(R.string.status_connecting), R.color.status_busy)
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: state=$newState status=$status")
            mainHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        reconnectAttempt = 0
                        setKeepScreenOn(true)
                        showStatus(
                            lastConnectedLabel ?: getString(R.string.status_connected),
                            R.color.status_connected
                        )
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.w(TAG, "Disconnected with status=$status")
                        }
                        gatt.close()
                        if (bluetoothGatt === gatt) bluetoothGatt = null
                        if (!userRequestedDisconnect && lastConnectedDevice != null) {
                            scheduleReconnect()
                        } else {
                            resetDisplay()
                            setKeepScreenOn(false)
                            showStatus(getString(R.string.status_not_connected), R.color.status_idle)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Service discovery failed, status=$status")
                    failConnection(R.string.discovery_failed)
                    return@post
                }
                val characteristic = gatt.getService(HEART_RATE_SERVICE_UUID)
                    ?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)
                if (characteristic == null) {
                    failConnection(R.string.hr_not_supported)
                    return@post
                }
                gatt.setCharacteristicNotification(characteristic, true)

                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (descriptor == null) {
                    failConnection(R.string.notify_setup_failed)
                    return@post
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed, status=$status")
                mainHandler.post { failConnection(R.string.notify_setup_failed) }
            }
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Android 13+ delivers the same packet to the newer overload below as well;
            // bail out here so each reading is counted exactly once.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_CHAR_UUID) return
            val value = characteristic.value ?: return
            handleHeartRatePacket(value)
        }

        // API 33+ variant
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_CHAR_UUID) return
            handleHeartRatePacket(value)
        }
    }

    private fun handleHeartRatePacket(value: ByteArray) {
        val heartRate = extractHeartRateFromBytes(value) ?: return
        mainHandler.post {
            txtHeartRate.text = heartRate.toString()
            updateAverageBPM(heartRate)
            currentBpm = heartRate
            startHeartbeat()
        }
    }

    /** Tears down a connection that can't deliver heart rate data, with feedback. */
    private fun failConnection(messageRes: Int) {
        showToast(getString(messageRes))
        userRequestedDisconnect = true
        cancelReconnect()
        closeGatt()
        lastConnectedDevice = null
        lastConnectedLabel = null
        resetDisplay()
        setKeepScreenOn(false)
        showStatus(getString(R.string.status_not_connected), R.color.status_idle)
    }

    // ── Auto-Reconnect ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            resetDisplay()
            setKeepScreenOn(false)
            showStatus(getString(R.string.status_not_connected), R.color.status_idle)
            showToast(getString(R.string.reconnect_failed))
            lastConnectedDevice = null
            lastConnectedLabel = null
            reconnectAttempt = 0
            return
        }

        reconnectAttempt++
        showStatus(getString(R.string.status_reconnecting), R.color.status_busy)

        val runnable = Runnable {
            reconnectRunnable = null
            val device = lastConnectedDevice ?: return@Runnable
            closeGatt()
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, RECONNECT_BASE_DELAY_MS * reconnectAttempt)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ── Bluetooth switched off ───────────────────────────────────────────────────

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                // Receivers registered on the main looper run on the main thread.
                onBluetoothTurnedOff()
            }
        }
    }

    private fun onBluetoothTurnedOff() {
        val wasActive = isScanning || bluetoothGatt != null || lastConnectedDevice != null
        userRequestedDisconnect = true
        cancelReconnect()
        stopBleScan()
        lastConnectedDevice = null
        lastConnectedLabel = null
        devicesSheet?.dismiss()
        closeGatt()
        reconnectAttempt = 0
        resetDisplay()
        setKeepScreenOn(false)
        showStatus(getString(R.string.status_not_connected), R.color.status_idle)
        if (wasActive) {
            showToast(getString(R.string.bluetooth_turned_off))
        }
    }

    // ── Heart Rate Parsing ───────────────────────────────────────────────────────

    /**
     * Parses a Heart Rate Measurement packet (GATT characteristic 0x2A37).
     * Returns null for malformed packets so they never poison the average.
     */
    private fun extractHeartRateFromBytes(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt()
        return if ((flags and 0x01) != 0) {
            // UINT16 format
            if (value.size >= 3) (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8) else null
        } else {
            // UINT8 format
            if (value.size >= 2) value[1].toInt() and 0xFF else null
        }
    }

    // ── Average BPM ──────────────────────────────────────────────────────────────

    private fun updateAverageBPM(newBPM: Int) {
        bpmSum += newBPM
        bpmCount++
        val average = (bpmSum.toDouble() / bpmCount).roundToInt()
        txtAverageHeartRate.text = getString(R.string.average_bpm_format, average)
    }

    private fun resetAverageBPM() {
        bpmSum = 0L
        bpmCount = 0
        txtAverageHeartRate.text = getString(R.string.average_bpm_placeholder)
    }

    private fun resetDisplay() {
        txtHeartRate.text = getString(R.string.bpm_placeholder)
        resetAverageBPM()
        currentBpm = 0
        stopHeartbeat()
    }

    // ── Activity Result ──────────────────────────────────────────────────────────

    private val requestEnableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                initiateBluetoothAction()
            } else {
                showToast(getString(R.string.bluetooth_required))
            }
        }

    // ── Utilities ────────────────────────────────────────────────────────────────

    /** Keep the display awake only while a strap is actually connected. */
    private fun setKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        userRequestedDisconnect = true
        heartbeatRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        stopBleScan()
        devicesSheet?.dismiss()
        devicesSheet = null
        closeGatt()
        lastConnectedDevice = null
        unregisterReceiver(bluetoothStateReceiver)
    }
}
