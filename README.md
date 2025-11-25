# Solace CLI

A command-line tool for publishing and consuming messages from Solace queues using the JCSMP API.

## Features

- **Publish** messages to Solace queues with configurable delivery modes
- **Consume** messages with auto/manual acknowledgment
- **Browse** queue messages non-destructively  
- Support for reading message content from stdin, file, or command line
- Verbose output with message metadata
- Write received messages to files

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Access to a Solace message broker

## Building

```bash
# Clone or copy the project
cd solace-cli

# Build executable JAR with all dependencies
mvn clean package

# The JAR will be at target/solace-cli-1.0.0.jar
```

## Usage

### General Help

```bash
java -jar target/solace-cli-1.0.0.jar --help
```

### Connection Options (required for all commands)

| Option | Description |
|--------|-------------|
| `-H, --host` | Solace broker host (e.g., `tcp://localhost:55555`) |
| `-v, --vpn` | Message VPN name |
| `-u, --username` | Username for authentication |
| `-p, --password` | Password (interactive prompt if omitted) |
| `-q, --queue` | Queue name |

### Publishing Messages

```bash
# Publish a simple message
java -jar target/solace-cli-1.0.0.jar publish \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  "Hello, Solace!"

# Publish from a file
java -jar target/solace-cli-1.0.0.jar publish \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  -f message.xml

# Publish from stdin (useful for piping)
echo "Message from pipe" | java -jar target/solace-cli-1.0.0.jar publish \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue

# Publish multiple messages
java -jar target/solace-cli-1.0.0.jar publish \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  -c 100 \
  "Test message"

# Publish with correlation ID and TTL
java -jar target/solace-cli-1.0.0.jar publish \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  --correlation-id "order-12345" \
  --ttl 60000 \
  "Order data"
```

#### Publish Options

| Option | Description |
|--------|-------------|
| `-f, --file` | Read message from file |
| `-c, --count` | Number of messages to send (default: 1) |
| `--correlation-id` | Set correlation ID |
| `--delivery-mode` | PERSISTENT (default) or DIRECT |
| `--ttl` | Time to live in milliseconds |

### Consuming Messages

```bash
# Consume messages (Ctrl+C to stop)
java -jar target/solace-cli-1.0.0.jar consume \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue

# Consume specific number of messages
java -jar target/solace-cli-1.0.0.jar consume \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  -n 10

# Consume with timeout
java -jar target/solace-cli-1.0.0.jar consume \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  -t 30

# Browse messages (non-destructive)
java -jar target/solace-cli-1.0.0.jar consume \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  --browse

# Verbose output with metadata
java -jar target/solace-cli-1.0.0.jar consume \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  --verbose

# Save messages to files
java -jar target/solace-cli-1.0.0.jar consume \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  -o ./received-messages
```

#### Consume Options

| Option | Description |
|--------|-------------|
| `-n, --count` | Number of messages to consume (0 = unlimited) |
| `-t, --timeout` | Timeout in seconds (0 = wait forever) |
| `--browse` | Browse without consuming |
| `--no-ack` | Don't acknowledge messages |
| `-V, --verbose` | Show message metadata |
| `-o, --output-dir` | Write payloads to files |

## Creating a Shell Alias

Add to your `~/.bashrc` or `~/.zshrc`:

```bash
alias solace-cli='java -jar /path/to/solace-cli-1.0.0.jar'

# Then use:
solace-cli publish -H tcp://localhost:55555 -v default -u admin -p admin -q test "Hello"
```

## Environment Variables (Optional Enhancement)

You can modify the source to support environment variables for connection defaults:

```bash
export SOLACE_HOST=tcp://localhost:55555
export SOLACE_VPN=default
export SOLACE_USERNAME=admin
export SOLACE_PASSWORD=admin
```

## Example Use Cases

### SWIFT Message Processing
```bash
# Publish a SWIFT MT540 message
java -jar target/solace-cli-1.0.0.jar publish \
  -H tcp://solace-prod:55555 \
  -v swift-vpn \
  -u swift-user \
  -p "$SWIFT_PASSWORD" \
  -q SWIFT.MT540.INBOUND \
  -f swift-message.txt \
  --correlation-id "SWIFT-$(date +%Y%m%d%H%M%S)"
```

### Queue Monitoring
```bash
# Browse to check queue depth without consuming
java -jar target/solace-cli-1.0.0.jar consume \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q my-queue \
  --browse \
  --verbose \
  -n 5
```

### Load Testing
```bash
# Send 1000 messages
java -jar target/solace-cli-1.0.0.jar publish \
  -H tcp://localhost:55555 \
  -v default \
  -u admin \
  -p admin \
  -q test-queue \
  -c 1000 \
  "Load test message"
```

## Troubleshooting

### Connection Refused
- Verify the broker host and port
- Check VPN name spelling
- Ensure the queue exists and is enabled

### Authentication Failed
- Verify username and password
- Check user permissions on the VPN

### Message Not Appearing
- Verify queue name (case-sensitive)
- Check if queue has consumers with selectors
- Use `--browse` to inspect without consuming

## License

MIT
