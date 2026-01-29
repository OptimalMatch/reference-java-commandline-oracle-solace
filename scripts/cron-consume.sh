#!/bin/bash
# =============================================================================
# Solace Queue Consumer - Cron Runner
# =============================================================================
# Non-interactive script to consume messages from a Solace queue over SSL
# and write each message to a file. Designed for RHEL cron execution.
#
# Usage:
#   ./cron-consume.sh run        Run the consumer (what cron calls)
#   ./cron-consume.sh install    Install cron entry for current user
#   ./cron-consume.sh uninstall  Remove cron entry
#   ./cron-consume.sh status     Show cron and last-run info
#
# Configuration: ~/.solace-consume.conf (see cron-consume.conf.example)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
source "${SCRIPT_DIR}/common.sh"

CRON_MARKER="# solace-consume-cron"
LOGROTATE_CRON_MARKER="# solace-consume-logrotate"
DEFAULT_CONFIG="${HOME}/.solace-consume.conf"
DEFAULT_LOG="${HOME}/.solace-consume.log"
DEFAULT_LOGROTATE_CONF="${HOME}/.solace-consume.logrotate"
DEFAULT_LOGROTATE_STATE="${HOME}/.solace-consume.logrotate.state"

# -----------------------------------------------------------------------------
# Config Loading
# -----------------------------------------------------------------------------

load_config() {
    local config_file="${1:-$DEFAULT_CONFIG}"

    if [[ ! -f "$config_file" ]]; then
        echo "Error: Config file not found: $config_file" >&2
        echo "Copy cron-consume.conf.example to $config_file and edit it." >&2
        exit 1
    fi

    # Source the config (values become shell variables)
    source "$config_file"

    # Validate required settings
    local missing=()
    [[ -z "${SOLACE_HOST:-}" ]] && missing+=("SOLACE_HOST")
    [[ -z "${SOLACE_VPN:-}" ]] && missing+=("SOLACE_VPN")
    [[ -z "${SOLACE_QUEUE:-}" ]] && missing+=("SOLACE_QUEUE")
    [[ -z "${KEY_STORE:-}" ]] && missing+=("KEY_STORE")
    [[ -z "${KEY_STORE_PASS:-}" ]] && missing+=("KEY_STORE_PASS")
    [[ -z "${KEY_ALIAS:-}" ]] && missing+=("KEY_ALIAS")
    [[ -z "${TRUST_STORE:-}" ]] && missing+=("TRUST_STORE")
    [[ -z "${TRUST_STORE_PASS:-}" ]] && missing+=("TRUST_STORE_PASS")
    [[ -z "${OUTPUT_DIR:-}" ]] && missing+=("OUTPUT_DIR")

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "Error: Missing required config values: ${missing[*]}" >&2
        exit 1
    fi

    # Defaults for optional settings
    CONSUME_COUNT="${CONSUME_COUNT:-0}"
    CONSUME_TIMEOUT="${CONSUME_TIMEOUT:-30}"
    BROWSE_ONLY="${BROWSE_ONLY:-false}"
    NO_ACK="${NO_ACK:-false}"
    USE_CORRELATION_ID="${USE_CORRELATION_ID:-false}"
    VERBOSE="${VERBOSE:-false}"
    CRON_SCHEDULE="${CRON_SCHEDULE:-*/5 * * * *}"
    LOG_FILE="${LOG_FILE:-$DEFAULT_LOG}"

    # Set JAVA_HOME/PATH if specified
    if [[ -n "${JAVA_HOME:-}" ]]; then
        export JAVA_HOME
        export PATH="${JAVA_HOME}/bin:${PATH}"
    fi
}

# -----------------------------------------------------------------------------
# Security Checks
# -----------------------------------------------------------------------------

check_file_permissions() {
    local file="$1"
    local label="$2"
    local max_perms="$3"  # e.g., 600 or 640

    if [[ ! -f "$file" ]]; then
        echo "Error: $label not found: $file" >&2
        return 1
    fi

    # Check ownership
    local file_owner
    file_owner=$(stat -c '%U' "$file" 2>/dev/null)
    if [[ "$file_owner" != "$(whoami)" ]]; then
        echo "Warning: $label is owned by '$file_owner', not '$(whoami)': $file" >&2
        return 1
    fi

    # Check permissions (must not be world-readable)
    local perms
    perms=$(stat -c '%a' "$file" 2>/dev/null)
    local other_bits="${perms:2:1}"
    if [[ "$other_bits" != "0" ]]; then
        echo "Warning: $label is world-accessible (mode $perms): $file" >&2
        echo "  Run: chmod $max_perms $file" >&2
        return 1
    fi

    return 0
}

validate_security() {
    local config_file="${1:-$DEFAULT_CONFIG}"
    local errors=0

    echo "Checking file security..."

    if ! check_file_permissions "$config_file" "Config file" "600"; then
        ((errors++))
    else
        echo "  Config file: OK ($config_file)"
    fi

    if ! check_file_permissions "$KEY_STORE" "Key store" "600"; then
        ((errors++))
    else
        echo "  Key store:   OK ($KEY_STORE)"
    fi

    # Only check trust store separately if it's a different file
    if [[ "$TRUST_STORE" != "$KEY_STORE" ]]; then
        if ! check_file_permissions "$TRUST_STORE" "Trust store" "600"; then
            ((errors++))
        else
            echo "  Trust store: OK ($TRUST_STORE)"
        fi
    else
        echo "  Trust store: same as key store"
    fi

    if [[ $errors -gt 0 ]]; then
        echo ""
        echo "$errors security issue(s) found. Fix before installing cron." >&2
        return 1
    fi

    echo "All security checks passed."
    return 0
}

# -----------------------------------------------------------------------------
# Run Mode
# -----------------------------------------------------------------------------

do_run() {
    local config_file="${1:-$DEFAULT_CONFIG}"
    load_config "$config_file"
    check_jar

    # Ensure output directory exists
    mkdir -p "$OUTPUT_DIR"

    # Build consume command arguments
    local args="-H $SOLACE_HOST -v $SOLACE_VPN"
    [[ -n "${SOLACE_USER:-}" ]] && args="$args -u $SOLACE_USER"
    [[ -n "${SOLACE_PASS:-}" ]] && args="$args -p '$SOLACE_PASS'"
    args="$args -q $SOLACE_QUEUE"
    args="$args --ssl"
    args="$args --key-store '$KEY_STORE'"
    args="$args --key-store-password '$KEY_STORE_PASS'"
    args="$args --key-alias '$KEY_ALIAS'"
    args="$args --trust-store '$TRUST_STORE'"
    args="$args --trust-store-password '$TRUST_STORE_PASS'"
    args="$args -o '$OUTPUT_DIR'"
    args="$args -n $CONSUME_COUNT"
    args="$args -t $CONSUME_TIMEOUT"
    [[ "$BROWSE_ONLY" == "true" ]] && args="$args --browse"
    [[ "$NO_ACK" == "true" ]] && args="$args --no-ack"
    [[ "$USE_CORRELATION_ID" == "true" ]] && args="$args --use-correlation-id"
    [[ "$VERBOSE" == "true" ]] && args="$args --verbose"

    # Capture jar output to temp file for filtering
    local tmp_out
    tmp_out=$(mktemp /tmp/solace-consume-XXXXXX.out)
    trap "rm -f '$tmp_out'" RETURN

    # Log start
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] Starting consume from $SOLACE_QUEUE" >> "$LOG_FILE"

    # Execute — capture all output to temp file
    local exit_code=0
    eval "solace_cli consume $args" > "$tmp_out" 2>&1 || exit_code=$?

    # Extract useful lines from jar output (skip progress updates, Ctrl+C hint,
    # Java INFO/SLF4J noise) and append to log
    grep -v \
        -e '^Consuming:' \
        -e 'Press Ctrl+C' \
        -e '^SLF4J:' \
        -e '^[A-Z][a-z]\{2\} [0-9]' \
        -e '^INFO:' \
        "$tmp_out" >> "$LOG_FILE" 2>/dev/null || true

    # Log result with summary
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    local summary
    summary=$(grep -o '[A-Za-z]* [0-9]* message(s)' "$tmp_out" | tail -1)
    if [[ $exit_code -eq 0 ]]; then
        echo "[$timestamp] Completed${summary:+ — $summary}" >> "$LOG_FILE"
    else
        echo "[$timestamp] Failed with exit code $exit_code${summary:+ — $summary}" >> "$LOG_FILE"
    fi

    return $exit_code
}

# -----------------------------------------------------------------------------
# Logrotate
# -----------------------------------------------------------------------------

install_logrotate() {
    local logrotate_conf="$DEFAULT_LOGROTATE_CONF"

    cat > "$logrotate_conf" <<LOGROTATE_EOF
$LOG_FILE {
    weekly
    rotate 4
    missingok
    notifempty
    compress
    delaycompress
    dateext
    dateformat -%Y%m%d
}
LOGROTATE_EOF

    chmod 600 "$logrotate_conf"

    # Add a daily cron entry to run logrotate as the current user
    local lr_cron_cmd="0 0 * * * /usr/sbin/logrotate --state $DEFAULT_LOGROTATE_STATE $logrotate_conf $LOGROTATE_CRON_MARKER"

    local existing
    existing=$(crontab -l 2>/dev/null || true)
    local filtered
    filtered=$(echo "$existing" | grep -v "$LOGROTATE_CRON_MARKER" || true)

    echo "$filtered
$lr_cron_cmd" | crontab -

    echo "Log rotation installed:"
    echo "  Config:   $logrotate_conf"
    echo "  Schedule: daily at midnight (via cron)"
    echo "  Retains 4 weekly compressed backups"
}

remove_logrotate() {
    local logrotate_conf="$DEFAULT_LOGROTATE_CONF"

    # Remove logrotate cron entry
    local existing
    existing=$(crontab -l 2>/dev/null || true)
    if echo "$existing" | grep -q "$LOGROTATE_CRON_MARKER"; then
        local filtered
        filtered=$(echo "$existing" | grep -v "$LOGROTATE_CRON_MARKER")
        echo "$filtered" | crontab -
        echo "Logrotate cron entry removed."
    fi

    # Remove logrotate config and state files
    if [[ -f "$logrotate_conf" ]]; then
        rm -f "$logrotate_conf"
        echo "Logrotate config removed: $logrotate_conf"
    fi
    rm -f "$DEFAULT_LOGROTATE_STATE"
}

# -----------------------------------------------------------------------------
# Install Mode
# -----------------------------------------------------------------------------

do_install() {
    local config_file="${1:-$DEFAULT_CONFIG}"
    load_config "$config_file"
    check_jar

    echo "Installing Solace consumer cron job..."
    echo ""

    # Security validation
    if ! validate_security "$config_file"; then
        echo ""
        read -p "Continue anyway? [y/N]: " answer
        if [[ "${answer,,}" != "y" ]]; then
            echo "Aborted."
            exit 1
        fi
    fi

    echo ""

    # Build cron line
    local cron_cmd="$CRON_SCHEDULE $SCRIPT_PATH run $config_file $CRON_MARKER"

    # Remove existing entry (if any) and add new one
    local existing
    existing=$(crontab -l 2>/dev/null || true)
    local filtered
    filtered=$(echo "$existing" | grep -v "$CRON_MARKER" || true)

    echo "$filtered
$cron_cmd" | crontab -

    echo "Cron entry installed:"
    echo "  $cron_cmd"
    echo ""
    echo "Log file: $LOG_FILE"
    echo "Output:   $OUTPUT_DIR"

    # Offer logrotate setup
    if [[ ! -f "$DEFAULT_LOGROTATE_CONF" ]]; then
        echo ""
        echo "Log rotation is not configured."
        read -p "Install log rotation (weekly, 4 backups)? [y/N]: " lr_answer
        if [[ "${lr_answer,,}" == "y" ]]; then
            install_logrotate
        else
            echo "Skipped. Re-run 'install' to set up later."
        fi
    else
        echo ""
        echo "Log rotation: $DEFAULT_LOGROTATE_CONF (already installed)"
    fi
}

# -----------------------------------------------------------------------------
# Uninstall Mode
# -----------------------------------------------------------------------------

do_uninstall() {
    echo "Removing Solace consumer cron job..."

    local existing
    existing=$(crontab -l 2>/dev/null || true)

    if echo "$existing" | grep -q "$CRON_MARKER"; then
        local filtered
        filtered=$(echo "$existing" | grep -v "$CRON_MARKER")
        echo "$filtered" | crontab -
        echo "Cron entry removed."
    else
        echo "No Solace consumer cron entry found."
    fi

    remove_logrotate
}

# -----------------------------------------------------------------------------
# Status Mode
# -----------------------------------------------------------------------------

do_status() {
    local config_file="${1:-$DEFAULT_CONFIG}"

    echo "=== Solace Consumer Cron Status ==="
    echo ""

    # Check cron entry
    echo "Cron entry:"
    local entry
    entry=$(crontab -l 2>/dev/null | grep "$CRON_MARKER" || true)
    if [[ -n "$entry" ]]; then
        echo "  $entry"
    else
        echo "  Not installed"
    fi
    echo ""

    # Check config
    echo "Config file:"
    if [[ -f "$config_file" ]]; then
        echo "  $config_file (exists)"
    else
        echo "  $config_file (NOT FOUND)"
    fi
    echo ""

    # Check logrotate
    echo "Log rotation:"
    if [[ -f "$DEFAULT_LOGROTATE_CONF" ]]; then
        echo "  $DEFAULT_LOGROTATE_CONF (installed)"
        local lr_entry
        lr_entry=$(crontab -l 2>/dev/null | grep "$LOGROTATE_CRON_MARKER" || true)
        if [[ -n "$lr_entry" ]]; then
            echo "  Cron: daily at midnight"
        else
            echo "  Warning: logrotate cron entry missing"
        fi
    else
        echo "  Not configured (run 'install' to set up)"
    fi
    echo ""

    # Show last log entries
    local log="${LOG_FILE:-$DEFAULT_LOG}"
    echo "Recent log ($log):"
    if [[ -f "$log" ]]; then
        local log_size
        log_size=$(du -h "$log" 2>/dev/null | cut -f1)
        echo "  Size: $log_size"
        echo ""
        tail -10 "$log" | sed 's/^/  /'
    else
        echo "  No log file yet"
    fi
}

# -----------------------------------------------------------------------------
# Entry Point
# -----------------------------------------------------------------------------

case "${1:-}" in
    run)
        do_run "${2:-$DEFAULT_CONFIG}"
        ;;
    install)
        do_install "${2:-$DEFAULT_CONFIG}"
        ;;
    uninstall)
        do_uninstall
        ;;
    status)
        do_status "${2:-$DEFAULT_CONFIG}"
        ;;
    *)
        echo "Solace Queue Consumer - Cron Runner"
        echo ""
        echo "Usage: $(basename "$0") <command> [config-file]"
        echo ""
        echo "Commands:"
        echo "  run        Consume messages (what cron calls)"
        echo "  install    Install cron entry for current user"
        echo "  uninstall  Remove cron entry"
        echo "  status     Show cron and last-run info"
        echo ""
        echo "Config file defaults to ~/.solace-consume.conf"
        echo "See cron-consume.conf.example for a template."
        exit 1
        ;;
esac
