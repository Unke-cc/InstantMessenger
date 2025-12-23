package com.example.lanchat.service;

import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.protocol.MessageType;
import com.example.lanchat.service.TransportService.Handler;
import com.example.lanchat.store.ConversationDao.Conversation;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.MessageDao;
import com.example.lanchat.store.MessageDao.Message;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.store.PeerDao.Peer;
import com.example.lanchat.store.SeenDao;
import com.example.lanchat.transport.PeerInfo;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.UUID;

public class MessageService implements Handler {

    private final Identity identity;
    private final LamportClock clock;
    private final ConversationService conversationService;
    private final MessageDao messageDao;
    private final SeenDao seenDao;
    private final PeerDao peerDao;
    private final TransportService transport;

    public MessageService(Identity identity, LamportClock clock, TransportService transport) {
        this.identity = identity;
        this.transport = transport;
        this.clock = clock;
        this.conversationService = new ConversationService();
        this.messageDao = new MessageDao();
        this.seenDao = new SeenDao();
        this.peerDao = new PeerDao();
    }

    public void sendPrivate(String target, String content) throws Exception {
        sendPrivateWithMsgId(target, content, UUID.randomUUID().toString());
    }

    public void sendPrivateWithMsgId(String target, String content, String msgId) throws Exception {
        String toNodeId;
        String ip;
        int port;

        if (target.contains(":")) {
            String[] parts = target.split(":", 2);
            ip = parts[0];
            port = Integer.parseInt(parts[1]);
            PeerInfo remote = transport.connectToAddr(ip, port);
            toNodeId = remote.nodeId;
            ip = remote.ip;
            port = remote.p2pPort;
        } else {
            toNodeId = target;
            Peer peer = peerDao.getPeerByNodeId(toNodeId);
            if (peer == null || peer.ip == null || peer.p2pPort <= 0) {
                throw new IllegalStateException("Unknown peer address. Please discover or /addpeer first.");
            }
            ip = peer.ip;
            port = peer.p2pPort;
        }

        long now = System.currentTimeMillis();
        Conversation conv = conversationService.getOrCreatePrivateConv(toNodeId, toNodeId, now);
        long clk = clock.tick();

        Message out = new Message();
        out.msgId = msgId;
        out.convId = conv.convId;
        out.chatType = "PRIVATE";
        out.roomId = null;
        out.direction = "OUT";
        out.fromNodeId = identity.nodeId;
        out.toNodeId = toNodeId;
        out.content = content;
        out.contentType = "text/plain";
        out.ts = now;
        out.updatedAt = now;
        out.clockValue = String.valueOf(clk);
        out.status = "SENT";

        boolean inserted = false;
        try {
            messageDao.insert(out);
            inserted = true;
            conversationService.touch(conv.convId, now);
        } catch (SQLException e) {
            // ignore duplicate msg_id for resend
        }

        MessageEnvelope env = new MessageEnvelope();
        env.protocolVersion = 1;
        env.type = MessageType.CHAT;
        env.msgId = msgId;
        env.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        env.ts = now;
        env.clock = clk;

        JsonObject payload = new JsonObject();
        payload.addProperty("chatType", "PRIVATE");
        payload.addProperty("toNodeId", toNodeId);
        payload.addProperty("content", content);
        env.payload = payload;

        try {
            transport.send(toNodeId, ip, port, env);
            if (inserted) {
                System.out.println("Sent PRIVATE msgId=" + msgId + " to " + toNodeId + " status=SENT");
            } else {
                System.out.println("Resent PRIVATE msgId=" + msgId + " to " + toNodeId);
            }
        } catch (Exception e) {
            try {
                messageDao.updateStatus(msgId, "FAILED");
            } catch (SQLException ignored) {
            }
            throw e;
        }
    }

    @Override
    public void onMessage(PeerInfo remotePeerInfo, MessageEnvelope message) {
        try {
            handleIncoming(remotePeerInfo, message);
        } catch (Exception e) {
            System.err.println("handleIncoming failed: " + e.getMessage());
        }
    }

    private void handleIncoming(PeerInfo remote, MessageEnvelope env) throws Exception {
        if (env == null || env.type == null) return;
        long now = System.currentTimeMillis();

        if (MessageType.CHAT.equals(env.type)) {
            handleChat(remote, env, now);
            return;
        }
        if (MessageType.ACK.equals(env.type)) {
            handleAck(env);
        }
    }

    private void handleChat(PeerInfo remote, MessageEnvelope env, long now) throws Exception {
        if (env.payload == null || !env.payload.isJsonObject()) return;
        JsonObject payload = env.payload.getAsJsonObject();
        String chatType = payload.has("chatType") ? payload.get("chatType").getAsString() : "";
        if (!"PRIVATE".equals(chatType)) return;

        if (env.msgId == null) return;
        boolean first = seenDao.markSeen(env.msgId, now);
        if (!first) {
            return;
        }

        String content = payload.has("content") ? payload.get("content").getAsString() : "";
        String fromNodeId = env.from != null ? env.from.nodeId : remote.nodeId;
        String toNodeId = payload.has("toNodeId") ? payload.get("toNodeId").getAsString() : identity.nodeId;

        Conversation conv = conversationService.getOrCreatePrivateConv(fromNodeId, remote.name, now);

        Message in = new Message();
        in.msgId = env.msgId;
        in.convId = conv.convId;
        in.chatType = "PRIVATE";
        in.roomId = null;
        in.direction = "IN";
        in.fromNodeId = fromNodeId;
        in.toNodeId = toNodeId;
        in.content = content;
        in.contentType = "text/plain";
        in.ts = env.ts > 0 ? env.ts : now;
        in.updatedAt = in.ts;
        in.clockValue = String.valueOf(env.clock);
        in.status = "DELIVERED";

        messageDao.insert(in);
        conversationService.touch(conv.convId, in.ts);

        sendAck(remote, env.msgId);
    }

    private void sendAck(PeerInfo remote, String ackMsgId) throws Exception {
        MessageEnvelope ack = new MessageEnvelope();
        ack.protocolVersion = 1;
        ack.type = MessageType.ACK;
        ack.msgId = UUID.randomUUID().toString();
        ack.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        ack.ts = System.currentTimeMillis();
        ack.clock = clock.tick();

        JsonObject payload = new JsonObject();
        payload.addProperty("ackMsgId", ackMsgId);
        payload.addProperty("status", "DELIVERED");
        ack.payload = payload;

        transport.send(remote.nodeId, remote.ip, remote.p2pPort, ack);
    }

    private void handleAck(MessageEnvelope env) throws SQLException {
        if (env.payload == null || !env.payload.isJsonObject()) return;
        JsonObject payload = env.payload.getAsJsonObject();
        if (!payload.has("ackMsgId")) return;
        String ackMsgId = payload.get("ackMsgId").getAsString();
        messageDao.updateStatus(ackMsgId, "DELIVERED");
        System.out.println("ACK DELIVERED for msgId=" + ackMsgId);
    }
}
