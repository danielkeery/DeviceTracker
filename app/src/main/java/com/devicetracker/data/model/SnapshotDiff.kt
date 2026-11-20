package com.devicetracker.data.model

/**
 * Represents a diff between two snapshots for a specific tracked entity.
 */
data class SnapshotDiff(
    val entityType: String,       // "package", "config", "file"
    val entityId: String,         // package name, property key, file path
    val changeType: ChangeType,
    val oldValue: String?,       // JSON or text
    val newValue: String?,       // JSON or text
    val diffText: String?,       // Unified diff text if applicable
)

enum class ChangeType {
    ADDED,
    MODIFIED,
    REMOVED,
    UNCHANGED,
}
