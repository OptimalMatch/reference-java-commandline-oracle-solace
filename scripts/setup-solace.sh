#!/bin/bash
# =============================================================================
# Solace Queue Setup via SEMP API
# =============================================================================
# This script provisions queues and configuration on a Solace broker using
# the SEMP (Solace Element Management Protocol) REST API.
#
# Prerequisites:
#   - Solace PubSub+ broker running (Docker or appliance)
#   - curl installed
#   - SEMP API enabled (default on Docker images)
#
# Usage:
#   ./setup-solace.sh [create|delete|status]
#
# =============================================================================

set -e

# Source common configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# SEMP API Configuration
SEMP_HOST="${SEMP_HOST:-localhost}"
SEMP_PORT="${SEMP_PORT:-8080}"
SEMP_USER="${SEMP_USER:-admin}"
SEMP_PASS="${SEMP_PASS:-admin}"
SEMP_BASE_URL="http://${SEMP_HOST}:${SEMP_PORT}/SEMP/v2/config"
MSG_VPN="${SOLACE_VPN:-default}"

# Queue names to create
QUEUES=(
    "demo.queue"
    "demo.queue.backup"
    "orders.inbound"
    "orders.processing"
    "orders.dlq"
    "orders.audit"
    "test.perf"
    "test.connectivity"
    "messages.retry"
)

# -----------------------------------------------------------------------------
# SEMP API Helper Functions
# -----------------------------------------------------------------------------

semp_get() {
    local endpoint="$1"
    curl -s -X GET \
        -u "${SEMP_USER}:${SEMP_PASS}" \
        "${SEMP_BASE_URL}${endpoint}"
}

semp_post() {
    local endpoint="$1"
    local data="$2"
    curl -s -X POST \
        -u "${SEMP_USER}:${SEMP_PASS}" \
        -H "Content-Type: application/json" \
        -d "$data" \
        "${SEMP_BASE_URL}${endpoint}"
}

semp_delete() {
    local endpoint="$1"
    curl -s -X DELETE \
        -u "${SEMP_USER}:${SEMP_PASS}" \
        "${SEMP_BASE_URL}${endpoint}"
}

semp_patch() {
    local endpoint="$1"
    local data="$2"
    curl -s -X PATCH \
        -u "${SEMP_USER}:${SEMP_PASS}" \
        -H "Content-Type: application/json" \
        -d "$data" \
        "${SEMP_BASE_URL}${endpoint}"
}

# -----------------------------------------------------------------------------
# Queue Management Functions
# -----------------------------------------------------------------------------

create_queue() {
    local queue_name="$1"
    local max_msg_size="${2:-10000000}"  # 10MB default
    local max_spool="${3:-100}"          # 100MB default

    info "Creating queue: $queue_name"

    local response
    response=$(semp_post "/msgVpns/${MSG_VPN}/queues" "{
        \"queueName\": \"${queue_name}\",
        \"accessType\": \"exclusive\",
        \"egressEnabled\": true,
        \"ingressEnabled\": true,
        \"permission\": \"consume\",
        \"maxMsgSpoolUsage\": ${max_spool},
        \"maxMsgSize\": ${max_msg_size},
        \"respectTtlEnabled\": true
    }")

    if echo "$response" | grep -q '"responseCode":200'; then
        echo -e "  ${GREEN}Created${NC}"
    elif echo "$response" | grep -q 'ALREADY_EXISTS'; then
        echo -e "  ${YELLOW}Already exists${NC}"
    else
        echo -e "  ${RED}Failed${NC}: $(echo "$response" | grep -o '"description":"[^"]*"' | head -1)"
    fi
}

delete_queue() {
    local queue_name="$1"

    info "Deleting queue: $queue_name"

    local response
    response=$(semp_delete "/msgVpns/${MSG_VPN}/queues/${queue_name}")

    if echo "$response" | grep -q '"responseCode":200'; then
        echo -e "  ${GREEN}Deleted${NC}"
    elif echo "$response" | grep -q 'NOT_FOUND'; then
        echo -e "  ${YELLOW}Not found${NC}"
    else
        echo -e "  ${RED}Failed${NC}: $(echo "$response" | grep -o '"description":"[^"]*"' | head -1)"
    fi
}

get_queue_status() {
    local queue_name="$1"

    local response
    response=$(semp_get "/msgVpns/${MSG_VPN}/queues/${queue_name}")

    if echo "$response" | grep -q '"responseCode":200'; then
        local ingress=$(echo "$response" | grep -o '"ingressEnabled":[^,]*' | cut -d: -f2)
        local egress=$(echo "$response" | grep -o '"egressEnabled":[^,]*' | cut -d: -f2)
        echo -e "  ${queue_name}: ${GREEN}exists${NC} (ingress=${ingress}, egress=${egress})"
    else
        echo -e "  ${queue_name}: ${RED}not found${NC}"
    fi
}

# -----------------------------------------------------------------------------
# Client Username Management
# -----------------------------------------------------------------------------

create_client_username() {
    local username="$1"
    local password="${2:-$username}"

    info "Creating client username: $username"

    local response
    response=$(semp_post "/msgVpns/${MSG_VPN}/clientUsernames" "{
        \"clientUsername\": \"${username}\",
        \"enabled\": true,
        \"password\": \"${password}\"
    }")

    if echo "$response" | grep -q '"responseCode":200'; then
        echo -e "  ${GREEN}Created${NC}"
    elif echo "$response" | grep -q 'ALREADY_EXISTS'; then
        echo -e "  ${YELLOW}Already exists${NC}"
    else
        echo -e "  ${RED}Failed${NC}: $(echo "$response" | grep -o '"description":"[^"]*"' | head -1)"
    fi
}

# -----------------------------------------------------------------------------
# VPN Configuration
# -----------------------------------------------------------------------------

configure_vpn() {
    info "Configuring Message VPN: $MSG_VPN"

    local response
    response=$(semp_patch "/msgVpns/${MSG_VPN}" "{
        \"maxMsgSpoolUsage\": 1500,
        \"maxConnectionCount\": 100,
        \"maxEgressFlowCount\": 1000,
        \"maxIngressFlowCount\": 1000,
        \"maxSubscriptionCount\": 500000,
        \"maxTransactedSessionCount\": 100,
        \"maxTransactionCount\": 5000
    }")

    if echo "$response" | grep -q '"responseCode":200'; then
        echo -e "  ${GREEN}Configured${NC}"
    else
        echo -e "  ${RED}Failed${NC}: $(echo "$response" | grep -o '"description":"[^"]*"' | head -1)"
    fi
}

# -----------------------------------------------------------------------------
# Main Commands
# -----------------------------------------------------------------------------

cmd_create() {
    header "Creating Solace Resources"

    echo "SEMP API: http://${SEMP_HOST}:${SEMP_PORT}"
    echo "Message VPN: ${MSG_VPN}"
    echo ""

    subheader "Configuring Message VPN"
    configure_vpn

    subheader "Creating Client Username"
    create_client_username "${SOLACE_USER}" "${SOLACE_PASS}"

    subheader "Creating Queues"
    for queue in "${QUEUES[@]}"; do
        create_queue "$queue"
    done

    echo ""
    info "Setup complete!"
    echo ""
    echo "You can now use the Solace CLI with:"
    echo "  Host: tcp://${SEMP_HOST}:55555"
    echo "  VPN:  ${MSG_VPN}"
    echo "  User: ${SOLACE_USER}"
    echo ""
}

cmd_delete() {
    header "Deleting Solace Resources"

    echo "SEMP API: http://${SEMP_HOST}:${SEMP_PORT}"
    echo "Message VPN: ${MSG_VPN}"
    echo ""

    subheader "Deleting Queues"
    for queue in "${QUEUES[@]}"; do
        delete_queue "$queue"
    done

    echo ""
    info "Cleanup complete!"
}

cmd_status() {
    header "Solace Resource Status"

    echo "SEMP API: http://${SEMP_HOST}:${SEMP_PORT}"
    echo "Message VPN: ${MSG_VPN}"
    echo ""

    subheader "Queue Status"
    for queue in "${QUEUES[@]}"; do
        get_queue_status "$queue"
    done
}

cmd_check_broker() {
    header "Checking Solace Broker Connectivity"

    echo "Testing SEMP API at http://${SEMP_HOST}:${SEMP_PORT}..."
    echo ""

    local response
    response=$(curl -s -o /dev/null -w "%{http_code}" \
        -u "${SEMP_USER}:${SEMP_PASS}" \
        "${SEMP_BASE_URL}/msgVpns/${MSG_VPN}")

    if [[ "$response" == "200" ]]; then
        echo -e "${GREEN}Broker is accessible${NC}"
        echo ""

        # Get broker info
        local info_response
        info_response=$(semp_get "/msgVpns/${MSG_VPN}")

        local max_spool=$(echo "$info_response" | grep -o '"maxMsgSpoolUsage":[0-9]*' | cut -d: -f2)
        local max_conn=$(echo "$info_response" | grep -o '"maxConnectionCount":[0-9]*' | cut -d: -f2)

        echo "VPN Configuration:"
        echo "  Max Spool Usage: ${max_spool:-unknown} MB"
        echo "  Max Connections: ${max_conn:-unknown}"
    else
        echo -e "${RED}Cannot connect to broker (HTTP $response)${NC}"
        echo ""
        echo "Please check:"
        echo "  1. Solace broker is running"
        echo "  2. SEMP API is enabled on port ${SEMP_PORT}"
        echo "  3. Credentials are correct (${SEMP_USER}:****)"
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# Usage
# -----------------------------------------------------------------------------

usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  create    Create all queues and configure VPN"
    echo "  delete    Delete all demo queues"
    echo "  status    Show status of all queues"
    echo "  check     Check broker connectivity"
    echo ""
    echo "Environment Variables:"
    echo "  SEMP_HOST     SEMP API host (default: localhost)"
    echo "  SEMP_PORT     SEMP API port (default: 8080)"
    echo "  SEMP_USER     SEMP admin username (default: admin)"
    echo "  SEMP_PASS     SEMP admin password (default: admin)"
    echo "  SOLACE_VPN    Message VPN name (default: default)"
    echo ""
    echo "Example:"
    echo "  # Using Docker Solace"
    echo "  docker run -d -p 55555:55555 -p 8080:8080 --name solace solace/solace-pubsub-standard"
    echo "  ./setup-solace.sh create"
    echo ""
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

case "${1:-}" in
    create)
        cmd_check_broker
        cmd_create
        ;;
    delete)
        cmd_delete
        ;;
    status)
        cmd_status
        ;;
    check)
        cmd_check_broker
        ;;
    *)
        usage
        ;;
esac
