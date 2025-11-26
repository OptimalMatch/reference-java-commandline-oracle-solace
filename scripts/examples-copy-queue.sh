#!/bin/bash
# =============================================================================
# Copy Queue Command Examples
# =============================================================================
# Demonstrates copying and moving messages between Solace queues.
#
# Usage: ./examples-copy-queue.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Copy Queue Command Examples"
show_connection_info

SOURCE_QUEUE="demo.queue"
DEST_QUEUE="demo.queue.backup"
DLQ_QUEUE="orders.dlq"
RETRY_QUEUE="messages.retry"

# -----------------------------------------------------------------------------
# Setup: Publish Test Messages
# -----------------------------------------------------------------------------

subheader "Setup: Publishing Test Messages to Source Queue"

# Clear destination queue first
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# Publish test messages
for i in {1..10}; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOURCE_QUEUE" \
        --correlation-id "copy-test-$i" \
        "Copy test message $i - $(date -Iseconds)"
done
info "Published 10 test messages to $SOURCE_QUEUE"

# -----------------------------------------------------------------------------
# Basic Copy (Browse Mode)
# -----------------------------------------------------------------------------

subheader "1. Copy Messages (Non-Destructive)"

show_cmd "solace-cli copy-queue ... -q $SOURCE_QUEUE --dest $DEST_QUEUE -c 5"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    -c 5

echo ""
info "Source queue still has messages (browse mode):"
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" --browse -n 3

# Clear destination for next example
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Move Messages (Destructive)
# -----------------------------------------------------------------------------

subheader "2. Move Messages (Removes from Source)"

show_cmd "solace-cli copy-queue ... -q $SOURCE_QUEUE --dest $DEST_QUEUE --move -c 5"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    --move \
    -c 5

echo ""
info "Messages moved - checking counts:"
echo "Source queue:"
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" --browse -t 2 -n 100 || true
echo "Destination queue:"
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" --browse -t 2 -n 100 || true

# -----------------------------------------------------------------------------
# Dry Run
# -----------------------------------------------------------------------------

# Re-populate source
for i in {1..5}; do
    solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" --correlation-id "dry-run-$i" "Dry run test $i"
done

subheader "3. Dry Run (Preview Without Copying)"

show_cmd "solace-cli copy-queue ... -q $SOURCE_QUEUE --dest $DEST_QUEUE --dry-run"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    --dry-run

# -----------------------------------------------------------------------------
# With Timeout
# -----------------------------------------------------------------------------

subheader "4. Copy with Custom Timeout"

show_cmd "solace-cli copy-queue ... -q $SOURCE_QUEUE --dest $DEST_QUEUE -t 10 -c 100"
echo "(Will wait up to 10 seconds for messages)"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    -t 10 \
    -c 100

# Clean up
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Preserve Properties
# -----------------------------------------------------------------------------

# Publish with specific properties
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --correlation-id "preserve-me" \
    --ttl 300000 \
    "Message with properties to preserve"

subheader "5. Copy Preserving Message Properties"

show_cmd "solace-cli copy-queue ... --preserve-properties -c 1"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    --preserve-properties \
    --move \
    -c 1

echo ""
info "Copied message preserves correlation ID:"
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" --verbose -n 1

# -----------------------------------------------------------------------------
# Override Delivery Mode
# -----------------------------------------------------------------------------

# Re-populate
for i in {1..3}; do
    solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" "Delivery mode test $i"
done

subheader "6. Copy with Delivery Mode Override"

show_cmd "solace-cli copy-queue ... --delivery-mode DIRECT --move -c 3"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    --delivery-mode DIRECT \
    --move \
    -c 3

# Clean destination
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# With Exclusion Patterns
# -----------------------------------------------------------------------------

# Publish mix of messages
for i in {1..3}; do
    solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" --correlation-id "keep-$i" "Keep this message $i"
done
for i in {1..2}; do
    solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" --correlation-id "test-$i" "Test message to exclude $i"
done

# Create exclusion file
create_exclusion_file

subheader "7. Copy with Exclusion Patterns"

show_cmd "solace-cli copy-queue ... --exclude-file exclusions.txt --move"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    --exclude-file "${DATA_DIR}/exclusions.txt" \
    --move \
    -c 10

echo ""
info "Only non-excluded messages were copied"

# Clean up
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# DLQ Reprocessing Workflow
# -----------------------------------------------------------------------------

subheader "8. DLQ Reprocessing Workflow"

# Simulate DLQ messages
info "Simulating failed messages in DLQ..."
for i in {1..5}; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$DLQ_QUEUE" \
        --correlation-id "failed-order-$i" \
        '{"orderId": "ORD-'$i'", "error": "Processing failed", "retryCount": 0}'
done

show_cmd "solace-cli copy-queue ... -q orders.dlq --dest messages.retry --move --preserve-properties"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$DLQ_QUEUE" \
    --dest "$RETRY_QUEUE" \
    --move \
    --preserve-properties

echo ""
info "Messages moved from DLQ to retry queue"

# Clean up
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$RETRY_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# With Audit Logging
# -----------------------------------------------------------------------------

# Re-populate
for i in {1..3}; do
    solace_cli publish -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" "Audit test $i"
done

subheader "9. Copy with Audit Logging"

show_cmd "solace-cli copy-queue ... --audit-log audit.log --move -c 3"
solace_cli copy-queue \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOURCE_QUEUE" \
    --dest "$DEST_QUEUE" \
    --audit-log "$AUDIT_LOG" \
    --move \
    -c 3

echo ""
info "Latest audit log entry:"
tail -1 "$AUDIT_LOG" | python3 -m json.tool 2>/dev/null || tail -1 "$AUDIT_LOG"

# Clean up
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$DEST_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Examples Complete"

echo "Source queue: $SOURCE_QUEUE"
echo "Destination queue: $DEST_QUEUE"
echo "Audit log: $AUDIT_LOG"
echo ""
echo "Common use cases:"
echo "  - Backup messages before maintenance"
echo "  - Reprocess DLQ messages after fixing issues"
echo "  - Migrate messages between queues"
echo "  - Filter and route messages to different queues"
echo ""
