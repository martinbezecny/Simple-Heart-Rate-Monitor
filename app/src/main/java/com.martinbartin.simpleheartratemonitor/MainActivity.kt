package com.martinbartin.simpleheartratemonitor

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private lateinit var devicesArrayAdapter: ArrayAdapter<String>
    private val discoveredDevices = ArrayList<BluetoothDevice>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var devicesDialog: AlertDialog? = null
    private lateinit var txtHeartRate: TextView
    private lateinit var txtAverageHeartRate: TextView

    private val bpmValues = mutableListOf<Int>()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        val connectButton = findViewById<Button>(R.id.btnConnect)
        val btnDecrease = findViewById<Button>(R.id.btnDecrease)
        val btnIncrease = findViewById<Button>(R.id.btnIncrease)
        txtHeartRate = findViewById(R.id.txtHeartRate)
        txtAverageHeartRate = findViewById(R.id.txtAverageHeartRate)

        devicesArrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)

        setupUIListeners(connectButton, btnDecrease, btnIncrease)

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(receiver, filter)

        checkPermissionsAndProceed()
    }

    // Modern way to handle permission results
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showToast(getString(R.string.permissions_granted))
                initiateBluetoothAction()
            } else {
                showToast(getString(R.string.permissions_denied))
            }
        }
    }

    // Modern way to handle Activity results (e.g., enabling Bluetooth)
    private val requestEnableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startDiscovery()
        } else {
            showToast(getString(R.string.bluetooth_required))
        }
    }

    private fun setupUIListeners(connectButton: Button, btnDecrease: Button, btnIncrease: Button) {
        connectButton.setOnClickListener {
            initiateBluetoothAction()
        }

        btnDecrease.setOnClickListener {
            val currentSize = txtHeartRate.textSize / resources.displayMetrics.scaledDensity
            if (currentSize > 20) { // Set a reasonable minimum size
                txtHeartRate.textSize = currentSize - 10
            }
        }

        btnIncrease.setOnClickListener {
            val currentSize = txtHeartRate.textSize / resources.displayMetrics.scaledDensity
            txtHeartRate.textSize = currentSize + 10
        }

        txtAverageHeartRate.setOnClickListener {
            resetAverageBPM()
        }
    }

    private fun checkPermissionsAndProceed() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initiateBluetoothAction()
        }
    }

    private fun initiateBluetoothAction() {
        bluetoothAdapter?.let { adapter ->
            if (adapter.isEnabled) {
                startDiscovery()
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                requestEnableBluetooth.launch(enableBtIntent)
            }
        } ?: run {
            showToast(getString(R.string.bluetooth_not_supported))
        }
    }

    private fun startDiscovery() {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        // Clear previous results
        devicesArrayAdapter.clear()
        discoveredDevices.clear()
        devicesArrayAdapter.notifyDataSetChanged()

        bluetoothAdapter?.startDiscovery()
        showToast(getString(R.string.searching_for_devices))
        showDeviceListDialog()
    }

    private fun showDeviceListDialog() {
        if (devicesDialog?.isShowing == true) return

        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.dialog_title_select_device))
        builder.setAdapter(devicesArrayAdapter) { _, which ->
            val device = discoveredDevices[which]
            showToast(getString(R.string.connecting_to_device, device.name ?: device.address))
            connectDevice(device)
            devicesDialog?.dismiss()
        }
        builder.setOnCancelListener { devicesDialog = null }
        devicesDialog = builder.create()
        devicesDialog?.show()
    }

    private fun connectDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("BluetoothGatt", "Connected to GATT server.")
                    bluetoothGatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("BluetoothGatt", "Disconnected from GATT server.")
                    runOnUiThread {
                        txtHeartRate.text = getString(R.string.bpm_placeholder)
                        resetAverageBPM()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val heartRateService = gatt.getService(HEART_RATE_SERVICE_UUID)
                val heartRateCharacteristic = heartRateService?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)
                if (heartRateCharacteristic != null) {
                    gatt.setCharacteristicNotification(heartRateCharacteristic, true)
                    val descriptor = heartRateCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                Log.w("BluetoothGatt", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_CHAR_UUID) {
                val heartRate = extractHeartRate(characteristic)
                runOnUiThread {
                    txtHeartRate.text = heartRate.toString()
                    updateAverageBPM(heartRate)
                }
            }
        }
    }

    private fun extractHeartRate(characteristic: BluetoothGattCharacteristic): Int {
        val flag = characteristic.value[0].toInt()
        val format = if ((flag and 0x01) != 0) {
            BluetoothGattCharacteristic.FORMAT_UINT16
        } else {
            BluetoothGattCharacteristic.FORMAT_UINT8
        }
        return characteristic.getIntValue(format, 1)
    }

    private fun updateAverageBPM(newBPM: Int) {
        bpmValues.add(newBPM)
        val average = bpmValues.average().toInt()
        txtAverageHeartRate.text = getString(R.string.average_bpm_prefix) + average.toString()
    }

    private fun resetAverageBPM() {
        bpmValues.clear()
        txtAverageHeartRate.text = getString(R.string.average_bpm_placeholder)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val deviceName = it.name
                        if (deviceName != null && !discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                            devicesArrayAdapter.add(it.name)
                            devicesArrayAdapter.notifyDataSetChanged()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    showToast(getString(R.string.search_finished))
                }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothAdapter?.cancelDiscovery()
        bluetoothGatt?.close()
        unregisterReceiver(receiver)
    }
}
