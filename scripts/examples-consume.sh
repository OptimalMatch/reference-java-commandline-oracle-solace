#!/bin/bash
# =============================================================================
# Consume Command Examples
# =============================================================================
# Demonstrates various ways to consume messages from Solace queues.
#
# Usage: ./examples-consume.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Consume Command Examples"
show_connection_info

# First, publish some test messages
subheader "Setup: Publishing Test Messages"

for i in {1..10}; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOLACE_QUEUE" \
        --correlation-id "msg-$(printf "%03d" $i)" \
        "Test message $i - $(date -Iseconds)"
done
info "Published 10 test messages"

# -----------------------------------------------------------------------------
# Basic Consuming
# -----------------------------------------------------------------------------

subheader "1. Consume Specific Number of Messages"

show_cmd "solace-cli consume ... -n 3"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -n 3

# -----------------------------------------------------------------------------
# Browse Mode (Non-Destructive)
# -----------------------------------------------------------------------------

subheader "2. Browse Messages (Non-Destructive)"

show_cmd "solace-cli consume ... --browse -n 3"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --browse \
    -n 3

# -----------------------------------------------------------------------------
# Verbose Output
# -----------------------------------------------------------------------------

subheader "3. Verbose Output with Metadata"

show_cmd "solace-cli consume ... --verbose --browse -n 2"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --verbose \
    --browse \
    -n 2

# -----------------------------------------------------------------------------
# With Timeout
# -----------------------------------------------------------------------------

subheader "4. Consume with Timeout"

show_cmd "solace-cli consume ... -t 5 -n 100"
echo "(Will wait up to 5 seconds for messages)"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -t 5 \
    -n 100

# -----------------------------------------------------------------------------
# Save to Files
# -----------------------------------------------------------------------------

# Re-publish for file save demo
for i in {1..5}; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOLACE_QUEUE" \
        --correlation-id "file-msg-$i" \
        "Message to save to file $i"
done

subheader "5. Save Messages to Files"

show_cmd "solace-cli consume ... -o output/ -n 5"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -o "$OUTPUT_DIR" \
    -n 5

info "Saved files:"
ls -la "$OUTPUT_DIR"

# -----------------------------------------------------------------------------
# Custom File Extension and Prefix
# -----------------------------------------------------------------------------

# Re-publish for custom extension demo
for i in {1..3}; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOLACE_QUEUE" \
        --correlation-id "order-$i" \
        '{"orderId": "ORD-'$i'", "status": "pending"}'
done

subheader "6. Custom File Extension and Prefix"

show_cmd "solace-cli consume ... -o output/ --extension .json --prefix order_ -n 3"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -o "$OUTPUT_DIR" \
    --extension .json \
    --prefix order_ \
    -n 3

info "Files with custom naming:"
ls -la "$OUTPUT_DIR"/order_*

# -----------------------------------------------------------------------------
# Use Correlation ID as Filename
# -----------------------------------------------------------------------------

# Re-publish with correlation IDs
for id in invoice-001 invoice-002 invoice-003; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOLACE_QUEUE" \
        --correlation-id "$id" \
        "Content for $id"
done

subheader "7. Use Correlation ID as Filename"

show_cmd "solace-cli consume ... -o output/ --use-correlation-id --extension .txt -n 3"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -o "$OUTPUT_DIR" \
    --use-correlation-id \
    --extension .txt \
    -n 3

info "Files named by correlation ID:"
ls -la "$OUTPUT_DIR"/invoice-*

# -----------------------------------------------------------------------------
# No Acknowledgment Mode
# -----------------------------------------------------------------------------

# Re-publish some messages
for i in {1..3}; do
    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOLACE_QUEUE" \
        "No-ack test message $i"
done

subheader "8. Consume Without Acknowledgment"

show_cmd "solace-cli consume ... --no-ack -n 3"
echo "(Messages remain in queue after consuming)"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --no-ack \
    -n 3

# -----------------------------------------------------------------------------
# With Audit Logging
# -----------------------------------------------------------------------------

subheader "9. Consume with Audit Logging"

show_cmd "solace-cli consume ... --audit-log audit.log --browse -n 2"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --audit-log "$AUDIT_LOG" \
    --browse \
    -n 2

echo ""
info "Latest audit log entry:"
tail -1 "$AUDIT_LOG" | python3 -m json.tool 2>/dev/null || tail -1 "$AUDIT_LOG"

# -----------------------------------------------------------------------------
# Clean up remaining messages
# -----------------------------------------------------------------------------

subheader "10. Drain Remaining Messages"

show_cmd "solace-cli consume ... -t 2 -n 1000"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -t 2 \
    -n 1000

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Examples Complete"

echo "Output directory: $OUTPUT_DIR"
echo "Audit log: $AUDIT_LOG"
echo ""
echo "Files created:"
ls -la "$OUTPUT_DIR" | head -15
echo ""
