package com.example.lanchat.transport;

import com.example.lanchat.core.Settings;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.util.LruTtlSet;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public class ConnectionManager implements Connection.HandshakeListener {

    private final Identity identity;
    private final PeerDao peerDao;
    private final LruTtlSet seenMsgIds;
    private final ExecutorService ioPool;
    private volatile Connection.MessageHandler messageHandler;
    private final Map<String, Connection> byPeerNodeId = new ConcurrentHashMap<>();

    public ConnectionManager(Identity identity, PeerDao peerDao, LruTtlSet seenMsgIds, ExecutorService ioPool) {
        this.identity = identity;
        this.peerDao = peerDao;
        this.seenMsgIds = seenMsgIds;
        this.ioPool = ioPool;
    }

    public void setMessageHandler(Connection.MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    public Connection getByPeerNodeId(String peerNodeId) {
        return byPeerNodeId.get(peerNodeId);
    }

    public Connection getByAddr(String ip, int port) {
        for (Connection c : byPeerNodeId.values()) {
            PeerInfo rp = c.remotePeer();
            if (rp == null) continue;
            if (rp.p2pPort == port && rp.ip.equals(ip) && !c.isClosed()) {
                return c;
            }
        }
        return null;
    }

    public Connection connectTo(String ip, int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), Settings.PROBE_TIMEOUT_MS);
        Connection conn = new Connection(socket, identity, peerDao, seenMsgIds, messageHandler, this);
        ioPool.submit(conn::runReadLoop);
        boolean ok = conn.awaitHandshake(Settings.HELLO_TIMEOUT_MS);
        if (!ok || conn.remotePeer() == null) {
            conn.close();
            throw new IllegalStateException("Handshake timeout");
        }
        return conn;
    }

    public Connection getOrConnect(String peerNodeId, String ip, int port) throws Exception {
        Connection existing = byPeerNodeId.get(peerNodeId);
        if (existing != null && !existing.isClosed() && existing.remotePeer() != null) {
            return existing;
        }
        Connection conn = connectTo(ip, port);
        PeerInfo rp = conn.remotePeer();
        if (rp == null) {
            conn.close();
            throw new IllegalStateException("No remote peer");
        }
        if (!peerNodeId.equals(rp.nodeId)) {
            conn.close();
            throw new IllegalStateException("peerNodeId mismatch");
        }
        return conn;
    }

    @Override
    public void onHandshakeSuccess(Connection conn, PeerInfo remote) {
        if (remote == null) return;
        byPeerNodeId.compute(remote.nodeId, (k, old) -> {
            if (old == null) return conn;
            if (old == conn) return conn;
            if (!old.isClosed()) {
                old.close();
            }
            return conn;
        });
    }

    @Override
    public void onClosed(Connection conn) {
        PeerInfo remote = conn.remotePeer();
        if (remote == null) return;
        byPeerNodeId.remove(remote.nodeId, conn);
    }

    public void closeAll() {
        for (Connection c : byPeerNodeId.values()) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        byPeerNodeId.clear();
    }
}
