package com.devicetracker

import android.app.Application
import android.util.Log
import com.devicetracker.data.db.AppDatabase
import com.devicetracker.tracker.*
import java.io.File

/**
 * Application class that initializes the tracker components.
 * Minimal setup — just init the repo and schedule work.
 */
class App : Application() {

    companion object {
        private const val TAG = "DeviceTracker"
    }

    lateinit var gitManager: GitManager
        private set
    lateinit var packageTracker: PackageTracker
        private set
    lateinit var configTracker: ConfigTracker
        private set
    lateinit var fileTracker: FileTracker
        private set
    lateinit var snapshotManager: SnapshotManager
        private set
    lateinit var rootWatcher: RootFileWatcher
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing DeviceTracker")

        gitManager = GitManager(this)
        packageTracker = PackageTracker(this)
        configTracker = ConfigTracker(this)
        fileTracker = FileTracker(this)
        snapshotManager = SnapshotManager(this, gitManager, packageTracker, configTracker, fileTracker)
        rootWatcher = RootFileWatcher(this, gitManager)

        // Auto-init repo on first run
        try {
            gitManager.initRepo()
            Log.i(TAG, "Repo initialized at: ${gitManager.getRepoDir()?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init repo", e)
        }

        // Schedule periodic snapshots (default: every hour)
        DeviceSnapshotWorker.schedule(this)
    }
}
