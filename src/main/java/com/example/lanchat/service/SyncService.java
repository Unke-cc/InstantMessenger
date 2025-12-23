package com.example.lanchat.service;

import com.example.lanchat.core.Settings;
import com.example.lanchat.protocol.Errors;
import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.protocol.MessageType;
import com.example.lanchat.store.MessageDao;
import com.example.lanchat.store.MessageDao.Message;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.store.RoomCursorDao;
import com.example.lanchat.store.RoomDao;
import com.example.lanchat.store.RoomDao.Room;
import com.example.lanchat.store.RoomMemberDao;
import com.example.lanchat.store.RoomMemberDao.RoomMember;
import com.example.lanchat.store.SeenDao;
import com.example.lanchat.transport.PeerInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SyncService implements TransportService.Handler {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_SOURCES = 3;

    private final RoomCursorDao roomCursorDao;
    private final RoomDao roomDao;
    private final RoomMemberDao roomMemberDao;
    private final PeerDao peerDao;
    private final MessageDao messageDao;
    private final SeenDao seenDao;
    private final ConversationService conversationService;

    private final com.example.lanchat.store.IdentityDao.Identity identity;
    private final LamportClock clock;
    private final TransportService transport;
    private final ExecutorService syncPool;

    private final ConcurrentHashMap<String, Pending> pendingByRequestId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> roomLocks = new ConcurrentHashMap<>();

    private static final class Pending {
        final String requestId;
        final String roomId;
        final String peerNodeId;
        final CompletableFuture<SyncBatch> future;

        Pending(String requestId, String roomId, String peerNodeId, CompletableFuture<SyncBatch> future) {
            this.requestId = requestId;
            this.roomId = roomId;
            this.peerNodeId = peerNodeId;
            this.future = future;
        }
    }

    private static final class SyncBatch {
        final List<MessageEnvelope> messages;
        final boolean hasMore;
        final String nextSinceClockValue;

        SyncBatch(List<MessageEnvelope> messages, boolean hasMore, String nextSinceClockValue) {
            this.messages = messages;
            this.hasMore = hasMore;
            this.nextSinceClockValue = nextSinceClockValue;
        }
    }

    public SyncService(com.example.lanchat.store.IdentityDao.Identity identity, LamportClock clock, TransportService transport) {
        this.identity = identity;
        this.clock = clock;
        this.transport = transport;
        this.roomCursorDao = new RoomCursorDao();
        this.roomDao = new RoomDao();
        this.roomMemberDao = new RoomMemberDao();
        this.peerDao = new PeerDao();
        this.messageDao = new MessageDao();
        this.seenDao = new SeenDao();
        this.conversationService = new ConversationService();
        this.syncPool = Executors.newFixedThreadPool(2);
    }

    public void shutdown() {
        syncPool.shutdownNow();
    }

    public void syncAllRoomsAsync() {
        try {
            List<Room> rooms = roomDao.listRooms();
            for (Room r : rooms) {
                if (r == null || r.roomId == null) continue;
                syncRoomAsync(r.roomId);
            }
        } catch (SQLException e) {
            System.err.println("SyncService syncAllRoomsAsync failed: " + e.getMessage());
        }
    }

    public void syncRoomAsync(String roomId) {
        if (roomId == null || roomId.isBlank()) return;
        syncPool.submit(() -> {
            try {
                syncRoom(roomId.trim());
            } catch (Exception e) {
                System.err.println("SyncService syncRoomAsync failed: " + e.getMessage());
            }
        });
    }

    public int syncRoom(String roomId) throws Exception {
        if (roomId == null || roomId.isBlank()) return 0;
        String rid = roomId.trim();
        Object lock = roomLocks.computeIfAbsent(rid, k -> new Object());
        synchronized (lock) {
            if (!roomMemberDao.isMember(rid, identity.nodeId)) return 0;

            String cursor = roomCursorDao.getCursor(rid);
            List<RoomMember> sources = pickOnlineSources(rid);
            int insertedTotal = 0;
            boolean progressed = false;

            for (RoomMember src : sources) {
                SourceAddr addr = resolveAddr(src);
                if (addr == null) continue;
                SyncResult r = syncFromSource(rid, cursor, src.memberNodeId, addr.ip, addr.port);
                insertedTotal += r.inserted;
                if (r.maxCursor != null && compareClock(r.maxCursor, cursor) > 0) {
                    cursor = r.maxCursor;
                    roomCursorDao.updateCursorMonotonic(rid, cursor);
                    progressed = true;
                }
                if (progressed) break;
            }

            return insertedTotal;
        }
    }

    private static final class SourceAddr {
        final String ip;
        final int port;

        SourceAddr(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    private static final class SyncResult {
        final int inserted;
        final String maxCursor;

        SyncResult(int inserted, String maxCursor) {
            this.inserted = inserted;
            this.maxCursor = maxCursor;
        }
    }

    private List<RoomMember> pickOnlineSources(String roomId) throws SQLException {
        long now = System.currentTimeMillis();
        List<RoomMember> members = roomMemberDao.listMembers(roomId);
        List<RoomMember> out = new ArrayList<>();
        for (RoomMember m : members) {
            if (m == null || m.memberNodeId == null) continue;
            if (identity.nodeId.equals(m.memberNodeId)) continue;
            long effectiveLastSeen = m.lastSeen;
            PeerDao.Peer p = peerDao.getPeerByNodeId(m.memberNodeId);
            if (p != null) effectiveLastSeen = Math.max(effectiveLastSeen, p.lastSeen);
            if (effectiveLastSeen > 0 && (now - effectiveLastSeen) < Settings.PEER_TTL_MS) {
                m.lastSeen = effectiveLastSeen;
                out.add(m);
            }
        }
        out.sort(Comparator.comparingLong((RoomMember m) -> m.lastSeen).reversed());
        if (out.size() > MAX_SOURCES) return out.subList(0, MAX_SOURCES);
        return out;
    }

    private SourceAddr resolveAddr(RoomMember m) {
        String ip = m.lastKnownIp;
        int port = m.lastKnownP2pPort;
        if (ip != null && !ip.isBlank() && port > 0) return new SourceAddr(ip, port);
        try {
            PeerDao.Peer p = peerDao.getPeerByNodeId(m.memberNodeId);
            if (p != null && p.ip != null && !p.ip.isBlank() && p.p2pPort > 0) return new SourceAddr(p.ip, p.p2pPort);
        } catch (SQLException ignored) {
        }
        return null;
    }

    private SyncResult syncFromSource(String roomId, String cursor, String peerNodeId, String ip, int port) throws Exception {
        String since = cursor == null || cursor.isBlank() ? "0" : cursor;
        int inserted = 0;
        String maxCursor = since;

        while (true) {
            SyncBatch batch = requestSync(peerNodeId, ip, port, roomId, since, DEFAULT_LIMIT);
            if (batch == null) break;

            int wrote = applyBatch(roomId, batch.messages);
            inserted += wrote;

            if (batch.nextSinceClockValue != null && compareClock(batch.nextSinceClockValue, maxCursor) > 0) {
                maxCursor = batch.nextSinceClockValue;
            }
            since = maxCursor;

            if (!batch.hasMore) break;
        }

        return new SyncResult(inserted, maxCursor);
    }

    private SyncBatch requestSync(String peerNodeId, String ip, int port, String roomId, String sinceClockValue, int limit) throws Exception {
        MessageEnvelope req = new MessageEnvelope();
        req.protocolVersion = 1;
        req.type = MessageType.SYNC_REQUEST;
        req.msgId = java.util.UUID.randomUUID().toString();
        req.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        req.ts = System.currentTimeMillis();
        req.clock = clock.tick();

        JsonObject payload = new JsonObject();
        payload.addProperty("roomId", roomId);
        JsonObject since = new JsonObject();
        since.addProperty("clockValue", sinceClockValue == null || sinceClockValue.isBlank() ? "0" : sinceClockValue);
        payload.add("since", since);
        payload.addProperty("limit", Math.max(1, Math.min(limit, 500)));
        payload.addProperty("wantMembers", false);
        req.payload = payload;

        CompletableFuture<SyncBatch> f = new CompletableFuture<>();
        pendingByRequestId.put(req.msgId, new Pending(req.msgId, roomId, peerNodeId, f));

        try {
            transport.send(peerNodeId, ip, port, req);
            return f.get(5, TimeUnit.SECONDS);
        } finally {
            pendingByRequestId.remove(req.msgId);
        }
    }

    private int applyBatch(String roomId, List<MessageEnvelope> messages) throws Exception {
        if (messages == null || messages.isEmpty()) return 0;
        long now = System.currentTimeMillis();

        Room room = roomDao.getById(roomId);
        String title = room != null && room.roomName != null ? room.roomName : roomId;
        com.example.lanchat.store.ConversationDao.Conversation conv = conversationService.getOrCreateRoomConv(roomId, title, now);

        int inserted = 0;
        long lastTouchTs = 0;
        for (MessageEnvelope env : messages) {
            if (env == null || env.msgId == null) continue;
            JsonObject payload = env.payload != null && env.payload.isJsonObject() ? env.payload.getAsJsonObject() : null;
            if (payload == null) continue;
            String chatType = payload.has("chatType") ? payload.get("chatType").getAsString() : "";
            if (!"ROOM".equals(chatType)) continue;
            String rid = payload.has("roomId") ? payload.get("roomId").getAsString() : null;
            if (!Objects.equals(roomId, rid)) continue;

            seenDao.markSeen(env.msgId, now);

            Message in = new Message();
            in.msgId = env.msgId;
            in.convId = conv.convId;
            in.chatType = "ROOM";
            in.roomId = roomId;
            in.direction = "IN";
            in.fromNodeId = env.from != null ? env.from.nodeId : null;
            in.toNodeId = null;
            in.content = payload.has("content") ? payload.get("content").getAsString() : "";
            in.contentType = payload.has("contentType") ? payload.get("contentType").getAsString() : "text/plain";
            in.ts = env.ts > 0 ? env.ts : now;
            in.updatedAt = now;
            in.clockValue = String.valueOf(env.clock);
            in.status = "DELIVERED";

            try {
                messageDao.insert(in);
                inserted += 1;
            } catch (SQLException ignored) {
            }

            if (env.from != null && env.from.nodeId != null) {
                RoomMember sender = new RoomMember();
                sender.roomId = roomId;
                sender.memberNodeId = env.from.nodeId;
                sender.memberName = env.from.name != null ? env.from.name : env.from.nodeId;
                sender.lastSeen = now;
                try {
                    roomMemberDao.upsert(sender);
                } catch (SQLException ignored) {
                }
            }

            lastTouchTs = Math.max(lastTouchTs, in.ts);
        }
        if (lastTouchTs > 0) {
            try {
                conversationService.touch(conv.convId, lastTouchTs);
            } catch (SQLException ignored) {
            }
        }
        return inserted;
    }

    @Override
    public void onMessage(PeerInfo remotePeerInfo, MessageEnvelope message) {
        try {
            handleIncoming(remotePeerInfo, message);
        } catch (Exception e) {
            System.err.println("SyncService handleIncoming failed: " + e.getMessage());
        }
    }

    private void handleIncoming(PeerInfo remote, MessageEnvelope env) throws Exception {
        if (env == null || env.type == null) return;
        if (MessageType.SYNC_REQUEST.equals(env.type)) {
            handleSyncRequest(remote, env);
            return;
        }
        if (MessageType.SYNC_RESPONSE.equals(env.type)) {
            handleSyncResponse(remote, env);
            return;
        }
        if (MessageType.ERROR.equals(env.type)) {
            handleError(env);
        }
    }

    private void handleSyncRequest(PeerInfo remote, MessageEnvelope env) throws Exception {
        if (remote == null) return;
        JsonObject payload = env.payload != null && env.payload.isJsonObject() ? env.payload.getAsJsonObject() : null;
        if (payload == null) return;
        if (!payload.has("roomId")) return;
        String roomId = payload.get("roomId").getAsString();

        if (!roomMemberDao.isMember(roomId, identity.nodeId)) {
            transport.send(remote.nodeId, remote.ip, remote.p2pPort, buildSyncError(env, roomId, Errors.NOT_MEMBER, "Not a member of room"));
            return;
        }

        String sinceClockValue = "0";
        if (payload.has("since") && payload.get("since").isJsonObject()) {
            JsonObject sinceObj = payload.getAsJsonObject("since");
            if (sinceObj.has("clockValue")) {
                JsonElement cv = sinceObj.get("clockValue");
                sinceClockValue = cv.isJsonPrimitive() ? cv.getAsString() : "0";
            }
        }
        int limit = payload.has("limit") ? payload.get("limit").getAsInt() : DEFAULT_LIMIT;
        limit = Math.max(1, Math.min(limit, 500));

        List<Message> list = messageDao.listRoomMessagesAfterClock(roomId, sinceClockValue, limit + 1);
        boolean hasMore = list.size() > limit;
        if (hasMore) list = list.subList(0, limit);

        String nextSince = sinceClockValue == null || sinceClockValue.isBlank() ? "0" : sinceClockValue;
        if (!list.isEmpty()) {
            Message last = list.get(list.size() - 1);
            if (last.clockValue != null && !last.clockValue.isBlank()) nextSince = last.clockValue;
        }

        JsonArray arr = new JsonArray();
        for (Message m : list) {
            JsonObject msg = new JsonObject();
            msg.addProperty("msgId", m.msgId);
            JsonObject from = new JsonObject();
            String fromNodeId = m.fromNodeId;
            from.addProperty("nodeId", fromNodeId);
            String fromName = resolveName(roomId, fromNodeId);
            if (fromName != null) from.addProperty("name", fromName);
            msg.add("from", from);
            msg.addProperty("ts", m.ts);
            msg.addProperty("clock", safeParseLong(m.clockValue));
            JsonObject p = new JsonObject();
            p.addProperty("chatType", "ROOM");
            p.addProperty("roomId", roomId);
            p.addProperty("content", m.content != null ? m.content : "");
            if (m.contentType != null) p.addProperty("contentType", m.contentType);
            msg.add("payload", p);
            arr.add(msg);
        }

        JsonObject outPayload = new JsonObject();
        outPayload.addProperty("roomId", roomId);
        outPayload.addProperty("requestId", env.msgId);
        outPayload.add("messages", arr);
        outPayload.addProperty("hasMore", hasMore);
        JsonObject next = new JsonObject();
        next.addProperty("clockValue", nextSince);
        outPayload.add("nextSince", next);

        MessageEnvelope resp = new MessageEnvelope();
        resp.protocolVersion = 1;
        resp.type = MessageType.SYNC_RESPONSE;
        resp.msgId = java.util.UUID.randomUUID().toString();
        resp.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
        resp.ts = System.currentTimeMillis();
        resp.clock = clock.tick();
        resp.payload = outPayload;

        transport.send(remote.nodeId, remote.ip, remote.p2pPort, resp);
    }

    private void handleSyncResponse(PeerInfo remote, MessageEnvelope env) {
        JsonObject payload = env.payload != null && env.payload.isJsonObject() ? env.payload.getAsJsonObject() : null;
        if (payload == null) return;

        String requestId = payload.has("requestId") ? payload.get("requestId").getAsString() : null;
        String roomId = payload.has("roomId") ? payload.get("roomId").getAsString() : null;
        if (requestId == null || roomId == null) return;

        Pending p = pendingByRequestId.get(requestId);
        if (p == null) return;

        List<MessageEnvelope> messages = new ArrayList<>();
        if (payload.has("messages") && payload.get("messages").isJsonArray()) {
            JsonArray arr = payload.getAsJsonArray("messages");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                MessageEnvelope me = new MessageEnvelope();
                me.msgId = m.has("msgId") ? m.get("msgId").getAsString() : null;
                me.ts = m.has("ts") ? m.get("ts").getAsLong() : 0;
                me.clock = m.has("clock") ? m.get("clock").getAsLong() : 0;
                if (m.has("from") && m.get("from").isJsonObject()) {
                    JsonObject f = m.getAsJsonObject("from");
                    MessageEnvelope.NodeInfo ni = new MessageEnvelope.NodeInfo();
                    ni.nodeId = f.has("nodeId") ? f.get("nodeId").getAsString() : null;
                    ni.name = f.has("name") ? f.get("name").getAsString() : null;
                    me.from = ni;
                }
                me.payload = m.has("payload") ? m.get("payload") : null;
                messages.add(me);
            }
        }

        boolean hasMore = payload.has("hasMore") && payload.get("hasMore").getAsBoolean();
        String nextSince = null;
        if (payload.has("nextSince") && payload.get("nextSince").isJsonObject()) {
            JsonObject ns = payload.getAsJsonObject("nextSince");
            if (ns.has("clockValue")) nextSince = ns.get("clockValue").getAsString();
        }

        p.future.complete(new SyncBatch(messages, hasMore, nextSince));
    }

    private void handleError(MessageEnvelope env) {
        JsonObject payload = env.payload != null && env.payload.isJsonObject() ? env.payload.getAsJsonObject() : null;
        if (payload == null) return;
        String requestId = payload.has("requestId") ? payload.get("requestId").getAsString() : null;
        if (requestId == null) return;
        Pending p = pendingByRequestId.get(requestId);
        if (p == null) return;
        String message = payload.has("message") ? payload.get("message").getAsString() : "ERROR";
        p.future.completeExceptionally(new IllegalStateException(message));
    }

    private MessageEnvelope buildSyncError(MessageEnvelope request, String roomId, String code, String message) {
        MessageEnvelope env = Errors.buildError(new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName), code, message);
        JsonObject payload = env.payload != null && env.payload.isJsonObject() ? env.payload.getAsJsonObject() : new JsonObject();
        payload.addProperty("requestId", request.msgId);
        payload.addProperty("roomId", roomId);
        env.payload = payload;
        return env;
    }

    private String resolveName(String roomId, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return null;
        if (nodeId.equals(identity.nodeId)) return identity.displayName;
        try {
            RoomMember rm = roomMemberDao.getMember(roomId, nodeId);
            if (rm != null && rm.memberName != null && !rm.memberName.isBlank()) return rm.memberName;
        } catch (SQLException ignored) {
        }
        try {
            PeerDao.Peer p = peerDao.getPeerByNodeId(nodeId);
            if (p != null && p.name != null && !p.name.isBlank()) return p.name;
        } catch (SQLException ignored) {
        }
        return nodeId;
    }

    private static long safeParseLong(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int compareClock(String a, String b) {
        long la = safeParseLong(a);
        long lb = safeParseLong(b);
        return Long.compare(la, lb);
    }
}
