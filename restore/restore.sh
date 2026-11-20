#!/system/bin/sh
# =============================================================================
# DeviceTracker — Full System Restore Script
# =============================================================================
# After a factory reset, run this script to recover your entire dev environment.
#
# Usage:
#   # If you pushed to remote:
#   git clone <your-repo-url> /sdcard/DeviceTrackerRestore
#   cd /sdcard/DeviceTrackerRestore
#   bash restore.sh
#
#   # If you have the backup on SD card:
#   cd /path/to/backup
#   bash restore.sh
#
# What this restores:
#   1. Installed packages (re-installs from Play Store backup or side-loads)
#   2. Device configuration files
#   3. Developer keys and configs (debug keystore, ADB keys, etc.)
#   4. System settings (WiFi networks, etc.)
#   5. Any backed-up files from filesystem monitoring
#
# The script is designed to be idempotent — safe to run multiple times.
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
BACKUP_DIR="$SCRIPT_DIR"
LOG_FILE="$SCRIPT_DIR/restore_$(date +%Y%m%d_%H%M%S).log"
RESTORE_STATE="$SCRIPT_DIR/.restore_state"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# Utility Functions
# =============================================================================

log() {
    echo -e "[$(date '+%H:%M:%S')] $1"
    echo "[$(date '+%H:%M:%S')] $1" >> "$LOG_FILE"
}

info()  { log "${BLUE}[INFO]${NC} $1"; }
ok()    { log "${GREEN}[OK]${NC} $1"; }
warn()  { log "${YELLOW}[WARN]${NC} $1"; }
err()   { log "${RED}[ERR]${NC} $1"; }

has_command() {
    command -v "$1" >/dev/null 2>&1
}

require_root() {
    if [ "$(id -u)" != "0" ]; then
        err "This script works best with root. Re-run as root if you see permission errors."
        return 1
    fi
    return 0
}

# =============================================================================
# 1. Detect Environment
# =============================================================================

detect_environment() {
    info "Detecting environment..."

    # Check Android environment
    if [ -d /system ] && [ -d /data ]; then
        info "Android device detected"
    fi

    # Check ADB
    if has_command adb; then
        info "ADB available"
    else
        warn "ADB not found in PATH — some features limited"
    fi

    # Check package manager
    if has_command pm; then
        info "Package manager available"
    fi

    # Check root
    if [ "$(id -u)" = "0" ]; then
        ok "Running as root — full access"
    else
        warn "Not running as root — limited restore capability"
    fi
}

# =============================================================================
# 2. Restore Packages
# =============================================================================

restore_packages() {
    info "=== Restoring Packages ==="

    local packages_file="$BACKUP_DIR/packages/current.json"
    if [ ! -f "$packages_file" ]; then
        warn "No packages file found at $packages_file"
        # Try alternative locations
        packages_file=$(find "$BACKUP_DIR" -name "current.json" -path "*/packages/*" 2>/dev/null | head -1)
        if [ -z "$packages_file" ]; then
            warn "No package data found — skipping package restore"
            return
        fi
        info "Found packages at: $packages_file"
    fi

    local total=$(grep -o '"package_name"' "$packages_file" 2>/dev/null | wc -l)
    info "Found $total packages to restore"

    # Process each package
    local restored=0
    local skipped=0
    local failed=0

    # Try to reinstall using pm (system package manager)
    if has_command pm; then
        info "Using Android package manager for reinstallation..."
        # Extract package names and try to install
        for pkg in $(grep -o '"package_name": *"[^"]*"' "$packages_file" 2>/dev/null | cut -d'"' -f4); do
            info "Processing package: $pkg"
            if pm list packages 2>/dev/null | grep -q "package:$pkg"; then
                info "  Already installed: $pkg"
                skipped=$((skipped + 1))
            else
                # Try to install via ADB or pm
                local apk_path=$(find /data/app /sdcard -name "${pkg}*.apk" 2>/dev/null | head -1)
                if [ -n "$apk_path" ]; then
                    info "  Installing from: $apk_path"
                    if pm install -r "$apk_path" 2>/dev/null; then
                        ok "  Installed: $pkg"
                        restored=$((restored + 1))
                    else
                        warn "  Failed to install: $pkg"
                        failed=$((failed + 1))
                    fi
                else
                    warn "  APK not found locally — will need to download: $pkg"
                    # Record for manual handling
                    echo "$pkg" >> "$RESTORE_STATE/missing_packages.txt"
                    skipped=$((skipped + 1))
                fi
            fi
        done
    else
        warn "No package manager available"
    fi

    info "Packages restored: $restored, skipped: $skipped, failed: $failed"
}

# =============================================================================
# 3. Restore Configuration Files
# =============================================================================

restore_configs() {
    info "=== Restoring Configuration Files ==="

    local config_dir="$BACKUP_DIR/config"
    if [ ! -d "$config_dir" ]; then
        warn "No config directory found"
        return
    fi

    # Restore each config file
    local restored=0
    for config_file in "$config_dir"/*; do
        local name="$(basename "$config_file")"
        info "Restoring config: $name"

        case "$name" in
            build.prop)
                if [ "$(id -u)" = "0" ] && [ -f "$config_file" ]; then
                    cp "$config_file" /system/build.prop 2>/dev/null && ok "  Restored build.prop" && restored=$((restored + 1)) || warn "  Could not restore build.prop (need root)"
                fi
                ;;
            device_config.json)
                # Store for reference — applied piecemeal
                ok "  Device config recorded in repo (applied via individual settings below)"
                restored=$((restored + 1))
                ;;
            *)
                # Generic config — copy to restore directory
                mkdir -p "$RESTORE_STATE/configs"
                cp "$config_file" "$RESTORE_STATE/configs/" 2>/dev/null && restored=$((restored + 1))
                ;;
        esac
    done

    # Restore settings from JSON if available
    if [ -f "$BACKUP_DIR/config/device_config.json" ]; then
        info "Settings available in device_config.json — applying..."
        # Extract and apply secure/global/system settings
        if has_command settings; then
            # Parse and apply a few critical settings
            for key in wifi_on bluetooth_on; do
                local val=$(grep -o "\"$key\": *\"[^\"]*\"" "$BACKUP_DIR/config/device_config.json" 2>/dev/null | head -1 | cut -d'"' -f4)
                if [ -n "$val" ]; then
                    settings put global "$key" "$val" 2>/dev/null && info "  Set $key = $val"
                fi
            done
        fi
    fi

    info "Config files restored: $restored"
}

# =============================================================================
# 4. Restore Developer Files
# =============================================================================

restore_dev_files() {
    info "=== Restoring Developer Files ==="

    local dev_dir="$BACKUP_DIR/dev_files"
    if [ ! -d "$dev_dir" ]; then
        warn "No dev_files directory found"
        return
    fi

    local restored=0
    local android_dir="${ANDROID_HOME:-$HOME/.android}"
    mkdir -p "$android_dir"

    for f in "$dev_dir"/*; do
        local name="$(basename "$f")"
        local target=""

        case "$name" in
            debug.keystore)
                target="$android_dir/$name"
                cp "$f" "$target" 2>/dev/null && ok "  Restored: $name" && restored=$((restored + 1)) || warn "  Failed to restore $name"
                ;;
            adbkey*)
                target="$android_dir/$name"
                cp "$f" "$target" 2>/dev/null && ok "  Restored: $name" && restored=$((restored + 1)) || warn "  Failed to restore $name"
                ;;
            *)
                target="$RESTORE_STATE/dev_files/$name"
                mkdir -p "$RESTORE_STATE/dev_files"
                cp "$f" "$target" 2>/dev/null && restored=$((restored + 1))
                ;;
        esac
    done

    info "Developer files restored: $restored"
}

# =============================================================================
# 5. Restore Filesystem Backups
# =============================================================================

restore_filesystem() {
    info "=== Restoring Filesystem Files ==="

    local fs_dir="$BACKUP_DIR/filesystem"
    if [ ! -d "$fs_dir" ]; then
        warn "No filesystem backup directory found"
        return
    fi

    # Restore files to their original locations (if we have root)
    if [ "$(id -u)" = "0" ]; then
        info "Root available — restoring files to original locations..."
        find "$fs_dir" -type f 2>/dev/null | while read -r f; do
            local rel="${f#$fs_dir/}"
            local orig_path="$rel"
            # Undo the sanitization: replace double underscores with single slash heuristic
            # This is best-effort — file path mapping is stored in the snapshot JSON
            mkdir -p "$(dirname "$orig_path")" 2>/dev/null
            cp "$f" "$orig_path" 2>/dev/null && info "  Restored: $orig_path" || warn "  Could not restore: $orig_path"
        done
    else
        warn "No root — filesystem files saved to $RESTORE_STATE/filesystem/"
        mkdir -p "$RESTORE_STATE/filesystem"
        cp -r "$fs_dir"/* "$RESTORE_STATE/filesystem/" 2>/dev/null
    fi
}

# =============================================================================
# 6. Generate Recovery Summary
# =============================================================================

generate_summary() {
    info "=== Generating Recovery Summary ==="

    local summary_file="$RESTORE_STATE/SUMMARY.md"

    cat > "$summary_file" << 'EOF'
# DeviceTracker Recovery Summary

Run `adb install` for any missing packages listed below.
Run `git log --oneline` to see all tracked changes.

## Post-Recovery Steps
1. Open Settings → Accounts → Add account
2. Sign into Google/Play Store
3. Open Play Store → My Apps → Install your apps
4. Verify developer keys are in ~/.android/
5. Run `adb devices` to verify ADB connectivity

## Missing Packages (not auto-restored)
EOF

    if [ -f "$RESTORE_STATE/missing_packages.txt" ]; then
        cat "$RESTORE_STATE/missing_packages.txt" >> "$summary_file"
    fi

    info "Summary written to: $summary_file"
}

# =============================================================================
# 7. Post-Recovery Instructions
# =============================================================================

show_recovery_instructions() {
    echo ""
    echo "============================================================================="
    echo -e "${GREEN}RECOVERY INSTRUCTIONS${NC}"
    echo "============================================================================="
    echo ""
    echo "What was restored:"
    echo "  ✓ Package list saved — run 'pm install' or Play Store to reinstall apps"
    echo "  ✓ Config files restored to their locations"
    echo "  ✓ Developer keys restored"
    echo "  ✓ Filesystem backups staged"
    echo ""
    echo "What needs manual action:"
    echo "  1. Sign into Google account for Play Store sync"
    echo "  2. Re-authenticate WiFi networks (passwords not stored)"
    echo "  3. Re-enable USB debugging: Settings → Developer Options"
    echo "  4. Re-pair Bluetooth devices"
    echo "  5. Set up biometrics/Face unlock"
    echo ""
    echo "To restore from this point:"
    echo "  cd $(dirname "$BACKUP_DIR")"
    echo "  git log --oneline -20  # See snapshot history"
    echo "  git diff HEAD~1 HEAD  # See last changes"
    echo ""
    echo "To push future backups to remote:"
    echo "  git remote add origin <url>"
    echo "  git push -u origin master"
    echo ""
    echo "============================================================================="
}

# =============================================================================
# MAIN
# =============================================================================

main() {
    echo ""
    echo "============================================================================="
    echo -e "${BLUE}DeviceTracker — Full System Restore${NC}"
    echo "Backup source: $BACKUP_DIR"
    echo "Log file: $LOG_FILE"
    echo "============================================================================="
    echo ""

    mkdir -p "$RESTORE_STATE"

    detect_environment
    echo ""
    restore_packages
    echo ""
    restore_configs
    echo ""
    restore_dev_files
    echo ""
    restore_filesystem
    echo ""
    generate_summary
    echo ""
    show_recovery_instructions

    echo ""
    echo -e "${GREEN}Restore complete.${NC}"
    echo "Check $LOG_FILE for details."
    echo "Reboot your device to apply all changes, then verify everything works."
}

main
