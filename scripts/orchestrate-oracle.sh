#!/bin/bash
# =============================================================================
# Solace CLI Oracle Orchestration Script
# =============================================================================
# Orchestrates message flow: Oracle Query -> File -> Transform -> File -> Publish
#
# This script performs the following workflow:
#   1. Execute Oracle query and export each row as a file
#   2. Transform each file's contents (via placeholder transform function)
#   3. Write transformed content to an output folder
#   4. Publish transformed files to a destination Solace queue
#
# Usage:
#   ./orchestrate-oracle.sh [options]
#   ./orchestrate-oracle.sh --sql "SELECT * FROM orders" --dest-queue orders.out
#   ./orchestrate-oracle.sh --config oracle-orchestration.conf
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# -----------------------------------------------------------------------------
# Default Configuration
# -----------------------------------------------------------------------------

# Oracle connection (inherited from common.sh if not overridden)
ORCH_DB_HOST="${ORACLE_HOST:-localhost}"
ORCH_DB_PORT="${ORACLE_PORT:-1521}"
ORCH_DB_SERVICE="${ORACLE_SERVICE:-ORCL}"
ORCH_DB_USER="${ORACLE_USER:-scott}"
ORCH_DB_PASS="${ORACLE_PASS:-tiger}"

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

# Oracle Query Configuration
ORCH_SQL_QUERY=""
ORCH_SQL_FILE=""
ORCH_MESSAGE_COLUMN=""
ORCH_FILENAME_COLUMN=""
ORCH_FILENAME_PREFIX="row_"

# Queue Configuration
ORCH_DEST_QUEUE="${SOLACE_QUEUE:-output.queue}"

# Directory Configuration
ORCH_WORK_DIR="${DATA_DIR:-/tmp/solace-oracle-orchestration}"
ORCH_INPUT_DIR="${ORCH_WORK_DIR}/input"
ORCH_OUTPUT_DIR="${ORCH_WORK_DIR}/output"
ORCH_FAILED_DIR="${ORCH_WORK_DIR}/failed"

# File Handling
ORCH_FILE_EXTENSION=".txt"
ORCH_PRESERVE_FILENAME=true
ORCH_OVERWRITE=false

# Logging
ORCH_AUDIT_LOG=""
ORCH_VERBOSE=false
ORCH_DRY_RUN=false

# Cleanup
ORCH_CLEANUP_INPUT=true
ORCH_CLEANUP_OUTPUT=true

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
        echo "# Source: Oracle query export"
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
Solace CLI Oracle Orchestration Script

DESCRIPTION:
    Orchestrates a message flow: Oracle Query -> Transform -> Publish

    1. Executes an Oracle query and exports each row as a file
    2. Transforms each file using a customizable transform function
    3. Writes transformed content to an output directory
    4. Publishes transformed files to a destination Solace queue

USAGE:
    orchestrate-oracle.sh [options]

ORACLE CONNECTION OPTIONS:
    --db-host HOST           Oracle database host (default: localhost)
    --db-port PORT           Oracle database port (default: 1521)
    --db-service SERVICE     Oracle service name or SID (default: ORCL)
    --db-user USER           Oracle database username (default: scott)
    --db-password PASS       Oracle database password (default: tiger)

ORACLE QUERY OPTIONS:
    -s, --sql QUERY          SQL SELECT statement to execute
    -f, --sql-file FILE      File containing SQL SELECT statement
    -m, --message-column COL Column containing message content (default: first column)
    --filename-column COL    Column to use as filename (default: sequential)
    --prefix PREFIX          Prefix for generated filenames (default: row_)

SOLACE CONNECTION OPTIONS:
    -H, --host HOST          Solace broker host (default: tcp://localhost:55555)
    -v, --vpn VPN            Message VPN name (default: default)
    -u, --username USER      Authentication username (default: admin)
    -p, --password PASS      Authentication password (default: admin)

SSL/TLS OPTIONS:
    --ssl                    Enable SSL/TLS
    --key-store PATH         Client keystore (JKS/PKCS12)
    --key-store-password PASS    Keystore password
    --trust-store PATH       Trust store path
    --trust-store-password PASS  Trust store password
    --client-cert PATH       Client certificate (PEM)
    --client-key PATH        Client private key (PEM)
    --ca-cert PATH           CA certificate (PEM)
    --skip-cert-validation   Skip server certificate validation

QUEUE OPTIONS:
    -d, --dest-queue QUEUE   Destination queue to publish to (required)

DIRECTORY OPTIONS:
    -w, --work-dir DIR       Base working directory (default: /tmp/solace-oracle-orchestration)
    --input-dir DIR          Input directory for exported files
    --output-dir DIR         Output directory for transformed files
    --failed-dir DIR         Directory for failed transformations

FILE OPTIONS:
    --extension EXT          File extension (default: .txt)
    --overwrite              Overwrite existing files during export

PROCESSING OPTIONS:
    --dry-run                Show what would be done without executing
    --verbose, -V            Enable verbose output
    --audit-log FILE         Write audit log to file
    --no-cleanup             Don't clean up files after processing
    --keep-input             Keep input files after transformation
    --keep-output            Keep output files after publishing

CONFIGURATION:
    --config FILE            Load configuration from file

EXAMPLES:
    # Basic orchestration with inline SQL
    orchestrate-oracle.sh --sql "SELECT payload FROM messages" -d orders.out

    # With SQL file and custom columns
    orchestrate-oracle.sh -f query.sql -m content_column --filename-column id -d output.q

    # With SSL and verbose output
    orchestrate-oracle.sh --ssl -H tcps://broker:55443 --sql "SELECT * FROM data" -d dest.q -V

    # Dry run to preview
    orchestrate-oracle.sh --sql "SELECT * FROM orders" -d queue.out --dry-run --verbose

    # Use a config file
    orchestrate-oracle.sh --config /etc/oracle-orchestration.conf

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
    mkdir -p "$ORCH_INPUT_DIR" "$ORCH_OUTPUT_DIR" "$ORCH_FAILED_DIR"

    if [[ "$ORCH_VERBOSE" == "true" ]]; then
        info "Working directories:"
        info "  Input:     $ORCH_INPUT_DIR"
        info "  Output:    $ORCH_OUTPUT_DIR"
        info "  Failed:    $ORCH_FAILED_DIR"
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

# Build Oracle connection arguments for solace_cli
build_oracle_args() {
    local args="--db-host $ORCH_DB_HOST --db-port $ORCH_DB_PORT --db-service $ORCH_DB_SERVICE"
    args="$args --db-user $ORCH_DB_USER --db-password '$ORCH_DB_PASS'"
    echo "$args"
}

# Build Solace connection arguments for solace_cli
build_solace_connection_args() {
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

# Step 1: Execute Oracle query and export rows to files
step_oracle_export() {
    header "Step 1: Oracle Query Export"

    info "Oracle connection: $ORCH_DB_USER@$ORCH_DB_HOST:$ORCH_DB_PORT/$ORCH_DB_SERVICE"
    info "Output directory: $ORCH_INPUT_DIR"

    if [[ -n "$ORCH_SQL_QUERY" ]]; then
        info "SQL: $ORCH_SQL_QUERY"
    elif [[ -n "$ORCH_SQL_FILE" ]]; then
        info "SQL file: $ORCH_SQL_FILE"
    else
        error "No SQL query specified. Use --sql or --sql-file"
        return 1
    fi

    if [[ "$ORCH_DRY_RUN" == "true" ]]; then
        info "[DRY RUN] Would execute Oracle query and export to $ORCH_INPUT_DIR"
        return 0
    fi

    local args
    args=$(build_oracle_args)
    args="$args -o '$ORCH_INPUT_DIR'"
    args="$args --extension '$ORCH_FILE_EXTENSION'"

    [[ -n "$ORCH_SQL_QUERY" ]] && args="$args --sql '$ORCH_SQL_QUERY'"
    [[ -n "$ORCH_SQL_FILE" ]] && args="$args --sql-file '$ORCH_SQL_FILE'"
    [[ -n "$ORCH_MESSAGE_COLUMN" ]] && args="$args --message-column '$ORCH_MESSAGE_COLUMN'"
    [[ -n "$ORCH_FILENAME_COLUMN" ]] && args="$args --filename-column '$ORCH_FILENAME_COLUMN'"
    [[ -n "$ORCH_FILENAME_PREFIX" ]] && args="$args --prefix '$ORCH_FILENAME_PREFIX'"
    [[ "$ORCH_OVERWRITE" == "true" ]] && args="$args --overwrite"
    [[ -n "$ORCH_AUDIT_LOG" ]] && args="$args --audit-log '$ORCH_AUDIT_LOG'"

    if [[ "$ORCH_VERBOSE" == "true" ]]; then
        show_cmd "solace_cli oracle-export $args"
    fi

    # Run oracle-export - capture exit code separately
    set +e
    eval "solace_cli oracle-export $args" 2>&1
    local export_exit=$?
    set -e

    if [[ $export_exit -ne 0 ]]; then
        error "Oracle export failed with exit code $export_exit"
        return $export_exit
    fi

    # Count exported files
    local exported_count
    exported_count=$(find "$ORCH_INPUT_DIR" -type f -name "*${ORCH_FILE_EXTENSION}" 2>/dev/null | wc -l)
    info "Exported $exported_count row(s) to files"

    return 0
}

# Step 2: Transform files from input to output directory
step_transform() {
    header "Step 2: Transforming Files"

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
            ((success_count++)) || true
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
    header "Step 3: Publishing to Solace"

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
    args=$(build_solace_connection_args)
    args="$args -q $ORCH_DEST_QUEUE"
    args="$args --pattern '*${ORCH_FILE_EXTENSION}'"
    args="$args --use-filename-as-correlation"

    [[ -n "$ORCH_AUDIT_LOG" ]] && args="$args --audit-log '$ORCH_AUDIT_LOG'"

    if [[ "$ORCH_VERBOSE" == "true" ]]; then
        show_cmd "solace_cli folder-publish $args '$ORCH_OUTPUT_DIR'"
    fi

    # Run folder-publish - capture exit code separately
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
    header "Oracle to Solace Orchestration"

    info "Configuration:"
    echo "  Oracle Host:   $ORCH_DB_HOST:$ORCH_DB_PORT/$ORCH_DB_SERVICE"
    echo "  Oracle User:   $ORCH_DB_USER"
    echo "  Dest Queue:    $ORCH_DEST_QUEUE"
    echo "  Work Dir:      $ORCH_WORK_DIR"
    echo "  Solace Broker: $ORCH_HOST"
    echo "  Solace VPN:    $ORCH_VPN"
    [[ "$ORCH_USE_SSL" == "true" ]] && echo "  SSL:           enabled"
    [[ "$ORCH_DRY_RUN" == "true" ]] && echo "  Mode:          DRY RUN"
    echo ""

    # Setup
    setup_orchestration_dirs

    local start_time
    start_time=$(date +%s)

    # Execute orchestration steps
    step_oracle_export
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
            # Oracle connection
            --db-host)
                ORCH_DB_HOST="$2"
                shift 2
                ;;
            --db-port)
                ORCH_DB_PORT="$2"
                shift 2
                ;;
            --db-service)
                ORCH_DB_SERVICE="$2"
                shift 2
                ;;
            --db-user)
                ORCH_DB_USER="$2"
                shift 2
                ;;
            --db-password)
                ORCH_DB_PASS="$2"
                shift 2
                ;;
            # Oracle query
            -s|--sql)
                ORCH_SQL_QUERY="$2"
                shift 2
                ;;
            -f|--sql-file)
                ORCH_SQL_FILE="$2"
                shift 2
                ;;
            -m|--message-column)
                ORCH_MESSAGE_COLUMN="$2"
                shift 2
                ;;
            --filename-column)
                ORCH_FILENAME_COLUMN="$2"
                shift 2
                ;;
            --prefix)
                ORCH_FILENAME_PREFIX="$2"
                shift 2
                ;;
            # Solace connection
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
            # SSL/TLS
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
            # Queue
            -d|--dest-queue)
                ORCH_DEST_QUEUE="$2"
                shift 2
                ;;
            # Directories
            -w|--work-dir)
                ORCH_WORK_DIR="$2"
                ORCH_INPUT_DIR="${ORCH_WORK_DIR}/input"
                ORCH_OUTPUT_DIR="${ORCH_WORK_DIR}/output"
                ORCH_FAILED_DIR="${ORCH_WORK_DIR}/failed"
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
            # File options
            --extension)
                ORCH_FILE_EXTENSION="$2"
                shift 2
                ;;
            --overwrite)
                ORCH_OVERWRITE=true
                shift
                ;;
            # Processing
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

    if [[ -z "$ORCH_SQL_QUERY" && -z "$ORCH_SQL_FILE" ]]; then
        error "SQL query is required (--sql or --sql-file)"
        ((errors++))
    fi

    if [[ -n "$ORCH_SQL_FILE" && ! -f "$ORCH_SQL_FILE" ]]; then
        error "SQL file not found: $ORCH_SQL_FILE"
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

    if [[ -z "$ORCH_DB_HOST" ]]; then
        error "Oracle host is required (--db-host)"
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
