package app.simple.felicity.engine.managers

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.simple.felicity.preferences.EqualizerPreferences
import app.simple.felicity.repository.database.instances.EqualizerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens for Bluetooth ACL connection events and automatically applies
 * saved EQ & DSP profiles associated with specific Bluetooth audio devices.
 */
object BluetoothProfileManager {

    private const val TAG = "BluetoothProfileManager"
    private var isRegistered = false
    private var registeredContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val receiver = object : BroadcastReceiver() {
        @Suppress("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        val hasBtPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                                ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                        val deviceName = if (hasBtPermission) {
                            runCatching { device.name }.getOrNull() ?: device.address ?: "Bluetooth Device"
                        } else {
                            runCatching { device.address }.getOrNull() ?: "Bluetooth Device"
                        }
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
            val appContext = context.applicationContext
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
            appContext.registerReceiver(receiver, filter)
            registeredContext = appContext
            isRegistered = true
            Log.d(TAG, "BluetoothProfileManager registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register BluetoothProfileManager receiver", e)
        }
    }

    fun unregister(context: Context) {
        if (!isRegistered) return
        try {
            (registeredContext ?: context.applicationContext).unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister BluetoothProfileManager receiver", e)
        } finally {
            registeredContext = null
            isRegistered = false
        }
    }

    fun saveProfileForDevice(context: Context, deviceName: String, presetName: String) {
        val prefs = context.getSharedPreferences("bt_dsp_profiles", Context.MODE_PRIVATE)
        prefs.edit().putString(deviceName, presetName).apply()
        Log.d(TAG, "Saved DSP profile '$presetName' for Bluetooth device '$deviceName'")
    }

    private fun applyProfileForDevice(context: Context, deviceName: String) {
        val prefs = context.getSharedPreferences("bt_dsp_profiles", Context.MODE_PRIVATE)
        val savedPresetName = prefs.getString(deviceName, null) ?: return
        Log.d(TAG, "Auto-applying EQ preset '$savedPresetName' for connected device '$deviceName'")
        scope.launch {
            val preset = EqualizerDatabase.getInstance(context)
                .equalizerPresetDao()
                .getPresetByName(savedPresetName)

            EqualizerPreferences.setEqEnabled(true)
            EqualizerManager.setEnabled(true)

            if (preset != null) {
                EqualizerManager.setPreamp(preset.preampDb)
                if (preset.isPeq()) {
                    EqualizerManager.setEqMode(EqualizerPreferences.EQ_MODE_PARAMETRIC)
                    EqualizerPreferences.setPeqBandsRaw(preset.peqBandsRaw)
                    val parsed = preset.getPeqBands()
                    if (parsed.isNotEmpty()) {
                        EqualizerManager.setPeqBands(parsed)
                    }
                } else {
                    EqualizerManager.setEqMode(EqualizerPreferences.EQ_MODE_GRAPHIC)
                    EqualizerManager.setAllBands(preset.getBandGains())
                }
            }
        }
    }
}