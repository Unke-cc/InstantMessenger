package com.example.lanchat.service;

import com.example.lanchat.core.Settings;
import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.transport.Connection;
import com.example.lanchat.transport.ConnectionManager;
import com.example.lanchat.transport.PeerInfo;
import com.example.lanchat.transport.TcpServer;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.util.LruTtlSet;
import com.google.gson.JsonElement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransportService {

    public interface Handler {
        void onMessage(PeerInfo remotePeerInfo, MessageEnvelope message);
    }

    private final Identity identity;
    private final PeerDao peerDao;
    private final ExecutorService ioPool;
    private final LruTtlSet seenMsgIds;
    private final ConnectionManager connectionManager;
    private final TcpServer tcpServer;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile Handler handler;

    public TransportService(Identity identity) {
        this.identity = identity;
        this.peerDao = new PeerDao();
        this.ioPool = Executors.newFixedThreadPool(Settings.TCP_IO_THREADS);
        this.seenMsgIds = new LruTtlSet(Settings.SEEN_MSG_MAX_SIZE, Settings.SEEN_MSG_TTL_MS);
        this.connectionManager = new ConnectionManager(identity, peerDao, seenMsgIds, ioPool);
        this.tcpServer = new TcpServer(
                identity.p2pPort,
                identity,
                peerDao,
                seenMsgIds,
                ioPool,
                this::dispatchInbound,
                connectionManager
        );
        connectionManager.setMessageHandler(this::dispatchInbound);
    }

    public void onMessage(Handler handler) {
        this.handler = handler;
    }

    public void start() throws Exception {
        if (!started.compareAndSet(false, true)) return;
        tcpServer.start();
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        tcpServer.stop();
        connectionManager.closeAll();
        ioPool.shutdownNow();
    }

    public PeerInfo connectToAddr(String ip, int port) throws Exception {
        Connection conn = connectionManager.getByAddr(ip, port);
        if (conn == null) {
            conn = connectionManager.connectTo(ip, port);
        }
        PeerInfo remote = conn.remotePeer();
        if (remote == null) {
            throw new IllegalStateException("Handshake failed");
        }
        return remote;
    }

    public void sendToAddr(String ip, int port, MessageEnvelope message) throws Exception {
        MessageEnvelope env = normalizeOutbound(message);
        Connection conn = connectionManager.getByAddr(ip, port);
        if (conn == null) conn = connectionManager.connectTo(ip, port);
        conn.send(env);
    }

    public void send(String peerNodeId, String ip, int port, MessageEnvelope message) throws Exception {
        MessageEnvelope env = normalizeOutbound(message);
        Connection conn = connectionManager.getOrConnect(peerNodeId, ip, port);
        conn.send(env);
    }

    private MessageEnvelope normalizeOutbound(MessageEnvelope message) {
        MessageEnvelope env = message;
        if (env.protocolVersion == 0) env.protocolVersion = 1;
        if (env.msgId == null) env.msgId = UUID.randomUUID().toString();
        if (env.ts == 0) env.ts = System.currentTimeMillis();
        if (env.from == null) env.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        if (env.from.nodeId == null) env.from.nodeId = identity.nodeId;
        if (env.from.name == null) env.from.name = identity.displayName;
        JsonElement payload = env.payload;
        env.payload = payload;
        return env;
    }

    private void dispatchInbound(PeerInfo remote, MessageEnvelope msg) {
        Handler h = handler;
        if (h == null) return;
        h.onMessage(remote, msg);
    }
}
