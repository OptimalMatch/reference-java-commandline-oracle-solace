#!/bin/bash
# =============================================================================
# Solace CLI Orchestration Script
# =============================================================================
# Orchestrates message flow: Consume -> File -> Transform -> File -> Publish
#
# This script performs the following workflow:
#   1. Consume messages from a source Solace queue
#   2. Save consumed messages as files in an input folder
#   3. Transform each file's contents (via placeholder transform function)
#   4. Write transformed content to an output folder
#   5. Publish transformed files to a destination Solace queue
#
# Usage:
#   ./orchestrate.sh [options]
#   ./orchestrate.sh --source-queue orders.in --dest-queue orders.out
#   ./orchestrate.sh --config orchestration.conf
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# -----------------------------------------------------------------------------
# Default Configuration
# -----------------------------------------------------------------------------

# Solace connection (inherited from common.sh if not overridden)
ORCH_HOST="${SOLACE_HOST}"
ORCH_VPN="${SOLACE_VPN}"
ORCH_USER="${SOLACE_USER}"
ORCH_PASS="${SOLACE_PASS}"

# SSL Configuration (optional)
ORCH_USE_SSL=false
ORCH_KEY_STORE=""
ORCH_KEY_STORE_PASS=""
ORCH_TRUST_STORE=""
ORCH_TRUST_STORE_PASS=""
ORCH_CLIENT_CERT=""
ORCH_CLIENT_KEY=""
ORCH_CA_CERT=""
ORCH_SKIP_CERT_VALIDATION=false

# Queue Configuration
ORCH_SOURCE_QUEUE="${SOLACE_QUEUE:-input.queue}"
ORCH_DEST_QUEUE="output.queue"

# Processing Configuration
ORCH_MESSAGE_COUNT=0        # 0 = consume all available
ORCH_CONSUME_TIMEOUT=10     # seconds
ORCH_BROWSE_ONLY=false      # if true, don't remove messages from source queue
ORCH_USE_CORRELATION=true   # use correlation ID as filename

# Directory Configuration
ORCH_WORK_DIR="${DATA_DIR:-/tmp/solace-orchestration}"
ORCH_INPUT_DIR="${ORCH_WORK_DIR}/input"
ORCH_OUTPUT_DIR="${ORCH_WORK_DIR}/output"
ORCH_FAILED_DIR="${ORCH_WORK_DIR}/failed"
ORCH_PROCESSED_DIR="${ORCH_WORK_DIR}/processed"

# File Handling
ORCH_FILE_PATTERN="*"
ORCH_FILE_EXTENSION=".txt"
ORCH_PRESERVE_FILENAME=true  # use original filename for transformed file

# Logging
ORCH_AUDIT_LOG=""
ORCH_VERBOSE=false
ORCH_DRY_RUN=false

# Cleanup
ORCH_CLEANUP_INPUT=true     # remove input files after processing
ORCH_CLEANUP_OUTPUT=true    # remove output files after publishing

# -----------------------------------------------------------------------------
# Transform Function (Placeholder)
# -----------------------------------------------------------------------------

# This is a placeholder transformation function.
# Override this function or replace its contents with your actual transformation logic.
#
# Arguments:
#   $1 - Input file path (file to transform)
#   $2 - Output file path (where to write transformed content)
#
# Returns:
#   0 on success, non-zero on failure
#
# Example transformations you might implement:
#   - JSON to XML conversion
#   - Data enrichment
#   - Field mapping
#   - Encryption/decryption
#   - Compression/decompression
#   - Content filtering
#   - Format normalization
#
transform_message() {
    local input_file="$1"
    local output_file="$2"

    # =========================================================================
    # PLACEHOLDER TRANSFORMATION
    # Replace this section with your actual transformation logic
    # =========================================================================

    if [[ "$ORCH_VERBOSE" == "true" ]]; then
        info "Transforming: $(basename "$input_file") -> $(basename "$output_file")"
    fi

    # Default behavior: copy content with a transformation marker
    # This is just a placeholder - replace with actual transformation
    {
        echo "# Transformed at: $(date -Iseconds)"
        echo "# Original file: $(basename "$input_file")"
        echo "# ----------------------------------------"
        cat "$input_file"
    } > "$output_file"

    # =========================================================================
    # END PLACEHOLDER
    # =========================================================================

    return 0
}

# -----------------------------------------------------------------------------
# Helper Functions
# -----------------------------------------------------------------------------

usage() {
    cat << 'EOF'
Solace CLI Orchestration Script

DESCRIPTION:
    Orchestrates a message flow: Consume -> Transform -> Publish

    1. Consumes messages from a source Solace queue
    2. Saves messages to files in an input directory
    3. Transforms each file using a customizable transform function
    4. Writes transformed content to an output directory
    5. Publishes transformed files to a destination Solace queue

USAGE:
    orchestrate.sh [options]

CONNECTION OPTIONS:
    -H, --host HOST              Solace broker host (default: tcp://localhost:55555)
    -v, --vpn VPN                Message VPN name (default: default)
    -u, --username USER          Authentication username (default: admin)
    -p, --password PASS          Authentication password (default: admin)

SSL/TLS OPTIONS:
    --ssl                        Enable SSL/TLS
    --key-store PATH             Client keystore (JKS/PKCS12)
    --key-store-password PASS    Keystore password
    --trust-store PATH           Trust store path
    --trust-store-password PASS  Trust store password
    --client-cert PATH           Client certificate (PEM)
    --client-key PATH            Client private key (PEM)
    --ca-cert PATH               CA certificate (PEM)
    --skip-cert-validation       Skip server certificate validation

QUEUE OPTIONS:
    -s, --source-queue QUEUE     Source queue to consume from (required)
    -d, --dest-queue QUEUE       Destination queue to publish to (required)
    -n, --count COUNT            Number of messages to process (0=all, default: 0)
    -t, --timeout SECONDS        Consume timeout in seconds (default: 10)
    --browse                     Browse only, don't remove from source queue

DIRECTORY OPTIONS:
    -w, --work-dir DIR           Base working directory (default: /tmp/solace-orchestration)
    --input-dir DIR              Input directory for consumed messages
    --output-dir DIR             Output directory for transformed messages
    --failed-dir DIR             Directory for failed transformations

FILE OPTIONS:
    --pattern PATTERN            File pattern for folder-publish (default: *)
    --extension EXT              File extension (default: .txt)
    --use-correlation            Use correlation ID as filename (default: true)
    --no-correlation             Use generated filenames instead of correlation ID

PROCESSING OPTIONS:
    --dry-run                    Show what would be done without executing
    --verbose, -V                Enable verbose output
    --audit-log FILE             Write audit log to file
    --no-cleanup                 Don't clean up files after processing
    --keep-input                 Keep input files after transformation
    --keep-output                Keep output files after publishing

CONFIGURATION:
    --config FILE                Load configuration from file

EXAMPLES:
    # Basic orchestration
    orchestrate.sh -s orders.in -d orders.out

    # With SSL and custom directories
    orchestrate.sh --ssl -H tcps://broker:55443 -s input.q -d output.q -w /data/orch

    # Dry run to preview
    orchestrate.sh -s source.q -d dest.q --dry-run --verbose

    # Process limited number of messages
    orchestrate.sh -s queue.in -d queue.out -n 100 -t 30

    # Use a config file
    orchestrate.sh --config /etc/orchestration.conf

EOF
    exit 0
}

# Load configuration from file
load_config() {
    local config_file="$1"
    if [[ -f "$config_file" ]]; then
        info "Loading configuration from: $config_file"
        # shellcheck source=/dev/null
        source "$config_file"
    else
        error "Configuration file not found: $config_file"
        exit 1
    fi
}

# Setup working directories
setup_orchestration_dirs() {
    mkdir -p "$ORCH_INPUT_DIR" "$ORCH_OUTPUT_DIR" "$ORCH_FAILED_DIR" "$ORCH_PROCESSED_DIR"

    if [[ "$ORCH_VERBOSE" == "true" ]]; then
        info "Working directories:"
        info "  Input:     $ORCH_INPUT_DIR"
        info "  Output:    $ORCH_OUTPUT_DIR"
        info "  Failed:    $ORCH_FAILED_DIR"
        info "  Processed: $ORCH_PROCESSED_DIR"
    fi
}

# Clean working directories
cleanup_orchestration_dirs() {
    if [[ "$ORCH_CLEANUP_INPUT" == "true" ]]; then
        rm -rf "${ORCH_INPUT_DIR:?}"/*
    fi
    if [[ "$ORCH_CLEANUP_OUTPUT" == "true" ]]; then
        rm -rf "${ORCH_OUTPUT_DIR:?}"/*
    fi
}

# Build connection arguments for solace_cli
build_orch_connection_args() {
    local args="-H $ORCH_HOST -v $ORCH_VPN"

    if [[ -n "$ORCH_USER" ]]; then
        args="$args -u $ORCH_USER"
    fi
    if [[ -n "$ORCH_PASS" ]]; then
        args="$args -p '$ORCH_PASS'"
    fi

    # SSL arguments
    if [[ "$ORCH_USE_SSL" == "true" ]]; then
        args="$args --ssl"

        [[ -n "$ORCH_KEY_STORE" ]] && args="$args --key-store '$ORCH_KEY_STORE'"
        [[ -n "$ORCH_KEY_STORE_PASS" ]] && args="$args --key-store-password '$ORCH_KEY_STORE_PASS'"
        [[ -n "$ORCH_TRUST_STORE" ]] && args="$args --trust-store '$ORCH_TRUST_STORE'"
        [[ -n "$ORCH_TRUST_STORE_PASS" ]] && args="$args --trust-store-password '$ORCH_TRUST_STORE_PASS'"
        [[ -n "$ORCH_CLIENT_CERT" ]] && args="$args --client-cert '$ORCH_CLIENT_CERT'"
        [[ -n "$ORCH_CLIENT_KEY" ]] && args="$args --client-key '$ORCH_CLIENT_KEY'"
        [[ -n "$ORCH_CA_CERT" ]] && args="$args --ca-cert '$ORCH_CA_CERT'"
        [[ "$ORCH_SKIP_CERT_VALIDATION" == "true" ]] && args="$args --skip-cert-validation"
    fi

    echo "$args"
}

# -----------------------------------------------------------------------------
# Orchestration Steps
# -----------------------------------------------------------------------------

# Step 1: Consume messages from source queue and save to files
step_consume() {
    header "Step 1: Consuming Messages"

    info "Source queue: $ORCH_SOURCE_QUEUE"
    info "Output directory: $ORCH_INPUT_DIR"
    info "Message count: ${ORCH_MESSAGE_COUNT:-unlimited}"
    info "Timeout: ${ORCH_CONSUME_TIMEOUT}s"

    if [[ "$ORCH_DRY_RUN" == "true" ]]; then
        info "[DRY RUN] Would consume from $ORCH_SOURCE_QUEUE"
        return 0
    fi

    local args
    args=$(build_orch_connection_args)
    args="$args -q $ORCH_SOURCE_QUEUE"
    args="$args -o '$ORCH_INPUT_DIR'"
    args="$args -n $ORCH_MESSAGE_COUNT"
    args="$args -t $ORCH_CONSUME_TIMEOUT"
    args="$args --extension '$ORCH_FILE_EXTENSION'"

    [[ "$ORCH_BROWSE_ONLY" == "true" ]] && args="$args --browse"
    [[ "$ORCH_USE_CORRELATION" == "true" ]] && args="$args --use-correlation-id"
    [[ "$ORCH_VERBOSE" == "true" ]] && args="$args --verbose"
    [[ -n "$ORCH_AUDIT_LOG" ]] && args="$args --audit-log '$ORCH_AUDIT_LOG'"

    if [[ "$ORCH_VERBOSE" == "true" ]]; then
        show_cmd "solace_cli consume $args"
    fi

    # Run consume - it may return non-zero on timeout or when reaching count limit
    set +e
    eval "solace_cli consume $args" 2>&1
    local consume_exit=$?
    set -e

    if [[ $consume_exit -ne 0 ]]; then
        warn "Consume exited with code $consume_exit (this is normal for timeout or count limit)"
    fi

    # Count consumed files
    local consumed_count
    consumed_count=$(find "$ORCH_INPUT_DIR" -type f -name "*${ORCH_FILE_EXTENSION}" 2>/dev/null | wc -l)
    info "Consumed $consumed_count message(s)"

    return 0
}

# Step 2: Transform files from input to output directory
step_transform() {
    header "Step 2: Transforming Messages"

    local input_files
    input_files=$(find "$ORCH_INPUT_DIR" -type f -name "*${ORCH_FILE_EXTENSION}" 2>/dev/null | sort)

    if [[ -z "$input_files" ]]; then
        warn "No files to transform in $ORCH_INPUT_DIR"
        return 0
    fi

    local total_count success_count fail_count
    total_count=$(echo "$input_files" | wc -l)
    success_count=0
    fail_count=0

    info "Processing $total_count file(s)"

    while IFS= read -r input_file; do
        local basename
        basename=$(basename "$input_file")

        local output_file
        if [[ "$ORCH_PRESERVE_FILENAME" == "true" ]]; then
            output_file="${ORCH_OUTPUT_DIR}/${basename}"
        else
            output_file="${ORCH_OUTPUT_DIR}/transformed_${basename}"
        fi

        if [[ "$ORCH_DRY_RUN" == "true" ]]; then
            info "[DRY RUN] Would transform: $basename"
            ((success_count++))
            continue
        fi

        if transform_message "$input_file" "$output_file"; then
            ((success_count++)) || true
        else
            error "Failed to transform: $basename"
            mv "$input_file" "$ORCH_FAILED_DIR/" 2>/dev/null || true
            ((fail_count++)) || true
        fi
    done <<< "$input_files"

    info "Transform complete: $success_count succeeded, $fail_count failed"

    return 0
}

# Step 3: Publish transformed files to destination queue
step_publish() {
    header "Step 3: Publishing Messages"

    local output_files
    output_files=$(find "$ORCH_OUTPUT_DIR" -type f -name "*${ORCH_FILE_EXTENSION}" 2>/dev/null | wc -l)

    if [[ "$output_files" -eq 0 ]]; then
        warn "No files to publish in $ORCH_OUTPUT_DIR"
        return 0
    fi

    info "Destination queue: $ORCH_DEST_QUEUE"
    info "Files to publish: $output_files"

    if [[ "$ORCH_DRY_RUN" == "true" ]]; then
        info "[DRY RUN] Would publish $output_files file(s) to $ORCH_DEST_QUEUE"
        return 0
    fi

    local args
    args=$(build_orch_connection_args)
    args="$args -q $ORCH_DEST_QUEUE"
    args="$args --pattern '*${ORCH_FILE_EXTENSION}'"
    args="$args --use-filename-as-correlation"

    [[ -n "$ORCH_AUDIT_LOG" ]] && args="$args --audit-log '$ORCH_AUDIT_LOG'"

    if [[ "$ORCH_VERBOSE" == "true" ]]; then
        show_cmd "solace_cli folder-publish $args '$ORCH_OUTPUT_DIR'"
    fi

    # Run folder-publish - capture exit code separately to handle non-zero returns
    set +e
    eval "solace_cli folder-publish $args '$ORCH_OUTPUT_DIR'" 2>&1
    local publish_exit=$?
    set -e

    if [[ $publish_exit -eq 0 ]]; then
        info "Published $output_files message(s) successfully"
    else
        error "Some messages may have failed to publish (exit code: $publish_exit)"
    fi

    return $publish_exit
}

# -----------------------------------------------------------------------------
# Main Orchestration Flow
# -----------------------------------------------------------------------------

run_orchestration() {
    header "Solace Message Orchestration"

    info "Configuration:"
    echo "  Source Queue:  $ORCH_SOURCE_QUEUE"
    echo "  Dest Queue:    $ORCH_DEST_QUEUE"
    echo "  Work Dir:      $ORCH_WORK_DIR"
    echo "  Broker:        $ORCH_HOST"
    echo "  VPN:           $ORCH_VPN"
    [[ "$ORCH_USE_SSL" == "true" ]] && echo "  SSL:           enabled"
    [[ "$ORCH_DRY_RUN" == "true" ]] && echo "  Mode:          DRY RUN"
    echo ""

    # Setup
    setup_orchestration_dirs

    local start_time
    start_time=$(date +%s)

    # Execute orchestration steps
    step_consume
    step_transform
    step_publish

    local end_time duration
    end_time=$(date +%s)
    duration=$((end_time - start_time))

    # Cleanup
    if [[ "$ORCH_DRY_RUN" != "true" ]]; then
        cleanup_orchestration_dirs
    fi

    header "Orchestration Complete"
    info "Total duration: ${duration}s"

    return 0
}

# -----------------------------------------------------------------------------
# Argument Parsing
# -----------------------------------------------------------------------------

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)
                usage
                ;;
            -H|--host)
                ORCH_HOST="$2"
                shift 2
                ;;
            -v|--vpn)
                ORCH_VPN="$2"
                shift 2
                ;;
            -u|--username)
                ORCH_USER="$2"
                shift 2
                ;;
            -p|--password)
                ORCH_PASS="$2"
                shift 2
                ;;
            --ssl)
                ORCH_USE_SSL=true
                shift
                ;;
            --key-store)
                ORCH_KEY_STORE="$2"
                shift 2
                ;;
            --key-store-password)
                ORCH_KEY_STORE_PASS="$2"
                shift 2
                ;;
            --trust-store)
                ORCH_TRUST_STORE="$2"
                shift 2
                ;;
            --trust-store-password)
                ORCH_TRUST_STORE_PASS="$2"
                shift 2
                ;;
            --client-cert)
                ORCH_CLIENT_CERT="$2"
                shift 2
                ;;
            --client-key)
                ORCH_CLIENT_KEY="$2"
                shift 2
                ;;
            --ca-cert)
                ORCH_CA_CERT="$2"
                shift 2
                ;;
            --skip-cert-validation)
                ORCH_SKIP_CERT_VALIDATION=true
                shift
                ;;
            -s|--source-queue)
                ORCH_SOURCE_QUEUE="$2"
                shift 2
                ;;
            -d|--dest-queue)
                ORCH_DEST_QUEUE="$2"
                shift 2
                ;;
            -n|--count)
                ORCH_MESSAGE_COUNT="$2"
                shift 2
                ;;
            -t|--timeout)
                ORCH_CONSUME_TIMEOUT="$2"
                shift 2
                ;;
            --browse)
                ORCH_BROWSE_ONLY=true
                shift
                ;;
            -w|--work-dir)
                ORCH_WORK_DIR="$2"
                ORCH_INPUT_DIR="${ORCH_WORK_DIR}/input"
                ORCH_OUTPUT_DIR="${ORCH_WORK_DIR}/output"
                ORCH_FAILED_DIR="${ORCH_WORK_DIR}/failed"
                ORCH_PROCESSED_DIR="${ORCH_WORK_DIR}/processed"
                shift 2
                ;;
            --input-dir)
                ORCH_INPUT_DIR="$2"
                shift 2
                ;;
            --output-dir)
                ORCH_OUTPUT_DIR="$2"
                shift 2
                ;;
            --failed-dir)
                ORCH_FAILED_DIR="$2"
                shift 2
                ;;
            --pattern)
                ORCH_FILE_PATTERN="$2"
                shift 2
                ;;
            --extension)
                ORCH_FILE_EXTENSION="$2"
                shift 2
                ;;
            --use-correlation)
                ORCH_USE_CORRELATION=true
                shift
                ;;
            --no-correlation)
                ORCH_USE_CORRELATION=false
                shift
                ;;
            --dry-run)
                ORCH_DRY_RUN=true
                shift
                ;;
            -V|--verbose)
                ORCH_VERBOSE=true
                shift
                ;;
            --audit-log)
                ORCH_AUDIT_LOG="$2"
                shift 2
                ;;
            --no-cleanup)
                ORCH_CLEANUP_INPUT=false
                ORCH_CLEANUP_OUTPUT=false
                shift
                ;;
            --keep-input)
                ORCH_CLEANUP_INPUT=false
                shift
                ;;
            --keep-output)
                ORCH_CLEANUP_OUTPUT=false
                shift
                ;;
            --config)
                load_config "$2"
                shift 2
                ;;
            *)
                error "Unknown option: $1"
                echo "Use --help for usage information"
                exit 1
                ;;
        esac
    done
}

# Validate required parameters
validate_params() {
    local errors=0

    if [[ -z "$ORCH_SOURCE_QUEUE" ]]; then
        error "Source queue is required (-s, --source-queue)"
        ((errors++))
    fi

    if [[ -z "$ORCH_DEST_QUEUE" ]]; then
        error "Destination queue is required (-d, --dest-queue)"
        ((errors++))
    fi

    if [[ -z "$ORCH_HOST" ]]; then
        error "Solace host is required (-H, --host)"
        ((errors++))
    fi

    if [[ $errors -gt 0 ]]; then
        echo ""
        echo "Use --help for usage information"
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# Entry Point
# -----------------------------------------------------------------------------

main() {
    check_jar

    parse_args "$@"
    validate_params
    run_orchestration
}

# Run if executed directly (not sourced)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
