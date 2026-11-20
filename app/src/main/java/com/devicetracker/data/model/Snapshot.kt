package com.devicetracker.data.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Represents the full state of a device at a point in time.
 */
data class Snapshot(
    @SerializedName("snapshot_id")
    val snapshotId: String,          // ISO 8601 timestamp
    @SerializedName("created_at")
    val createdAt: Long,             // epoch millis
    @SerializedName("device_id")
    val deviceId: String,            // unique device identifier
    @SerializedName("packages")
    val packages: List<AppInfo> = emptyList(),
    @SerializedName("config")
    val config: DeviceConfig? = null,
    @SerializedName("backed_up_files")
    val backedUpFiles: List<BackedUpFile> = emptyList(),
    @SerializedName("total_apk_count")
    val totalApkCount: Int = 0,
    @SerializedName("notes")
    val notes: String = "",
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): Snapshot = Gson().fromJson(json, Snapshot::class.java)
    }
}

data class BackedUpFile(
    @SerializedName("relative_path")
    val relativePath: String,
    @SerializedName("size_bytes")
    val sizeBytes: Long,
    @SerializedName("sha256")
    val sha256: String,
    @SerializedName("last_modified")
    val lastModified: Long,
    @SerializedName("is_symlink")
    val isSymlink: Boolean = false,
    @SerializedName("target_path")
    val targetPath: String? = null,
)
