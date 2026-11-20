# DeviceTracker

**Track all device changes in a git repo. Recover your entire Android development box after a factory reset — in minutes, not days.**

## Why

You develop on Android. Sometimes a mistake forces a data wipe. Factory reset means losing:
- Installed apps and their data
- Android Studio configs, keystores, platform certs
- ADB keys, WiFi networks, device settings
- Build environments, Gradle caches, SDK configs
- System modifications, custom scripts

DeviceTracker watches everything, commits changes to a git repo, and gives you a restore script to bounce back fast.

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────┐
│  RootFileWatcher  │────▶│  SnapshotManager  │────▶│  GitManager  │
│  (inotify + find) │     │  + PackageTracker │     │  (JGit repo) │
│                  │     │  + ConfigTracker  │     │              │
│  /data/app/       │     │  + FileTracker   │     │  commit hash │
│  /data/system/    │     │                   │     │  diff output │
│  /system/etc/     │     │  Snapshots every  │     │  push remote │
│  ...              │     │  1 hour + on boot │     └──────┬──────┘
└─────────────────┘     └──────────────────┘            │
                           ┌──────────────────────────────┘
                           ▼
                    ┌──────────────┐
                    │  restore.sh   │
                    │  (post-reset) │
                    └──────────────┘
```

## What Gets Tracked

| Category | Data | Storage Strategy |
|-----------|------|-----------------|
| **Packages** | App IDs, version codes, version names, install sources, permissions, APK hashes | JSON metadata (tiny) |
| **Device Config** | build.prop, Settings (Global/Secure/System), WiFi networks, accounts, features | Full config files + git diffs |
| **Developer Files** | debug.keystore, ADB keys, Gradle configs, platform certs | Whole files stored |
| **Filesystem** | /data/app/, /data/data/, /system/etc/, /vendor/etc/, /data/local/tmp/ | Full files for configs, reference+hash for large files |
| **Diffs** | Package version changes, setting changes, file modifications | Git-tracked per commit |

## Build

```bash
cd android-device-tracker
./gradlew assembleDebug
```

Or just grab `app/build/outputs/apk/debug/app-debug.apk`.

## Setup

### 1. Install the APK on your device

```bash
adb install -r app-debug.apk
```

### 2. Grant permissions

- Open the app → Grant notification access
- If rooted: grant superuser access when prompted

### 3. Configure (optional)

The app works with zero configuration:
- Repo lives at `/sdcard/Android/data/com.devicetracker/files/DeviceTrackerBackup/`
- Snapshots auto-run every hour
- Root detection happens automatically

### 4. Push to remote (optional but recommended)

```bash
# After first snapshot, push to a private GitHub/GitLab repo
cd /sdcard/Android/data/com.devicetracker/files/DeviceTrackerBackup
git remote add origin https://github.com/YOUR_USER/device-backup.git
git push -u origin master
```

## Recovery (Factory Reset)

After a factory reset:

### Method 1: Remote clone (fastest)

```bash
# First, enable ADB on the fresh device
# Then:
git clone https://github.com/YOUR_USER/device-backup.git /sdcard/DeviceTrackerRestore
cd /sdcard/DeviceTrackerRestore
bash restore.sh
```

### Method 2: Local backup (no network)

If you copied the repo to an SD card or host before reset:

```bash
cd /path/to/backup
bash restore.sh
```

### What the restore script does:

1. **Reinstall packages** — uses `pm install` for local APKs, records missing ones
2. **Restore configs** — copies back build.prop, settings, etc.
3. **Restore dev files** — debug.keystore, ADB keys, Gradle configs
4. **Restore filesystem files** — from filesystem snapshots
5. **Generate summary** — lists what needs manual action

### Post-recovery checklist:

```
✓ Debug keystore restored → ADB works, debug builds sign correctly
✓ Package list recovered → Play Store knows what to reinstall
✓ Device configs restored → settings, accounts, WiFi recorded
After:
  - Sign into Google → Play Store auto-installs apps
  - Enable USB debugging → ADB authenticated (keys restored)
  - Reboot → all changes take effect
```

## Minimal Configuration

The whole system works with just:
1. One APK installed
2. (Optional) One git remote URL
3. (Optional) Root access for full filesystem monitoring

## Space Optimization

| Item | Space |
|------|-------|
| Package metadata | ~200 bytes per app |
| Device config snapshot | ~50 KB |
| Git repo (1 year of hourly snapshots) | ~100 MB |
| Developer keys | ~2 KB |
| **Total for 1 year** | **~100–200 MB** |

APK files are **not** stored — only hashes and metadata. Recovery re-downloads from Play Store or side-loads.

## Security

- Debug keystores are **included** (you need these for development)
- WiFi passwords are **not** stored (Android prevents reading them on API 29+)
- Account tokens are **not** stored
- Git repo should be private if pushed to a remote
- All data stays local unless you explicitly push

## Commands Quick Reference

```bash
# Manual snapshot
adb shell am broadcast -a com.devicetracker.SNAPSHOT

# Check repo status
adb shell "cd /sdcard/Android/data/com.devicetracker/files/DeviceTrackerBackup && git log --oneline -10"

# View last snapshot summary
adb shell "cat /sdcard/Android/data/com.devicetracker/files/DeviceTrackerBackup/CHANGELOG.md | tail -20"

# Push to remote
adb shell "cd /sdcard/Android/data/com.devicetracker/files/DeviceTrackerBackup && git push"

# Full restore from host
adb push restore.sh /sdcard/
adb shell "bash /sdcard/restore.sh"
```

## Files

```
android-device-tracker/
├── app/
│   ├── src/main/java/com/devicetracker/
│   │   ├── App.kt              # Application + init
│   │   ├── tracker/
│   │   │   ├── GitManager.kt           # JGit wrapper
│   │   │   ├── PackageTracker.kt        # Package monitoring
│   │   │   ├── ConfigTracker.kt         # Device config tracking
│   │   │   ├── FileTracker.kt           # File backup
│   │   │   ├── RootFileWatcher.kt       # Root-powered FS watcher
│   │   │   ├── DeviceSnapshotWorker.kt  # WorkManager periodic worker
│   │   │   ├── BootReceiver.kt          # Auto-start on boot
│   │   │   └── SnapshotManager.kt       # Orchestrator
│   │   ├── data/
│   │   │   ├── db/              # Room database
│   │   │   └── model/           # Data models
│   │   └── ui/
│   │       └── MainActivity.kt  # Simple control UI
│   └── build.gradle.kts
├── restore/
│   └── restore.sh              # THE recovery script
└── README.md
```
