package com.example.lanchat.service;

import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.protocol.MessageType;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.store.RoomDao;
import com.example.lanchat.store.RoomDao.Room;
import com.example.lanchat.store.RoomEventDao;
import com.example.lanchat.store.RoomMemberDao;
import com.example.lanchat.store.RoomMemberDao.RoomMember;
import com.example.lanchat.transport.PeerInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class RoomMembershipService implements TransportService.Handler {

    private final Identity identity;
    private final LamportClock clock;
    private final TransportService transport;
    private final RoomDao roomDao;
    private final RoomMemberDao roomMemberDao;
    private final RoomEventDao roomEventDao;
    private final PeerDao peerDao;
    private final ConversationService conversationService;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingJoinByRoomId = new ConcurrentHashMap<>();
    private volatile Consumer<String> joinAcceptedListener;

    public RoomMembershipService(Identity identity, LamportClock clock, TransportService transport) {
        this.identity = identity;
        this.clock = clock;
        this.transport = transport;
        this.roomDao = new RoomDao();
        this.roomMemberDao = new RoomMemberDao();
        this.roomEventDao = new RoomEventDao();
        this.peerDao = new PeerDao();
        this.conversationService = new ConversationService();
    }

    public void onJoinAccepted(Consumer<String> listener) {
        this.joinAcceptedListener = listener;
    }

    public void joinRoom(String roomId, String inviterIp, int inviterPort, String token) throws Exception {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        CompletableFuture<Boolean> prev = pendingJoinByRoomId.putIfAbsent(roomId, f);
        if (prev != null) f = prev;

        PeerInfo inviter = transport.connectToAddr(inviterIp, inviterPort);

        MessageEnvelope req = new MessageEnvelope();
        req.protocolVersion = 1;
        req.type = MessageType.JOIN_REQUEST;
        req.msgId = UUID.randomUUID().toString();
        req.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        req.ts = System.currentTimeMillis();
        req.clock = clock.tick();

        JsonObject payload = new JsonObject();
        payload.addProperty("roomId", roomId);
        JsonObject invite = new JsonObject();
        if (token != null) invite.addProperty("token", token);
        payload.add("invite", invite);
        JsonObject joiner = new JsonObject();
        joiner.addProperty("nodeId", identity.nodeId);
        joiner.addProperty("name", identity.displayName);
        joiner.addProperty("p2pPort", identity.p2pPort);
        payload.add("joiner", joiner);
        req.payload = payload;

        transport.send(inviter.nodeId, inviter.ip, inviter.p2pPort, req);

        boolean ok;
        try {
            ok = f.get(8, TimeUnit.SECONDS);
        } finally {
            pendingJoinByRoomId.remove(roomId, f);
        }
        if (!ok) throw new IllegalStateException("JOIN_ACCEPT failed");
    }

    @Override
    public void onMessage(PeerInfo remotePeerInfo, MessageEnvelope message) {
        try {
            handleIncoming(remotePeerInfo, message);
        } catch (Exception e) {
            System.err.println("RoomMembershipService handleIncoming failed: " + e.getMessage());
        }
    }

    private void handleIncoming(PeerInfo remote, MessageEnvelope env) throws Exception {
        if (env == null || env.type == null) return;
        if (MessageType.JOIN_REQUEST.equals(env.type)) {
            handleJoinRequest(remote, env);
            return;
        }
        if (MessageType.JOIN_ACCEPT.equals(env.type)) {
            handleJoinAccept(env);
            return;
        }
        if (MessageType.MEMBER_EVENT.equals(env.type)) {
            handleMemberEvent(remote, env);
        }
    }

    private void handleJoinRequest(PeerInfo remote, MessageEnvelope env) throws Exception {
        JsonObject payload = asObject(env.payload);
        if (payload == null) return;
        if (!payload.has("roomId")) return;
        String roomId = payload.get("roomId").getAsString();
        if (!roomMemberDao.isMember(roomId, identity.nodeId)) return;

        Room room = roomDao.getById(roomId);
        if (room == null) return;

        JsonObject joiner = payload.has("joiner") && payload.get("joiner").isJsonObject() ? payload.getAsJsonObject("joiner") : null;
        if (joiner == null) return;
        String joinerNodeId = joiner.has("nodeId") ? joiner.get("nodeId").getAsString() : null;
        String joinerName = joiner.has("name") ? joiner.get("name").getAsString() : null;
        int joinerP2pPort = joiner.has("p2pPort") ? joiner.get("p2pPort").getAsInt() : remote.p2pPort;
        if (joinerNodeId == null || joinerNodeId.isEmpty()) return;
        if (joinerName == null) joinerName = joinerNodeId;

        long now = System.currentTimeMillis();

        RoomMember m = new RoomMember();
        m.roomId = roomId;
        m.memberNodeId = joinerNodeId;
        m.memberName = joinerName;
        m.lastKnownIp = remote.ip;
        m.lastKnownP2pPort = joinerP2pPort > 0 ? joinerP2pPort : remote.p2pPort;
        m.lastSeen = now;
        roomMemberDao.upsert(m);

        try {
            peerDao.upsertPeer(joinerNodeId, joinerName, remote.ip, m.lastKnownP2pPort, now);
        } catch (SQLException ignored) {
        }

        MessageEnvelope accept = new MessageEnvelope();
        accept.protocolVersion = 1;
        accept.type = MessageType.JOIN_ACCEPT;
        accept.msgId = UUID.randomUUID().toString();
        accept.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        accept.ts = now;
        accept.clock = clock.tick();

        JsonObject out = new JsonObject();
        out.addProperty("roomId", roomId);
        JsonObject roomObj = new JsonObject();
        roomObj.addProperty("name", room.roomName);
        roomObj.addProperty("policy", room.policy);
        out.add("room", roomObj);

        JsonArray snapshot = new JsonArray();
        List<RoomMember> members = roomMemberDao.listMembers(roomId);
        for (RoomMember rm : members) {
            JsonObject mm = new JsonObject();
            mm.addProperty("nodeId", rm.memberNodeId);
            mm.addProperty("name", rm.memberName);
            if (rm.lastKnownIp != null) mm.addProperty("addr", rm.lastKnownIp);
            if (rm.lastKnownP2pPort > 0) mm.addProperty("p2pPort", rm.lastKnownP2pPort);
            mm.addProperty("lastSeen", rm.lastSeen);
            snapshot.add(mm);
        }
        out.add("memberSnapshot", snapshot);
        accept.payload = out;

        transport.send(remote.nodeId, remote.ip, remote.p2pPort, accept);

        broadcastMemberJoin(roomId, joinerNodeId, joinerName);
    }

    private void broadcastMemberJoin(String roomId, String memberNodeId, String memberName) throws Exception {
        String eventId = UUID.randomUUID().toString();
        long ts = System.currentTimeMillis();
        long clk = clock.tick();
        boolean first;
        try {
            first = roomEventDao.insertIgnore(eventId, roomId, "JOIN", memberNodeId, String.valueOf(clk), ts);
        } catch (SQLException e) {
            first = false;
        }
        if (!first) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("roomId", roomId);
        payload.addProperty("eventId", eventId);
        payload.addProperty("op", "JOIN");
        JsonObject member = new JsonObject();
        member.addProperty("nodeId", memberNodeId);
        member.addProperty("name", memberName);
        payload.add("member", member);

        MessageEnvelope env = new MessageEnvelope();
        env.protocolVersion = 1;
        env.type = MessageType.MEMBER_EVENT;
        env.msgId = UUID.randomUUID().toString();
        env.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        env.ts = ts;
        env.clock = clk;
        env.payload = payload;

        List<RoomMember> members = roomMemberDao.listMembers(roomId);
        for (RoomMember rm : members) {
            if (rm.memberNodeId == null) continue;
            if (identity.nodeId.equals(rm.memberNodeId)) continue;
            sendToMemberIfAddressKnown(rm.memberNodeId, rm.lastKnownIp, rm.lastKnownP2pPort, env);
        }
    }

    private void handleJoinAccept(MessageEnvelope env) throws SQLException {
        JsonObject payload = asObject(env.payload);
        if (payload == null) return;
        if (!payload.has("roomId")) return;
        String roomId = payload.get("roomId").getAsString();

        Room r = new Room();
        r.roomId = roomId;
        r.createdAt = System.currentTimeMillis();
        if (payload.has("room") && payload.get("room").isJsonObject()) {
            JsonObject roomObj = payload.getAsJsonObject("room");
            r.roomName = roomObj.has("name") ? roomObj.get("name").getAsString() : roomId;
            r.policy = roomObj.has("policy") ? roomObj.get("policy").getAsString() : "open";
        } else {
            r.roomName = roomId;
            r.policy = "open";
        }
        r.roomKeyHash = null;
        roomDao.upsert(r);
        conversationService.getOrCreateRoomConv(roomId, r.roomName != null ? r.roomName : roomId, System.currentTimeMillis());

        if (payload.has("memberSnapshot") && payload.get("memberSnapshot").isJsonArray()) {
            JsonArray arr = payload.getAsJsonArray("memberSnapshot");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject mObj = el.getAsJsonObject();
                if (!mObj.has("nodeId")) continue;
                RoomMember m = new RoomMember();
                m.roomId = roomId;
                m.memberNodeId = mObj.get("nodeId").getAsString();
                m.memberName = mObj.has("name") ? mObj.get("name").getAsString() : m.memberNodeId;
                m.lastKnownIp = mObj.has("addr") ? mObj.get("addr").getAsString() : null;
                m.lastKnownP2pPort = mObj.has("p2pPort") ? mObj.get("p2pPort").getAsInt() : 0;
                m.lastSeen = mObj.has("lastSeen") ? mObj.get("lastSeen").getAsLong() : 0;
                roomMemberDao.upsert(m);
            }
        }

        RoomMember self = new RoomMember();
        self.roomId = roomId;
        self.memberNodeId = identity.nodeId;
        self.memberName = identity.displayName;
        self.lastKnownIp = null;
        self.lastKnownP2pPort = identity.p2pPort;
        self.lastSeen = System.currentTimeMillis();
        roomMemberDao.upsert(self);

        CompletableFuture<Boolean> f = pendingJoinByRoomId.get(roomId);
        if (f != null) f.complete(true);

        Consumer<String> listener = joinAcceptedListener;
        if (listener != null) listener.accept(roomId);
    }

    private void handleMemberEvent(PeerInfo remote, MessageEnvelope env) throws Exception {
        JsonObject payload = asObject(env.payload);
        if (payload == null) return;
        if (!payload.has("roomId") || !payload.has("eventId") || !payload.has("op")) return;
        String roomId = payload.get("roomId").getAsString();
        String eventId = payload.get("eventId").getAsString();
        String op = payload.get("op").getAsString();
        JsonObject memberObj = payload.has("member") && payload.get("member").isJsonObject() ? payload.getAsJsonObject("member") : null;
        if (memberObj == null || !memberObj.has("nodeId")) return;
        String memberNodeId = memberObj.get("nodeId").getAsString();
        String memberName = memberObj.has("name") ? memberObj.get("name").getAsString() : memberNodeId;

        boolean first = roomEventDao.insertIgnore(eventId, roomId, op, memberNodeId, String.valueOf(env.clock), env.ts);
        if (!first) return;

        if ("JOIN".equals(op)) {
            String senderNodeId = env.from != null && env.from.nodeId != null ? env.from.nodeId : (remote != null ? remote.nodeId : null);
            RoomMember m = new RoomMember();
            m.roomId = roomId;
            m.memberNodeId = memberNodeId;
            m.memberName = memberName;
            if (senderNodeId != null && senderNodeId.equals(memberNodeId) && remote != null) {
                m.lastKnownIp = remote.ip;
                m.lastKnownP2pPort = remote.p2pPort;
            } else {
                m.lastKnownIp = null;
                m.lastKnownP2pPort = 0;
            }
            m.lastSeen = System.currentTimeMillis();
            roomMemberDao.upsert(m);
        } else if ("LEAVE".equals(op)) {
            roomMemberDao.removeMember(roomId, memberNodeId);
        }

        propagateMemberEvent(remote, roomId, env);
    }

    private void propagateMemberEvent(PeerInfo remote, String roomId, MessageEnvelope env) throws Exception {
        String senderNodeId = env.from != null ? env.from.nodeId : null;
        if (senderNodeId == null && remote != null) senderNodeId = remote.nodeId;
        List<RoomMember> members = roomMemberDao.listMembers(roomId);
        for (RoomMember rm : members) {
            if (rm.memberNodeId == null) continue;
            if (identity.nodeId.equals(rm.memberNodeId)) continue;
            if (senderNodeId != null && senderNodeId.equals(rm.memberNodeId)) continue;
            sendToMemberIfAddressKnown(rm.memberNodeId, rm.lastKnownIp, rm.lastKnownP2pPort, env);
        }
    }

    private void sendToMemberIfAddressKnown(String memberNodeId, String lastKnownIp, int lastKnownPort, MessageEnvelope env) throws Exception {
        String ip = lastKnownIp;
        int port = lastKnownPort;
        if ((ip == null || ip.isEmpty() || port <= 0)) {
            try {
                PeerDao.Peer p = peerDao.getPeerByNodeId(memberNodeId);
                if (p != null) {
                    ip = p.ip;
                    port = p.p2pPort;
                }
            } catch (SQLException ignored) {
            }
        }
        if (ip == null || ip.isEmpty() || port <= 0) return;
        transport.send(memberNodeId, ip, port, env);
    }

    private JsonObject asObject(JsonElement el) {
        if (el == null) return null;
        if (!el.isJsonObject()) return null;
        return el.getAsJsonObject();
    }
}
