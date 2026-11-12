package app.simple.felicity.manager

import android.content.Context
import android.content.SharedPreferences
import app.simple.felicity.manager.SharedPreferences.init
import app.simple.felicity.shared.utils.ConditionUtils.isNull
import dev.spght.encryptedprefs.EncryptedSharedPreferences
import dev.spght.encryptedprefs.MasterKey
import android.util.Log
import java.io.File

object SharedPreferences {

    private const val PREFERENCES = "Preferences"
    private const val PREFERENCES_ENCRYPTED = "PreferencesSecured"
    private var sharedPreferences: SharedPreferences? = null
    private var encryptedSharedPreferences: SharedPreferences? = null

    private var isInitialized = false

    fun init(context: Context) {
        if (sharedPreferences.isNull()) {
            sharedPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
    }

    fun Context.initSharedPreferences() {
        if (sharedPreferences.isNull()) {
            sharedPreferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
    }

    fun SharedPreferences.OnSharedPreferenceChangeListener.initRegisterSharedPreferenceChangeListener(context: Context) {
        init(context)
        registerSharedPreferenceChangeListener()
    }

    fun initEncrypted(context: Context) {
        if (encryptedSharedPreferences != null) return
        runCatching {
            val masterKeyAlias = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            encryptedSharedPreferences = EncryptedSharedPreferences.create(
                    context,
                    PREFERENCES_ENCRYPTED,
                    masterKeyAlias,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.onFailure { error ->
            // Do not destructively delete the encrypted preferences file on transient or
            // keystore/backup-restore failures; preserve it and surface diagnostics.
            Log.e("SharedPreferences", "Failed to initialize encrypted shared preferences", error)
        }
    }

    fun resetEncrypted(context: Context) {
        encryptedSharedPreferences = null
        runCatching { File(getEncryptedSharedPreferencesPath(context)).delete() }
        initEncrypted(context)
    }

    /**
     * Singleton to hold reference of SharedPreference.
     * Call [init] first before making a instance request
     *
     * @see init
     * @throws NullPointerException
     */
    fun getSharedPreferences(): SharedPreferences {
        return sharedPreferences ?: throw NullPointerException()
    }

    fun getSharedPreference(context: Context): SharedPreferences {
        init(context)
        return sharedPreferences ?: throw NullPointerException()
    }

    fun getEncryptedSharedPreferences(): SharedPreferences {
        return encryptedSharedPreferences ?: throw NullPointerException()
    }

    fun registerSharedPreferencesListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        getSharedPreferences().registerOnSharedPreferenceChangeListener(listener)
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        getSharedPreferences().registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        getSharedPreferences().unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Use this function to register shared preference change listener if
     * the current context has [SharedPreferences.OnSharedPreferenceChangeListener]
     * implemented.
     */
    fun SharedPreferences.OnSharedPreferenceChangeListener.registerSharedPreferenceChangeListener() {
        registerSharedPreferencesListener(this)
    }

    /**
     * Use this function to unregister shared preference change listener if
     * the current context has [SharedPreferences.OnSharedPreferenceChangeListener]
     * implemented.
     */
    fun SharedPreferences.OnSharedPreferenceChangeListener.unregisterSharedPreferenceChangeListener() {
        unregisterListener(this)
    }

    fun SharedPreferences.OnSharedPreferenceChangeListener.registerEncryptedSharedPreferencesListener() {
        getEncryptedSharedPreferences().registerOnSharedPreferenceChangeListener(this)
    }

    fun SharedPreferences.OnSharedPreferenceChangeListener.unregisterEncryptedSharedPreferencesListener() {
        getEncryptedSharedPreferences().unregisterOnSharedPreferenceChangeListener(this)
    }

    /**
     * Singleton to hold reference of SharedPreference.
     *
     * @see init
     */
    fun getSharedPreferences(context: Context): SharedPreferences {
        kotlin.runCatching {
            return sharedPreferences ?: throw NullPointerException()
        }.getOrElse {
            init(context)
            return sharedPreferences!!
        }
    }

    fun getSharedPreferencesPath(context: Context): String {
        return context.applicationInfo.dataDir + "/shared_prefs/" + PREFERENCES + ".xml"
    }

    fun getEncryptedSharedPreferencesPath(context: Context): String {
        return context.applicationInfo.dataDir + "/shared_prefs/" + PREFERENCES_ENCRYPTED + ".xml"
    }
}