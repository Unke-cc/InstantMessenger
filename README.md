# LAN Chat P2P Discovery

This project implements the first step of a P2P LAN Chat: **Discovery & Peer Directory**.
It uses UDP Broadcast for discovery and SQLite for local persistence.

## Features
- **UDP Discovery**: Periodically broadcasts presence on port 19001.
- **Peer Directory**: Maintains a list of online peers with `lastSeen` timestamp.
- **Persistence**: Stores local identity and known peers in SQLite (`lanchat_<port>.db`).
- **Bootstrap Probe**: On startup, attempts to TCP connect to previously known peers to verify status.
- **Manual Add**: Supports manually adding a peer via CLI.

## Requirements
- Java 17+
- Maven

## How to Run

1. **Build the project:**
   ```bash
   mvn package
   ```

2. **Run two instances (verification):**
   To verify discovery on the same machine, you can run two processes with different P2P ports.
   (The application handles port conflict on the broadcast port automatically using `SO_REUSEPORT` where supported).

   **Terminal 1 (Node 1):**
   ```bash
   java -jar target/lanchat-core-1.0-SNAPSHOT.jar 19000 Node1
   ```

   **Terminal 2 (Node 2):**
   ```bash
   java -jar target/lanchat-core-1.0-SNAPSHOT.jar 19002 Node2
   ```

3. **Observe Output:**
   - Both terminals should show "Starting LAN Chat Node..."
   - After a few seconds, you should see "Updated peer..." messages.
   - The periodic "Online Peers" list should show the other node.

## CLI Commands
The application accepts commands via standard input:
- `add <ip> <port> <name>`: Manually add a peer.
- `quit`: Stop the application.

## Project Structure
- `com.example.lanchat.core`: Settings and constants.
- `com.example.lanchat.discovery`: UDP broadcast logic (`DiscoveryService`).
- `com.example.lanchat.protocol`: JSON message models.
- `com.example.lanchat.service`: Business logic (`PeerDirectory`).
- `com.example.lanchat.store`: Database access (`Db`, `IdentityDao`, `PeerDao`).
