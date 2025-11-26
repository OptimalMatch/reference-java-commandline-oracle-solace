#!/bin/bash
# =============================================================================
# Folder Publish Command Examples
# =============================================================================
# Demonstrates batch publishing messages from files in a directory.
#
# Usage: ./examples-folder-publish.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Folder Publish Command Examples"
show_connection_info

# -----------------------------------------------------------------------------
# Create Sample Files
# -----------------------------------------------------------------------------

subheader "Setup: Creating Sample Message Files"

# Create JSON messages
create_sample_messages 5

# Create XML messages
create_sample_xml_messages 5

# Create some test/debug files for exclusion demos
cat > "${MESSAGES_DIR}/test-debug.json" << 'EOF'
{"type": "debug", "message": "This should be excluded"}
EOF
cat > "${MESSAGES_DIR}/debug_trace.xml" << 'EOF'
<debug><trace>Exclude this</trace></debug>
EOF

info "Created sample files in $MESSAGES_DIR"
ls -la "$MESSAGES_DIR"

# -----------------------------------------------------------------------------
# Basic Folder Publishing
# -----------------------------------------------------------------------------

subheader "1. Publish All Files from Folder"

show_cmd "solace-cli folder-publish ... $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    "$MESSAGES_DIR"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Filter by Pattern
# -----------------------------------------------------------------------------

subheader "2. Publish Only JSON Files"

show_cmd "solace-cli folder-publish ... --pattern '*.json' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --pattern "*.json" \
    "$MESSAGES_DIR"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Publish Only XML Files
# -----------------------------------------------------------------------------

subheader "3. Publish Only XML Files"

show_cmd "solace-cli folder-publish ... --pattern '*.xml' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --pattern "*.xml" \
    "$MESSAGES_DIR"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Use Filename as Correlation ID
# -----------------------------------------------------------------------------

subheader "4. Use Filename as Correlation ID"

show_cmd "solace-cli folder-publish ... --use-filename-as-correlation --pattern '*.json' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --use-filename-as-correlation \
    --pattern "*.json" \
    "$MESSAGES_DIR"

echo ""
info "Consuming to verify correlation IDs:"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --verbose \
    -n 3

# Drain remaining
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Dry Run Mode
# -----------------------------------------------------------------------------

subheader "5. Dry Run (Preview Without Publishing)"

show_cmd "solace-cli folder-publish ... --dry-run --pattern '*.xml' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --dry-run \
    --pattern "*.xml" \
    "$MESSAGES_DIR"

# -----------------------------------------------------------------------------
# Sort Files
# -----------------------------------------------------------------------------

subheader "6. Sort Files by Name Before Publishing"

show_cmd "solace-cli folder-publish ... --sort NAME --pattern '*.json' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --sort NAME \
    --pattern "*.json" \
    "$MESSAGES_DIR"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Fan-Out to Second Queue
# -----------------------------------------------------------------------------

subheader "7. Fan-Out to Multiple Queues"

show_cmd "solace-cli folder-publish ... -Q demo.queue.backup --pattern '*.json' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -Q "demo.queue.backup" \
    --pattern "*.json" \
    "$MESSAGES_DIR"

# Drain both queues
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "demo.queue.backup" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# With Exclusion File
# -----------------------------------------------------------------------------

subheader "8. Publish with Exclusion Patterns"

# Create exclusion file
create_exclusion_file

show_cmd "solace-cli folder-publish ... --exclude-file exclusions.txt $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --exclude-file "${DATA_DIR}/exclusions.txt" \
    "$MESSAGES_DIR"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# With Failed Message Directory
# -----------------------------------------------------------------------------

subheader "9. Publish with Failed Message Persistence"

show_cmd "solace-cli folder-publish ... --failed-dir $FAILED_DIR --pattern '*.json' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --failed-dir "$FAILED_DIR" \
    --pattern "*.json" \
    "$MESSAGES_DIR"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Recursive Directory Scan
# -----------------------------------------------------------------------------

subheader "10. Recursive Directory Publishing"

# Create subdirectory with files
mkdir -p "${MESSAGES_DIR}/subdir"
cat > "${MESSAGES_DIR}/subdir/nested_message.json" << 'EOF'
{"type": "nested", "message": "From subdirectory"}
EOF

show_cmd "solace-cli folder-publish ... --recursive --pattern '*.json' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --recursive \
    --pattern "*.json" \
    "$MESSAGES_DIR"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# With Audit Logging
# -----------------------------------------------------------------------------

subheader "11. Folder Publish with Audit Logging"

show_cmd "solace-cli folder-publish ... --audit-log audit.log --pattern '*.xml' $MESSAGES_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --audit-log "$AUDIT_LOG" \
    --pattern "*.xml" \
    "$MESSAGES_DIR"

echo ""
info "Latest audit log entry:"
tail -1 "$AUDIT_LOG" | python3 -m json.tool 2>/dev/null || tail -1 "$AUDIT_LOG"

# Drain the queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Examples Complete"

echo "Message files: $MESSAGES_DIR"
echo "Audit log: $AUDIT_LOG"
echo ""
