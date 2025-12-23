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

## Group Chat Demo (Step 4)

This demo implements:
- Room creation + dynamic join (`JOIN_REQUEST` / `JOIN_ACCEPT` / `MEMBER_EVENT`)
- Room message fan-out + SQLite persistence

1. **Build:**
   ```bash
   mvn package
   ```

2. **Run two nodes:**
   **Terminal A:**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.demo.ChatCliMain 19101 Alice dataA.db
   ```

   **Terminal B:**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.demo.ChatCliMain 19102 Bob dataB.db
   ```

3. **Create + join + send:**
   In Terminal A:
   ```text
   /mkroom Room1
   ```
   Copy the `roomId`, then in Terminal B:
   ```text
   /join <roomId> 127.0.0.1:19101
   /rsend <roomId> hello-group
   ```

4. **View room history:**
   ```text
   /rhistory <roomId> 50
   ```

## Local Web UI Demo (Step 5)

This demo starts a local-only Web UI (serves `localhost` only):
- Static UI: `http://localhost:<webPort>/`
- REST API: `http://localhost:<webPort>/api/*`

1. **Build:**
   ```bash
   mvn package
   ```

2. **Run two nodes (different P2P/Web ports + different DB files):**
   **Terminal A (Alice):**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.Launcher 19201 Alice 18201 /tmp/webA.db
   ```

   **Terminal B (Bob):**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.Launcher 19202 Bob 18202 /tmp/webB.db
   ```

3. **Open browsers:**
   - Alice: `http://localhost:18201/`
   - Bob: `http://localhost:18202/`

4. **Verify features:**
   - Peers list shows online/offline
   - Private chat shows `SENT -> DELIVERED` after ACK
   - Alice creates a room, Bob joins with `roomId + inviterIp:port`, then room chat works
   - Restart processes and messages/history remain in SQLite

## Offline Room Sync (Step 6)
dist/
├── LANChat-v1.0.jar       (通过 mvn package 生成的胖 JAR)
├── 启动-Windows.bat       (Windows 双击启动)
├── 启动-macOS.command     (macOS 双击启动)
└── 启动-Linux.sh          (Linux 双击启动)dist/
├── LANChat-v1.0.jar       (通过 mvn package 生成的胖 JAR)
├── 启动-Windows.bat       (Windows 双击启动)
├── 启动-macOS.command     (macOS 双击启动)
└── 启动-Linux.sh          (Linux 双击启动)dist/
├── LANChat-v1.0.jar       (通过 mvn package 生成的胖 JAR)
├── 启动-Windows.bat       (Windows 双击启动)
├── 启动-macOS.command     (macOS 双击启动)
└── 启动-Linux.sh          (Linux 双击启动)
This step adds **best-effort offline room message sync** (no central server):
- Each room maintains a local cursor in SQLite table `room_cursor(room_id, last_clock_value, updated_at)`.
- When a node comes back online / joins a room / opens a room in Web UI, it requests missing room messages from online members.
- Sync is **eventually consistent** and depends on whether online nodes still have the missing messages.

### Web API
- `POST /api/rooms/sync { "roomId": "<uuid>" }` triggers background sync for the room.

### Demo (3 nodes: A/B/C)
1. **Run three nodes:**
   ```bash
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.Launcher 19301 Alice 18301 /tmp/webA.db
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.Launcher 19302 Bob   18302 /tmp/webB.db
   java -cp target/lanchat-core-1.0-SNAPSHOT.jar com.example.lanchat.Launcher 19303 Carol 18303 /tmp/webC.db
   ```

2. **A creates a room, B and C join:**
   - Open:
     - A: `http://localhost:18301/`
     - B: `http://localhost:18302/`
     - C: `http://localhost:18303/`
   - In A UI: create a room and copy `roomId`.
   - In B/C UI: join with `roomId` and inviter `127.0.0.1:19301`.

3. **B goes offline, A/C keep chatting:**
   - Stop Bob process.
   - In A and C, send multiple room messages.

4. **B comes back and syncs:**
   - Restart Bob process.
   - Open B room conversation: UI triggers sync automatically, missing messages appear via polling.
   - Or manually call:
     ```bash
     curl -s -X POST http://localhost:18302/api/rooms/sync \
       -H 'Content-Type: application/json' \
       -d '{"roomId":"<roomId>"}'
     ```

### Notes
- This is a P2P system without a central server: missing messages can only be fetched if an online member still has them.
- Duplicate messages from multiple sources are deduped by `seen_messages(msg_id)`.
