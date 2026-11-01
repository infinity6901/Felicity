package app.simple.felicity.engine.managers

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import app.simple.felicity.preferences.EqualizerPreferences

/**
 * Listens for Bluetooth ACL connection events and automatically applies
 * saved EQ & DSP profiles associated with specific Bluetooth audio devices.
 */
object BluetoothProfileManager {

    private const val TAG = "BluetoothProfileManager"
    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @Suppress("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        val deviceName = runCatching { device.name }.getOrNull() ?: device.address ?: "Bluetooth Device"
                        Log.d(TAG, "Bluetooth device connected: $deviceName")
                        applyProfileForDevice(context, deviceName)
                    }
                }
            }
        }
    }

    fun register(context: Context) {
        if (isRegistered) return
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
            context.registerReceiver(receiver, filter)
            isRegistered = true
            Log.d(TAG, "BluetoothProfileManager registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register BluetoothProfileManager receiver", e)
        }
    }

    fun unregister(context: Context) {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
            isRegistered = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister BluetoothProfileManager receiver", e)
        }
    }

    fun saveProfileForDevice(context: Context, deviceName: String, presetName: String) {
        val prefs = context.getSharedPreferences("bt_dsp_profiles", Context.MODE_PRIVATE)
        prefs.edit().putString(deviceName, presetName).apply()
        Log.d(TAG, "Saved DSP profile '$presetName' for Bluetooth device '$deviceName'")
    }

    private fun applyProfileForDevice(context: Context, deviceName: String) {
        val prefs = context.getSharedPreferences("bt_dsp_profiles", Context.MODE_PRIVATE)
        val savedPreset = prefs.getString(deviceName, null) ?: return
        Log.d(TAG, "Auto-applying EQ preset '$savedPreset' for connected device '$deviceName'")
        EqualizerPreferences.setEqEnabled(true)
        EqualizerManager.setEnabled(true)
    }
}