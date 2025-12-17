#!/bin/bash
# =============================================================================
# Solace CLI Interactive Wizard
# =============================================================================
# A user-friendly wizard that guides users through all CLI operations.
#
# Usage: ./wizard.sh
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# -----------------------------------------------------------------------------
# Wizard State
# -----------------------------------------------------------------------------
WIZARD_SOLACE_HOST=""
WIZARD_SOLACE_VPN=""
WIZARD_SOLACE_USER=""
WIZARD_SOLACE_PASS=""
WIZARD_SOLACE_QUEUE=""
WIZARD_CONNECTED=false

# SSL/TLS State
WIZARD_USE_SSL=false
WIZARD_AUTH_TYPE="password"  # password, certificate, or both
WIZARD_TRUST_STORE=""
WIZARD_TRUST_STORE_PASS=""
WIZARD_KEY_STORE=""
WIZARD_KEY_STORE_PASS=""
WIZARD_KEY_PASSWORD=""
WIZARD_KEY_ALIAS=""
WIZARD_CLIENT_CERT=""
WIZARD_CLIENT_KEY=""
WIZARD_CA_CERT=""
WIZARD_SKIP_CERT_VALIDATION=false

# -----------------------------------------------------------------------------
# Color Functions (use printf for portability)
# -----------------------------------------------------------------------------

color_blue() { printf '\033[0;34m%s\033[0m' "$1"; }
color_green() { printf '\033[0;32m%s\033[0m' "$1"; }
color_yellow() { printf '\033[1;33m%s\033[0m' "$1"; }
color_red() { printf '\033[0;31m%s\033[0m' "$1"; }

println_blue() { printf '\033[0;34m%s\033[0m\n' "$1"; }
println_green() { printf '\033[0;32m%s\033[0m\n' "$1"; }
println_yellow() { printf '\033[1;33m%s\033[0m\n' "$1"; }
println_red() { printf '\033[0;31m%s\033[0m\n' "$1"; }

# -----------------------------------------------------------------------------
# UI Helpers
# -----------------------------------------------------------------------------

clear_screen() {
    clear 2>/dev/null || printf "\033c"
}

print_banner() {
    println_blue ""
    println_blue "  ____        _                   ____ _     ___"
    println_blue " / ___|  ___ | | __ _  ___ ___   / ___| |   |_ _|"
    println_blue " \\___ \\ / _ \\| |/ _\` |/ __/ _ \\ | |   | |    | |"
    println_blue "  ___) | (_) | | (_| | (_|  __/ | |___| |___ | |"
    println_blue " |____/ \\___/|_|\\__,_|\\___|\\___| \\____|_____|___|"
    println_blue ""
    println_green "Interactive Wizard"
    echo "=============================================="
    echo ""
}

print_menu_header() {
    echo ""
    println_yellow "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    println_yellow "  $1"
    println_yellow "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
}

prompt() {
    local var_name="$1"
    local prompt_text="$2"
    local default_value="$3"
    local is_password="$4"

    if [[ -n "$default_value" ]]; then
        prompt_text="${prompt_text} [${default_value}]"
    fi

    if [[ "$is_password" == "true" ]]; then
        read -sp "${prompt_text}: " value
        echo ""
    else
        read -p "${prompt_text}: " value
    fi

    if [[ -z "$value" && -n "$default_value" ]]; then
        value="$default_value"
    fi

    eval "$var_name=\"$value\""
}

prompt_yes_no() {
    local prompt_text="$1"
    local default="${2:-n}"

    if [[ "$default" == "y" ]]; then
        prompt_text="${prompt_text} [Y/n]"
    else
        prompt_text="${prompt_text} [y/N]"
    fi

    read -p "${prompt_text}: " answer
    answer="${answer:-$default}"

    [[ "${answer,,}" == "y" || "${answer,,}" == "yes" ]]
}

select_option() {
    local prompt_text="$1"
    shift
    local options=("$@")

    echo "$prompt_text"
    echo ""

    local i=1
    for opt in "${options[@]}"; do
        echo "  $i) $opt"
        ((i++))
    done
    echo ""

    local selection
    while true; do
        read -p "Enter choice (1-${#options[@]}): " selection
        if [[ "$selection" =~ ^[0-9]+$ ]] && (( selection >= 1 && selection <= ${#options[@]} )); then
            return $((selection - 1))
        fi
        println_red "Invalid selection. Please try again."
    done
}

wait_for_key() {
    echo ""
    read -p "Press Enter to continue..."
}

show_connection_status() {
    if [[ "$WIZARD_CONNECTED" == "true" ]]; then
        local auth_info=""
        case "$WIZARD_AUTH_TYPE" in
            password) auth_info="user: $WIZARD_SOLACE_USER" ;;
            certificate) auth_info="cert auth" ;;
            both) auth_info="user: $WIZARD_SOLACE_USER + cert" ;;
        esac
        if [[ "$WIZARD_USE_SSL" == "true" ]]; then
            printf '\033[0;32m●\033[0m Connected to: %s (VPN: %s) \033[0;33m[SSL]\033[0m %s\n' "$WIZARD_SOLACE_HOST" "$WIZARD_SOLACE_VPN" "$auth_info"
        else
            printf '\033[0;32m●\033[0m Connected to: %s (VPN: %s) %s\n' "$WIZARD_SOLACE_HOST" "$WIZARD_SOLACE_VPN" "$auth_info"
        fi
    else
        printf '\033[0;31m●\033[0m Not connected\n'
    fi
}

# -----------------------------------------------------------------------------
# SSL/TLS Configuration
# -----------------------------------------------------------------------------

setup_ssl() {
    print_menu_header "SSL/TLS Configuration"

    echo "Configure SSL/TLS settings for secure connections."
    echo ""

    # Certificate format selection
    select_option "How are your certificates stored?" \
        "Java KeyStore (JKS) / PKCS12 (.p12/.pfx)" \
        "PEM files (separate cert and key files)" \
        "PEM file (combined cert and key)" \
        "Skip - use system defaults"
    local cert_format=$?

    case $cert_format in
        0)
            # JKS/PKCS12 format
            echo ""
            println_yellow "Client Certificate (for authentication):"
            prompt WIZARD_KEY_STORE "Key store path (.jks, .p12, .pfx)" ""
            if [[ -n "$WIZARD_KEY_STORE" ]]; then
                if [[ ! -f "$WIZARD_KEY_STORE" ]]; then
                    println_red "Warning: File not found: $WIZARD_KEY_STORE"
                fi
                prompt WIZARD_KEY_STORE_PASS "Key store password" "" "true"
                prompt WIZARD_KEY_PASSWORD "Private key password (if different, press Enter to skip)" "" "true"
                prompt WIZARD_KEY_ALIAS "Key alias (press Enter to use default)" ""
            fi

            echo ""
            println_yellow "Trust Store (for server validation):"
            prompt WIZARD_TRUST_STORE "Trust store path (optional)" ""
            if [[ -n "$WIZARD_TRUST_STORE" ]]; then
                if [[ ! -f "$WIZARD_TRUST_STORE" ]]; then
                    println_red "Warning: File not found: $WIZARD_TRUST_STORE"
                fi
                prompt WIZARD_TRUST_STORE_PASS "Trust store password" "" "true"
            fi
            ;;
        1)
            # Separate PEM files
            echo ""
            println_yellow "Client Certificate (for authentication):"
            prompt WIZARD_CLIENT_CERT "Client certificate path (.pem, .crt)" ""
            if [[ -n "$WIZARD_CLIENT_CERT" ]]; then
                if [[ ! -f "$WIZARD_CLIENT_CERT" ]]; then
                    println_red "Warning: File not found: $WIZARD_CLIENT_CERT"
                fi
                prompt WIZARD_CLIENT_KEY "Client private key path (.pem, .key)" ""
                if [[ -n "$WIZARD_CLIENT_KEY" && ! -f "$WIZARD_CLIENT_KEY" ]]; then
                    println_red "Warning: File not found: $WIZARD_CLIENT_KEY"
                fi
                prompt WIZARD_KEY_STORE_PASS "Private key password (if encrypted)" "" "true"
            fi

            echo ""
            println_yellow "CA Certificate (for server validation):"
            prompt WIZARD_CA_CERT "CA certificate path (optional)" ""
            if [[ -n "$WIZARD_CA_CERT" && ! -f "$WIZARD_CA_CERT" ]]; then
                println_red "Warning: File not found: $WIZARD_CA_CERT"
            fi
            ;;
        2)
            # Combined PEM file
            echo ""
            println_yellow "Combined PEM file (contains both cert and key):"
            prompt WIZARD_KEY_STORE "PEM file path" ""
            if [[ -n "$WIZARD_KEY_STORE" && ! -f "$WIZARD_KEY_STORE" ]]; then
                println_red "Warning: File not found: $WIZARD_KEY_STORE"
            fi
            prompt WIZARD_KEY_STORE_PASS "Private key password (if encrypted)" "" "true"

            echo ""
            println_yellow "CA Certificate (for server validation):"
            prompt WIZARD_CA_CERT "CA certificate path (optional)" ""
            ;;
        3)
            # Skip - use defaults
            echo ""
            println_yellow "Using system default certificates."
            ;;
    esac

    # Server validation option
    echo ""
    if prompt_yes_no "Skip server certificate validation? (NOT recommended for production)" "n"; then
        WIZARD_SKIP_CERT_VALIDATION=true
        println_yellow "⚠ Server certificate validation disabled"
    else
        WIZARD_SKIP_CERT_VALIDATION=false
    fi

    WIZARD_USE_SSL=true
    println_green "✓ SSL/TLS configuration complete"
}

# Build SSL arguments string
build_ssl_args() {
    local args=""

    if [[ "$WIZARD_USE_SSL" == "true" ]]; then
        args="$args --ssl"

        # Key store (client certificate)
        if [[ -n "$WIZARD_KEY_STORE" ]]; then
            args="$args --key-store '$WIZARD_KEY_STORE'"
            [[ -n "$WIZARD_KEY_STORE_PASS" ]] && args="$args --key-store-password '$WIZARD_KEY_STORE_PASS'"
            [[ -n "$WIZARD_KEY_PASSWORD" ]] && args="$args --key-password '$WIZARD_KEY_PASSWORD'"
            [[ -n "$WIZARD_KEY_ALIAS" ]] && args="$args --key-alias '$WIZARD_KEY_ALIAS'"
        fi

        # Separate PEM files
        if [[ -n "$WIZARD_CLIENT_CERT" ]]; then
            args="$args --client-cert '$WIZARD_CLIENT_CERT'"
            [[ -n "$WIZARD_CLIENT_KEY" ]] && args="$args --client-key '$WIZARD_CLIENT_KEY'"
        fi

        # Trust store
        if [[ -n "$WIZARD_TRUST_STORE" ]]; then
            args="$args --trust-store '$WIZARD_TRUST_STORE'"
            [[ -n "$WIZARD_TRUST_STORE_PASS" ]] && args="$args --trust-store-password '$WIZARD_TRUST_STORE_PASS'"
        fi

        # CA cert
        if [[ -n "$WIZARD_CA_CERT" ]]; then
            args="$args --ca-cert '$WIZARD_CA_CERT'"
        fi

        # Skip validation
        if [[ "$WIZARD_SKIP_CERT_VALIDATION" == "true" ]]; then
            args="$args --skip-cert-validation"
        fi
    fi

    echo "$args"
}

# Build connection arguments (basic + SSL)
build_connection_args() {
    local args="-H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN"

    # Add username/password if provided
    if [[ -n "$WIZARD_SOLACE_USER" ]]; then
        args="$args -u $WIZARD_SOLACE_USER"
    fi
    if [[ -n "$WIZARD_SOLACE_PASS" ]]; then
        args="$args -p '$WIZARD_SOLACE_PASS'"
    fi

    # Add SSL args
    local ssl_args
    ssl_args=$(build_ssl_args)
    if [[ -n "$ssl_args" ]]; then
        args="$args $ssl_args"
    fi

    echo "$args"
}

# -----------------------------------------------------------------------------
# Connection Setup
# -----------------------------------------------------------------------------

setup_connection() {
    print_menu_header "Connection Setup"

    echo "Configure your Solace broker connection:"
    echo ""

    prompt WIZARD_SOLACE_HOST "Solace host" "${SOLACE_HOST:-tcp://localhost:55555}"
    prompt WIZARD_SOLACE_VPN "Message VPN" "${SOLACE_VPN:-default}"

    # Check if SSL is implied by the host URL
    local host_lower="${WIZARD_SOLACE_HOST,,}"
    if [[ "$host_lower" == tcps://* ]]; then
        println_yellow "Detected secure connection (tcps://)"
        WIZARD_USE_SSL=true
    fi

    # Ask about authentication type
    echo ""
    select_option "Authentication method:" \
        "Username and Password" \
        "Client Certificate (mTLS)" \
        "Both (certificate + username for authorization)"
    local auth_choice=$?

    case $auth_choice in
        0)
            WIZARD_AUTH_TYPE="password"
            prompt WIZARD_SOLACE_USER "Username" "${SOLACE_USER:-admin}"
            prompt WIZARD_SOLACE_PASS "Password" "${SOLACE_PASS:-admin}" "true"
            ;;
        1)
            WIZARD_AUTH_TYPE="certificate"
            WIZARD_USE_SSL=true
            setup_ssl
            # Clear username/password for pure cert auth
            WIZARD_SOLACE_USER=""
            WIZARD_SOLACE_PASS=""
            ;;
        2)
            WIZARD_AUTH_TYPE="both"
            WIZARD_USE_SSL=true
            prompt WIZARD_SOLACE_USER "Username" "${SOLACE_USER:-admin}"
            prompt WIZARD_SOLACE_PASS "Password" "${SOLACE_PASS:-admin}" "true"
            setup_ssl
            ;;
    esac

    # Ask about SSL for password auth if not already configured and using tcps://
    if [[ "$WIZARD_AUTH_TYPE" == "password" && "$WIZARD_USE_SSL" == "true" ]]; then
        echo ""
        if prompt_yes_no "Configure custom SSL certificates?" "n"; then
            setup_ssl
        fi
    fi

    prompt WIZARD_SOLACE_QUEUE "Default queue" "${SOLACE_QUEUE:-demo.queue}"

    echo ""
    echo "Testing connection..."

    # Build test command
    local test_args
    test_args=$(build_connection_args)
    test_args="$test_args -q $WIZARD_SOLACE_QUEUE --browse -n 0 -t 2"

    # Test connection by trying to browse
    if eval "solace_cli consume $test_args" 2>/dev/null; then
        println_green "✓ Connection successful!"
        WIZARD_CONNECTED=true
    else
        println_yellow "⚠ Could not verify connection (queue may not exist yet)"
        if prompt_yes_no "Continue anyway?"; then
            WIZARD_CONNECTED=true
        fi
    fi

    wait_for_key
}

# -----------------------------------------------------------------------------
# Publish Wizard
# -----------------------------------------------------------------------------

wizard_publish() {
    print_menu_header "Publish Message"

    # Destination type selection
    echo ""
    select_option "Publish to:" "Queue" "Topic"
    local dest_type=$?

    local queue=""
    local topic=""
    if [[ $dest_type -eq 0 ]]; then
        prompt queue "Target queue" "$WIZARD_SOLACE_QUEUE"
    else
        prompt topic "Target topic" "my/sample/topic"
    fi

    echo ""
    echo "Message source:"
    select_option "How do you want to provide the message?" \
        "Type message content" \
        "Read from file" \
        "Generate test messages"
    local source_choice=$?

    local message_content=""
    local message_file=""
    local message_count=1

    case $source_choice in
        0)
            echo ""
            echo "Enter message content (press Enter when done):"
            read -r message_content
            ;;
        1)
            prompt message_file "File path" ""
            if [[ ! -f "$message_file" ]]; then
                println_red "File not found: $message_file"
                wait_for_key
                return
            fi
            ;;
        2)
            prompt message_count "Number of messages" "10"
            message_content="Test message from wizard - $(date)"
            ;;
    esac

    # Optional settings
    echo ""
    local correlation_id=""
    local ttl="0"
    local delivery_mode="PERSISTENT"
    local second_queue=""
    local audit_log=""

    if prompt_yes_no "Configure advanced options?" "n"; then
        prompt correlation_id "Correlation ID (optional)" ""
        prompt ttl "TTL in milliseconds (0=no expiry)" "0"

        select_option "Delivery mode:" "PERSISTENT" "DIRECT"
        [[ $? -eq 1 ]] && delivery_mode="DIRECT"

        prompt second_queue "Second queue for fan-out (optional)" ""
        prompt audit_log "Audit log file (optional)" ""
    fi

    # Build command display
    echo ""
    println_yellow "Executing:"

    local display_cmd="solace-cli publish -H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN"
    [[ -n "$WIZARD_SOLACE_USER" ]] && display_cmd="$display_cmd -u $WIZARD_SOLACE_USER -p ****"
    [[ "$WIZARD_USE_SSL" == "true" ]] && display_cmd="$display_cmd --ssl [cert options...]"
    # Show queue (required param) and topic if publishing to topic
    display_cmd="$display_cmd -q ${queue:-$WIZARD_SOLACE_QUEUE}"
    [[ -n "$topic" ]] && display_cmd="$display_cmd -T $topic"
    [[ -n "$message_file" ]] && display_cmd="$display_cmd -f $message_file"
    [[ $message_count -gt 1 ]] && display_cmd="$display_cmd -c $message_count"
    [[ -n "$correlation_id" ]] && display_cmd="$display_cmd --correlation-id $correlation_id"
    [[ "$ttl" != "0" && -n "$ttl" ]] && display_cmd="$display_cmd --ttl $ttl"
    [[ "$delivery_mode" == "DIRECT" ]] && display_cmd="$display_cmd --delivery-mode DIRECT"
    [[ -n "$second_queue" ]] && display_cmd="$display_cmd -Q $second_queue"
    [[ -n "$audit_log" ]] && display_cmd="$display_cmd --audit-log $audit_log"

    echo "$display_cmd"
    echo ""

    # Execute command
    local args
    args=$(build_connection_args)
    # Queue is required by ConnectionOptions, use provided queue or default
    args="$args -q ${queue:-$WIZARD_SOLACE_QUEUE}"
    [[ -n "$topic" ]] && args="$args -T '$topic'"
    [[ -n "$message_file" ]] && args="$args -f '$message_file'"
    [[ $message_count -gt 1 ]] && args="$args -c $message_count"
    [[ -n "$correlation_id" ]] && args="$args --correlation-id '$correlation_id'"
    [[ "$ttl" != "0" && -n "$ttl" ]] && args="$args --ttl $ttl"
    [[ "$delivery_mode" == "DIRECT" ]] && args="$args --delivery-mode DIRECT"
    [[ -n "$second_queue" ]] && args="$args -Q '$second_queue'"
    [[ -n "$audit_log" ]] && args="$args --audit-log '$audit_log'"

    if [[ -n "$message_file" ]]; then
        eval "solace_cli publish $args"
    else
        eval "solace_cli publish $args '$message_content'"
    fi

    wait_for_key
}

# -----------------------------------------------------------------------------
# Consume Wizard
# -----------------------------------------------------------------------------

wizard_consume() {
    print_menu_header "Consume Messages"

    local queue
    prompt queue "Source queue" "$WIZARD_SOLACE_QUEUE"

    echo ""
    select_option "Consume mode:" \
        "Consume (remove from queue)" \
        "Browse (non-destructive)" \
        "Consume without acknowledgment"
    local mode_choice=$?

    local count
    local timeout
    prompt count "Number of messages (0=unlimited)" "10"
    prompt timeout "Timeout in seconds (0=wait forever)" "30"

    # Output options
    local output_dir=""
    local verbose=false
    local use_correlation=false

    echo ""
    if prompt_yes_no "Save messages to files?" "n"; then
        prompt output_dir "Output directory" "/tmp/solace-messages"
        mkdir -p "$output_dir" 2>/dev/null

        if prompt_yes_no "Use correlation ID as filename?" "n"; then
            use_correlation=true
        fi
    fi

    if prompt_yes_no "Show verbose output with metadata?" "y"; then
        verbose=true
    fi

    # Build and execute command
    echo ""
    println_yellow "Executing:"

    local display_cmd="solace-cli consume -H $WIZARD_SOLACE_HOST ... -q $queue -n $count -t $timeout"
    echo "$display_cmd"
    echo ""

    local args
    args=$(build_connection_args)
    args="$args -q $queue -n $count -t $timeout"

    [[ $mode_choice -eq 1 ]] && args="$args --browse"
    [[ $mode_choice -eq 2 ]] && args="$args --no-ack"
    [[ "$verbose" == "true" ]] && args="$args --verbose"
    [[ -n "$output_dir" ]] && args="$args -o '$output_dir'"
    [[ "$use_correlation" == "true" ]] && args="$args --use-correlation-id"

    eval "solace_cli consume $args"

    if [[ -n "$output_dir" ]]; then
        echo ""
        echo "Files saved to: $output_dir"
        ls -la "$output_dir" 2>/dev/null | head -10
    fi

    wait_for_key
}

# -----------------------------------------------------------------------------
# Folder Publish Wizard
# -----------------------------------------------------------------------------

wizard_folder_publish() {
    print_menu_header "Folder Publish"

    local folder
    prompt folder "Folder path" ""

    if [[ ! -d "$folder" ]]; then
        println_red "Directory not found: $folder"
        wait_for_key
        return
    fi

    local queue
    prompt queue "Target queue" "$WIZARD_SOLACE_QUEUE"

    local pattern
    prompt pattern "File pattern (e.g., *.json, *.xml)" "*"

    # Preview files
    echo ""
    echo "Files matching pattern:"
    find "$folder" -maxdepth 1 -name "$pattern" -type f 2>/dev/null | head -10
    local file_count
    file_count=$(find "$folder" -maxdepth 1 -name "$pattern" -type f 2>/dev/null | wc -l)
    echo "Total: $file_count file(s)"
    echo ""

    local recursive=false
    local use_filename_correlation=false
    local dry_run=false
    local sort_by="NAME"

    if prompt_yes_no "Include subdirectories (recursive)?" "n"; then
        recursive=true
    fi

    if prompt_yes_no "Use filename as correlation ID?" "y"; then
        use_filename_correlation=true
    fi

    select_option "Sort files by:" "Name" "Date" "Size" "No sorting"
    case $? in
        0) sort_by="NAME" ;;
        1) sort_by="DATE" ;;
        2) sort_by="SIZE" ;;
        3) sort_by="" ;;
    esac

    if prompt_yes_no "Dry run (preview without publishing)?" "n"; then
        dry_run=true
    fi

    # Build and execute
    echo ""
    println_yellow "Executing:"

    echo "solace-cli folder-publish ... --pattern '$pattern' $folder"
    echo ""

    local args
    args=$(build_connection_args)
    args="$args -q $queue --pattern '$pattern'"

    [[ "$recursive" == "true" ]] && args="$args --recursive"
    [[ "$use_filename_correlation" == "true" ]] && args="$args --use-filename-as-correlation"
    [[ -n "$sort_by" ]] && args="$args --sort $sort_by"
    [[ "$dry_run" == "true" ]] && args="$args --dry-run"

    eval "solace_cli folder-publish $args '$folder'"

    wait_for_key
}

# -----------------------------------------------------------------------------
# Copy Queue Wizard
# -----------------------------------------------------------------------------

wizard_copy_queue() {
    print_menu_header "Copy/Move Queue Messages"

    local source_queue
    local dest_queue
    prompt source_queue "Source queue" "$WIZARD_SOLACE_QUEUE"
    prompt dest_queue "Destination queue" ""

    if [[ -z "$dest_queue" ]]; then
        println_red "Destination queue is required"
        wait_for_key
        return
    fi

    echo ""
    select_option "Operation mode:" \
        "Copy (browse - non-destructive)" \
        "Move (remove from source after copy)"
    local move_mode=$?

    local count
    local timeout
    prompt count "Number of messages (0=all)" "0"
    prompt timeout "Timeout in seconds" "10"

    local preserve_props=false
    local dry_run=false

    if prompt_yes_no "Preserve message properties (correlation ID, TTL)?" "y"; then
        preserve_props=true
    fi

    if prompt_yes_no "Dry run (preview without copying)?" "n"; then
        dry_run=true
    fi

    # Build and execute
    echo ""
    println_yellow "Executing:"

    echo "solace-cli copy-queue ... -q $source_queue --dest $dest_queue"
    echo ""

    local args
    args=$(build_connection_args)
    args="$args -q $source_queue --dest '$dest_queue'"
    args="$args -c $count -t $timeout"

    [[ $move_mode -eq 1 ]] && args="$args --move"
    [[ "$preserve_props" == "true" ]] && args="$args --preserve-properties"
    [[ "$dry_run" == "true" ]] && args="$args --dry-run"

    eval "solace_cli copy-queue $args"

    wait_for_key
}

# -----------------------------------------------------------------------------
# Performance Test Wizard
# -----------------------------------------------------------------------------

wizard_perf_test() {
    print_menu_header "Performance Test"

    local queue
    prompt queue "Test queue" "test.perf"

    echo ""
    select_option "Test mode:" \
        "Publish only" \
        "Consume only" \
        "Both (bidirectional)"
    local mode_choice=$?
    local mode
    case $mode_choice in
        0) mode="publish" ;;
        1) mode="consume" ;;
        2) mode="both" ;;
    esac

    local count
    local size
    prompt count "Number of messages" "1000"
    prompt size "Message size in bytes" "100"

    local measure_latency=false
    local rate=""
    local threads="1"

    echo ""
    if [[ "$mode" == "both" ]]; then
        if prompt_yes_no "Measure end-to-end latency?" "y"; then
            measure_latency=true
        fi
    fi

    if prompt_yes_no "Set rate limit?" "n"; then
        prompt rate "Target messages per second" "1000"
    fi

    if [[ "$mode" != "consume" ]]; then
        prompt threads "Publisher threads" "1"
    fi

    # Build and execute
    echo ""
    println_yellow "Executing:"

    echo "solace-cli perf-test ... --mode $mode --count $count --size $size"
    echo ""

    local args
    args=$(build_connection_args)
    args="$args -q $queue --mode $mode --count $count --size $size"

    [[ "$measure_latency" == "true" ]] && args="$args --latency"
    [[ -n "$rate" ]] && args="$args --rate $rate"
    [[ "$threads" != "1" ]] && args="$args --threads $threads"

    eval "solace_cli perf-test $args"

    wait_for_key
}

# -----------------------------------------------------------------------------
# Test Orchestration Wizards
# -----------------------------------------------------------------------------

wizard_test_orchestration() {
    print_menu_header "Test Queue Orchestration"

    echo "This will run the automated test suite for queue orchestration."
    echo ""
    echo "The test will:"
    echo "  - Start Solace Docker container (if not running)"
    echo "  - Create Solace queues (if not configured)"
    echo "  - Run 7 test steps covering basic, browse, dry-run, and batch operations"
    echo "  - Clean up test files and queue messages"
    echo ""

    local skip_cleanup=false
    local skip_solace=false

    if prompt_yes_no "Skip cleanup after tests (keep files for inspection)?" "n"; then
        skip_cleanup=true
    fi

    if prompt_yes_no "Skip Solace Docker/queue setup (use existing broker)?" "n"; then
        skip_solace=true
    fi

    echo ""
    println_yellow "Running test-orchestration.sh..."
    echo ""

    local args=""
    [[ "$skip_cleanup" == "true" ]] && args="$args --skip-cleanup"
    [[ "$skip_solace" == "true" ]] && args="$args --skip-solace-setup"

    "${SCRIPT_DIR}/test-orchestration.sh" $args

    wait_for_key
}

wizard_test_oracle_orchestration() {
    print_menu_header "Test Oracle Orchestration"

    echo "This will run the automated test suite for Oracle orchestration."
    echo ""
    echo "The test will:"
    echo "  - Start Solace Docker container (if not running)"
    echo "  - Create Solace queues (if not configured)"
    echo "  - Start Oracle XE Docker container"
    echo "  - Create test table with sample data"
    echo "  - Run orchestration tests (basic, custom columns, dry-run)"
    echo "  - Clean up Oracle container and test files"
    echo ""
    println_yellow "Note: First run may take a few minutes to pull the Oracle Docker image."
    echo ""

    local keep_oracle=false
    local skip_cleanup=false
    local skip_solace=false

    if prompt_yes_no "Keep Oracle container after tests?" "n"; then
        keep_oracle=true
    fi

    if prompt_yes_no "Skip cleanup of test files?" "n"; then
        skip_cleanup=true
    fi

    if prompt_yes_no "Skip Solace Docker/queue setup (use existing broker)?" "n"; then
        skip_solace=true
    fi

    echo ""
    println_yellow "Running test-oracle-orchestration.sh..."
    echo ""

    local args=""
    [[ "$keep_oracle" == "true" ]] && args="$args --keep-oracle"
    [[ "$skip_cleanup" == "true" ]] && args="$args --skip-cleanup"
    [[ "$skip_solace" == "true" ]] && args="$args --skip-solace-setup"

    "${SCRIPT_DIR}/test-oracle-orchestration.sh" $args

    wait_for_key
}

# -----------------------------------------------------------------------------
# Oracle Wizard
# -----------------------------------------------------------------------------

wizard_oracle() {
    print_menu_header "Oracle Integration"

    echo "Oracle connection settings:"
    echo ""

    local db_host db_port db_service db_user db_pass
    prompt db_host "Oracle host" "${ORACLE_HOST:-localhost}"
    prompt db_port "Oracle port" "${ORACLE_PORT:-1521}"
    prompt db_service "Oracle service name" "${ORACLE_SERVICE:-ORCL}"
    prompt db_user "Database username" "${ORACLE_USER:-scott}"
    prompt db_pass "Database password" "${ORACLE_PASS:-tiger}" "true"

    echo ""
    select_option "Oracle operation:" \
        "Query → Publish to Solace" \
        "Query → Export to Files" \
        "Files → Insert to Oracle"
    local op_choice=$?

    case $op_choice in
        0) wizard_oracle_publish "$db_host" "$db_port" "$db_service" "$db_user" "$db_pass" ;;
        1) wizard_oracle_export "$db_host" "$db_port" "$db_service" "$db_user" "$db_pass" ;;
        2) wizard_oracle_insert "$db_host" "$db_port" "$db_service" "$db_user" "$db_pass" ;;
    esac
}

wizard_oracle_publish() {
    local db_host="$1" db_port="$2" db_service="$3" db_user="$4" db_pass="$5"

    echo ""
    echo "Oracle Publish: Query database and publish results to Solace"
    echo ""

    local queue sql_query message_col correlation_col
    prompt queue "Target Solace queue" "$WIZARD_SOLACE_QUEUE"

    echo ""
    echo "Enter SQL query (single line):"
    read -r sql_query

    prompt message_col "Message column name (optional)" ""
    prompt correlation_col "Correlation ID column (optional)" ""

    local dry_run=false
    if prompt_yes_no "Dry run (preview without publishing)?" "n"; then
        dry_run=true
    fi

    # Build and execute
    echo ""
    println_yellow "Executing:"
    echo "solace-cli oracle-publish ... --sql \"$sql_query\""
    echo ""

    local exec_args
    exec_args=$(build_connection_args)
    exec_args="$exec_args -q $queue"
    exec_args="$exec_args --db-host $db_host --db-port $db_port --db-service $db_service --db-user $db_user --db-password '$db_pass'"

    [[ -n "$message_col" ]] && exec_args="$exec_args --message-column $message_col"
    [[ -n "$correlation_col" ]] && exec_args="$exec_args --correlation-column $correlation_col"
    [[ "$dry_run" == "true" ]] && exec_args="$exec_args --dry-run"

    eval "solace_cli oracle-publish $exec_args --sql '$sql_query'"

    wait_for_key
}

wizard_oracle_export() {
    local db_host="$1" db_port="$2" db_service="$3" db_user="$4" db_pass="$5"

    echo ""
    echo "Oracle Export: Query database and save results to files"
    echo ""

    local output_folder sql_query message_col filename_col extension
    prompt output_folder "Output folder" "/tmp/oracle-export"
    mkdir -p "$output_folder" 2>/dev/null

    echo ""
    echo "Enter SQL query (single line):"
    read -r sql_query

    prompt message_col "Content column name (optional)" ""
    prompt filename_col "Filename column (optional)" ""
    prompt extension "File extension" ".txt"

    local dry_run=false
    if prompt_yes_no "Dry run (preview without writing)?" "n"; then
        dry_run=true
    fi

    echo ""
    println_yellow "Executing:"
    echo "solace-cli oracle-export ... --sql \"$sql_query\" -o $output_folder"
    echo ""

    local exec_args="--db-host $db_host --db-port $db_port --db-service $db_service --db-user $db_user --db-password $db_pass"
    exec_args="$exec_args --output-folder $output_folder --extension $extension"

    [[ -n "$message_col" ]] && exec_args="$exec_args --message-column $message_col"
    [[ -n "$filename_col" ]] && exec_args="$exec_args --filename-column $filename_col"
    [[ "$dry_run" == "true" ]] && exec_args="$exec_args --dry-run"

    solace_cli oracle-export $exec_args --sql "$sql_query"

    wait_for_key
}

wizard_oracle_insert() {
    local db_host="$1" db_port="$2" db_service="$3" db_user="$4" db_pass="$5"

    echo ""
    echo "Oracle Insert: Read files and insert into database table"
    echo ""

    local folder table content_col filename_col pattern
    prompt folder "Source folder" ""

    if [[ ! -d "$folder" ]]; then
        println_red "Directory not found: $folder"
        wait_for_key
        return
    fi

    prompt pattern "File pattern" "*"
    prompt table "Target table name" ""
    prompt content_col "Content column name" "content"
    prompt filename_col "Filename column (optional)" ""

    local dry_run=false
    if prompt_yes_no "Dry run (preview without inserting)?" "y"; then
        dry_run=true
    fi

    echo ""
    println_yellow "Executing:"
    echo "solace-cli oracle-insert ... --folder $folder --table $table"
    echo ""

    local exec_args="--db-host $db_host --db-port $db_port --db-service $db_service --db-user $db_user --db-password $db_pass"
    exec_args="$exec_args --folder $folder --pattern '$pattern' --table $table --content-column $content_col"

    [[ -n "$filename_col" ]] && exec_args="$exec_args --filename-column $filename_col"
    [[ "$dry_run" == "true" ]] && exec_args="$exec_args --dry-run"

    eval "solace_cli oracle-insert $exec_args"

    wait_for_key
}

# -----------------------------------------------------------------------------
# Orchestration Wizard
# -----------------------------------------------------------------------------

wizard_orchestration() {
    print_menu_header "Message Orchestration"

    echo "Orchestrate message flow: Consume -> Transform -> Publish"
    echo ""
    echo "This will:"
    echo "  1. Consume messages from a source queue"
    echo "  2. Save them to files in an input folder"
    echo "  3. Transform each file (placeholder - customize as needed)"
    echo "  4. Write transformed content to an output folder"
    echo "  5. Publish transformed files to a destination queue"
    echo ""

    local source_queue dest_queue
    prompt source_queue "Source queue (consume from)" "$WIZARD_SOLACE_QUEUE"
    prompt dest_queue "Destination queue (publish to)" "output.queue"

    if [[ -z "$source_queue" || -z "$dest_queue" ]]; then
        println_red "Both source and destination queues are required"
        wait_for_key
        return
    fi

    local message_count timeout
    prompt message_count "Number of messages to process (0=all)" "0"
    prompt timeout "Consume timeout in seconds" "10"

    local work_dir
    prompt work_dir "Working directory" "/tmp/solace-orchestration"

    # Processing options
    local browse_only=false
    local use_correlation=true
    local dry_run=false
    local verbose=false
    local keep_files=false

    echo ""
    if prompt_yes_no "Browse only (don't remove messages from source)?" "n"; then
        browse_only=true
    fi

    if prompt_yes_no "Use correlation ID as filename?" "y"; then
        use_correlation=true
    else
        use_correlation=false
    fi

    if prompt_yes_no "Keep files after processing (no cleanup)?" "n"; then
        keep_files=true
    fi

    if prompt_yes_no "Enable verbose output?" "n"; then
        verbose=true
    fi

    if prompt_yes_no "Dry run (preview without executing)?" "n"; then
        dry_run=true
    fi

    # Build and execute command
    echo ""
    println_yellow "Executing orchestration:"
    echo ""

    local display_cmd="orchestrate.sh"
    display_cmd="$display_cmd -s $source_queue -d $dest_queue"
    display_cmd="$display_cmd -n $message_count -t $timeout"
    display_cmd="$display_cmd -w $work_dir"
    [[ "$browse_only" == "true" ]] && display_cmd="$display_cmd --browse"
    [[ "$use_correlation" == "false" ]] && display_cmd="$display_cmd --no-correlation"
    [[ "$keep_files" == "true" ]] && display_cmd="$display_cmd --no-cleanup"
    [[ "$verbose" == "true" ]] && display_cmd="$display_cmd --verbose"
    [[ "$dry_run" == "true" ]] && display_cmd="$display_cmd --dry-run"

    echo "$display_cmd"
    echo ""

    # Build actual command with connection args
    local orch_args=""
    orch_args="$orch_args -H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN"
    [[ -n "$WIZARD_SOLACE_USER" ]] && orch_args="$orch_args -u $WIZARD_SOLACE_USER"
    [[ -n "$WIZARD_SOLACE_PASS" ]] && orch_args="$orch_args -p '$WIZARD_SOLACE_PASS'"

    # Add SSL args if enabled
    if [[ "$WIZARD_USE_SSL" == "true" ]]; then
        orch_args="$orch_args --ssl"
        [[ -n "$WIZARD_KEY_STORE" ]] && orch_args="$orch_args --key-store '$WIZARD_KEY_STORE'"
        [[ -n "$WIZARD_KEY_STORE_PASS" ]] && orch_args="$orch_args --key-store-password '$WIZARD_KEY_STORE_PASS'"
        [[ -n "$WIZARD_KEY_PASSWORD" ]] && orch_args="$orch_args --key-password '$WIZARD_KEY_PASSWORD'"
        [[ -n "$WIZARD_KEY_ALIAS" ]] && orch_args="$orch_args --key-alias '$WIZARD_KEY_ALIAS'"
        [[ -n "$WIZARD_TRUST_STORE" ]] && orch_args="$orch_args --trust-store '$WIZARD_TRUST_STORE'"
        [[ -n "$WIZARD_TRUST_STORE_PASS" ]] && orch_args="$orch_args --trust-store-password '$WIZARD_TRUST_STORE_PASS'"
        [[ -n "$WIZARD_CLIENT_CERT" ]] && orch_args="$orch_args --client-cert '$WIZARD_CLIENT_CERT'"
        [[ -n "$WIZARD_CLIENT_KEY" ]] && orch_args="$orch_args --client-key '$WIZARD_CLIENT_KEY'"
        [[ -n "$WIZARD_CA_CERT" ]] && orch_args="$orch_args --ca-cert '$WIZARD_CA_CERT'"
        [[ "$WIZARD_SKIP_CERT_VALIDATION" == "true" ]] && orch_args="$orch_args --skip-cert-validation"
    fi

    orch_args="$orch_args -s $source_queue -d $dest_queue"
    orch_args="$orch_args -n $message_count -t $timeout"
    orch_args="$orch_args -w '$work_dir'"

    [[ "$browse_only" == "true" ]] && orch_args="$orch_args --browse"
    [[ "$use_correlation" == "false" ]] && orch_args="$orch_args --no-correlation"
    [[ "$keep_files" == "true" ]] && orch_args="$orch_args --no-cleanup"
    [[ "$verbose" == "true" ]] && orch_args="$orch_args --verbose"
    [[ "$dry_run" == "true" ]] && orch_args="$orch_args --dry-run"

    eval "${SCRIPT_DIR}/orchestrate.sh $orch_args"

    wait_for_key
}

# -----------------------------------------------------------------------------
# Oracle Orchestration Wizard
# -----------------------------------------------------------------------------

wizard_oracle_orchestration() {
    print_menu_header "Oracle to Solace Orchestration"

    echo "Orchestrate message flow: Oracle Query -> Transform -> Publish"
    echo ""
    echo "This will:"
    echo "  1. Execute an Oracle query and export each row as a file"
    echo "  2. Transform each file (placeholder - customize as needed)"
    echo "  3. Publish transformed files to a destination Solace queue"
    echo ""

    # Oracle connection
    local db_host db_port db_service db_user db_pass
    println_yellow "Oracle Connection:"
    prompt db_host "Oracle host" "${ORACLE_HOST:-localhost}"
    prompt db_port "Oracle port" "${ORACLE_PORT:-1521}"
    prompt db_service "Oracle service name" "${ORACLE_SERVICE:-ORCL}"
    prompt db_user "Database username" "${ORACLE_USER:-scott}"
    prompt db_pass "Database password" "${ORACLE_PASS:-tiger}" "true"

    # SQL query
    echo ""
    println_yellow "SQL Query:"
    select_option "How do you want to provide the SQL query?" \
        "Type SQL query inline" \
        "Read from SQL file"
    local sql_choice=$?

    local sql_query=""
    local sql_file=""

    case $sql_choice in
        0)
            echo ""
            echo "Enter SQL SELECT statement (single line):"
            read -r sql_query
            if [[ -z "$sql_query" ]]; then
                println_red "SQL query is required"
                wait_for_key
                return
            fi
            ;;
        1)
            prompt sql_file "SQL file path" ""
            if [[ ! -f "$sql_file" ]]; then
                println_red "SQL file not found: $sql_file"
                wait_for_key
                return
            fi
            ;;
    esac

    # Column configuration
    echo ""
    local message_col filename_col
    prompt message_col "Message column name (leave empty for first column)" ""
    prompt filename_col "Filename column (leave empty for sequential)" ""

    # Destination queue
    echo ""
    println_yellow "Solace Destination:"
    local dest_queue
    prompt dest_queue "Destination queue" "output.queue"

    if [[ -z "$dest_queue" ]]; then
        println_red "Destination queue is required"
        wait_for_key
        return
    fi

    # Working directory
    local work_dir
    prompt work_dir "Working directory" "/tmp/solace-oracle-orchestration"

    # Processing options
    local dry_run=false
    local verbose=false
    local keep_files=false

    echo ""
    if prompt_yes_no "Keep files after processing (no cleanup)?" "n"; then
        keep_files=true
    fi

    if prompt_yes_no "Enable verbose output?" "n"; then
        verbose=true
    fi

    if prompt_yes_no "Dry run (preview without executing)?" "n"; then
        dry_run=true
    fi

    # Build and execute command
    echo ""
    println_yellow "Executing Oracle orchestration:"
    echo ""

    local display_cmd="orchestrate-oracle.sh"
    display_cmd="$display_cmd --db-host $db_host --db-port $db_port --db-service $db_service"
    display_cmd="$display_cmd -d $dest_queue -w $work_dir"
    [[ -n "$sql_query" ]] && display_cmd="$display_cmd --sql \"...\""
    [[ -n "$sql_file" ]] && display_cmd="$display_cmd --sql-file $sql_file"
    [[ "$keep_files" == "true" ]] && display_cmd="$display_cmd --no-cleanup"
    [[ "$verbose" == "true" ]] && display_cmd="$display_cmd --verbose"
    [[ "$dry_run" == "true" ]] && display_cmd="$display_cmd --dry-run"

    echo "$display_cmd"
    echo ""

    # Build actual command with all args
    local orch_args=""

    # Oracle args
    orch_args="$orch_args --db-host $db_host --db-port $db_port --db-service $db_service"
    orch_args="$orch_args --db-user $db_user --db-password '$db_pass'"

    # SQL args
    [[ -n "$sql_query" ]] && orch_args="$orch_args --sql '$sql_query'"
    [[ -n "$sql_file" ]] && orch_args="$orch_args --sql-file '$sql_file'"
    [[ -n "$message_col" ]] && orch_args="$orch_args --message-column '$message_col'"
    [[ -n "$filename_col" ]] && orch_args="$orch_args --filename-column '$filename_col'"

    # Solace connection args
    orch_args="$orch_args -H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN"
    [[ -n "$WIZARD_SOLACE_USER" ]] && orch_args="$orch_args -u $WIZARD_SOLACE_USER"
    [[ -n "$WIZARD_SOLACE_PASS" ]] && orch_args="$orch_args -p '$WIZARD_SOLACE_PASS'"

    # Add SSL args if enabled
    if [[ "$WIZARD_USE_SSL" == "true" ]]; then
        orch_args="$orch_args --ssl"
        [[ -n "$WIZARD_KEY_STORE" ]] && orch_args="$orch_args --key-store '$WIZARD_KEY_STORE'"
        [[ -n "$WIZARD_KEY_STORE_PASS" ]] && orch_args="$orch_args --key-store-password '$WIZARD_KEY_STORE_PASS'"
        [[ -n "$WIZARD_KEY_PASSWORD" ]] && orch_args="$orch_args --key-password '$WIZARD_KEY_PASSWORD'"
        [[ -n "$WIZARD_KEY_ALIAS" ]] && orch_args="$orch_args --key-alias '$WIZARD_KEY_ALIAS'"
        [[ -n "$WIZARD_TRUST_STORE" ]] && orch_args="$orch_args --trust-store '$WIZARD_TRUST_STORE'"
        [[ -n "$WIZARD_TRUST_STORE_PASS" ]] && orch_args="$orch_args --trust-store-password '$WIZARD_TRUST_STORE_PASS'"
        [[ -n "$WIZARD_CLIENT_CERT" ]] && orch_args="$orch_args --client-cert '$WIZARD_CLIENT_CERT'"
        [[ -n "$WIZARD_CLIENT_KEY" ]] && orch_args="$orch_args --client-key '$WIZARD_CLIENT_KEY'"
        [[ -n "$WIZARD_CA_CERT" ]] && orch_args="$orch_args --ca-cert '$WIZARD_CA_CERT'"
        [[ "$WIZARD_SKIP_CERT_VALIDATION" == "true" ]] && orch_args="$orch_args --skip-cert-validation"
    fi

    orch_args="$orch_args -d $dest_queue"
    orch_args="$orch_args -w '$work_dir'"

    [[ "$keep_files" == "true" ]] && orch_args="$orch_args --no-cleanup"
    [[ "$verbose" == "true" ]] && orch_args="$orch_args --verbose"
    [[ "$dry_run" == "true" ]] && orch_args="$orch_args --dry-run"

    eval "${SCRIPT_DIR}/orchestrate-oracle.sh $orch_args"

    wait_for_key
}

# -----------------------------------------------------------------------------
# Queue Setup Wizard
# -----------------------------------------------------------------------------

wizard_setup_queues() {
    print_menu_header "Queue Setup (SEMP API)"

    local semp_host semp_port semp_user semp_pass
    prompt semp_host "SEMP API host" "${SEMP_HOST:-localhost}"
    prompt semp_port "SEMP API port" "${SEMP_PORT:-8095}"
    prompt semp_user "SEMP admin username" "${SEMP_USER:-admin}"
    prompt semp_pass "SEMP admin password" "${SEMP_PASS:-admin}" "true"

    echo ""
    select_option "Action:" \
        "Create demo queues" \
        "Check queue status" \
        "Delete demo queues"
    local action=$?

    echo ""

    export SEMP_HOST="$semp_host"
    export SEMP_PORT="$semp_port"
    export SEMP_USER="$semp_user"
    export SEMP_PASS="$semp_pass"

    case $action in
        0) "${SCRIPT_DIR}/setup-solace.sh" create ;;
        1) "${SCRIPT_DIR}/setup-solace.sh" status ;;
        2) "${SCRIPT_DIR}/setup-solace.sh" delete ;;
    esac

    wait_for_key
}

# -----------------------------------------------------------------------------
# Help
# -----------------------------------------------------------------------------

show_help() {
    print_menu_header "Help & Documentation"

    cat << 'EOF'
COMMANDS OVERVIEW

  publish              Publish messages to a Solace queue or topic
  consume              Consume/browse messages from a queue
  folder-publish       Batch publish files from a directory
  copy-queue           Copy or move messages between queues
  orchestration        Consume -> Transform -> Publish workflow
  oracle-orchestration Oracle Query -> Transform -> Publish workflow
  perf-test            Run performance tests
  oracle-publish       Query Oracle -> Publish to Solace
  oracle-export        Query Oracle -> Save to files
  oracle-insert        Read files -> Insert to Oracle

COMMON OPTIONS

  -H, --host      Solace broker host (tcp://host:port or tcps://host:port)
  -v, --vpn       Message VPN name
  -u, --username  Authentication username (optional with cert auth)
  -p, --password  Authentication password
  -q, --queue     Queue name
  -T, --topic     Topic name (publish command only, overrides -q)

SSL/TLS OPTIONS

  --ssl                     Enable SSL/TLS connection
  --key-store PATH          Client keystore (JKS/PKCS12) for mTLS
  --key-store-password      Password for keystore
  --key-password            Password for private key (if different from keystore)
  --key-alias               Alias of private key entry in keystore
  --trust-store PATH        Trust store for server validation
  --trust-store-password    Password for trust store
  --client-cert PATH        Client certificate (PEM format)
  --client-key PATH         Client private key (PEM format)
  --ca-cert PATH            CA certificate (PEM format)
  --skip-cert-validation    Skip server cert validation (dev only)

AUTHENTICATION MODES

  1. Username/Password - Traditional authentication
  2. Client Certificate (mTLS) - Certificate-based authentication
  3. Both - Certificate auth with username for authorization

GETTING STARTED

  1. Setup connection using "Configure Connection"
     - Choose your authentication method
     - For certificate auth, provide cert/key paths
  2. Create queues using "Queue Setup"
  3. Try publishing a message
  4. Try consuming messages

For more details, see the README.md file or run:
  java -jar solace-cli.jar --help
  java -jar solace-cli.jar <command> --help

EOF

    wait_for_key
}

# -----------------------------------------------------------------------------
# Main Menu
# -----------------------------------------------------------------------------

main_menu() {
    while true; do
        clear_screen
        print_banner
        show_connection_status

        echo ""
        echo "What would you like to do?"
        echo ""
        println_green "  Messages"
        echo "    1) Publish message"
        echo "    2) Consume messages"
        echo "    3) Folder publish (batch)"
        echo "    4) Copy/Move queue"
        echo ""
        println_green "  Orchestration"
        echo "    5) Queue orchestration (consume->transform->publish)"
        echo "    6) Oracle orchestration (query->transform->publish)"
        echo ""
        println_green "  Testing"
        echo "    7) Performance test"
        echo "    t) Test queue orchestration"
        echo "    o) Test Oracle orchestration"
        echo ""
        println_green "  Oracle Integration"
        echo "    8) Oracle operations"
        echo ""
        println_green "  Setup"
        echo "    9) Configure connection"
        echo "    s) Queue setup (SEMP)"
        echo ""
        println_green "  Other"
        echo "    h) Help"
        echo "    0) Exit"
        echo ""

        read -p "Enter choice: " choice

        case $choice in
            1)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_publish
                ;;
            2)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_consume
                ;;
            3)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_folder_publish
                ;;
            4)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_copy_queue
                ;;
            5)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_orchestration
                ;;
            6)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_oracle_orchestration
                ;;
            7)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_perf_test
                ;;
            t|T) wizard_test_orchestration ;;
            o|O) wizard_test_oracle_orchestration ;;
            8)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_oracle
                ;;
            9) setup_connection ;;
            s|S) wizard_setup_queues ;;
            h|H) show_help ;;
            0|q|Q|exit)
                echo ""
                echo "Goodbye!"
                exit 0
                ;;
            *)
                println_red "Invalid choice"
                sleep 1
                ;;
        esac
    done
}

# -----------------------------------------------------------------------------
# Entry Point
# -----------------------------------------------------------------------------

check_jar

# Check for arguments
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    echo "Solace CLI Interactive Wizard"
    echo ""
    echo "Usage: $0"
    echo ""
    echo "An interactive wizard that guides you through all Solace CLI operations."
    echo ""
    exit 0
fi

main_menu
