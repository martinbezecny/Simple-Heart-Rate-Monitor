package com.martinbartin.simpleheartratemonitor

import android.Manifest
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
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : AppCompatActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var devicesArrayAdapter: ArrayAdapter<String>
    private val devices = ArrayList<String>()
    private var bluetoothGatt: BluetoothGatt? = null
    private var devicesDialog: AlertDialog? = null
    private lateinit var txtHeartRate: TextView
    private lateinit var txtAverageHeartRate: TextView

    // List to store BPM values for calculating the average
    private val bpmValues = mutableListOf<Int>()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 101
        private const val REQUEST_ENABLE_BT = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val connectButton = findViewById<Button>(R.id.btnConnect)
        val btnDecrease = findViewById<Button>(R.id.btnDecrease)
        val btnIncrease = findViewById<Button>(R.id.btnIncrease)
        txtHeartRate = findViewById(R.id.txtHeartRate)
        txtAverageHeartRate = findViewById(R.id.txtAverageHeartRate)

        devicesArrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devices)

        checkAndRequestPermissions()

        connectButton.setOnClickListener {
            if (bluetoothAdapter != null && !bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else {
                startDiscovery()
            }
        }

        btnDecrease.setOnClickListener {
            val currentSize = txtHeartRate.textSize / resources.displayMetrics.scaledDensity
            if (currentSize > 10) {
                txtHeartRate.textSize = currentSize - 10
            }
        }

        btnIncrease.setOnClickListener {
            val currentSize = txtHeartRate.textSize / resources.displayMetrics.scaledDensity
            txtHeartRate.textSize = currentSize + 10
        }

        // Set up the click listener for the average BPM TextView
        txtAverageHeartRate.setOnClickListener {
            resetAverageBPM()
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(receiver, filter)
    }

    private fun startDiscovery() {
        if (bluetoothAdapter!!.isDiscovering) {
            bluetoothAdapter!!.cancelDiscovery()
        }
        bluetoothAdapter!!.startDiscovery()
        Toast.makeText(this, "Searching for devices...", Toast.LENGTH_SHORT).show()
        showDeviceListDialog()
    }

    private fun showDeviceListDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select a Device")
        builder.setAdapter(devicesArrayAdapter) { _, which ->
            val deviceInfo = devices[which]
            val deviceAddress = deviceInfo.substring(deviceInfo.indexOf("(") + 1, deviceInfo.indexOf(")"))
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            device?.let {
                Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
                connectDevice(it)
                devicesDialog?.dismiss()
            }
        }
        builder.setOnCancelListener { devicesDialog = null }
        devicesDialog = builder.create()
        devicesDialog?.show()
    }

    private fun connectDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i("BluetoothGatt", "Connected to GATT server.")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i("BluetoothGatt", "Disconnected from GATT server.")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.services.forEach { service ->
                        Log.i("BluetoothGatt", "Service discovered: ${service.uuid}")
                        if (service.uuid.toString().equals("0000180d-0000-1000-8000-00805f9b34fb", ignoreCase = true)) {
                            val heartRateCharacteristic = service.getCharacteristic(UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb"))
                            if (heartRateCharacteristic != null) {
                                gatt.setCharacteristicNotification(heartRateCharacteristic, true)
                                val descriptor = heartRateCharacteristic.getDescriptor(
                                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                } else {
                    Log.w("BluetoothGatt", "onServicesDiscovered received: $status")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")) {
                    val heartRate = extractHeartRate(characteristic)
                    runOnUiThread {
                        txtHeartRate.text = heartRate.toString()
                        updateAverageBPM(heartRate) // Update the average BPM
                    }
                }
            }

            private fun extractHeartRate(characteristic: BluetoothGattCharacteristic): Int {
                val flag = characteristic.properties
                val format = if (flag and 0x01 != 0) {
                    BluetoothGattCharacteristic.FORMAT_UINT16
                } else {
                    BluetoothGattCharacteristic.FORMAT_UINT8
                }
                return characteristic.getIntValue(format, 1)
            }
        })
    }

    // Helper function to update the average BPM
    private fun updateAverageBPM(newBPM: Int) {
        bpmValues.add(newBPM) // Add the new BPM value to the list
        val average = bpmValues.average().toInt() // Calculate the average
        txtAverageHeartRate.text = "Avg: $average" // Update the UI
    }

    // Helper function to reset the average BPM
    private fun resetAverageBPM() {
        bpmValues.clear() // Clear the list of BPM values
        txtAverageHeartRate.text = "Avg: ---" // Reset the UI
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (requiredPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            if (bluetoothAdapter != null && !bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else {
                startDiscovery()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "All necessary permissions granted.", Toast.LENGTH_SHORT).show()
                if (bluetoothAdapter != null && !bluetoothAdapter!!.isEnabled) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                } else {
                    startDiscovery()
                }
            } else {
                Toast.makeText(this, "All necessary permissions must be granted.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        val deviceName = device.name ?: return
                        if (deviceName != "Unknown device") {
                            val deviceAddress = device.address
                            val deviceEntry = "$deviceName ($deviceAddress)"
                            if (!devices.contains(deviceEntry)) {
                                devices.add(deviceEntry)
                                devicesArrayAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Toast.makeText(context, "Done searching for devices.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
        unregisterReceiver(receiver)
    }
}
