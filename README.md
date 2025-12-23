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

## Transport Demo (Step 2)

This demo starts a TCP server on `p2pPort`, performs `HELLO` handshake, and supports sending JSON-Lines framed test chat messages.

1. **Build:**
   ```bash
   mvn package
   ```

2. **Run two processes:**
   **Terminal 1 (Node1):**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.DemoMain 19000 Node1
   ```

   **Terminal 2 (Node2):**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.DemoMain 19002 Node2
   ```

3. **Send messages:**
   In Node1 terminal:
   ```text
   /send 127.0.0.1 19002 hello
   /senddup 127.0.0.1 19002 dup_test
   ```

   Node2 should print:
   - `Node1: hello`
   - `Node1: dup_test` (only once, dedup by `msgId`)

## Private Chat Demo (Step 3)

This demo implements:
- Private chat `CHAT` + `ACK(DELIVERED)` flow
- SQLite persistence for conversations and messages
- `seen_messages` dedup (duplicate `msgId` won't be stored/printed twice)

1. **Build:**
   ```bash
   mvn package
   ```

2. **Run two nodes (use different DB files):**
   **Terminal A:**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.demo.ChatCliMain 19001 Alice dataA.db
   ```

   **Terminal B:**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.demo.ChatCliMain 19002 Bob dataB.db
   ```

3. **Send a private message (by ip:port):**
   In Terminal A:
   ```text
   /send 127.0.0.1:19002 hi
   ```
   Terminal A should print `ACK DELIVERED for msgId=...` after Bob replies ACK.

4. **Verify persistence (restart and view history):**
   In Terminal A:
   ```text
   /convs
   /history <convId> 20
   ```
   Exit and restart Terminal A, run `/history <convId> 20` again to confirm messages are still in SQLite.

5. **Verify dedup:**
   In Terminal A:
   ```text
   /senddup 127.0.0.1:19002 dup_test
   ```
   Terminal B should print `Alice: dup_test` only once.
