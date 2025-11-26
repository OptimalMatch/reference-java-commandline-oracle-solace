#!/bin/bash
# =============================================================================
# Exclusion List Examples
# =============================================================================
# Demonstrates filtering messages and files using exclusion patterns.
#
# Usage: ./examples-exclusions.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Exclusion List Examples"
show_connection_info

EXCLUSION_FILE="${DATA_DIR}/exclusions.txt"

# -----------------------------------------------------------------------------
# Create Exclusion File
# -----------------------------------------------------------------------------

subheader "1. Creating Exclusion Patterns File"

cat > "$EXCLUSION_FILE" << 'EOF'
# =============================================================================
# Exclusion Patterns for Solace CLI
# =============================================================================
# Blank lines and lines starting with # are ignored

# -----------------------------------------------------------------------------
# EXACT MATCH PATTERNS
# These match the entire string exactly
# -----------------------------------------------------------------------------
test-message-001
debug-payload
temp-data

# -----------------------------------------------------------------------------
# WILDCARD PATTERNS
# * matches any sequence of characters
# ? matches any single character
# -----------------------------------------------------------------------------

# Match any string starting with "test-"
test-*

# Match any string ending with ".tmp"
*.tmp

# Match "debug_" followed by any characters
debug_*

# Match "order_" + exactly 3 characters + ".xml"
order_???.xml

# Match backup files
*.bak
*.backup

# -----------------------------------------------------------------------------
# REGEX PATTERNS
# Prefix with "regex:" for full regex support
# -----------------------------------------------------------------------------

# Match "temp-" followed by exactly 3 digits
regex:^temp-\d{3}$

# Match anything containing "DEBUG" (case-sensitive)
regex:.*DEBUG.*

# Match test files with lowercase letters only
regex:^test-[a-z]+\.json$

# Match UUIDs
regex:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}

# Match ISO timestamps
regex:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}

EOF

info "Created exclusion file at: $EXCLUSION_FILE"
echo ""
cat "$EXCLUSION_FILE"

# -----------------------------------------------------------------------------
# Setup Test Data
# -----------------------------------------------------------------------------

subheader "2. Creating Test Messages"

# Create messages with various correlation IDs
CORRELATIONS=(
    "order-001"
    "test-message-001"
    "debug-payload"
    "test-abc"
    "production-order"
    "temp-123"
    "DEBUG-trace"
    "normal-message"
    "order_ABC.xml"
    "backup.bak"
)

for corr in "${CORRELATIONS[@]}"; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOLACE_QUEUE" \
        --correlation-id "$corr" \
        "Message with correlation: $corr"
done

info "Published ${#CORRELATIONS[@]} messages with various correlation IDs"

# -----------------------------------------------------------------------------
# Consume with Exclusions
# -----------------------------------------------------------------------------

subheader "3. Consuming with Exclusion Filter"

show_cmd "solace-cli consume ... --exclude-file exclusions.txt --browse -n 20"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --exclude-file "$EXCLUSION_FILE" \
    --verbose \
    --browse \
    -n 20

echo ""
info "Notice: Messages with excluded correlation IDs were skipped"

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Content-Based Exclusion
# -----------------------------------------------------------------------------

subheader "4. Content-Based Exclusion"

# Publish messages with specific content
solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" \
    --correlation-id "content-1" '{"type": "order", "DEBUG": true}'
solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" \
    --correlation-id "content-2" '{"type": "order", "status": "pending"}'
solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" \
    --correlation-id "content-3" '{"type": "test-data", "temp": true}'

show_cmd "solace-cli consume ... --exclude-file exclusions.txt --exclude-content --browse"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --exclude-file "$EXCLUSION_FILE" \
    --exclude-content \
    --verbose \
    --browse \
    -n 10

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Folder Publish with Exclusions
# -----------------------------------------------------------------------------

subheader "5. Folder Publish with Filename Exclusion"

# Create test files
mkdir -p "${MESSAGES_DIR}/exclusion_test"
cat > "${MESSAGES_DIR}/exclusion_test/order-001.json" << 'EOF'
{"type": "order", "id": "001"}
EOF
cat > "${MESSAGES_DIR}/exclusion_test/test-abc.json" << 'EOF'
{"type": "test", "id": "abc"}
EOF
cat > "${MESSAGES_DIR}/exclusion_test/debug_trace.json" << 'EOF'
{"type": "debug", "trace": true}
EOF
cat > "${MESSAGES_DIR}/exclusion_test/production.json" << 'EOF'
{"type": "production", "data": "important"}
EOF
cat > "${MESSAGES_DIR}/exclusion_test/backup.bak" << 'EOF'
backup data
EOF

info "Created test files:"
ls -la "${MESSAGES_DIR}/exclusion_test/"

show_cmd "solace-cli folder-publish ... --exclude-file exclusions.txt"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --exclude-file "$EXCLUSION_FILE" \
    "${MESSAGES_DIR}/exclusion_test"

echo ""
info "Files matching exclusion patterns were skipped"

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Copy Queue with Exclusions
# -----------------------------------------------------------------------------

subheader "6. Copy Queue with Exclusion Filter"

# Publish messages to source queue
SOURCE_Q="demo.queue"
DEST_Q="demo.queue.backup"

for corr in "keep-001" "test-skip" "keep-002" "debug_skip" "keep-003"; do
    solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_Q" \
        --correlation-id "$corr" "Message: $corr"
done

show_cmd "solace-cli copy-queue ... --exclude-file exclusions.txt --move"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_Q" \
    --dest "$DEST_Q" \
    --exclude-file "$EXCLUSION_FILE" \
    --move

echo ""
info "Only messages not matching exclusion patterns were copied"

# Verify what was copied
info "Messages in destination queue:"
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_Q" --verbose -t 2 -n 10 || true

# Clean up
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_Q" -t 2 -n 1000 > /dev/null 2>&1 || true
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_Q" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Pattern Types Summary
# -----------------------------------------------------------------------------

subheader "7. Exclusion Pattern Reference"

echo "Pattern Types:"
echo ""
echo "┌─────────────────────────────────────────────────────────────────┐"
echo "│ Type     │ Syntax           │ Example          │ Matches       │"
echo "├──────────┼──────────────────┼──────────────────┼───────────────┤"
echo "│ Exact    │ value            │ test-001         │ 'test-001'    │"
echo "│ Wildcard │ * and ?          │ order-*.xml      │ 'order-1.xml' │"
echo "│ Regex    │ regex:pattern    │ regex:^test-\\d+$ │ 'test-123'    │"
echo "└──────────┴──────────────────┴──────────────────┴───────────────┘"
echo ""
echo "Wildcard characters:"
echo "  *  matches any sequence of characters (including empty)"
echo "  ?  matches exactly one character"
echo ""
echo "Regex tips:"
echo "  ^      start of string"
echo "  $      end of string"
echo "  \\d     digit (0-9)"
echo "  \\w     word character (a-z, A-Z, 0-9, _)"
echo "  .      any character"
echo "  .*     any sequence"
echo "  +      one or more"
echo "  {n}    exactly n times"
echo ""

# -----------------------------------------------------------------------------
# Commands Supporting Exclusions
# -----------------------------------------------------------------------------

subheader "8. Commands Supporting Exclusions"

echo "┌───────────────────┬─────────────────┬───────────────────────────┐"
echo "│ Command           │ --exclude-file  │ --exclude-content         │"
echo "│                   │ checks          │ (additional check)        │"
echo "├───────────────────┼─────────────────┼───────────────────────────┤"
echo "│ consume           │ Correlation ID  │ Message content           │"
echo "│ folder-publish    │ Filename        │ File content              │"
echo "│ oracle-publish    │ Correlation ID  │ Content (always checked)  │"
echo "│ copy-queue        │ Correlation ID  │ Message content           │"
echo "│ oracle-insert     │ Filename        │ File content              │"
echo "│ perf-test         │ Message marker  │ Message content           │"
echo "└───────────────────┴─────────────────┴───────────────────────────┘"
echo ""

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Exclusion Examples Complete"

echo "Exclusion file: $EXCLUSION_FILE"
echo ""
echo "Key features:"
echo "  - Comments with #"
echo "  - Exact string matching"
echo "  - Wildcard patterns (* and ?)"
echo "  - Full regex support (regex: prefix)"
echo "  - Content-based filtering (--exclude-content)"
echo ""
