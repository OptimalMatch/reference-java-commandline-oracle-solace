# Solace CLI Example Scripts

This directory contains bash scripts demonstrating all features of the Solace CLI tool.

## Prerequisites

1. **Build the JAR first:**
   ```bash
   cd ..
   mvn clean package
   ```

2. **Start Solace broker (Docker):**
   ```bash
   docker run -d -p 55555:55555 -p 8080:8080 \
     --shm-size=2g \
     --name solace \
     solace/solace-pubsub-standard
   ```

3. **Set up queues:**
   ```bash
   ./setup-solace.sh create
   ```

## Quick Start

```bash
# 1. Setup Solace queues
./setup-solace.sh create

# 2. Run publish examples
./examples-publish.sh

# 3. Run consume examples
./examples-consume.sh
```

## Script Overview

| Script | Description |
|--------|-------------|
| `common.sh` | Shared configuration and helper functions (source this) |
| `setup-solace.sh` | Create/delete queues via SEMP API |
| `examples-publish.sh` | Basic message publishing examples |
| `examples-consume.sh` | Message consuming examples |
| `examples-folder-publish.sh` | Batch publish from directory |
| `examples-copy-queue.sh` | Queue-to-queue copy/move |
| `examples-oracle.sh` | Oracle database integration |
| `examples-perf-test.sh` | Performance testing |
| `examples-recovery.sh` | Failure recovery workflows |
| `examples-exclusions.sh` | Exclusion pattern filtering |

## Configuration

### Environment Variables

Set these before running scripts, or edit `common.sh`:

```bash
# Solace Connection
export SOLACE_HOST=tcp://localhost:55555
export SOLACE_VPN=default
export SOLACE_USER=admin
export SOLACE_PASS=admin
export SOLACE_QUEUE=demo.queue

# Oracle Connection (for oracle-* commands)
export ORACLE_HOST=localhost
export ORACLE_PORT=1521
export ORACLE_SERVICE=ORCL
export ORACLE_USER=scott
export ORACLE_PASS=tiger

# SEMP API (for setup-solace.sh)
export SEMP_HOST=localhost
export SEMP_PORT=8080
export SEMP_USER=admin
export SEMP_PASS=admin
```

### Data Directories

Scripts use these directories (configurable via `DATA_DIR`):

```
/tmp/solace-cli-demo/
├── audit.log          # Audit log file
├── messages/          # Sample message files
├── output/            # Consumed message output
├── failed/            # Failed messages for retry
├── retry/             # Messages being retried
└── exclusions.txt     # Exclusion patterns
```

## Setup Scripts

### setup-solace.sh

Manages Solace resources via SEMP REST API:

```bash
# Check broker connectivity
./setup-solace.sh check

# Create all demo queues
./setup-solace.sh create

# Show queue status
./setup-solace.sh status

# Delete demo queues
./setup-solace.sh delete
```

**Queues Created:**
- `demo.queue` - Primary demo queue
- `demo.queue.backup` - Backup/fan-out queue
- `orders.inbound` - Order processing
- `orders.processing` - In-progress orders
- `orders.dlq` - Dead letter queue
- `orders.audit` - Audit trail
- `test.perf` - Performance testing
- `test.connectivity` - Connectivity tests
- `messages.retry` - Retry queue

## Example Scripts

### examples-publish.sh

Demonstrates message publishing:
- Simple message publishing
- Publishing from file
- Publishing from stdin (pipe)
- Multiple messages (`-c`)
- Correlation IDs
- TTL (time to live)
- Delivery modes (PERSISTENT/DIRECT)
- Fan-out to second queue (`-Q`)
- Audit logging

### examples-consume.sh

Demonstrates message consumption:
- Basic consumption with count limit
- Browse mode (non-destructive)
- Verbose output with metadata
- Timeout handling
- Save to files
- Custom file naming
- Correlation ID as filename
- No-ack mode
- Audit logging

### examples-folder-publish.sh

Demonstrates batch publishing:
- Publish all files from directory
- Pattern filtering (`*.json`, `*.xml`)
- Filename as correlation ID
- Dry run mode
- File sorting
- Fan-out to multiple queues
- Exclusion patterns
- Recursive directory scan
- Failed message persistence

### examples-copy-queue.sh

Demonstrates queue operations:
- Copy messages (browse mode)
- Move messages (destructive)
- Dry run preview
- Preserve message properties
- Delivery mode override
- Exclusion filtering
- DLQ reprocessing workflow

### examples-oracle.sh

Demonstrates Oracle integration:
- oracle-publish: Query → Publish
- oracle-export: Query → Files
- oracle-insert: Files → Table
- SQL file support
- Column mapping
- Dry run mode
- Two-step workflow

**Note:** Requires Oracle database. Script shows command syntax.

### examples-perf-test.sh

Demonstrates performance testing:
- Publish throughput
- Consume throughput
- Bidirectional testing
- Latency measurement
- Custom message sizes
- Rate limiting
- Multi-threaded publishing
- Exclusion filter overhead

### examples-recovery.sh

Demonstrates failure recovery:
- `--failed-dir` for persistence
- `--retry-dir` for retry
- File format explanation
- Combined workflows
- Audit logging integration

### examples-exclusions.sh

Demonstrates pattern filtering:
- Exclusion file format
- Exact match patterns
- Wildcard patterns (`*`, `?`)
- Regex patterns (`regex:`)
- Content-based exclusion
- Command coverage table

## Running All Examples

```bash
# Setup
./setup-solace.sh create

# Run all Solace examples (no Oracle required)
./examples-publish.sh
./examples-consume.sh
./examples-folder-publish.sh
./examples-copy-queue.sh
./examples-perf-test.sh
./examples-recovery.sh
./examples-exclusions.sh

# Oracle examples (requires database)
./examples-oracle.sh

# Cleanup
./setup-solace.sh delete
```

## Troubleshooting

### "JAR not found"
Build the project first:
```bash
cd .. && mvn clean package
```

### "Connection refused"
Start Solace broker:
```bash
docker start solace
# or
docker run -d -p 55555:55555 -p 8080:8080 --name solace solace/solace-pubsub-standard
```

### "Queue not found"
Create queues:
```bash
./setup-solace.sh create
```

### SEMP API errors
Check SEMP is enabled and credentials are correct:
```bash
curl -u admin:admin http://localhost:8080/SEMP/v2/config/msgVpns/default
```

## Customization

### Adding New Queues

Edit `setup-solace.sh` and add to the `QUEUES` array:
```bash
QUEUES=(
    "demo.queue"
    "your.new.queue"
    # ...
)
```

### Changing Defaults

Edit `common.sh` to change default values for all scripts.

### Creating New Example Scripts

```bash
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

check_jar
setup_dirs

header "My Example Script"
show_connection_info

# Your examples here...
solace_cli publish $(solace_args) "Hello"
```
