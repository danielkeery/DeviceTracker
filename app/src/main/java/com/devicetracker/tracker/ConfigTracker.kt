package com.devicetracker.tracker

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.accounts.AccountManager
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import com.devicetracker.data.model.*
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Tracks device configuration — settings, build properties, system properties, etc.
 * These are stored as full JSON snapshots. Git handles the diff for storage efficiency.
 */
class ConfigTracker(private val context: Context) {

    companion object {
        private const val TAG = "ConfigTracker"
        private const val BUILD_PROPS_FILE = "/system/build.prop"
    }

    /**
     * Capture all device configs.
     */
    fun captureConfig(): DeviceConfig {
        return DeviceConfig(
            build = getBuildProperties(),
            systemProperties = getSystemProperties(),
            settingsGlobal = getSettingsByPrefix(Settings.Global.CONTENT_URI, Settings.Global::getString),
            settingsSecure = getSettingsByPrefix(Settings.Secure.CONTENT_URI, Settings.Secure::getString),
            settingsSystem = getSettingsByPrefix(Settings.System.CONTENT_URI, Settings.System::getString),
            wifiConfigs = getWifiNetworks(),
            accounts = getAccounts(),
            features = getFeatures(),
            locale = context.resources.configuration.locales.get(0)?.toLanguageTag() ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            deviceInfo = getDeviceInfo(),
        )
    }

    private fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            device = Build.DEVICE,
            board = Build.BOARD,
            brand = Build.BRAND,
            hardware = Build.HARDWARE,
            serial = try { Build.getSerial() } catch (e: Exception) { Build.UNKNOWN },
            fingerprint = Build.FINGERPRINT,
            displayId = Build.DISPLAY,
            buildDate = Build.TIME.toString(),
            buildType = Build.TYPE,
            tags = Build.TAGS,
        )
    }

    private fun getBuildProperties(): Map<String, String> {
        val props = mutableMapOf<String, String>()
        try {
            val file = File(BUILD_PROPS_FILE)
            if (file.exists()) {
                file.useLines { lines ->
                    for (line in lines) {
                        if (line.isNotBlank() && !line.startsWith("#")) {
                            val eq = line.indexOf('=')
                            if (eq > 0) {
                                props[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read build.prop", e)
        }
        return props
    }

    private fun getSystemProperties(): Map<String, String> {
        // We can only access a subset of system properties without root.
        // Capture the most useful ones.
        val keys = listOf(
            "ro.build.version.sdk",
            "ro.build.version.release",
            "ro.build.version.codename",
            "ro.build.version.security_patch",
            "ro.build.version.base_os",
            "ro.build.fingerprint",
            "ro.product.name",
            "ro.product.device",
            "ro.product.board",
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.serialno",
            "ro.boot.serialno",
            "ro.build.ab_update",
            "ro.build.characteristics",
            "ro.crypto.state",
            "ro.crypto.type",
            "ro.debuggable",
            "ro.secure",
            "ro.zygote",
            "ro.build.tags",
            "ro.build.type",
            "ro.build.user",
            "ro.build.host",
        )
        val props = mutableMapOf<String, String>()
        for (key in keys) {
            try {
                val value = getSystemProperty(key)
                if (value != null) props[key] = value
            } catch (e: Exception) { /* skip */ }
        }
        return props
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get settings from a content URI using the Settings provider.
     */
    private fun getSettingsByPrefix(
        uri: android.net.Uri,
        getter: (android.content.ContentResolver, String) -> String?
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                while (c.moveToNext()) {
                    try {
                        val name = c.getString(c.getColumnIndex("name"))
                        val value = c.getString(c.getColumnIndex("value"))
                        if (name != null && value != null) {
                            // Filter out noise — skip binary/large values
                            if (value.length < 1024 && value.chars().allMatch { it.toInt() in 0x20..0x7e || it.toInt() == '\n'.toInt() || it.toInt() == '\r'.toInt() || it.toInt() == '\t'.toInt() }) {
                                result[name] = value
                            }
                        }
                    } catch (e: Exception) { /* skip row */ }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings from $uri", e)
        }
        return result
    }

    private fun getWifiNetworks(): List<WifiConfig> {
        val list = mutableListOf<WifiConfig>()
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val wifiInfo = wifiManager.connectionInfo
                if (wifiInfo != null) {
                    list.add(WifiConfig(
                        ssid = wifiInfo.ssid?.removeSurrounding("\"") ?: "",
                        bssid = wifiInfo.bssid,
                        security = getWifiSecurity(wifiInfo),
                        frequency = wifiInfo.frequency,
                        isSaved = true,
                    ))
                }
                // Get configured networks (requires additional permissions on newer Android)
                try {
                    @Suppress("DEPRECATION")
                    val configured = wifiManager.configuredNetworks
                    for (net in configured) {
                        if (list.none { it.ssid == net.SSID?.removeSurrounding("\"") }) {
                            list.add(WifiConfig(
                                ssid = net.SSID?.removeSurrounding("\"") ?: "unknown",
                                bssid = net.BSSID,
                                security = net.allowedKeyManagement.toString(),
                                isSaved = true,
                                priority = net.priority,
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot access configured networks (API 29+ restriction)", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi info", e)
        }
        return list
    }

    private fun getWifiSecurity(wifiInfo: android.net.wifi.WifiInfo): String {
        return when {
            wifiInfo.ssid != null && !wifiInfo.ssid.isNullOrEmpty() -> "WPA2" // approximate
            else -> "OPEN"
        }
    }

    private fun getAccounts(): List<AccountInfo> {
        val list = mutableListOf<AccountInfo>()
        try {
            val accountManager = AccountManager.get(context)
            val accounts = accountManager.accounts
            for (account in accounts) {
                val label = try {
                    accountManager.getUserData(account, "label")
                } catch (e: Exception) { null }
                list.add(AccountInfo(
                    name = account.name,
                    type = account.type,
                    typeLabel = label,
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get accounts", e)
        }
        return list
    }

    private fun getFeatures(): List<String> {
        val list = mutableListOf<String>()
        try {
            val pm = context.packageManager
            val features = pm.systemAvailableFeatures
            for (f in features) {
                f.name?.let { list.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get features", e)
        }
        return list.sorted()
    }

    /**
     * Write configuration as JSON string.
     */
    fun configToJson(config: DeviceConfig): String {
        return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config)
    }

    /**
     * Compare two device configs and return diffs.
     */
    fun diffConfigs(old: DeviceConfig, new: DeviceConfig): List<SnapshotDiff> {
        val diffs = mutableListOf<SnapshotDiff>()

        // Diff build properties
        diffMaps("config.build", old.build, new.build)?.let { diffs.addAll(it) }
        diffMaps("config.settings_global", old.settingsGlobal, new.settingsGlobal)?.let { diffs.addAll(it) }
        diffMaps("config.settings_secure", old.settingsSecure, new.settingsSecure)?.let { diffs.addAll(it) }
        diffMaps("config.settings_system", old.settingsSystem, new.settingsSystem)?.let { diffs.addAll(it) }

        // Diff apps count
        val oldPkgCount = "${old.build["ro.build.version.sdk"] ?: "?"}"
        val newPkgCount = "${new.build["ro.build.version.sdk"] ?: "?"}"

        return diffs
    }

    private fun diffMaps(
        entityId: String,
        old: Map<String, String>,
        new: Map<String, String>
    ): List<SnapshotDiff>? {
        val diffs = mutableListOf<SnapshotDiff>()
        val allKeys = (old.keys + new.keys).distinct().sorted()

        for (key in allKeys) {
            val oldVal = old[key]
            val newVal = new[key]
            if (oldVal == newVal) continue

            diffs.add(SnapshotDiff(
                entityType = "config",
                entityId = "$entityId.$key",
                changeType = when {
                    oldVal == null -> ChangeType.ADDED
                    newVal == null -> ChangeType.REMOVED
                    else -> ChangeType.MODIFIED
                },
                oldValue = oldVal,
                newValue = newVal,
                diffText = if (oldVal != null && newVal != null) {
                    "--- $key\n+++ $key\n@@ -1 +1 @@\n- $oldVal\n+ $newVal"
                } else null,
            ))
        }

        return diffs.ifEmpty { null }
    }
}
