#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# DEPRECATED — This monolithic script is no longer called by the app.
# Steps 3-5 are now handled by modular scripts:
#   install-proot.sh    (Step 3)
#   install-java.sh     (Step 4)
#   download-runelite.sh (Step 5)
# Kept for reference only. Do not call directly.

# Retry helper: up to 3 attempts with 5s backoff for apt network operations.
# Transient DNS failures, 503 from mirror CDN, or GPG timeout can cause apt to
# fail non-zero. Under set -euo pipefail this would abort the entire script.
retry_apt() {
    local attempt=1
    while [ $attempt -le 3 ]; do
        if "$@"; then
            return 0
        fi
        echo "WARNING: apt command failed (attempt $attempt/3), retrying in 5s..." >&2
        sleep 5
        attempt=$((attempt + 1))
    done
    echo "ERROR: apt command failed after 3 attempts: $*" >&2
    return 1
}

ROOTFS_DIR="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"

# Check available disk space in Termux home (need at least 1GB)
# Termux uses toybox df which supports -k but not -m
AVAIL_KB=$(df -k "$HOME" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt 1000000 ]; then
    AVAIL_MB=$((AVAIL_KB / 1024))
    echo "ERROR: Insufficient disk space. Need at least 1000MB, have ${AVAIL_MB}MB"
    exit 1
fi

# Also check disk space on the rootfs partition — OpenJDK + RuneLite need ~600MB there.
# The rootfs may be on a different partition than $HOME.
ROOTFS_PARENT="$(dirname "$ROOTFS_DIR")"
if [ -d "$ROOTFS_PARENT" ]; then
    ROOTFS_AVAIL_KB=$(df -k "$ROOTFS_PARENT" 2>/dev/null | awk 'NR==2 {print $4}')
    if [ -n "$ROOTFS_AVAIL_KB" ] && [ "$ROOTFS_AVAIL_KB" -lt 1000000 ]; then
        ROOTFS_AVAIL_MB=$((ROOTFS_AVAIL_KB / 1024))
        echo "ERROR: Insufficient space for Ubuntu rootfs. Need at least 1000MB in $ROOTFS_PARENT, have ${ROOTFS_AVAIL_MB}MB"
        exit 1
    fi
fi

echo "=== Step 1: Updating Termux packages ==="
# Only update apt cache if it is more than 60 minutes old — avoids 10-60s download on retries
# where packages are already installed. find returns files newer than 60min; grep matches any line.
if ! find "$PREFIX/var/lib/apt/lists" -maxdepth 1 -type f -mmin -60 2>/dev/null | grep -q .; then
    # apt-get update may return non-zero due to GPG warnings when gpgv is not installed
    apt-get update -y 2>&1 || true
fi
retry_apt apt-get install -y -o Dpkg::Options::="--force-confnew" proot-distro wget pulseaudio

echo "=== Step 1b: Installing Termux:X11 CLI package ==="
# x11-repo adds the graphical packages repository; update needed after adding it
apt-get install -y x11-repo 2>&1 || true
# Always update after adding a new repo — the new repo's metadata must be fetched
apt-get update -y 2>&1 || true
retry_apt apt-get install -y termux-x11-nightly

echo "=== Step 2: Installing Ubuntu ARM64 ==="
if [ -d "$ROOTFS_DIR" ]; then
    echo "Ubuntu already installed, skipping"
else
    # proot-distro install may return non-zero due to proot warnings about
    # /proc/self/fd bindings — these are harmless on Android. Providing
    # < /dev/null gives proot a valid fd 0 in background (no-PTY) mode.
    # Verify by checking if the rootfs directory was actually created.
    proot-distro install ubuntu < /dev/null 2>&1 || true
    if [ ! -d "$ROOTFS_DIR" ]; then
        echo "proot-distro install failed — attempting manual rootfs extraction"
        CACHE_DIR="$PREFIX/var/lib/proot-distro/dlcache"
        # Tarball includes codename (e.g. ubuntu-questing-aarch64-pd-v4.37.0.tar.xz)
        # Use || true because ls returns non-zero when no files match, and pipefail
        # would propagate that as a script-killing error under set -e.
        TARBALL=$(ls "$CACHE_DIR"/ubuntu-*-aarch64-pd-*.tar.xz 2>/dev/null | head -1 || true)
        if [ -n "$TARBALL" ]; then
            mkdir -p "$ROOTFS_DIR"
            mkdir -p "$ROOTFS_DIR/.l2s"
            # proot emits /proc/self/fd warnings in background mode → non-zero exit even on success
            proot --link2symlink tar xf "$TARBALL" -C "$ROOTFS_DIR" --strip-components=1 < /dev/null 2>&1 || true
            # Verify extraction succeeded by checking a key binary
            if [ ! -f "$ROOTFS_DIR/bin/bash" ]; then
                echo "ERROR: rootfs extraction failed — $ROOTFS_DIR/bin/bash not found" >&2
                exit 1
            fi
            echo "Manual rootfs extraction complete"

            # proot-distro install normally configures the rootfs after extraction.
            # Since we bypassed install, replicate the critical post-install steps:
            # DNS, hosts, environment, and user registration.

            # DNS resolution (without this, apt-get hangs trying to reach repos)
            cat > "$ROOTFS_DIR/etc/resolv.conf" <<'RESOLV'
nameserver 8.8.8.8
nameserver 8.8.4.4
RESOLV

            # Localhost resolution
            cat > "$ROOTFS_DIR/etc/hosts" <<'HOSTS'
127.0.0.1 localhost
::1 localhost
HOSTS

            # Environment variables (PATH, locale, tmpdir)
            cat > "$ROOTFS_DIR/etc/environment" <<'ENV'
LANG=en_US.UTF-8
LANGUAGE=en_US:en
MOZ_FAKE_NO_SANDBOX=1
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
TERM=xterm
TMPDIR=/tmp
PULSE_SERVER=127.0.0.1:4713
ENV

            echo "Post-extraction rootfs configuration complete"
        else
            echo "ERROR: No cached Ubuntu tarball found in $CACHE_DIR"
            exit 1
        fi
    fi
    echo "Ubuntu rootfs verified at $ROOTFS_DIR"
fi

echo "=== Step 3: Installing Java inside Ubuntu ==="
# DEBIAN_FRONTEND=noninteractive prevents debconf prompts (timezone, keyboard)
# that hang even with -y when stdin is /dev/null.
# proot login has harmless /proc/self/fd warnings — tolerate non-zero and verify.
# Full JDK required — RuneLite needs AWT/X11 libs (libawt_xawt.so) not in headless.
# Also install openbox for window management (maximizes RuneLite window on launch).
# Verification (java -version) is combined into the same proot login to avoid a
# second full proot process spawn (~200-500ms overhead per invocation).
JAVA_INSTALL_LOG="$HOME/.rlt-java-install.log"
proot-distro login ubuntu -- env DEBIAN_FRONTEND=noninteractive bash -s << 'JAVA_SCRIPT' 2>&1 | tee "$JAVA_INSTALL_LOG" || true
    # Retry apt-get update up to 3 times — transient DNS/CDN errors are common inside proot
    attempt=1
    while [ $attempt -le 3 ]; do
        if apt-get update -y; then break; fi
        echo "WARNING: apt-get update failed (attempt $attempt/3), retrying in 5s..."
        sleep 5
        attempt=$((attempt + 1))
    done
    apt-get install -y openjdk-21-jdk openbox wget ca-certificates
    update-alternatives --auto java
    update-alternatives --auto javac
    # Verify Java installed in the same login — saves one full proot spawn
    if java -version 2>&1; then
        echo "=== Java install OK ==="
    fi
JAVA_SCRIPT
if ! grep -q "=== Java install OK ===" "$JAVA_INSTALL_LOG"; then
    echo "ERROR: Java installation failed"
    rm -f "$JAVA_INSTALL_LOG"
    exit 1
fi
rm -f "$JAVA_INSTALL_LOG"

echo "=== Step 3b: Deploying openbox config inside Ubuntu ==="
# Write openbox config via stdin pass-through into proot (avoids nested heredoc conflicts).
# The config maximizes all windows and removes decorations so RuneLite fills the X11 display.
# This is idempotent — safe to re-run if setup is retried.
proot-distro login ubuntu -- bash -c 'mkdir -p /root/.config/openbox && cat > /root/.config/openbox/rc.xml' << 'OBCFG' 2>&1 || true
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc"
    xmlns:xi="http://www.w3.org/2001/XInclude">
  <applications>
    <application class="*" groupclass="*">
      <decor>no</decor>
      <maximized>yes</maximized>
    </application>
  </applications>
</openbox_config>
OBCFG

echo "=== Step 4: Downloading RuneLite ==="
# Verify RuneLite download in the same proot login to avoid a second proot spawn.
RUNELITE_INSTALL_LOG="$HOME/.rlt-runelite-dl.log"
proot-distro login ubuntu -- bash -s << 'RUNELITE_SCRIPT' 2>&1 | tee "$RUNELITE_INSTALL_LOG" || true
    RUNELITE_URL="https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar"
    mkdir -p /root/runelite
    if [ ! -f /root/runelite/RuneLite.jar ]; then
        wget -O /root/runelite/RuneLite.jar "$RUNELITE_URL"
    fi
    ls -la /root/runelite/RuneLite.jar
    if test -f /root/runelite/RuneLite.jar; then
        echo "=== RuneLite download OK ==="
    fi
RUNELITE_SCRIPT
if ! grep -q "=== RuneLite download OK ===" "$RUNELITE_INSTALL_LOG"; then
    echo "ERROR: RuneLite download failed"
    rm -f "$RUNELITE_INSTALL_LOG"
    exit 1
fi
rm -f "$RUNELITE_INSTALL_LOG"

echo "=== Setup complete ==="
