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
        printf '\033[0;32m●\033[0m Connected to: %s (VPN: %s)\n' "$WIZARD_SOLACE_HOST" "$WIZARD_SOLACE_VPN"
    else
        printf '\033[0;31m●\033[0m Not connected\n'
    fi
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
    prompt WIZARD_SOLACE_USER "Username" "${SOLACE_USER:-admin}"
    prompt WIZARD_SOLACE_PASS "Password" "${SOLACE_PASS:-admin}" "true"
    prompt WIZARD_SOLACE_QUEUE "Default queue" "${SOLACE_QUEUE:-demo.queue}"

    echo ""
    echo "Testing connection..."

    # Test connection by trying to browse
    if solace_cli consume \
        -H "$WIZARD_SOLACE_HOST" \
        -v "$WIZARD_SOLACE_VPN" \
        -u "$WIZARD_SOLACE_USER" \
        -p "$WIZARD_SOLACE_PASS" \
        -q "$WIZARD_SOLACE_QUEUE" \
        --browse -n 0 -t 2 2>/dev/null; then
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

    local queue
    prompt queue "Target queue" "$WIZARD_SOLACE_QUEUE"

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

    local cmd="solace-cli publish -H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN -u $WIZARD_SOLACE_USER -p **** -q $queue"
    [[ -n "$message_file" ]] && cmd="$cmd -f $message_file"
    [[ $message_count -gt 1 ]] && cmd="$cmd -c $message_count"
    [[ -n "$correlation_id" ]] && cmd="$cmd --correlation-id $correlation_id"
    [[ "$ttl" != "0" && -n "$ttl" ]] && cmd="$cmd --ttl $ttl"
    [[ "$delivery_mode" == "DIRECT" ]] && cmd="$cmd --delivery-mode DIRECT"
    [[ -n "$second_queue" ]] && cmd="$cmd -Q $second_queue"
    [[ -n "$audit_log" ]] && cmd="$cmd --audit-log $audit_log"

    echo "$cmd"
    echo ""

    # Execute command
    local args="-H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN -u $WIZARD_SOLACE_USER -p $WIZARD_SOLACE_PASS -q $queue"
    [[ -n "$message_file" ]] && args="$args -f $message_file"
    [[ $message_count -gt 1 ]] && args="$args -c $message_count"
    [[ -n "$correlation_id" ]] && args="$args --correlation-id $correlation_id"
    [[ "$ttl" != "0" && -n "$ttl" ]] && args="$args --ttl $ttl"
    [[ "$delivery_mode" == "DIRECT" ]] && args="$args --delivery-mode DIRECT"
    [[ -n "$second_queue" ]] && args="$args -Q $second_queue"
    [[ -n "$audit_log" ]] && args="$args --audit-log $audit_log"

    if [[ -n "$message_file" ]]; then
        solace_cli publish $args
    else
        solace_cli publish $args "$message_content"
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

    local args="-H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN -u $WIZARD_SOLACE_USER -p $WIZARD_SOLACE_PASS -q $queue"
    args="$args -n $count -t $timeout"

    [[ $mode_choice -eq 1 ]] && args="$args --browse"
    [[ $mode_choice -eq 2 ]] && args="$args --no-ack"
    [[ "$verbose" == "true" ]] && args="$args --verbose"
    [[ -n "$output_dir" ]] && args="$args -o $output_dir"
    [[ "$use_correlation" == "true" ]] && args="$args --use-correlation-id"

    echo "solace-cli consume ... -q $queue -n $count -t $timeout"
    echo ""

    solace_cli consume $args

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

    local args="-H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN -u $WIZARD_SOLACE_USER -p $WIZARD_SOLACE_PASS -q $queue"
    args="$args --pattern '$pattern'"

    [[ "$recursive" == "true" ]] && args="$args --recursive"
    [[ "$use_filename_correlation" == "true" ]] && args="$args --use-filename-as-correlation"
    [[ -n "$sort_by" ]] && args="$args --sort $sort_by"
    [[ "$dry_run" == "true" ]] && args="$args --dry-run"

    echo "solace-cli folder-publish ... --pattern '$pattern' $folder"
    echo ""

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

    local args="-H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN -u $WIZARD_SOLACE_USER -p $WIZARD_SOLACE_PASS"
    args="$args -q $source_queue --dest $dest_queue"
    args="$args -c $count -t $timeout"

    [[ $move_mode -eq 1 ]] && args="$args --move"
    [[ "$preserve_props" == "true" ]] && args="$args --preserve-properties"
    [[ "$dry_run" == "true" ]] && args="$args --dry-run"

    echo "solace-cli copy-queue ... -q $source_queue --dest $dest_queue"
    echo ""

    solace_cli copy-queue $args

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

    local args="-H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN -u $WIZARD_SOLACE_USER -p $WIZARD_SOLACE_PASS -q $queue"
    args="$args --mode $mode --count $count --size $size"

    [[ "$measure_latency" == "true" ]] && args="$args --latency"
    [[ -n "$rate" ]] && args="$args --rate $rate"
    [[ "$threads" != "1" ]] && args="$args --threads $threads"

    echo "solace-cli perf-test ... --mode $mode --count $count --size $size"
    echo ""

    solace_cli perf-test $args

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

    local exec_args="-H $WIZARD_SOLACE_HOST -v $WIZARD_SOLACE_VPN -u $WIZARD_SOLACE_USER -p $WIZARD_SOLACE_PASS -q $queue"
    exec_args="$exec_args --db-host $db_host --db-port $db_port --db-service $db_service --db-user $db_user --db-password $db_pass"

    [[ -n "$message_col" ]] && exec_args="$exec_args --message-column $message_col"
    [[ -n "$correlation_col" ]] && exec_args="$exec_args --correlation-column $correlation_col"
    [[ "$dry_run" == "true" ]] && exec_args="$exec_args --dry-run"

    solace_cli oracle-publish $exec_args --sql "$sql_query"

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

  publish         Publish messages to a Solace queue
  consume         Consume/browse messages from a queue
  folder-publish  Batch publish files from a directory
  copy-queue      Copy or move messages between queues
  perf-test       Run performance tests
  oracle-publish  Query Oracle -> Publish to Solace
  oracle-export   Query Oracle -> Save to files
  oracle-insert   Read files -> Insert to Oracle

COMMON OPTIONS

  -H, --host      Solace broker host (tcp://host:port)
  -v, --vpn       Message VPN name
  -u, --username  Authentication username
  -p, --password  Authentication password
  -q, --queue     Queue name

GETTING STARTED

  1. Setup connection using "Configure Connection"
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
        println_green "  Testing"
        echo "    5) Performance test"
        echo ""
        println_green "  Oracle Integration"
        echo "    6) Oracle operations"
        echo ""
        println_green "  Setup"
        echo "    7) Configure connection"
        echo "    8) Queue setup (SEMP)"
        echo ""
        println_green "  Other"
        echo "    9) Help"
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
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_perf_test
                ;;
            6)
                if [[ "$WIZARD_CONNECTED" != "true" ]]; then
                    setup_connection
                fi
                [[ "$WIZARD_CONNECTED" == "true" ]] && wizard_oracle
                ;;
            7) setup_connection ;;
            8) wizard_setup_queues ;;
            9) show_help ;;
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
