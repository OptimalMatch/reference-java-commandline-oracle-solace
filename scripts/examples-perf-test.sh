#!/bin/bash
# =============================================================================
# Performance Test Command Examples
# =============================================================================
# Demonstrates performance testing against Solace broker.
#
# Usage: ./examples-perf-test.sh
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "Performance Test Command Examples"
show_connection_info

PERF_QUEUE="test.perf"

# -----------------------------------------------------------------------------
# Basic Publish Performance Test
# -----------------------------------------------------------------------------

subheader "1. Basic Publish Performance Test"

show_cmd "solace-cli perf-test ... --mode publish --count 1000"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode publish \
    --count 1000

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$PERF_QUEUE" -t 5 -n 10000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Consume Performance Test
# -----------------------------------------------------------------------------

subheader "2. Consume Performance Test"

# First, publish messages
info "Publishing 1000 messages for consume test..."
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode publish \
    --count 1000 2>&1 | tail -3

show_cmd "solace-cli perf-test ... --mode consume --count 1000"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode consume \
    --count 1000

# -----------------------------------------------------------------------------
# Bidirectional Test (Publish and Consume)
# -----------------------------------------------------------------------------

subheader "3. Bidirectional Test (Publish + Consume)"

show_cmd "solace-cli perf-test ... --mode both --count 1000"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode both \
    --count 1000

# -----------------------------------------------------------------------------
# With Latency Measurement
# -----------------------------------------------------------------------------

subheader "4. Latency Measurement Test"

show_cmd "solace-cli perf-test ... --mode both --count 500 --latency"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode both \
    --count 500 \
    --latency

# -----------------------------------------------------------------------------
# Custom Message Size
# -----------------------------------------------------------------------------

subheader "5. Test with Larger Messages (1KB)"

show_cmd "solace-cli perf-test ... --mode publish --count 500 --size 1024"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode publish \
    --count 500 \
    --size 1024

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$PERF_QUEUE" -t 5 -n 10000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Rate Limited Test
# -----------------------------------------------------------------------------

subheader "6. Rate Limited Test (100 msg/s)"

show_cmd "solace-cli perf-test ... --mode publish --count 300 --rate 100"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode publish \
    --count 300 \
    --rate 100

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$PERF_QUEUE" -t 5 -n 10000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# Direct Delivery Mode
# -----------------------------------------------------------------------------

subheader "7. Direct Delivery Mode Test"

show_cmd "solace-cli perf-test ... --mode both --count 1000 --delivery-mode DIRECT"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode both \
    --count 1000 \
    --delivery-mode DIRECT

# -----------------------------------------------------------------------------
# Custom Warmup
# -----------------------------------------------------------------------------

subheader "8. Test with Extended Warmup"

show_cmd "solace-cli perf-test ... --mode both --count 500 --warmup 200"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode both \
    --count 500 \
    --warmup 200

# -----------------------------------------------------------------------------
# Multi-threaded Publishing
# -----------------------------------------------------------------------------

subheader "9. Multi-threaded Publisher Test"

show_cmd "solace-cli perf-test ... --mode publish --count 1000 --threads 4"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode publish \
    --count 1000 \
    --threads 4

# Drain queue
solace_cli consume -H "$SOLACE_HOST" -v "$SOLACE_VPN" -u "$SOLACE_USER" -p "$SOLACE_PASS" -q "$PERF_QUEUE" -t 5 -n 10000 > /dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# With Exclusion Filter Overhead
# -----------------------------------------------------------------------------

subheader "10. Test with Exclusion Filtering"

# Create exclusion file
create_exclusion_file

show_cmd "solace-cli perf-test ... --mode both --count 500 --exclude-file exclusions.txt --exclude-rate 20"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode both \
    --count 500 \
    --exclude-file "${DATA_DIR}/exclusions.txt" \
    --exclude-rate 20

# -----------------------------------------------------------------------------
# Full Featured Test
# -----------------------------------------------------------------------------

subheader "11. Full Featured Test"

show_cmd "solace-cli perf-test ... --mode both --count 1000 --size 512 --latency --threads 2 --warmup 100"
solace_cli perf-test \
    -H "$SOLACE_HOST" \
    -v "$SOLACE_VPN" \
    -u "$SOLACE_USER" \
    -p "$SOLACE_PASS" \
    -q "$PERF_QUEUE" \
    --mode both \
    --count 1000 \
    --size 512 \
    --latency \
    --threads 2 \
    --warmup 100

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

header "Performance Test Examples Complete"

echo "Performance test options:"
echo "  --mode          publish, consume, or both"
echo "  --count         Number of messages"
echo "  --size          Message size in bytes"
echo "  --rate          Target messages per second"
echo "  --latency       Measure end-to-end latency"
echo "  --threads       Number of publisher threads"
echo "  --warmup        Warmup message count"
echo "  --delivery-mode PERSISTENT or DIRECT"
echo ""
echo "Tips:"
echo "  - Use --latency with --mode both for end-to-end latency"
echo "  - DIRECT mode has lower latency but no persistence"
echo "  - Multi-threading improves publish throughput"
echo "  - Use --rate to simulate real-world load patterns"
echo ""
