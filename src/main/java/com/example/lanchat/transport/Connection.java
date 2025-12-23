package com.example.lanchat.transport;

import com.example.lanchat.core.Settings;
import com.example.lanchat.protocol.Errors;
import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.protocol.MessageType;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.util.LruTtlSet;
import com.example.lanchat.util.Net;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Connection implements AutoCloseable {

    public interface MessageHandler {
        void onMessage(PeerInfo remote, MessageEnvelope message);
    }

    public interface HandshakeListener {
        void onHandshakeSuccess(Connection conn, PeerInfo remote);
        void onClosed(Connection conn);
    }

    private final Socket socket;
    private final Identity localIdentity;
    private final PeerDao peerDao;
    private final LruTtlSet seenMsgIds;
    private final MessageHandler messageHandler;
    private final HandshakeListener handshakeListener;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch handshakeDone = new CountDownLatch(1);
    private final BufferedInputStream in;
    private final BufferedOutputStream out;

    private volatile PeerInfo remotePeer;
    private volatile long lastActiveAtMs;

    public Connection(
            Socket socket,
            Identity localIdentity,
            PeerDao peerDao,
            LruTtlSet seenMsgIds,
            MessageHandler messageHandler,
            HandshakeListener handshakeListener
    ) throws IOException {
        this.socket = socket;
        this.localIdentity = localIdentity;
        this.peerDao = peerDao;
        this.seenMsgIds = seenMsgIds;
        this.messageHandler = messageHandler;
        this.handshakeListener = handshakeListener;
        this.in = Framing.wrapIn(socket.getInputStream());
        this.out = Framing.wrapOut(socket.getOutputStream());
        this.lastActiveAtMs = System.currentTimeMillis();
    }

    public Socket socket() {
        return socket;
    }

    public PeerInfo remotePeer() {
        return remotePeer;
    }

    public long lastActiveAtMs() {
        return lastActiveAtMs;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean awaitHandshake(long timeoutMs) throws InterruptedException {
        return handshakeDone.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void runReadLoop() {
        try {
            socket.setSoTimeout(Settings.HELLO_TIMEOUT_MS);
            sendHello();
            MessageEnvelope first = readNext();
            if (first == null) {
                close();
                return;
            }
            if (!MessageType.HELLO.equals(first.type)) {
                sendErrorAndClose(Errors.BAD_MESSAGE, "Expected HELLO");
                return;
            }
            if (!handleHello(first)) {
                return;
            }

            handshakeDone.countDown();
            handshakeListener.onHandshakeSuccess(this, remotePeer);
            socket.setSoTimeout(0);

            while (!isClosed()) {
                MessageEnvelope msg = readNext();
                if (msg == null) {
                    close();
                    return;
                }
                lastActiveAtMs = System.currentTimeMillis();
                if (msg.msgId != null) {
                    boolean firstSeen = seenMsgIds.addIfAbsent(msg.msgId, lastActiveAtMs);
                    if (!firstSeen) {
                        continue;
                    }
                }
                if (MessageType.HELLO.equals(msg.type)) {
                    continue;
                }
                if (MessageType.ERROR.equals(msg.type)) {
                    continue;
                }
                PeerInfo rp = remotePeer;
                if (rp != null && messageHandler != null) {
                    messageHandler.onMessage(rp, msg);
                }
            }
        } catch (Framing.FrameTooLargeException e) {
            sendErrorAndClose(Errors.TOO_LARGE, "Frame too large: " + e.sizeBytes);
        } catch (IOException e) {
            close();
        } catch (Exception e) {
            close();
        }
    }

    private MessageEnvelope readNext() throws IOException, Framing.FrameTooLargeException {
        MessageEnvelope msg = Framing.readMessage(in);
        if (msg != null) {
            lastActiveAtMs = System.currentTimeMillis();
        }
        return msg;
    }

    private void sendHello() throws IOException, Framing.FrameTooLargeException {
        MessageEnvelope env = new MessageEnvelope();
        env.protocolVersion = 1;
        env.type = MessageType.HELLO;
        env.msgId = UUID.randomUUID().toString();
        env.from = new MessageEnvelope.NodeInfo(localIdentity.nodeId, localIdentity.displayName);
        env.ts = System.currentTimeMillis();

        JsonObject payload = new JsonObject();
        payload.addProperty("p2pPort", localIdentity.p2pPort);
        JsonArray supported = new JsonArray();
        supported.add(1);
        payload.add("supportedVersions", supported);
        env.payload = payload;

        send(env);
    }

    private boolean handleHello(MessageEnvelope hello) {
        if (hello.protocolVersion != 1) {
            sendErrorAndClose(Errors.UNSUPPORTED_VERSION, "protocolVersion=" + hello.protocolVersion);
            return false;
        }
        if (hello.from == null || hello.from.nodeId == null || hello.from.name == null) {
            sendErrorAndClose(Errors.BAD_MESSAGE, "Missing from");
            return false;
        }
        if (hello.payload == null || !hello.payload.isJsonObject()) {
            sendErrorAndClose(Errors.BAD_MESSAGE, "Missing payload");
            return false;
        }

        JsonObject payload = hello.payload.getAsJsonObject();
        int p2pPort = payload.has("p2pPort") ? payload.get("p2pPort").getAsInt() : 0;
        if (p2pPort <= 0) {
            sendErrorAndClose(Errors.BAD_MESSAGE, "Invalid p2pPort");
            return false;
        }
        if (payload.has("supportedVersions")) {
            boolean ok = false;
            JsonArray arr = payload.getAsJsonArray("supportedVersions");
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i).getAsInt() == 1) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                sendErrorAndClose(Errors.UNSUPPORTED_VERSION, "supportedVersions missing 1");
                return false;
            }
        }

        String ip = socket.getInetAddress().getHostAddress();
        PeerInfo rp = new PeerInfo(hello.from.nodeId, hello.from.name, ip, p2pPort);
        this.remotePeer = rp;

        try {
            peerDao.upsertPeer(rp.nodeId, rp.name, rp.ip, rp.p2pPort, System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        System.out.println("Handshake OK: " + Net.formatRemote(socket) + " => " + rp);
        return true;
    }

    public void send(MessageEnvelope env) throws IOException, Framing.FrameTooLargeException {
        Objects.requireNonNull(env, "env");
        synchronized (out) {
            Framing.writeMessage(out, env);
        }
    }

    private void sendErrorAndClose(String code, String message) {
        try {
            MessageEnvelope.NodeInfo from = new MessageEnvelope.NodeInfo(localIdentity.nodeId, localIdentity.displayName);
            MessageEnvelope err = Errors.buildError(from, code, message);
            send(err);
        } catch (Exception ignored) {
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Net.safeClose(socket);
        handshakeDone.countDown();
        if (handshakeListener != null) {
            handshakeListener.onClosed(this);
        }
        System.out.println("Connection closed: " + Net.formatRemote(socket));
    }
}
