#!/bin/bash
# =============================================================================
# Publish Command Examples
# =============================================================================
# Demonstrates various ways to publish messages to Solace queues.
#
# Usage: ./examples-publish.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Publish Command Examples"
show_connection_info

# -----------------------------------------------------------------------------
# Basic Publishing
# -----------------------------------------------------------------------------

subheader "1. Simple Message Publishing"

show_cmd "solace-cli publish ... 'Hello, Solace!'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    "Hello, Solace!"

# -----------------------------------------------------------------------------
# Publishing from File
# -----------------------------------------------------------------------------

subheader "2. Publish from File"

# Create a sample message file
cat > "${DATA_DIR}/sample-message.json" << 'EOF'
{
  "orderId": "ORD-12345",
  "customer": "John Doe",
  "items": [
    {"sku": "ITEM-001", "qty": 2},
    {"sku": "ITEM-002", "qty": 1}
  ],
  "total": 149.99
}
EOF

show_cmd "solace-cli publish ... -f sample-message.json"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -f "${DATA_DIR}/sample-message.json"

# -----------------------------------------------------------------------------
# Publishing from Stdin (Pipe)
# -----------------------------------------------------------------------------

subheader "3. Publish from Stdin (Pipe)"

show_cmd "echo 'Piped message' | solace-cli publish ..."
echo "Piped message content from stdin" | solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE"

# -----------------------------------------------------------------------------
# Multiple Messages
# -----------------------------------------------------------------------------

subheader "4. Publish Multiple Messages"

show_cmd "solace-cli publish ... -c 5 'Test message'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -c 5 \
    "Test message batch"

# -----------------------------------------------------------------------------
# With Correlation ID
# -----------------------------------------------------------------------------

subheader "5. Publish with Correlation ID"

show_cmd "solace-cli publish ... --correlation-id 'order-12345' 'Order data'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --correlation-id "order-12345" \
    "Order data with correlation"

# -----------------------------------------------------------------------------
# With TTL (Time to Live)
# -----------------------------------------------------------------------------

subheader "6. Publish with TTL"

show_cmd "solace-cli publish ... --ttl 60000 'Expiring message'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --ttl 60000 \
    "This message expires in 60 seconds"

# -----------------------------------------------------------------------------
# Direct Delivery Mode
# -----------------------------------------------------------------------------

subheader "7. Publish with DIRECT Delivery Mode"

show_cmd "solace-cli publish ... --delivery-mode DIRECT 'Direct message'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --delivery-mode DIRECT \
    "Direct delivery message (non-persistent)"

# -----------------------------------------------------------------------------
# Fan-Out to Second Queue
# -----------------------------------------------------------------------------

subheader "8. Fan-Out Publishing (Two Queues)"

show_cmd "solace-cli publish ... -q demo.queue -Q demo.queue.backup 'Fan-out message'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "demo.queue" \
    -Q "demo.queue.backup" \
    "This message goes to both queues"

# -----------------------------------------------------------------------------
# With Audit Logging
# -----------------------------------------------------------------------------

subheader "9. Publish with Audit Logging"

show_cmd "solace-cli publish ... --audit-log audit.log 'Audited message'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --audit-log "$AUDIT_LOG" \
    "This publish is being audited"

echo ""
info "Audit log entry:"
tail -1 "$AUDIT_LOG" | python3 -m json.tool 2>/dev/null || tail -1 "$AUDIT_LOG"

# -----------------------------------------------------------------------------
# Combined Options
# -----------------------------------------------------------------------------

subheader "10. Publish with Multiple Options"

show_cmd "solace-cli publish ... -c 3 --correlation-id 'batch-001' --ttl 300000 --audit-log audit.log 'Combined options'"
solace_cli publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    -c 3 \
    --correlation-id "batch-001" \
    --ttl 300000 \
    --audit-log "$AUDIT_LOG" \
    "Message with multiple options"

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Examples Complete"

echo "Published messages to: $SOLACE_QUEUE"
echo "Audit log location: $AUDIT_LOG"
echo ""
echo "Next steps:"
echo "  - Run ./examples-consume.sh to consume these messages"
echo "  - Check audit log: cat $AUDIT_LOG"
echo ""
