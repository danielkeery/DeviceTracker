package com.devicetracker.data.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Snapshot of device-level configuration properties.
 */
data class DeviceConfig(
    @SerializedName("build")
    val build: Map<String, String> = emptyMap(),
    @SerializedName("system_properties")
    val systemProperties: Map<String, String> = emptyMap(),
    @SerializedName("settings_global")
    val settingsGlobal: Map<String, String> = emptyMap(),
    @SerializedName("settings_secure")
    val settingsSecure: Map<String, String> = emptyMap(),
    @SerializedName("settings_system")
    val settingsSystem: Map<String, String> = emptyMap(),
    @SerializedName("wifi_configs")
    val wifiConfigs: List<WifiConfig> = emptyList(),
    @SerializedName("accounts")
    val accounts: List<AccountInfo> = emptyList(),
    @SerializedName("features")
    val features: List<String> = emptyList(),
    @SerializedName("locale")
    val locale: String = "",
    @SerializedName("sdk_int")
    val sdkInt: Int = 0,
    @SerializedName("device_info")
    val deviceInfo: DeviceInfo = DeviceInfo(),
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): DeviceConfig = Gson().fromJson(json, DeviceConfig::class.java)
    }
}

data class DeviceInfo(
    @SerializedName("manufacturer")
    val manufacturer: String = "",
    @SerializedName("model")
    val model: String = "",
    @SerializedName("product")
    val product: String = "",
    @SerializedName("device")
    val device: String = "",
    @SerializedName("board")
    val board: String = "",
    @SerializedName("brand")
    val brand: String = "",
    @SerializedName("hardware")
    val hardware: String = "",
    @SerializedName("serial")
    val serial: String = "",
    @SerializedName("fingerprint")
    val fingerprint: String = "",
    @SerializedName("display_id")
    val displayId: String = "",
    @SerializedName("build_date")
    val buildDate: String = "",
    @SerializedName("build_type")
    val buildType: String = "",
    @SerializedName("tags")
    val tags: String = "",
)

data class WifiConfig(
    @SerializedName("ssid")
    val ssid: String,
    @SerializedName("bssid")
    val bssid: String?,
    @SerializedName("security")
    val security: String,
    @SerializedName("frequency")
    val frequency: Int = 0,
    @SerializedName("is_saved")
    val isSaved: Boolean = false,
    @SerializedName("priority")
    val priority: Int = 0,
)

data class AccountInfo(
    @SerializedName("name")
    val name: String,
    @SerializedName("type")
    val type: String,
    @SerializedName("type_label")
    val typeLabel: String? = null,
)
