package com.devicetracker.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.devicetracker.App
import com.devicetracker.R
import com.devicetracker.data.db.AppDatabase
import com.devicetracker.tracker.DeviceSnapshotWorker
import com.devicetracker.tracker.GitManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var repoPathText: TextView
    private lateinit var repoSizeText: TextView
    private lateinit var packagesText: TextView
    private lateinit var lastSnapshotText: TextView
    private lateinit var changelogText: TextView
    private lateinit var btnSnapshot: Button
    private lateinit var btnPush: Button
    private lateinit var watcherStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        repoPathText = findViewById(R.id.repo_path)
        repoSizeText = findViewById(R.id.repo_size)
        packagesText = findViewById(R.id.packages_count)
        lastSnapshotText = findViewById(R.id.last_snapshot)
        changelogText = findViewById(R.id.changelog)
        btnSnapshot = findViewById(R.id.btn_snapshot)
        btnPush = findViewById(R.id.btn_push)
        watcherStatus = findViewById(R.id.watcher_status)

        changelogText.movementMethod = ScrollingMovementMethod()

        val app = application as App
        val gitManager = app.gitManager

        // Update UI with current state
        refreshStatus(gitManager)

        btnSnapshot.setOnClickListener {
            btnSnapshot.isEnabled = false
            statusText.text = "Snapshots: Running..."
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    DeviceSnapshotWorker.scheduleNow(this@MainActivity)
                }
                // Give it a moment then refresh
                kotlinx.coroutines.delay(2000)
                refreshStatus(gitManager)
                btnSnapshot.isEnabled = true
            }
        }

        btnPush.setOnClickListener {
            val remoteUrl = "https://github.com/<user>/device-backup.git"
            val input = EditText(this).apply {
                setText(remoteUrl)
                hint = "Remote URL"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Push to Remote")
                .setView(input)
                .setPositiveButton("Push") { _, _ ->
                    val url = input.text.toString().trim()
                    if (url.isNotBlank()) {
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                gitManager.pushToRemote(url)
                            }
                            Toast.makeText(this@MainActivity,
                                result.getOrElse { "Push failed: ${result.exceptionOrNull()?.message}" },
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshStatus(gitManager: GitManager) {
        val info = gitManager.getRepoInfo()
        repoPathText.text = info.repoPath.ifEmpty { "Not initialized" }
        repoSizeText.text = formatSize(info.sizeBytes)
        lastSnapshotText.text = info.lastCommitTime?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it))
        } ?: "Never"

        val rootWatcher = (application as App).rootWatcher
        watcherStatus.text = if (rootWatcher.isRootAvailable()) "✅ Root available — full FS watch active"
            else "⚠️ No root — limited to app scope"

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@MainActivity)
            val lastSnap = db.snapshotDao().getLastSuccessfulSnapshotTime()
            packagesText.text = lastSnap?.let {
                db.snapshotDao().getRecentPackageChanges().size.toString()
            } ?: "0"

            val changelogFile = gitManager.getRepoDir()?.resolve("CHANGELOG.md")
            if (changelogFile?.exists() == true) {
                changelogText.text = changelogFile.readText().take(5000)
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "%.2f %s".format(size, units[unitIndex])
    }
}
