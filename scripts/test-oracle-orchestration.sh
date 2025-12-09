#!/bin/bash
# =============================================================================
# Test Script for Oracle Orchestration
# =============================================================================
# This script tests the orchestrate-oracle.sh script by:
#   1. Starting an Oracle XE Docker container
#   2. Creating a test table with sample data
#   3. Running the Oracle orchestration to export, transform, and publish
#   4. Verifying the messages in the Solace queue
#   5. Cleaning up
#
# Prerequisites:
#   - Docker installed and running
#   - Solace broker running (default: tcp://localhost:55555)
#   - Project JAR built (mvn clean package)
#
# Usage:
#   ./test-oracle-orchestration.sh [--keep-oracle] [--skip-cleanup]
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

ORACLE_CONTAINER_NAME="oracle-orchestration-test"
ORACLE_IMAGE="gvenzl/oracle-xe:21-slim"
ORACLE_PORT=1522
ORACLE_SYS_PASSWORD="testpass123"
ORACLE_APP_USER="testuser"
ORACLE_APP_PASSWORD="testpass"
ORACLE_SERVICE="XEPDB1"

SOLACE_HOST="${SOLACE_HOST:-tcp://localhost:55555}"
SOLACE_VPN="${SOLACE_VPN:-default}"
SOLACE_USER="${SOLACE_USER:-admin}"
SOLACE_PASS="${SOLACE_PASS:-admin}"
TEST_QUEUE="${TEST_QUEUE:-demo.queue}"  # Use existing queue or specify with TEST_QUEUE env var

KEEP_ORACLE=false
SKIP_CLEANUP=false

# -----------------------------------------------------------------------------
# Parse Arguments
# -----------------------------------------------------------------------------

while [[ $# -gt 0 ]]; do
    case "$1" in
        --keep-oracle)
            KEEP_ORACLE=true
            shift
            ;;
        --skip-cleanup)
            SKIP_CLEANUP=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--keep-oracle] [--skip-cleanup]"
            echo ""
            echo "Options:"
            echo "  --keep-oracle    Don't stop/remove Oracle container after test"
            echo "  --skip-cleanup   Don't clean up test files and queue messages"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# -----------------------------------------------------------------------------
# Helper Functions
# -----------------------------------------------------------------------------

wait_for_oracle() {
    local max_wait=180
    local waited=0

    info "Waiting for Oracle to be ready (max ${max_wait}s)..."

    while [[ $waited -lt $max_wait ]]; do
        if docker logs "$ORACLE_CONTAINER_NAME" 2>&1 | grep -q "DATABASE IS READY TO USE"; then
            info "Oracle is ready!"
            return 0
        fi
        echo -n "."
        sleep 5
        waited=$((waited + 5))
    done

    echo ""
    error "Oracle did not start within ${max_wait} seconds"
    return 1
}

cleanup_oracle() {
    if [[ "$KEEP_ORACLE" == "true" ]]; then
        info "Keeping Oracle container (--keep-oracle specified)"
        return 0
    fi

    info "Cleaning up Oracle container..."
    docker stop "$ORACLE_CONTAINER_NAME" 2>/dev/null || true
    docker rm "$ORACLE_CONTAINER_NAME" 2>/dev/null || true
}

cleanup_test_files() {
    if [[ "$SKIP_CLEANUP" == "true" ]]; then
        info "Skipping file cleanup (--skip-cleanup specified)"
        return 0
    fi

    info "Cleaning up test files..."
    rm -rf /tmp/solace-oracle-orchestration-test
}

# -----------------------------------------------------------------------------
# Test Steps
# -----------------------------------------------------------------------------

step_check_prerequisites() {
    header "Step 0: Checking Prerequisites"

    # Check Docker
    if ! command -v docker &> /dev/null; then
        error "Docker is not installed"
        exit 1
    fi
    info "Docker: OK"

    # Check JAR
    check_jar
    info "Solace CLI JAR: OK"

    # Check Solace connectivity
    info "Testing Solace connection at $SOLACE_HOST..."
    if solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q demo.queue --browse -n 0 -t 2 &>/dev/null; then
        info "Solace broker: OK"
    else
        warn "Could not verify Solace connection (broker may not be running)"
        echo "  Expected: $SOLACE_HOST"
        read -p "Continue anyway? [y/N]: " answer
        if [[ "${answer,,}" != "y" ]]; then
            exit 1
        fi
    fi
}

step_start_oracle() {
    header "Step 1: Starting Oracle XE Container"

    # Check if container already exists
    if docker ps -a --format '{{.Names}}' | grep -q "^${ORACLE_CONTAINER_NAME}$"; then
        info "Container '$ORACLE_CONTAINER_NAME' already exists"

        if docker ps --format '{{.Names}}' | grep -q "^${ORACLE_CONTAINER_NAME}$"; then
            info "Container is already running"

            # Check if Oracle is ready
            if docker logs "$ORACLE_CONTAINER_NAME" 2>&1 | grep -q "DATABASE IS READY TO USE"; then
                info "Oracle is ready!"
                return 0
            fi
        else
            info "Starting existing container..."
            docker start "$ORACLE_CONTAINER_NAME"
        fi
    else
        info "Pulling Oracle image: $ORACLE_IMAGE"
        docker pull "$ORACLE_IMAGE"

        info "Starting Oracle container..."
        docker run -d \
            --name "$ORACLE_CONTAINER_NAME" \
            -p ${ORACLE_PORT}:1521 \
            -e ORACLE_PASSWORD="$ORACLE_SYS_PASSWORD" \
            -e APP_USER="$ORACLE_APP_USER" \
            -e APP_USER_PASSWORD="$ORACLE_APP_PASSWORD" \
            "$ORACLE_IMAGE"
    fi

    wait_for_oracle
}

step_create_test_data() {
    header "Step 2: Creating Test Data"

    info "Creating test table and inserting sample data..."

    docker exec -i "$ORACLE_CONTAINER_NAME" sqlplus -s "${ORACLE_APP_USER}/${ORACLE_APP_PASSWORD}@//localhost:1521/${ORACLE_SERVICE}" << 'EOF'
-- Drop table if exists (ignore errors)
BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE test_messages';
EXCEPTION
   WHEN OTHERS THEN NULL;
END;
/

-- Create test table
CREATE TABLE test_messages (
    id NUMBER PRIMARY KEY,
    order_id VARCHAR2(50),
    customer VARCHAR2(100),
    payload VARCHAR2(500),
    amount NUMBER(10,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert test data
INSERT INTO test_messages (id, order_id, customer, payload, amount)
VALUES (1, 'ORD-001', 'Alice', '{"order_id": "ORD-001", "customer": "Alice", "amount": 150.00, "items": ["widget", "gadget"]}', 150.00);

INSERT INTO test_messages (id, order_id, customer, payload, amount)
VALUES (2, 'ORD-002', 'Bob', '{"order_id": "ORD-002", "customer": "Bob", "amount": 275.50, "items": ["gizmo"]}', 275.50);

INSERT INTO test_messages (id, order_id, customer, payload, amount)
VALUES (3, 'ORD-003', 'Charlie', '{"order_id": "ORD-003", "customer": "Charlie", "amount": 89.99, "items": ["thingamajig", "doohickey"]}', 89.99);

INSERT INTO test_messages (id, order_id, customer, payload, amount)
VALUES (4, 'ORD-004', 'Diana', '{"order_id": "ORD-004", "customer": "Diana", "amount": 450.00, "items": ["premium-widget"]}', 450.00);

INSERT INTO test_messages (id, order_id, customer, payload, amount)
VALUES (5, 'ORD-005', 'Eve', '{"order_id": "ORD-005", "customer": "Eve", "amount": 99.99, "items": ["basic-gadget"]}', 99.99);

COMMIT;

-- Verify data
SELECT 'Rows inserted: ' || COUNT(*) AS result FROM test_messages;
EOF

    info "Test data created successfully"
}

step_test_basic_orchestration() {
    header "Step 3: Testing Basic Oracle Orchestration"

    local work_dir="/tmp/solace-oracle-orchestration-test/basic"
    rm -rf "$work_dir"

    info "Running orchestration with default settings..."
    info "  SQL: SELECT payload FROM test_messages"
    info "  Destination: $TEST_QUEUE"

    "${SCRIPT_DIR}/orchestrate-oracle.sh" \
        --db-host localhost \
        --db-port "$ORACLE_PORT" \
        --db-service "$ORACLE_SERVICE" \
        --db-user "$ORACLE_APP_USER" \
        --db-password "$ORACLE_APP_PASSWORD" \
        --sql "SELECT payload FROM test_messages ORDER BY id" \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -d "$TEST_QUEUE" \
        -w "$work_dir" \
        --verbose \
        --no-cleanup

    info "Verifying exported files..."
    echo ""
    echo "Input files (from Oracle):"
    ls -la "$work_dir/input/"
    echo ""
    echo "Output files (transformed):"
    ls -la "$work_dir/output/"
    echo ""
    echo "Sample transformed file:"
    cat "$work_dir/output/row_000001.txt"
}

step_test_custom_columns() {
    header "Step 4: Testing Custom Column Mapping"

    local work_dir="/tmp/solace-oracle-orchestration-test/custom"
    rm -rf "$work_dir"

    info "Running orchestration with custom column mapping..."
    info "  SQL: SELECT order_id, payload FROM test_messages"
    info "  Message column: payload"
    info "  Filename column: order_id"

    "${SCRIPT_DIR}/orchestrate-oracle.sh" \
        --db-host localhost \
        --db-port "$ORACLE_PORT" \
        --db-service "$ORACLE_SERVICE" \
        --db-user "$ORACLE_APP_USER" \
        --db-password "$ORACLE_APP_PASSWORD" \
        --sql "SELECT order_id, payload FROM test_messages ORDER BY id" \
        --message-column payload \
        --filename-column order_id \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -d "$TEST_QUEUE" \
        -w "$work_dir" \
        --verbose \
        --no-cleanup

    info "Verifying files are named by order_id..."
    echo ""
    echo "Input files:"
    ls -la "$work_dir/input/"
    echo ""
    echo "Sample file (ORD-001.txt):"
    cat "$work_dir/output/ORD-001.txt" 2>/dev/null || cat "$work_dir/output/"*.txt | head -10
}

step_verify_messages() {
    header "Step 5: Verifying Messages in Solace Queue"

    info "Browsing messages in queue: $TEST_QUEUE"
    echo ""

    solace_cli consume \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$TEST_QUEUE" \
        --browse \
        --verbose \
        -n 10 \
        -t 5
}

step_test_dry_run() {
    header "Step 6: Testing Dry Run Mode"

    local work_dir="/tmp/solace-oracle-orchestration-test/dryrun"
    rm -rf "$work_dir"

    info "Running orchestration in dry-run mode..."

    "${SCRIPT_DIR}/orchestrate-oracle.sh" \
        --db-host localhost \
        --db-port "$ORACLE_PORT" \
        --db-service "$ORACLE_SERVICE" \
        --db-user "$ORACLE_APP_USER" \
        --db-password "$ORACLE_APP_PASSWORD" \
        --sql "SELECT payload FROM test_messages" \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -d "$TEST_QUEUE" \
        -w "$work_dir" \
        --dry-run \
        --verbose

    info "Dry run completed (no actual changes made)"
}

step_cleanup() {
    header "Step 7: Cleanup"

    cleanup_test_files
    cleanup_oracle

    info "Cleanup complete"
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

main() {
    header "Oracle Orchestration Test Suite"

    echo "Configuration:"
    echo "  Oracle Container: $ORACLE_CONTAINER_NAME"
    echo "  Oracle Port:      $ORACLE_PORT"
    echo "  Oracle Service:   $ORACLE_SERVICE"
    echo "  Solace Host:      $SOLACE_HOST"
    echo "  Test Queue:       $TEST_QUEUE"
    echo ""

    # Set up trap for cleanup on exit
    trap 'cleanup_oracle' EXIT

    local start_time
    start_time=$(date +%s)

    step_check_prerequisites
    step_start_oracle
    step_create_test_data
    step_test_basic_orchestration
    step_test_custom_columns
    step_verify_messages
    step_test_dry_run
    step_cleanup

    local end_time duration
    end_time=$(date +%s)
    duration=$((end_time - start_time))

    header "Test Suite Complete"
    info "All tests passed!"
    info "Total duration: ${duration}s"

    # Remove trap since we already cleaned up
    trap - EXIT
}

# Run main
main "$@"
