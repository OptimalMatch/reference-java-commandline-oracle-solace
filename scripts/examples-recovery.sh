#!/bin/bash
# =============================================================================
# Publish Recovery Examples
# =============================================================================
# Demonstrates failure recovery features: --failed-dir and --retry-dir
#
# Usage: ./examples-recovery.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Publish Recovery Examples"
show_connection_info

# Clean start
rm -rf "$FAILED_DIR" "$RETRY_DIR"
mkdir -p "$FAILED_DIR" "$RETRY_DIR"

# -----------------------------------------------------------------------------
# Basic Failed Message Directory
# -----------------------------------------------------------------------------

subheader "1. Publishing with Failed Message Persistence"

info "Publishing messages with --failed-dir enabled..."
show_cmd "solace-cli publish ... --failed-dir $FAILED_DIR -c 5"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --failed-dir "$FAILED_DIR" \
    -c 5 \
    "Recovery test message"

echo ""
info "If any messages failed, they would be saved to: $FAILED_DIR"
ls -la "$FAILED_DIR" 2>/dev/null || echo "(No failed messages)"

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Simulating Failed Messages
# -----------------------------------------------------------------------------

subheader "2. Simulating Failed Messages (Manual Creation)"

info "Creating simulated failed message files..."

# Create .msg file (message content)
cat > "${FAILED_DIR}/20250115_103000_000_order-001_1.msg" << 'EOF'
{"orderId": "ORD-001", "customer": "John Doe", "total": 99.99}
EOF

# Create .meta file (metadata)
cat > "${FAILED_DIR}/20250115_103000_000_order-001_1.meta" << EOF
{
  "timestamp": "$(date -Iseconds)",
  "queue": "$SOLACE_QUEUE",
  "correlationId": "order-001",
  "index": 1,
  "error": "Connection timeout",
  "contentFile": "20250115_103000_000_order-001_1.msg"
}
EOF

# Second failed message
cat > "${FAILED_DIR}/20250115_103001_000_order-002_2.msg" << 'EOF'
{"orderId": "ORD-002", "customer": "Jane Smith", "total": 149.99}
EOF

cat > "${FAILED_DIR}/20250115_103001_000_order-002_2.meta" << EOF
{
  "timestamp": "$(date -Iseconds)",
  "queue": "$SOLACE_QUEUE",
  "correlationId": "order-002",
  "index": 2,
  "error": "Connection timeout",
  "contentFile": "20250115_103001_000_order-002_2.msg"
}
EOF

echo ""
info "Created failed message files:"
ls -la "$FAILED_DIR"

# -----------------------------------------------------------------------------
# Retry Failed Messages
# -----------------------------------------------------------------------------

subheader "3. Retrying Failed Messages"

show_cmd "solace-cli publish ... --retry-dir $FAILED_DIR"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --retry-dir "$FAILED_DIR"

echo ""
info "After successful retry, files are deleted:"
ls -la "$FAILED_DIR" 2>/dev/null || echo "(Directory empty - all retries succeeded)"

# Verify messages were published
info "Verifying retried messages:"
solace_cli consume \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --verbose \
    -n 5

# -----------------------------------------------------------------------------
# Combined Workflow: Retry with New Failures
# -----------------------------------------------------------------------------

subheader "4. Retry with New Failed Directory"

# Create more failed messages
cat > "${FAILED_DIR}/retry_msg_1.msg" << 'EOF'
{"type": "retry", "id": "RETRY-001"}
EOF
cat > "${FAILED_DIR}/retry_msg_1.meta" << EOF
{"queue": "$SOLACE_QUEUE", "correlationId": "retry-001"}
EOF

show_cmd "solace-cli publish ... --retry-dir $FAILED_DIR --failed-dir $RETRY_DIR"
info "Retry from $FAILED_DIR, save new failures to $RETRY_DIR"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --retry-dir "$FAILED_DIR" \
    --failed-dir "$RETRY_DIR"

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Folder Publish with Recovery
# -----------------------------------------------------------------------------

subheader "5. Folder Publish with Failed Directory"

# Create sample messages
create_sample_messages 5

show_cmd "solace-cli folder-publish ... --failed-dir $FAILED_DIR"
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --failed-dir "$FAILED_DIR" \
    --pattern "*.json" \
    "$MESSAGES_DIR"

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Recovery with Audit Logging
# -----------------------------------------------------------------------------

subheader "6. Recovery with Full Audit Trail"

# Create a failed message
cat > "${FAILED_DIR}/audit_msg.msg" << 'EOF'
{"type": "audit-test", "id": "AUDIT-001"}
EOF
cat > "${FAILED_DIR}/audit_msg.meta" << EOF
{"queue": "$SOLACE_QUEUE", "correlationId": "audit-001", "error": "Test failure"}
EOF

show_cmd "solace-cli publish ... --retry-dir $FAILED_DIR --audit-log audit.log"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --retry-dir "$FAILED_DIR" \
    --audit-log "$AUDIT_LOG"

echo ""
info "Audit log entry shows retry details:"
tail -1 "$AUDIT_LOG" | python3 -m json.tool 2>/dev/null || tail -1 "$AUDIT_LOG"

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOLACE_QUEUE" -t 2 -n 1000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Failed Message File Format
# -----------------------------------------------------------------------------

subheader "7. Understanding Failed Message File Format"

echo "Failed Message File Structure:"
echo ""
echo "Each failed message creates two files:"
echo ""
echo "1. Message file (.msg):"
echo "   Filename: {timestamp}_{correlationId}_{index}.msg"
echo "   Content: The original message payload"
echo ""
echo "2. Metadata file (.meta):"
echo "   Filename: {timestamp}_{correlationId}_{index}.meta"
echo "   Content: JSON with:"
cat << 'EOF'
   {
     "timestamp": "2025-01-15T10:30:00Z",
     "queue": "target-queue-name",
     "correlationId": "original-correlation-id",
     "index": 1,
     "error": "Error message from failed publish",
     "contentFile": "20250115_103000_000_correlation_1.msg"
   }
EOF
echo ""

# -----------------------------------------------------------------------------
# Complete Recovery Workflow
# -----------------------------------------------------------------------------

subheader "8. Complete Recovery Workflow Example"

cat << 'EOF'
Complete workflow for handling publish failures:

# Step 1: Initial publish attempt with failure tracking
solace-cli publish \
    -H tcp://localhost:55555 \
    -v default \
    -u admin \
    -p admin \
    -q orders.inbound \
    -c 1000 \
    --failed-dir /data/failed \
    --audit-log /var/log/solace-audit.log \
    "Order message content"

# Step 2: Check for failures
ls /data/failed/
# If files present, investigate the .meta files for error details

# Step 3: After fixing connectivity, retry failed messages
solace-cli publish \
    -H tcp://localhost:55555 \
    -v default \
    -u admin \
    -p admin \
    -q orders.inbound \
    --retry-dir /data/failed \
    --failed-dir /data/retry-failed \
    --audit-log /var/log/solace-audit.log

# Step 4: Verify all messages published
cat /var/log/solace-audit.log | jq -s 'map(select(.command=="publish"))'

# Step 5: Clean up (if all successful)
rm -rf /data/failed /data/retry-failed

EOF

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Recovery Examples Complete"

echo "Key options:"
echo "  --failed-dir    Directory to save failed messages"
echo "  --retry-dir     Directory with messages to retry"
echo ""
echo "File structure:"
echo "  *.msg           Message content"
echo "  *.meta          JSON metadata (queue, correlation, error)"
echo ""
echo "Commands supporting recovery:"
echo "  publish         Both --failed-dir and --retry-dir"
echo "  folder-publish  Only --failed-dir"
echo "  oracle-publish  Only --failed-dir"
echo ""
echo "Directories used:"
echo "  Failed: $FAILED_DIR"
echo "  Retry:  $RETRY_DIR"
echo ""
