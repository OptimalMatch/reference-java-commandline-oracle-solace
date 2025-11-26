#!/bin/bash
# =============================================================================
# Oracle Commands Examples
# =============================================================================
# Demonstrates oracle-publish, oracle-export, and oracle-insert commands.
#
# Prerequisites:
#   - Oracle database accessible
#   - Required tables created (see setup section)
#
# Usage: ./examples-oracle.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Oracle Commands Examples"
show_connection_info
show_oracle_info

# -----------------------------------------------------------------------------
# Note: These examples assume you have an Oracle database with the following
# tables. You can create them with the SQL below:
# -----------------------------------------------------------------------------

cat << 'EOF'
Required Oracle Tables (run this SQL first):

-- Outbound messages table
CREATE TABLE outbound_messages (
    message_id VARCHAR2(50) PRIMARY KEY,
    message_content CLOB,
    correlation_id VARCHAR2(100),
    status VARCHAR2(20) DEFAULT 'PENDING',
    created_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Message archive table
CREATE TABLE message_archive (
    id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    content CLOB,
    source_filename VARCHAR2(255),
    inserted_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sample data
INSERT INTO outbound_messages (message_id, message_content, correlation_id, status)
VALUES ('MSG-001', '{"orderId": "ORD-001", "customer": "John"}', 'order-001', 'PENDING');
INSERT INTO outbound_messages (message_id, message_content, correlation_id, status)
VALUES ('MSG-002', '{"orderId": "ORD-002", "customer": "Jane"}', 'order-002', 'PENDING');
COMMIT;

EOF

# =============================================================================
# ORACLE-PUBLISH EXAMPLES
# =============================================================================

header "Oracle Publish Examples"

# -----------------------------------------------------------------------------
# Basic Oracle Publish
# -----------------------------------------------------------------------------

subheader "1. Basic Oracle Publish"

show_cmd 'solace-cli oracle-publish ... --sql "SELECT message_content FROM outbound_messages WHERE status = '\''PENDING'\''"'
cat << 'EOF'
# Example command (uncomment when Oracle is available):

solace_cli oracle-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --db-host "$ORACLE_HOST" \
    --db-port "$ORACLE_PORT" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT message_content FROM outbound_messages WHERE status = 'PENDING'"
EOF

# -----------------------------------------------------------------------------
# With Message and Correlation Columns
# -----------------------------------------------------------------------------

subheader "2. Oracle Publish with Column Mapping"

show_cmd 'solace-cli oracle-publish ... --message-column message_content --correlation-column correlation_id'
cat << 'EOF'
# Example command:

solace_cli oracle-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT message_id, message_content, correlation_id FROM outbound_messages" \
    --message-column message_content \
    --correlation-column correlation_id
EOF

# -----------------------------------------------------------------------------
# Using SQL File
# -----------------------------------------------------------------------------

subheader "3. Oracle Publish with SQL File"

# Create sample SQL file
cat > "${DATA_DIR}/query.sql" << 'EOF'
SELECT
    message_id,
    message_content,
    correlation_id,
    created_timestamp
FROM outbound_messages
WHERE status = 'PENDING'
    AND created_timestamp > SYSDATE - 1
ORDER BY created_timestamp
EOF

show_cmd "solace-cli oracle-publish ... --sql-file query.sql"
cat << 'EOF'
# Example command:

solace_cli oracle-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql-file query.sql \
    --message-column message_content \
    --correlation-column message_id
EOF

info "SQL file created at: ${DATA_DIR}/query.sql"

# -----------------------------------------------------------------------------
# Dry Run
# -----------------------------------------------------------------------------

subheader "4. Oracle Publish Dry Run"

show_cmd "solace-cli oracle-publish ... --dry-run"
cat << 'EOF'
# Preview messages without publishing:

solace_cli oracle-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT * FROM outbound_messages FETCH FIRST 5 ROWS ONLY" \
    --dry-run
EOF

# -----------------------------------------------------------------------------
# With Fan-Out
# -----------------------------------------------------------------------------

subheader "5. Oracle Publish with Fan-Out"

show_cmd "solace-cli oracle-publish ... -Q orders.audit"
cat << 'EOF'
# Publish to primary queue and audit queue:

solace_cli oracle-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "orders.processing" \
    -Q "orders.audit" \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT message_content FROM outbound_messages"
EOF

# -----------------------------------------------------------------------------
# With Failed Message Persistence
# -----------------------------------------------------------------------------

subheader "6. Oracle Publish with Failure Recovery"

show_cmd "solace-cli oracle-publish ... --failed-dir /data/failed --audit-log audit.log"
cat << 'EOF'
# Enable failure persistence and audit logging:

solace_cli oracle-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$SOLACE_QUEUE" \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT message_content, correlation_id FROM outbound_messages" \
    --message-column message_content \
    --correlation-column correlation_id \
    --failed-dir "$FAILED_DIR" \
    --audit-log "$AUDIT_LOG"
EOF

# =============================================================================
# ORACLE-EXPORT EXAMPLES
# =============================================================================

header "Oracle Export Examples"

# -----------------------------------------------------------------------------
# Basic Export
# -----------------------------------------------------------------------------

subheader "7. Basic Oracle Export"

show_cmd "solace-cli oracle-export ... --output-folder /data/export"
cat << 'EOF'
# Export query results to files:

solace_cli oracle-export \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT message_content FROM outbound_messages" \
    --output-folder "$OUTPUT_DIR"
EOF

# -----------------------------------------------------------------------------
# Export with Filename Column
# -----------------------------------------------------------------------------

subheader "8. Oracle Export with Custom Filenames"

show_cmd "solace-cli oracle-export ... --filename-column message_id --extension .json"
cat << 'EOF'
# Use a column value as the filename:

solace_cli oracle-export \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT message_content, message_id FROM outbound_messages" \
    --message-column message_content \
    --filename-column message_id \
    --output-folder "$OUTPUT_DIR" \
    --extension .json
EOF

# -----------------------------------------------------------------------------
# Export with Prefix
# -----------------------------------------------------------------------------

subheader "9. Oracle Export with File Prefix"

show_cmd "solace-cli oracle-export ... --prefix order_ --extension .xml"
cat << 'EOF'
# Add prefix to generated filenames:

solace_cli oracle-export \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT payload FROM orders" \
    --output-folder "$OUTPUT_DIR" \
    --prefix order_ \
    --extension .xml
EOF

# -----------------------------------------------------------------------------
# Export Dry Run
# -----------------------------------------------------------------------------

subheader "10. Oracle Export Dry Run"

show_cmd "solace-cli oracle-export ... --dry-run"
cat << 'EOF'
# Preview export without writing files:

solace_cli oracle-export \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT * FROM outbound_messages FETCH FIRST 3 ROWS ONLY" \
    --output-folder "$OUTPUT_DIR" \
    --dry-run
EOF

# =============================================================================
# ORACLE-INSERT EXAMPLES
# =============================================================================

header "Oracle Insert Examples"

# Create sample files for insert
mkdir -p "${DATA_DIR}/to_insert"
for i in {1..3}; do
    cat > "${DATA_DIR}/to_insert/message_$i.json" << EOF
{"id": "INSERT-$i", "content": "Message to insert $i", "timestamp": "$(date -Iseconds)"}
EOF
done
info "Created sample files in ${DATA_DIR}/to_insert"

# -----------------------------------------------------------------------------
# Basic Insert
# -----------------------------------------------------------------------------

subheader "11. Basic Oracle Insert"

show_cmd "solace-cli oracle-insert ... --folder /data/messages --table message_archive"
cat << 'EOF'
# Insert file contents into Oracle:

solace_cli oracle-insert \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --folder "${DATA_DIR}/to_insert" \
    --table message_archive \
    --content-column content
EOF

# -----------------------------------------------------------------------------
# Insert with Filename Column
# -----------------------------------------------------------------------------

subheader "12. Oracle Insert with Filename Tracking"

show_cmd "solace-cli oracle-insert ... --filename-column source_filename"
cat << 'EOF'
# Track source filename in database:

solace_cli oracle-insert \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --folder "${DATA_DIR}/to_insert" \
    --table message_archive \
    --content-column content \
    --filename-column source_filename
EOF

# -----------------------------------------------------------------------------
# Insert with Pattern Filter
# -----------------------------------------------------------------------------

subheader "13. Oracle Insert with File Pattern"

show_cmd "solace-cli oracle-insert ... --pattern '*.json'"
cat << 'EOF'
# Only insert JSON files:

solace_cli oracle-insert \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --folder "${DATA_DIR}/to_insert" \
    --pattern "*.json" \
    --table message_archive \
    --content-column content
EOF

# -----------------------------------------------------------------------------
# Insert with Custom SQL
# -----------------------------------------------------------------------------

subheader "14. Oracle Insert with Custom SQL File"

# Create custom insert SQL
cat > "${DATA_DIR}/insert.sql" << 'EOF'
INSERT INTO message_archive (
    content,
    source_filename,
    inserted_timestamp,
    status
) VALUES (?, ??, CURRENT_TIMESTAMP, 'NEW')
EOF

show_cmd "solace-cli oracle-insert ... --sql-file insert.sql"
cat << 'EOF'
# Use custom INSERT statement:

solace_cli oracle-insert \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --folder "${DATA_DIR}/to_insert" \
    --sql-file "${DATA_DIR}/insert.sql"
EOF

info "Custom SQL file created at: ${DATA_DIR}/insert.sql"

# -----------------------------------------------------------------------------
# Insert Dry Run
# -----------------------------------------------------------------------------

subheader "15. Oracle Insert Dry Run"

show_cmd "solace-cli oracle-insert ... --dry-run"
cat << 'EOF'
# Preview files to insert without executing:

solace_cli oracle-insert \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --folder "${DATA_DIR}/to_insert" \
    --table message_archive \
    --content-column content \
    --dry-run
EOF

# =============================================================================
# TWO-STEP WORKFLOW
# =============================================================================

header "Two-Step Workflow: Export then Publish"

cat << 'EOF'
Complete workflow for exporting from Oracle and publishing to Solace:

# Step 1: Export from Oracle to files
solace_cli oracle-export \
    --db-host "$ORACLE_HOST" \
    --db-service "$ORACLE_SERVICE" \
    --db-user "$ORACLE_USER" \
    --db-password "$ORACLE_PASS" \
    --sql "SELECT message_content, message_id FROM outbound_messages WHERE status = 'PENDING'" \
    --message-column message_content \
    --filename-column message_id \
    --output-folder /data/staging \
    --extension .json

# Step 2: Publish files to Solace
solace_cli folder-publish \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q orders.inbound \
    --folder /data/staging \
    --pattern "*.json" \
    --use-filename-as-correlation \
    --failed-dir /data/failed \
    --audit-log /var/log/audit.log

# Step 3 (Optional): Update Oracle status
# UPDATE outbound_messages SET status = 'PUBLISHED' WHERE status = 'PENDING';

EOF

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Examples Complete"

echo "Sample files created:"
echo "  SQL query file: ${DATA_DIR}/query.sql"
echo "  Insert SQL file: ${DATA_DIR}/insert.sql"
echo "  Files to insert: ${DATA_DIR}/to_insert/"
echo ""
echo "Note: These examples show command syntax."
echo "Connect to an actual Oracle database to run them."
echo ""
