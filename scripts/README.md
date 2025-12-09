# Solace CLI Example Scripts

This directory contains bash scripts demonstrating all features of the Solace CLI tool, including an **interactive wizard** for guided usage.

## Quick Start with Wizard

For the easiest experience, use the interactive wizard:

```bash
./wizard.sh
```

The wizard provides a menu-driven interface that guides you through all CLI operations.

### Main Menu

```
  ____        _                   ____ _     ___
 / ___|  ___ | | __ _  ___ ___   / ___| |   |_ _|
 \___ \ / _ \| |/ _` |/ __/ _ \ | |   | |    | |
  ___) | (_) | | (_| | (_|  __/ | |___| |___ | |
 |____/ \___/|_|\__,_|\___\___| \____|_____|___|

Interactive Wizard
==============================================

● Connected to: tcp://localhost:55555 (VPN: default)

What would you like to do?

  Messages
    1) Publish message
    2) Consume messages
    3) Folder publish (batch)
    4) Copy/Move queue

  Orchestration
    5) Queue orchestration (consume->transform->publish)
    6) Oracle orchestration (query->transform->publish)

  Testing
    7) Performance test

  Oracle Integration
    8) Oracle operations

  Setup
    9) Configure connection
    s) Queue setup (SEMP)

  Other
    h) Help
    0) Exit

Enter choice:
```

### Wizard Features

| Option | Description |
|--------|-------------|
| **1) Publish message** | Send messages manually, from file, or generate test messages |
| **2) Consume messages** | Receive messages with browse, consume, or no-ack modes |
| **3) Folder publish** | Batch publish all files from a directory |
| **4) Copy/Move queue** | Transfer messages between queues |
| **5) Queue orchestration** | Consume → Transform → Publish workflow |
| **6) Oracle orchestration** | Oracle Query → Transform → Publish workflow |
| **7) Performance test** | Run throughput and latency benchmarks |
| **8) Oracle operations** | Database integration (publish, export, insert) |
| **9) Configure connection** | Change Solace host, VPN, credentials, and SSL/TLS settings |
| **s) Queue setup** | Create/delete queues via SEMP API |

### Example: Publishing Messages

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Publish Message
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Target queue [demo.queue]:

Message source:
How do you want to provide the message?

  1) Type message content
  2) Read from file
  3) Generate test messages

Enter choice (1-3): 3
Number of messages [10]:

Configure advanced options? [y/N]:

Executing:
solace-cli publish -H tcp://localhost:55555 -v default -u admin -p **** -q demo.queue -c 10
```

### Example: Consuming Messages

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Consume Messages
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Source queue [demo.queue]:

Consume mode:

  1) Consume (remove from queue)
  2) Browse (non-destructive)
  3) Consume without acknowledgment

Enter choice (1-3): 2
Number of messages (0=unlimited) [10]:
Timeout in seconds (0=wait forever) [30]:

Save messages to files? [y/N]:
Show verbose output with metadata? [Y/n]:
```

### Example: Queue Setup via SEMP

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Queue Setup (SEMP API)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SEMP API host [localhost]:
SEMP API port [8095]:
SEMP admin username [admin]:
SEMP admin password [admin]:

Action:

  1) Create demo queues
  2) Check queue status
  3) Delete demo queues

Enter choice (1-3):
```

### Example: SSL/TLS Configuration

The wizard supports SSL/TLS connections for secure broker communication:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Configure Connection
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Current: tcp://localhost:55555

Solace host URL [tcp://localhost:55555]: tcps://solace-broker:55443
Message VPN [default]: production
Username [admin]:
Password [****]:

Enable SSL/TLS? [Y/n]: y

SSL/TLS Authentication:

  1) Trust store only (server validation)
  2) Key store (PKCS12/JKS client certificate)
  3) PEM files (client cert + key)
  4) Skip certificate validation (dev only)

Enter choice (1-4): 2

Key store file: /path/to/client.p12
Key store password: ********
Trust store file [optional]: /path/to/truststore.jks
Trust store password: ********
TLS version [TLSv1.2]: TLSv1.3

Testing connection...
✓ Connected successfully with SSL/TLS
```

The wizard automatically:
- Validates connection settings on startup
- Shows the current connection status (including SSL indicator)
- Provides sensible defaults for all prompts
- Displays the exact CLI command being executed
- Returns to the main menu after each operation

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
| `wizard.sh` | **Interactive wizard** - guided menu-driven interface |
| `common.sh` | Shared configuration and helper functions (source this) |
| `setup-solace.sh` | Create/delete queues via SEMP API |
| `solace-docker.sh` | Start/stop local Solace broker Docker container |
| `orchestrate.sh` | **Orchestration** - Consume → Transform → Publish workflow |
| `orchestrate-oracle.sh` | **Oracle Orchestration** - Query → Transform → Publish workflow |
| `test-oracle-orchestration.sh` | Test script for Oracle orchestration (starts Oracle Docker) |
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

# SSL/TLS Settings (optional)
export SOLACE_SSL=false                    # Enable SSL/TLS
export SOLACE_KEY_STORE=/path/to/client.p12
export SOLACE_KEY_STORE_PASSWORD=password
export SOLACE_TRUST_STORE=/path/to/truststore.jks
export SOLACE_TRUST_STORE_PASSWORD=password
export SOLACE_CLIENT_CERT=/path/to/client.pem
export SOLACE_CLIENT_KEY=/path/to/client.key
export SOLACE_CA_CERT=/path/to/ca.pem
export SOLACE_SKIP_CERT_VALIDATION=false
export SOLACE_TLS_VERSION=TLSv1.2

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

## Orchestration Scripts

### orchestrate.sh

End-to-end message orchestration: **Consume → Transform → Publish**

```bash
# Basic usage
./orchestrate.sh -s source.queue -d dest.queue

# With all options
./orchestrate.sh \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -s input.queue \
  -d output.queue \
  -n 100 \
  -t 30 \
  --verbose \
  --no-cleanup
```

**Workflow:**
1. **Consume** - Read messages from source queue, save to files
2. **Transform** - Apply customizable transformation to each file
3. **Publish** - Send transformed files to destination queue

**Key Options:**
| Option | Description |
|--------|-------------|
| `-s, --source-queue` | Source queue to consume from |
| `-d, --dest-queue` | Destination queue to publish to |
| `-n, --count` | Number of messages to process (0=all) |
| `-t, --timeout` | Consume timeout in seconds |
| `--browse` | Browse only (non-destructive) |
| `-w, --work-dir` | Working directory for files |
| `--dry-run` | Preview without executing |
| `--verbose` | Enable verbose output |
| `--no-cleanup` | Keep files after processing |
| `--config` | Load settings from config file |

**Customizing the Transform:**

Edit the `transform_message()` function in `orchestrate.sh`:

```bash
transform_message() {
    local input_file="$1"
    local output_file="$2"

    # Example: Add JSON wrapper
    echo '{"transformed": true, "data":' > "$output_file"
    cat "$input_file" >> "$output_file"
    echo '}' >> "$output_file"
}
```

### orchestrate-oracle.sh

Oracle-to-Solace orchestration: **Query → Transform → Publish**

```bash
# Basic usage
./orchestrate-oracle.sh \
  --sql "SELECT payload FROM messages" \
  -d output.queue

# With custom columns
./orchestrate-oracle.sh \
  --db-host localhost \
  --db-port 1521 \
  --db-service ORCL \
  --db-user scott \
  --db-password tiger \
  --sql "SELECT id, content FROM orders" \
  --message-column content \
  --filename-column id \
  -H tcp://localhost:55555 \
  -d orders.queue \
  --verbose
```

**Workflow:**
1. **Oracle Export** - Execute SQL query, save each row as a file
2. **Transform** - Apply customizable transformation to each file
3. **Publish** - Send transformed files to Solace queue

**Key Options:**
| Option | Description |
|--------|-------------|
| `--db-host` | Oracle database host |
| `--db-port` | Oracle database port (default: 1521) |
| `--db-service` | Oracle service name or SID |
| `--db-user` | Database username |
| `--db-password` | Database password |
| `-s, --sql` | SQL SELECT statement |
| `-f, --sql-file` | SQL file path |
| `-m, --message-column` | Column containing message content |
| `--filename-column` | Column to use as filename |
| `-d, --dest-queue` | Destination Solace queue |
| `--dry-run` | Preview without executing |

### test-orchestration.sh

Automated test script for queue orchestration:

```bash
# Run full test suite
./test-orchestration.sh

# Skip cleanup of test files and queue messages
./test-orchestration.sh --skip-cleanup

# Use custom queues
SOURCE_QUEUE=my.source DEST_QUEUE=my.dest ./test-orchestration.sh
```

**What it does:**
1. Checks prerequisites (JAR, Solace broker connectivity)
2. Tests basic orchestration (consume → transform → publish)
3. Tests browse mode (non-destructive)
4. Tests dry-run mode
5. Tests correlation ID as filename
6. Verifies messages in destination queue
7. Tests larger batch (10 messages)
8. Cleans up test files and drains queues

**Prerequisites:**
- Solace broker running (default: `tcp://localhost:55555`)
- Project JAR built (`mvn clean package`)
- Demo queues created (`./setup-solace.sh create`)

**Sample Output:**
```
=============================================================================
  Queue Orchestration Test Suite
=============================================================================
Configuration:
  Solace Host:      tcp://localhost:55555
  Source Queue:     demo.queue
  Destination Queue: demo.queue.backup

Step 1: Testing Basic Orchestration
[INFO] Published 3 messages
[INFO] Transform complete: 3 succeeded, 0 failed
[INFO] Published 3 message(s) successfully

Step 2: Testing Browse Mode (Non-Destructive)
[INFO] Browse mode verified: messages not removed from source queue

Step 3: Testing Dry Run Mode
[INFO] Dry run verified: no input files created

Step 4: Testing Correlation ID as Filename
[INFO] Correlation ID filenames verified (ORDER-001.txt, ORDER-002.txt)

Step 5: Verifying Destination Queue
[INFO] Browsed messages successfully

Step 6: Testing Larger Batch
[INFO] Batch test passed: all 10 messages processed

Step 7: Cleanup
[INFO] Cleanup complete

=============================================================================
  Test Suite Complete
=============================================================================
[INFO] All tests passed!
[INFO] Total duration: 30s
```

### test-oracle-orchestration.sh

Automated test script for Oracle orchestration:

```bash
# Run full test suite (starts Oracle Docker automatically)
./test-oracle-orchestration.sh

# Keep Oracle container running after tests
./test-oracle-orchestration.sh --keep-oracle

# Skip cleanup of test files
./test-oracle-orchestration.sh --skip-cleanup

# Use custom test queue
TEST_QUEUE=my.test.queue ./test-oracle-orchestration.sh
```

**What it does:**
1. Starts Oracle XE Docker container (`gvenzl/oracle-xe:21-slim`)
2. Creates test table with 5 sample order records
3. Tests basic orchestration (default column mapping)
4. Tests custom column mapping (order_id as filename)
5. Verifies messages in Solace queue
6. Tests dry-run mode
7. Cleans up Oracle container and test files

**Prerequisites:**
- Docker installed and running
- Solace broker running (default: `tcp://localhost:55555`)
- Project JAR built (`mvn clean package`)

**Sample Output:**
```
=============================================================================
  Oracle Orchestration Test Suite
=============================================================================
Configuration:
  Oracle Container: oracle-orchestration-test
  Oracle Port:      1522
  Oracle Service:   XEPDB1
  Solace Host:      tcp://localhost:55555
  Test Queue:       demo.queue

Step 1: Starting Oracle XE Container
[INFO] Oracle is ready!

Step 2: Creating Test Data
[INFO] Test data created successfully (5 rows)

Step 3: Testing Basic Oracle Orchestration
[INFO] Exported 5 row(s) to files
[INFO] Transform complete: 5 succeeded, 0 failed
[INFO] Published 5 message(s) successfully

Step 4: Testing Custom Column Mapping
[INFO] Files named by order_id: ORD-001.txt, ORD-002.txt, ...

Step 5: Verifying Messages in Solace Queue
[INFO] Browsed 10 message(s)

Step 6: Testing Dry Run Mode
[INFO] Dry run completed (no actual changes made)

Step 7: Cleanup
[INFO] Oracle container removed

=============================================================================
  Test Suite Complete
=============================================================================
[INFO] All tests passed!
[INFO] Total duration: 25s
```

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

### SSL/TLS connection errors
- Ensure the host URL uses `tcps://` (not `tcp://`)
- Verify certificate files exist and have correct permissions
- Check certificate format matches the option:
  - `--key-store` / `--trust-store`: PKCS12 (.p12) or JKS (.jks)
  - `--client-cert` / `--client-key` / `--ca-cert`: PEM format
- For testing with self-signed certs, use `--skip-cert-validation`
- Verify TLS version is supported by both client and broker

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
