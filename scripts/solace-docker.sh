#!/bin/bash
# =============================================================================
# Solace PubSub+ Docker Management Script
# =============================================================================
# Deploy and manage a local Solace PubSub+ Standard Edition broker via Docker
# for development and testing purposes.
#
# Usage:
#   ./solace-docker.sh [start|stop|restart|status|logs|shell|clean]
#
# =============================================================================

set -e

# Source common configuration if available
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${SCRIPT_DIR}/common.sh" ]]; then
    source "${SCRIPT_DIR}/common.sh"
else
    # Fallback colors if common.sh not available
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    NC='\033[0m'
    info() { echo -e "${GREEN}[INFO]${NC} $1"; }
    warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
    error() { echo -e "${RED}[ERROR]${NC} $1"; }
    header() {
        echo ""
        echo -e "${BLUE}=============================================================================${NC}"
        echo -e "${BLUE}  $1${NC}"
        echo -e "${BLUE}=============================================================================${NC}"
        echo ""
    }
fi

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

# Container settings
CONTAINER_NAME="${SOLACE_CONTAINER_NAME:-solace-pubsub}"
SOLACE_IMAGE="${SOLACE_IMAGE:-solace/solace-pubsub-standard:10.8.1}"

# Port mappings
# SMF (Solace Message Format) - plain text messaging
PORT_SMF="${SOLACE_PORT_SMF:-55555}"
# SMF Compressed
PORT_SMF_COMPRESSED="${SOLACE_PORT_SMF_COMPRESSED:-55003}"
# SMF SSL/TLS
PORT_SMF_SSL="${SOLACE_PORT_SMF_SSL:-55443}"
# SEMP Management API
PORT_SEMP="${SOLACE_PORT_SEMP:-8080}"
# SEMP SSL
PORT_SEMP_SSL="${SOLACE_PORT_SEMP_SSL:-1943}"
# Web Transport
PORT_WEB="${SOLACE_PORT_WEB:-8008}"
# Web Transport SSL
PORT_WEB_SSL="${SOLACE_PORT_WEB_SSL:-1443}"
# AMQP
PORT_AMQP="${SOLACE_PORT_AMQP:-5672}"
# AMQP SSL
PORT_AMQP_SSL="${SOLACE_PORT_AMQP_SSL:-5671}"
# MQTT
PORT_MQTT="${SOLACE_PORT_MQTT:-1883}"
# MQTT SSL
PORT_MQTT_SSL="${SOLACE_PORT_MQTT_SSL:-8883}"
# REST
PORT_REST="${SOLACE_PORT_REST:-9000}"
# REST SSL
PORT_REST_SSL="${SOLACE_PORT_REST_SSL:-9443}"

# Broker settings
ADMIN_USER="${SOLACE_ADMIN_USER:-admin}"
ADMIN_PASS="${SOLACE_ADMIN_PASS:-admin}"
MAX_SPOOL_MB="${SOLACE_MAX_SPOOL_MB:-1024}"
SHM_SIZE="${SOLACE_SHM_SIZE:-1g}"

# Data persistence (optional)
DATA_DIR="${SOLACE_DATA_DIR:-}"

# -----------------------------------------------------------------------------
# Helper Functions
# -----------------------------------------------------------------------------

check_docker() {
    if ! command -v docker &> /dev/null; then
        error "Docker is not installed or not in PATH"
        echo "Please install Docker: https://docs.docker.com/get-docker/"
        exit 1
    fi

    if ! docker info &> /dev/null; then
        error "Docker daemon is not running"
        echo "Please start the Docker daemon"
        exit 1
    fi
}

container_exists() {
    docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"
}

container_running() {
    docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"
}

wait_for_broker() {
    local max_attempts=60
    local attempt=1

    info "Waiting for Solace broker to be ready..."

    while [[ $attempt -le $max_attempts ]]; do
        if curl -s -o /dev/null -w "%{http_code}" \
            -u "${ADMIN_USER}:${ADMIN_PASS}" \
            "http://localhost:${PORT_SEMP}/SEMP/v2/config/msgVpns/default" 2>/dev/null | grep -q "200"; then
            echo ""
            info "Broker is ready!"
            return 0
        fi

        printf "."
        sleep 2
        ((attempt++))
    done

    echo ""
    error "Broker did not become ready within $((max_attempts * 2)) seconds"
    return 1
}

print_connection_info() {
    echo ""
    echo -e "${GREEN}Solace PubSub+ is running!${NC}"
    echo ""
    echo "Connection Details:"
    echo "  -------------------------------------------------------------------------"
    echo "  SMF (messaging):     tcp://localhost:${PORT_SMF}"
    echo "  SMF SSL:             tcps://localhost:${PORT_SMF_SSL}"
    echo "  SEMP (management):   http://localhost:${PORT_SEMP}"
    echo "  Web Console:         http://localhost:${PORT_SEMP}"
    echo "  -------------------------------------------------------------------------"
    echo ""
    echo "Credentials:"
    echo "  Admin User:          ${ADMIN_USER}"
    echo "  Admin Password:      ${ADMIN_PASS}"
    echo "  Message VPN:         default"
    echo ""
    echo "Additional Ports (if needed):"
    echo "  AMQP:                localhost:${PORT_AMQP} (SSL: ${PORT_AMQP_SSL})"
    echo "  MQTT:                localhost:${PORT_MQTT} (SSL: ${PORT_MQTT_SSL})"
    echo "  REST:                localhost:${PORT_REST} (SSL: ${PORT_REST_SSL})"
    echo "  Web Transport:       localhost:${PORT_WEB} (SSL: ${PORT_WEB_SSL})"
    echo ""
    echo "Quick Start:"
    echo "  # Set up queues"
    echo "  ./setup-solace.sh create"
    echo ""
    echo "  # Publish a message"
    echo "  java -jar target/solace-cli-1.0.0.jar publish \\"
    echo "    -H tcp://localhost:${PORT_SMF} -v default -u ${ADMIN_USER} -p ${ADMIN_PASS} \\"
    echo "    -q demo.queue -m \"Hello Solace!\""
    echo ""
}

# -----------------------------------------------------------------------------
# Commands
# -----------------------------------------------------------------------------

cmd_start() {
    header "Starting Solace PubSub+ Docker Container"

    check_docker

    if container_running; then
        info "Container '${CONTAINER_NAME}' is already running"
        print_connection_info
        return 0
    fi

    if container_exists; then
        info "Starting existing container '${CONTAINER_NAME}'..."
        docker start "${CONTAINER_NAME}"
        wait_for_broker
        print_connection_info
        return 0
    fi

    info "Pulling Solace image (if not cached)..."
    docker pull "${SOLACE_IMAGE}"

    info "Creating and starting container '${CONTAINER_NAME}'..."

    # Build port mapping arguments
    local port_args=(
        -p "${PORT_SMF}:55555"
        -p "${PORT_SMF_COMPRESSED}:55003"
        -p "${PORT_SMF_SSL}:55443"
        -p "${PORT_SEMP}:8080"
        -p "${PORT_SEMP_SSL}:1943"
        -p "${PORT_WEB}:8008"
        -p "${PORT_WEB_SSL}:1443"
        -p "${PORT_AMQP}:5672"
        -p "${PORT_AMQP_SSL}:5671"
        -p "${PORT_MQTT}:1883"
        -p "${PORT_MQTT_SSL}:8883"
        -p "${PORT_REST}:9000"
        -p "${PORT_REST_SSL}:9443"
    )

    # Build volume argument if data directory specified
    local volume_args=()
    if [[ -n "${DATA_DIR}" ]]; then
        mkdir -p "${DATA_DIR}"
        volume_args=(-v "${DATA_DIR}:/var/lib/solace")
        info "Using persistent storage at ${DATA_DIR}"
    fi

    docker run -d \
        --name "${CONTAINER_NAME}" \
        --shm-size="${SHM_SIZE}" \
        "${port_args[@]}" \
        "${volume_args[@]}" \
        -e username_admin_globalaccesslevel=admin \
        -e username_admin_password="${ADMIN_PASS}" \
        -e system_scaling_maxconnectioncount=100 \
        -e messagespool_maxspoolusage="${MAX_SPOOL_MB}" \
        "${SOLACE_IMAGE}"

    wait_for_broker
    print_connection_info
}

cmd_stop() {
    header "Stopping Solace PubSub+ Docker Container"

    check_docker

    if ! container_exists; then
        warn "Container '${CONTAINER_NAME}' does not exist"
        return 0
    fi

    if ! container_running; then
        info "Container '${CONTAINER_NAME}' is not running"
        return 0
    fi

    info "Stopping container '${CONTAINER_NAME}'..."
    docker stop "${CONTAINER_NAME}"
    info "Container stopped"
}

cmd_restart() {
    cmd_stop
    cmd_start
}

cmd_status() {
    header "Solace PubSub+ Container Status"

    check_docker

    if ! container_exists; then
        echo -e "Container:  ${RED}Not created${NC}"
        echo ""
        echo "Run './solace-docker.sh start' to create and start the container"
        return 0
    fi

    if container_running; then
        echo -e "Container:  ${GREEN}Running${NC}"
        echo ""

        # Show container details
        docker ps --filter "name=${CONTAINER_NAME}" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | head -2

        echo ""

        # Check SEMP API
        local http_code
        http_code=$(curl -s -o /dev/null -w "%{http_code}" \
            -u "${ADMIN_USER}:${ADMIN_PASS}" \
            "http://localhost:${PORT_SEMP}/SEMP/v2/config/msgVpns/default" 2>/dev/null || echo "000")

        if [[ "$http_code" == "200" ]]; then
            echo -e "SEMP API:   ${GREEN}Accessible${NC} (http://localhost:${PORT_SEMP})"
        else
            echo -e "SEMP API:   ${RED}Not accessible${NC} (HTTP ${http_code})"
        fi

        # Check SMF port
        if nc -z localhost "${PORT_SMF}" 2>/dev/null; then
            echo -e "SMF Port:   ${GREEN}Open${NC} (tcp://localhost:${PORT_SMF})"
        else
            echo -e "SMF Port:   ${RED}Closed${NC}"
        fi
    else
        echo -e "Container:  ${YELLOW}Stopped${NC}"
        echo ""
        echo "Run './solace-docker.sh start' to start the container"
    fi
}

cmd_logs() {
    check_docker

    if ! container_exists; then
        error "Container '${CONTAINER_NAME}' does not exist"
        exit 1
    fi

    local follow=""
    local lines="100"

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -f|--follow)
                follow="-f"
                shift
                ;;
            -n|--lines)
                lines="$2"
                shift 2
                ;;
            *)
                shift
                ;;
        esac
    done

    docker logs ${follow} --tail "${lines}" "${CONTAINER_NAME}"
}

cmd_shell() {
    check_docker

    if ! container_running; then
        error "Container '${CONTAINER_NAME}' is not running"
        exit 1
    fi

    info "Entering Solace CLI shell..."
    echo "Type 'exit' to leave"
    echo ""
    docker exec -it "${CONTAINER_NAME}" /usr/sw/loads/currentload/bin/cli -A
}

cmd_clean() {
    header "Removing Solace PubSub+ Docker Container"

    check_docker

    if ! container_exists; then
        info "Container '${CONTAINER_NAME}' does not exist"
        return 0
    fi

    if container_running; then
        info "Stopping container..."
        docker stop "${CONTAINER_NAME}"
    fi

    info "Removing container '${CONTAINER_NAME}'..."
    docker rm "${CONTAINER_NAME}"

    if [[ -n "${DATA_DIR}" && -d "${DATA_DIR}" ]]; then
        read -p "Remove persistent data at ${DATA_DIR}? (y/N) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            rm -rf "${DATA_DIR}"
            info "Removed ${DATA_DIR}"
        fi
    fi

    info "Cleanup complete"
}

cmd_pull() {
    header "Pulling Latest Solace Image"

    check_docker

    info "Pulling ${SOLACE_IMAGE}..."
    docker pull "${SOLACE_IMAGE}"
    info "Done"
}

# -----------------------------------------------------------------------------
# Usage
# -----------------------------------------------------------------------------

usage() {
    echo "Solace PubSub+ Docker Management Script"
    echo ""
    echo "Usage: $0 [command] [options]"
    echo ""
    echo "Commands:"
    echo "  start       Start the Solace broker (creates container if needed)"
    echo "  stop        Stop the Solace broker"
    echo "  restart     Restart the Solace broker"
    echo "  status      Show container and broker status"
    echo "  logs        Show container logs (use -f to follow)"
    echo "  shell       Open Solace CLI shell"
    echo "  clean       Stop and remove the container"
    echo "  pull        Pull the latest Solace image"
    echo ""
    echo "Options for 'logs':"
    echo "  -f, --follow     Follow log output"
    echo "  -n, --lines N    Number of lines to show (default: 100)"
    echo ""
    echo "Environment Variables:"
    echo "  SOLACE_CONTAINER_NAME   Container name (default: solace-pubsub)"
    echo "  SOLACE_IMAGE            Docker image (default: solace/solace-pubsub-standard:latest)"
    echo "  SOLACE_ADMIN_USER       Admin username (default: admin)"
    echo "  SOLACE_ADMIN_PASS       Admin password (default: admin)"
    echo "  SOLACE_MAX_SPOOL_MB     Max message spool in MB (default: 1024)"
    echo "  SOLACE_DATA_DIR         Persistent data directory (optional)"
    echo ""
    echo "  Port mappings (all configurable via environment):"
    echo "  SOLACE_PORT_SMF         SMF port (default: 55555)"
    echo "  SOLACE_PORT_SMF_SSL     SMF SSL port (default: 55443)"
    echo "  SOLACE_PORT_SEMP        SEMP/Web Console port (default: 8080)"
    echo "  SOLACE_PORT_AMQP        AMQP port (default: 5672)"
    echo "  SOLACE_PORT_MQTT        MQTT port (default: 1883)"
    echo "  SOLACE_PORT_REST        REST port (default: 9000)"
    echo ""
    echo "Examples:"
    echo "  # Start with defaults"
    echo "  $0 start"
    echo ""
    echo "  # Start with custom admin password"
    echo "  SOLACE_ADMIN_PASS=mysecret $0 start"
    echo ""
    echo "  # Start with persistent storage"
    echo "  SOLACE_DATA_DIR=/var/solace/data $0 start"
    echo ""
    echo "  # Use different ports (avoid conflicts)"
    echo "  SOLACE_PORT_SMF=56555 SOLACE_PORT_SEMP=8180 $0 start"
    echo ""
    echo "  # Follow logs"
    echo "  $0 logs -f"
    echo ""
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

case "${1:-}" in
    start)
        cmd_start
        ;;
    stop)
        cmd_stop
        ;;
    restart)
        cmd_restart
        ;;
    status)
        cmd_status
        ;;
    logs)
        shift
        cmd_logs "$@"
        ;;
    shell)
        cmd_shell
        ;;
    clean)
        cmd_clean
        ;;
    pull)
        cmd_pull
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        usage
        ;;
esac
