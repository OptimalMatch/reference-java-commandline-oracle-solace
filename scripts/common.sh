#!/bin/bash
# =============================================================================
# Common Configuration for Solace CLI Examples
# =============================================================================
# Source this file in other scripts: source "$(dirname "$0")/common.sh"

# Solace Connection Settings
export SOLACE_HOST="${SOLACE_HOST:-tcp://localhost:55555}"
export SOLACE_VPN="${SOLACE_VPN:-default}"
export SOLACE_USER="${SOLACE_USER:-admin}"
export SOLACE_PASS="${SOLACE_PASS:-admin}"
export SOLACE_QUEUE="${SOLACE_QUEUE:-demo.queue}"

# Oracle Connection Settings
export ORACLE_HOST="${ORACLE_HOST:-localhost}"
export ORACLE_PORT="${ORACLE_PORT:-1521}"
export ORACLE_SERVICE="${ORACLE_SERVICE:-ORCL}"
export ORACLE_USER="${ORACLE_USER:-scott}"
export ORACLE_PASS="${ORACLE_PASS:-tiger}"

# SEMP API Settings (for queue management)
export SEMP_HOST="${SEMP_HOST:-localhost}"
export SEMP_PORT="${SEMP_PORT:-8095}"
export SEMP_USER="${SEMP_USER:-admin}"
export SEMP_PASS="${SEMP_PASS:-admin}"

# Paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JAR_PATH="${PROJECT_ROOT}/target/solace-cli-1.0.0.jar"

# Data directories
export DATA_DIR="${DATA_DIR:-/tmp/solace-cli-demo}"
export AUDIT_LOG="${DATA_DIR}/audit.log"
export FAILED_DIR="${DATA_DIR}/failed"
export RETRY_DIR="${DATA_DIR}/retry"
export OUTPUT_DIR="${DATA_DIR}/output"
export MESSAGES_DIR="${DATA_DIR}/messages"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# -----------------------------------------------------------------------------
# Helper Functions
# -----------------------------------------------------------------------------

# Print section header
header() {
    echo ""
    echo -e "${BLUE}=============================================================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}=============================================================================${NC}"
    echo ""
}

# Print subsection
subheader() {
    echo ""
    echo -e "${YELLOW}--- $1 ---${NC}"
    echo ""
}

# Print info message
info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

# Print warning message
warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# Print error message
error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Print command being executed
show_cmd() {
    echo -e "${YELLOW}\$ $1${NC}"
}

# Run solace-cli command
solace_cli() {
    java -jar "$JAR_PATH" "$@"
}

# Check if JAR exists
check_jar() {
    if [[ ! -f "$JAR_PATH" ]]; then
        error "JAR not found at $JAR_PATH"
        echo "Please build the project first: mvn clean package"
        exit 1
    fi
}

# Setup demo directories
setup_dirs() {
    mkdir -p "$DATA_DIR" "$FAILED_DIR" "$RETRY_DIR" "$OUTPUT_DIR" "$MESSAGES_DIR"
    info "Created demo directories in $DATA_DIR"
}

# Cleanup demo directories
cleanup_dirs() {
    rm -rf "$DATA_DIR"
    info "Cleaned up $DATA_DIR"
}

# Create sample message files
create_sample_messages() {
    local count=${1:-5}
    for i in $(seq 1 $count); do
        cat > "${MESSAGES_DIR}/message_${i}.json" << EOF
{
  "id": "msg-${i}",
  "timestamp": "$(date -Iseconds)",
  "data": "Sample message content ${i}",
  "priority": $((i % 3 + 1))
}
EOF
    done
    info "Created $count sample message files in $MESSAGES_DIR"
}

# Create sample XML messages
create_sample_xml_messages() {
    local count=${1:-5}
    for i in $(seq 1 $count); do
        cat > "${MESSAGES_DIR}/order_${i}.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<order>
  <orderId>ORD-$(printf "%05d" $i)</orderId>
  <timestamp>$(date -Iseconds)</timestamp>
  <customer>Customer ${i}</customer>
  <amount>$((RANDOM % 1000 + 100)).00</amount>
  <status>pending</status>
</order>
EOF
    done
    info "Created $count sample XML files in $MESSAGES_DIR"
}

# Create exclusion patterns file
create_exclusion_file() {
    cat > "${DATA_DIR}/exclusions.txt" << 'EOF'
# Exclusion patterns for Solace CLI
# Lines starting with # are comments

# Exact matches
test-message
debug-payload

# Wildcard patterns
test-*.json
debug_*
*.tmp

# Regex patterns (prefix with regex:)
regex:^temp-\d{3}$
regex:.*DEBUG.*
regex:^test-[a-z]+\.xml$
EOF
    info "Created exclusion file at ${DATA_DIR}/exclusions.txt"
}

# Print connection info
show_connection_info() {
    echo "Solace Connection:"
    echo "  Host:     $SOLACE_HOST"
    echo "  VPN:      $SOLACE_VPN"
    echo "  User:     $SOLACE_USER"
    echo "  Queue:    $SOLACE_QUEUE"
    echo ""
}

# Print Oracle connection info
show_oracle_info() {
    echo "Oracle Connection:"
    echo "  Host:     $ORACLE_HOST"
    echo "  Port:     $ORACLE_PORT"
    echo "  Service:  $ORACLE_SERVICE"
    echo "  User:     $ORACLE_USER"
    echo ""
}

# Common Solace connection arguments
solace_args() {
    echo "-H $SOLACE_HOST -v $SOLACE_VPN -u $SOLACE_USER -p $SOLACE_PASS -q $SOLACE_QUEUE"
}

# Common Oracle connection arguments
oracle_args() {
    echo "--db-host $ORACLE_HOST --db-port $ORACLE_PORT --db-service $ORACLE_SERVICE --db-user $ORACLE_USER --db-password $ORACLE_PASS"
}

# Pause for user
pause() {
    echo ""
    read -p "Press Enter to continue..."
}
