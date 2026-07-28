#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# update-runelite.sh — Check for RuneLite updates and download if newer version available.
# Always exits 0. Update status reported via structured output protocol.
# Non-destructive: never deletes a working RuneLite.jar.

MARKER_DIR="$HOME/.runelite-tablet/markers"
VERSION_FILE="$MARKER_DIR/runelite-version"
REFRESH_REQUIRED_FILE="$MARKER_DIR/runelite-refresh-required"
RUNELITE_DIR="/root/runelite"
RUNELITE_JAR="$RUNELITE_DIR/RuneLite.jar"
GITHUB_API_URL="https://api.github.com/repos/runelite/launcher/releases/latest"

echo "=== Checking for RuneLite updates ==="

# Read stored version
mkdir -p "$MARKER_DIR"
if [ -f "$VERSION_FILE" ]; then
    STORED_VERSION=$(cat "$VERSION_FILE" 2>/dev/null || echo "none")
else
    STORED_VERSION="none"
fi
echo "Stored version: $STORED_VERSION"

# Fetch latest version from GitHub API (10 second timeout)
# Run wget inside proot where it's available
API_RESPONSE=$(proot-distro login ubuntu -- bash -c "
    wget -qO- --timeout=10 '$GITHUB_API_URL' 2>/dev/null || echo 'API_FAILED'
" 2>/dev/null || echo "API_FAILED")

if echo "$API_RESPONSE" | grep -q "API_FAILED"; then
    echo "UPDATE_STATUS offline"
    echo "=== update-runelite complete ==="
    exit 0
fi

# Parse tag_name from JSON without jq
LATEST_VERSION=$(echo "$API_RESPONSE" | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$LATEST_VERSION" ]; then
    echo "WARNING: Could not parse version from API response"
    echo "UPDATE_STATUS offline"
    echo "=== update-runelite complete ==="
    exit 0
fi

echo "Latest version: $LATEST_VERSION"

# Compare versions
if [ "$STORED_VERSION" = "$LATEST_VERSION" ]; then
    echo "UPDATE_STATUS current $LATEST_VERSION"
    echo "=== update-runelite complete ==="
    exit 0
fi

# New version available — download it
echo "UPDATE_STATUS downloading $STORED_VERSION -> $LATEST_VERSION"

DOWNLOAD_URL="https://github.com/runelite/launcher/releases/download/$LATEST_VERSION/RuneLite.jar"
TEMP_JAR="${RUNELITE_JAR}.tmp"

DL_RESULT=$(proot-distro login ubuntu -- bash -c "
    mkdir -p '$RUNELITE_DIR'
    rm -f '$TEMP_JAR'

    if ! wget -O '$TEMP_JAR' '$DOWNLOAD_URL' 2>&1; then
        echo 'DOWNLOAD_FAILED'
        rm -f '$TEMP_JAR'
        exit 0
    fi

    # Verify file size > 1MB
    FILE_SIZE=\$(wc -c < '$TEMP_JAR' 2>/dev/null || echo 0)
    if [ \"\$FILE_SIZE\" -lt 1048576 ]; then
        echo 'VERIFY_FAILED size_too_small'
        rm -f '$TEMP_JAR'
        exit 0
    fi

    # Verify ZIP magic bytes (50 4B 03 04 = PK..)
    MAGIC=\$(od -A n -t x1 -N 4 '$TEMP_JAR' 2>/dev/null | tr -d ' ')
    if [ \"\$MAGIC\" != '504b0304' ]; then
        echo 'VERIFY_FAILED bad_magic'
        rm -f '$TEMP_JAR'
        exit 0
    fi

    # Replace old jar with new one
    mv '$TEMP_JAR' '$RUNELITE_JAR'
    echo 'DOWNLOAD_OK'
" 2>/dev/null || echo "DOWNLOAD_FAILED")

if echo "$DL_RESULT" | grep -q "DOWNLOAD_OK"; then
    # Write new version to marker file
    echo "$LATEST_VERSION" > "$VERSION_FILE"
    # The native launcher cannot bootstrap repository2 itself. Preserve the
    # last verified official artifacts and request one normal official-launcher
    # run. That launcher updates, cleans, downloads, and hash-verifies
    # repository2 before it starts the stock client. Deleting repository2 here
    # used to leave the immediately-following native launch with no classpath.
    printf '%s\n' "$LATEST_VERSION" > "$REFRESH_REQUIRED_FILE"
    echo "Marked official client artifacts for launcher refresh"
    echo "UPDATE_STATUS updated $LATEST_VERSION"
else
    echo "WARNING: Download or verification failed, keeping current version"
    echo "UPDATE_STATUS failed"
fi

echo "=== update-runelite complete ==="
