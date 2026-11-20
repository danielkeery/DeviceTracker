package com.devicetracker.data.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Represents a tracked installed application.
 */
data class AppInfo(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("version_code")
    val versionCode: Long,
    @SerializedName("version_name")
    val versionName: String,
    @SerializedName("first_install_time")
    val firstInstallTime: Long,
    @SerializedName("last_update_time")
    val lastUpdateTime: Long,
    @SerializedName("installer_package")
    val installerPackage: String?,
    @SerializedName("is_system")
    val isSystem: Boolean,
    @SerializedName("is_enabled")
    val isEnabled: Boolean,
    @SerializedName("primary_abi")
    val primaryAbi: String?,
    @SerializedName("secondary_abi")
    val secondaryAbi: String?,
    @SerializedName("source_dirs")
    val sourceDirs: List<String> = emptyList(),
    @SerializedName("apk_size_bytes")
    val apkSizeBytes: Long = 0,
    @SerializedName("apk_sha256")
    val apkSha256: String? = null,
    @SerializedName("split_sources")
    val splitSources: List<String> = emptyList(),
    @SerializedName("requested_permissions")
    val requestedPermissions: List<String> = emptyList(),
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): AppInfo = Gson().fromJson(json, AppInfo::class.java)
    }
}
