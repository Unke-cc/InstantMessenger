package com.example.lanchat.service;

import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.protocol.MessageType;
import com.example.lanchat.store.ConversationDao.Conversation;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.MessageDao;
import com.example.lanchat.store.MessageDao.Message;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.store.RoomDao;
import com.example.lanchat.store.RoomDao.Room;
import com.example.lanchat.store.RoomMemberDao;
import com.example.lanchat.store.RoomMemberDao.RoomMember;
import com.example.lanchat.store.SeenDao;
import com.example.lanchat.transport.PeerInfo;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public class GroupMessageService implements TransportService.Handler {

    private final Identity identity;
    private final LamportClock clock;
    private final TransportService transport;
    private final ConversationService conversationService;
    private final MessageDao messageDao;
    private final SeenDao seenDao;
    private final RoomDao roomDao;
    private final RoomMemberDao roomMemberDao;
    private final PeerDao peerDao;

    public GroupMessageService(Identity identity, LamportClock clock, TransportService transport) {
        this.identity = identity;
        this.clock = clock;
        this.transport = transport;
        this.conversationService = new ConversationService();
        this.messageDao = new MessageDao();
        this.seenDao = new SeenDao();
        this.roomDao = new RoomDao();
        this.roomMemberDao = new RoomMemberDao();
        this.peerDao = new PeerDao();
    }

    public void sendRoomMessage(String roomId, String content) throws Exception {
        if (!roomMemberDao.isMember(roomId, identity.nodeId)) {
            throw new IllegalStateException("Not a member of room " + roomId);
        }

        long now = System.currentTimeMillis();
        Room room = roomDao.getById(roomId);
        String title = room != null && room.roomName != null ? room.roomName : roomId;
        Conversation conv = conversationService.getOrCreateRoomConv(roomId, title, now);
        long clk = clock.tick();
        String msgId = UUID.randomUUID().toString();

        Message out = new Message();
        out.msgId = msgId;
        out.convId = conv.convId;
        out.chatType = "ROOM";
        out.roomId = roomId;
        out.direction = "OUT";
        out.fromNodeId = identity.nodeId;
        out.toNodeId = null;
        out.content = content;
        out.contentType = "text/plain";
        out.ts = now;
        out.updatedAt = now;
        out.clockValue = String.valueOf(clk);
        out.status = "SENT";

        messageDao.insert(out);
        conversationService.touch(conv.convId, now);

        MessageEnvelope env = new MessageEnvelope();
        env.protocolVersion = 1;
        env.type = MessageType.CHAT;
        env.msgId = msgId;
        env.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        env.ts = now;
        env.clock = clk;
        JsonObject payload = new JsonObject();
        payload.addProperty("chatType", "ROOM");
        payload.addProperty("roomId", roomId);
        payload.addProperty("content", content);
        payload.addProperty("contentType", "text/plain");
        env.payload = payload;

        List<RoomMember> members = roomMemberDao.listMembers(roomId);
        for (RoomMember m : members) {
            if (m.memberNodeId == null) continue;
            if (identity.nodeId.equals(m.memberNodeId)) continue;
            String ip = m.lastKnownIp;
            int port = m.lastKnownP2pPort;
            if (ip == null || ip.isEmpty() || port <= 0) {
                PeerDao.Peer p = peerDao.getPeerByNodeId(m.memberNodeId);
                if (p != null) {
                    ip = p.ip;
                    port = p.p2pPort;
                }
            }
            if (ip == null || ip.isEmpty() || port <= 0) continue;
            transport.send(m.memberNodeId, ip, port, env);
        }
    }

    @Override
    public void onMessage(PeerInfo remotePeerInfo, MessageEnvelope message) {
        try {
            handleIncoming(remotePeerInfo, message);
        } catch (Exception e) {
            System.err.println("GroupMessageService handleIncoming failed: " + e.getMessage());
        }
    }

    private void handleIncoming(PeerInfo remote, MessageEnvelope env) throws Exception {
        if (env == null || env.type == null) return;
        if (!MessageType.CHAT.equals(env.type)) return;
        if (env.payload == null || !env.payload.isJsonObject()) return;
        JsonObject payload = env.payload.getAsJsonObject();
        String chatType = payload.has("chatType") ? payload.get("chatType").getAsString() : "";
        if (!"ROOM".equals(chatType)) return;

        if (env.msgId == null) return;
        long now = System.currentTimeMillis();
        boolean first = seenDao.markSeen(env.msgId, now);
        if (!first) return;

        String roomId = payload.has("roomId") ? payload.get("roomId").getAsString() : null;
        if (roomId == null) return;
        if (!roomMemberDao.isMember(roomId, identity.nodeId)) return;

        String content = payload.has("content") ? payload.get("content").getAsString() : "";
        String fromNodeId = env.from != null && env.from.nodeId != null ? env.from.nodeId : remote.nodeId;
        String fromName = env.from != null && env.from.name != null ? env.from.name : remote.name;

        RoomMember sender = new RoomMember();
        sender.roomId = roomId;
        sender.memberNodeId = fromNodeId;
        sender.memberName = fromName;
        sender.lastKnownIp = remote.ip;
        sender.lastKnownP2pPort = remote.p2pPort;
        sender.lastSeen = now;
        try {
            roomMemberDao.upsert(sender);
        } catch (SQLException ignored) {
        }

        Room room = roomDao.getById(roomId);
        String title = room != null && room.roomName != null ? room.roomName : roomId;
        Conversation conv = conversationService.getOrCreateRoomConv(roomId, title, now);

        Message in = new Message();
        in.msgId = env.msgId;
        in.convId = conv.convId;
        in.chatType = "ROOM";
        in.roomId = roomId;
        in.direction = "IN";
        in.fromNodeId = fromNodeId;
        in.toNodeId = null;
        in.content = content;
        in.contentType = payload.has("contentType") ? payload.get("contentType").getAsString() : "text/plain";
        in.ts = env.ts > 0 ? env.ts : now;
        in.updatedAt = in.ts;
        in.clockValue = String.valueOf(env.clock);
        in.status = "DELIVERED";

        messageDao.insert(in);
        conversationService.touch(conv.convId, in.ts);

        System.out.println("[" + title + "] " + fromName + ": " + content);
    }
}
