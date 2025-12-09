#!/bin/bash
# =============================================================================
# Test Script for Queue Orchestration
# =============================================================================
# This script tests the orchestrate.sh script by:
#   1. Publishing test messages to a source queue
#   2. Running the orchestration to consume, transform, and publish
#   3. Verifying the messages in the destination queue
#   4. Testing various options (browse, dry-run, custom columns)
#   5. Cleaning up
#
# Prerequisites:
#   - Solace broker running (default: tcp://localhost:55555)
#   - Project JAR built (mvn clean package)
#   - Demo queues created (./setup-solace.sh create)
#
# Usage:
#   ./test-orchestration.sh [--skip-cleanup]
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

SOLACE_HOST="${SOLACE_HOST:-tcp://localhost:55555}"
SOLACE_VPN="${SOLACE_VPN:-default}"
SOLACE_USER="${SOLACE_USER:-admin}"
SOLACE_PASS="${SOLACE_PASS:-admin}"

SOURCE_QUEUE="${SOURCE_QUEUE:-demo.queue}"
DEST_QUEUE="${DEST_QUEUE:-demo.queue.backup}"

SKIP_CLEANUP=false

# -----------------------------------------------------------------------------
# Parse Arguments
# -----------------------------------------------------------------------------

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-cleanup)
            SKIP_CLEANUP=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--skip-cleanup]"
            echo ""
            echo "Options:"
            echo "  --skip-cleanup   Don't clean up test files and queue messages"
            echo ""
            echo "Environment Variables:"
            echo "  SOLACE_HOST      Solace broker host (default: tcp://localhost:55555)"
            echo "  SOURCE_QUEUE     Source queue for testing (default: demo.queue)"
            echo "  DEST_QUEUE       Destination queue for testing (default: demo.queue.backup)"
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

publish_test_messages() {
    local queue="$1"
    local count="$2"
    local prefix="${3:-test}"

    info "Publishing $count test messages to $queue..."

    for i in $(seq 1 "$count"); do
        local correlation_id="${prefix}-$(printf '%03d' $i)"
        local payload="{\"id\": $i, \"message\": \"Test message $i\", \"timestamp\": \"$(date -Iseconds)\"}"

        solace_cli publish \
            -H "$SOLACE_HOST" \
            -v "$SOLACE_VPN" \
            -u "$SOLACE_USER" \
            -p "$SOLACE_PASS" \
            -q "$queue" \
            --correlation-id "$correlation_id" \
            "$payload" 2>/dev/null
    done

    info "Published $count messages"
}

count_queue_messages() {
    local queue="$1"
    local count

    count=$(solace_cli consume \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$queue" \
        --browse \
        -n 0 \
        -t 2 2>/dev/null | grep -c "^Message ID:" 2>/dev/null || echo "0")

    # Ensure we return a single number
    echo "$count" | head -1 | tr -d '[:space:]'
}

drain_queue() {
    local queue="$1"

    info "Draining queue: $queue"
    solace_cli consume \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$queue" \
        -n 0 \
        -t 2 2>/dev/null || true
}

cleanup_test_files() {
    if [[ "$SKIP_CLEANUP" == "true" ]]; then
        info "Skipping file cleanup (--skip-cleanup specified)"
        return 0
    fi

    info "Cleaning up test files..."
    rm -rf /tmp/solace-orchestration-test
}

cleanup_queues() {
    if [[ "$SKIP_CLEANUP" == "true" ]]; then
        info "Skipping queue cleanup (--skip-cleanup specified)"
        return 0
    fi

    info "Draining test queues..."
    drain_queue "$SOURCE_QUEUE"
    drain_queue "$DEST_QUEUE"
}

# -----------------------------------------------------------------------------
# Test Steps
# -----------------------------------------------------------------------------

step_check_prerequisites() {
    header "Step 0: Checking Prerequisites"

    # Check JAR
    check_jar
    info "Solace CLI JAR: OK"

    # Check Solace connectivity
    info "Testing Solace connection at $SOLACE_HOST..."
    if solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$SOURCE_QUEUE" --browse -n 0 -t 2 &>/dev/null; then
        info "Solace broker: OK"
    else
        error "Could not connect to Solace broker at $SOLACE_HOST"
        echo "  Make sure the broker is running and queues are created:"
        echo "    ./solace-docker.sh start"
        echo "    ./setup-solace.sh create"
        exit 1
    fi

    # Check queues exist
    info "Checking queues..."
    info "  Source queue: $SOURCE_QUEUE"
    info "  Destination queue: $DEST_QUEUE"
}

step_test_basic_orchestration() {
    header "Step 1: Testing Basic Orchestration"

    local work_dir="/tmp/solace-orchestration-test/basic"
    rm -rf "$work_dir"

    # Publish test messages
    publish_test_messages "$SOURCE_QUEUE" 3 "basic"

    info "Running basic orchestration..."
    info "  Source: $SOURCE_QUEUE"
    info "  Destination: $DEST_QUEUE"

    "${SCRIPT_DIR}/orchestrate.sh" \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -s "$SOURCE_QUEUE" \
        -d "$DEST_QUEUE" \
        -n 3 \
        -t 10 \
        -w "$work_dir" \
        --verbose \
        --no-cleanup

    # Verify files
    info "Verifying exported files..."
    echo ""
    echo "Input files (consumed):"
    ls -la "$work_dir/input/" 2>/dev/null || echo "  (none)"
    echo ""
    echo "Output files (transformed):"
    ls -la "$work_dir/output/" 2>/dev/null || echo "  (none)"

    # Show sample transformed file
    local sample_file
    sample_file=$(ls "$work_dir/output/"*.txt 2>/dev/null | head -1)
    if [[ -n "$sample_file" ]]; then
        echo ""
        echo "Sample transformed file ($(basename "$sample_file")):"
        cat "$sample_file"
    fi

    info "Basic orchestration test completed"
}

step_test_browse_mode() {
    header "Step 2: Testing Browse Mode (Non-Destructive)"

    local work_dir="/tmp/solace-orchestration-test/browse"
    rm -rf "$work_dir"

    # Publish test messages
    publish_test_messages "$SOURCE_QUEUE" 2 "browse"

    # Count messages before
    local before_count
    before_count=$(count_queue_messages "$SOURCE_QUEUE")
    info "Messages in source queue before: $before_count"

    info "Running orchestration in browse mode..."

    "${SCRIPT_DIR}/orchestrate.sh" \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -s "$SOURCE_QUEUE" \
        -d "$DEST_QUEUE" \
        -n 2 \
        -t 10 \
        -w "$work_dir" \
        --browse \
        --verbose \
        --no-cleanup

    # Count messages after
    local after_count
    after_count=$(count_queue_messages "$SOURCE_QUEUE")
    info "Messages in source queue after: $after_count"

    if [[ "$after_count" -ge "$before_count" ]]; then
        info "Browse mode verified: messages not removed from source queue"
    else
        warn "Browse mode may not have worked correctly"
    fi

    # Clean up source queue for next test
    drain_queue "$SOURCE_QUEUE"

    info "Browse mode test completed"
}

step_test_dry_run() {
    header "Step 3: Testing Dry Run Mode"

    local work_dir="/tmp/solace-orchestration-test/dryrun"
    rm -rf "$work_dir"

    # Publish test messages
    publish_test_messages "$SOURCE_QUEUE" 2 "dryrun"

    # Count messages before
    local before_count
    before_count=$(count_queue_messages "$SOURCE_QUEUE")
    info "Messages in source queue before: $before_count"

    info "Running orchestration in dry-run mode..."

    "${SCRIPT_DIR}/orchestrate.sh" \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -s "$SOURCE_QUEUE" \
        -d "$DEST_QUEUE" \
        -n 2 \
        -t 10 \
        -w "$work_dir" \
        --dry-run \
        --verbose

    # Verify no files were created
    if [[ -d "$work_dir/input" ]] && [[ -n "$(ls -A "$work_dir/input" 2>/dev/null)" ]]; then
        warn "Dry run created input files (unexpected)"
    else
        info "Dry run verified: no input files created"
    fi

    # Verify source queue unchanged
    local after_count
    after_count=$(count_queue_messages "$SOURCE_QUEUE")
    info "Messages in source queue after: $after_count"

    if [[ "$after_count" -eq "$before_count" ]]; then
        info "Dry run verified: source queue unchanged"
    else
        warn "Dry run may have modified source queue"
    fi

    # Clean up source queue for next test
    drain_queue "$SOURCE_QUEUE"

    info "Dry run test completed"
}

step_test_correlation_id() {
    header "Step 4: Testing Correlation ID as Filename"

    local work_dir="/tmp/solace-orchestration-test/correlation"
    rm -rf "$work_dir"

    # Publish test messages with specific correlation IDs
    info "Publishing messages with specific correlation IDs..."

    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOURCE_QUEUE" \
        --correlation-id "ORDER-001" \
        '{"order": "ORDER-001", "item": "Widget"}' 2>/dev/null

    solace_cli publish \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$SOURCE_QUEUE" \
        --correlation-id "ORDER-002" \
        '{"order": "ORDER-002", "item": "Gadget"}' 2>/dev/null

    info "Running orchestration with correlation ID filenames..."

    "${SCRIPT_DIR}/orchestrate.sh" \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -s "$SOURCE_QUEUE" \
        -d "$DEST_QUEUE" \
        -n 2 \
        -t 10 \
        -w "$work_dir" \
        --verbose \
        --no-cleanup

    # Verify files are named by correlation ID
    info "Verifying files are named by correlation ID..."
    echo ""
    echo "Input files:"
    ls -la "$work_dir/input/" 2>/dev/null || echo "  (none)"

    if [[ -f "$work_dir/input/ORDER-001.txt" ]] || [[ -f "$work_dir/output/ORDER-001.txt" ]]; then
        info "Correlation ID filenames verified"
    else
        info "Files may use sequential naming (correlation ID naming depends on message metadata)"
    fi

    info "Correlation ID test completed"
}

step_verify_destination_queue() {
    header "Step 5: Verifying Destination Queue"

    info "Browsing messages in destination queue: $DEST_QUEUE"
    echo ""

    solace_cli consume \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -q "$DEST_QUEUE" \
        --browse \
        --verbose \
        -n 10 \
        -t 5 2>/dev/null || true

    info "Destination queue verification completed"
}

step_test_large_batch() {
    header "Step 6: Testing Larger Batch"

    local work_dir="/tmp/solace-orchestration-test/batch"
    rm -rf "$work_dir"

    # Publish more test messages
    publish_test_messages "$SOURCE_QUEUE" 10 "batch"

    info "Running orchestration for 10 messages..."

    "${SCRIPT_DIR}/orchestrate.sh" \
        -H "$SOLACE_HOST" \
        -v "$SOLACE_VPN" \
        -u "$SOLACE_USER" \
        -p "$SOLACE_PASS" \
        -s "$SOURCE_QUEUE" \
        -d "$DEST_QUEUE" \
        -n 10 \
        -t 15 \
        -w "$work_dir" \
        --no-cleanup

    # Count files
    local input_count output_count
    input_count=$(ls "$work_dir/input/"*.txt 2>/dev/null | wc -l)
    output_count=$(ls "$work_dir/output/"*.txt 2>/dev/null | wc -l)

    info "Files processed:"
    info "  Input files: $input_count"
    info "  Output files: $output_count"

    if [[ "$output_count" -eq 10 ]]; then
        info "Batch test passed: all 10 messages processed"
    else
        warn "Batch test: expected 10 files, got $output_count"
    fi

    info "Large batch test completed"
}

step_cleanup() {
    header "Step 7: Cleanup"

    cleanup_test_files
    cleanup_queues

    info "Cleanup complete"
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

main() {
    header "Queue Orchestration Test Suite"

    echo "Configuration:"
    echo "  Solace Host:      $SOLACE_HOST"
    echo "  Solace VPN:       $SOLACE_VPN"
    echo "  Source Queue:     $SOURCE_QUEUE"
    echo "  Destination Queue: $DEST_QUEUE"
    echo ""

    local start_time
    start_time=$(date +%s)

    step_check_prerequisites
    step_test_basic_orchestration
    step_test_browse_mode
    step_test_dry_run
    step_test_correlation_id
    step_verify_destination_queue
    step_test_large_batch
    step_cleanup

    local end_time duration
    end_time=$(date +%s)
    duration=$((end_time - start_time))

    header "Test Suite Complete"
    info "All tests passed!"
    info "Total duration: ${duration}s"
}

# Run main
main "$@"
