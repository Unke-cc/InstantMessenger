package com.example.lanchat.web;

import com.example.lanchat.core.Settings;
import com.example.lanchat.service.GroupMessageService;
import com.example.lanchat.service.MessageService;
import com.example.lanchat.service.RoomMembershipService;
import com.example.lanchat.service.RoomService;
import com.example.lanchat.service.SyncService;
import com.example.lanchat.store.ConversationDao;
import com.example.lanchat.store.ConversationDao.Conversation;
import com.example.lanchat.store.IdentityDao;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.MessageDao;
import com.example.lanchat.store.MessageDao.Message;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.store.PeerDao.Peer;
import com.example.lanchat.store.RoomDao;
import com.example.lanchat.store.RoomDao.Room;
import com.example.lanchat.store.RoomMemberDao;
import com.example.lanchat.store.RoomMemberDao.RoomMember;
import com.google.gson.Gson;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import spark.Request;
import spark.Response;
import spark.Spark;

public class ApiRoutes {

    private final Gson gson;
    private final Identity identity;
    private final IdentityDao identityDao;
    private final PeerDao peerDao;
    private final ConversationDao conversationDao;
    private final MessageDao messageDao;
    private final RoomDao roomDao;
    private final RoomMemberDao roomMemberDao;
    private final MessageService messageService;
    private final RoomService roomService;
    private final RoomMembershipService roomMembershipService;
    private final GroupMessageService groupMessageService;
    private final SyncService syncService;

    public ApiRoutes(
            Identity identity,
            IdentityDao identityDao,
            PeerDao peerDao,
            ConversationDao conversationDao,
            MessageDao messageDao,
            RoomDao roomDao,
            RoomMemberDao roomMemberDao,
            MessageService messageService,
            RoomService roomService,
            RoomMembershipService roomMembershipService,
            GroupMessageService groupMessageService,
            SyncService syncService
    ) {
        this.gson = new Gson();
        this.identity = identity;
        this.identityDao = identityDao;
        this.peerDao = peerDao;
        this.conversationDao = conversationDao;
        this.messageDao = messageDao;
        this.roomDao = roomDao;
        this.roomMemberDao = roomMemberDao;
        this.messageService = messageService;
        this.roomService = roomService;
        this.roomMembershipService = roomMembershipService;
        this.groupMessageService = groupMessageService;
        this.syncService = syncService;
    }

    public void register() {
        Spark.get("/api/me", this::getMe);
        Spark.post("/api/me", this::postMe);

        Spark.get("/api/peers", this::getPeers);
        Spark.post("/api/peers", this::postPeer);

        Spark.get("/api/conversations", this::getConversations);
        Spark.get("/api/messages", this::getMessages);

        Spark.post("/api/send/private", this::postSendPrivate);
        Spark.post("/api/send/room", this::postSendRoom);

        Spark.get("/api/rooms", this::getRooms);
        Spark.post("/api/rooms", this::postRoom);
        Spark.post("/api/rooms/join", this::postJoinRoom);
        Spark.get("/api/rooms/members", this::getRoomMembers);
        Spark.post("/api/rooms/sync", this::postSyncRoom);

        Spark.get("/api/poll", this::getPoll);
    }

    private Object getMe(Request req, Response res) {
        res.type("application/json");
        Dto.MeDto me = new Dto.MeDto();
        me.nodeId = identity.nodeId;
        me.name = identity.displayName;
        me.p2pPort = identity.p2pPort;
        me.webPort = identity.webPort;
        return gson.toJson(Dto.ok(me));
    }

    private Object postMe(Request req, Response res) {
        res.type("application/json");
        Dto.UpdateMeRequest body = parse(req.body(), Dto.UpdateMeRequest.class);
        if (body == null || body.name == null) return gson.toJson(Dto.fail("Missing name"));
        String name = body.name.trim();
        if (name.isEmpty()) return gson.toJson(Dto.fail("Name empty"));
        if (name.length() > 64) return gson.toJson(Dto.fail("Name too long"));
        try {
            identityDao.updateDisplayName(identity.nodeId, name);
            identity.displayName = name;
            return getMe(req, res);
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private Object getPeers(Request req, Response res) {
        res.type("application/json");
        try {
            long now = System.currentTimeMillis();
            List<Peer> peers = peerDao.listAllPeers();
            List<Dto.PeerDto> out = new ArrayList<>();
            for (Peer p : peers) {
                Dto.PeerDto dto = new Dto.PeerDto();
                dto.nodeId = p.nodeId;
                dto.name = p.name;
                dto.ip = p.ip;
                dto.p2pPort = p.p2pPort;
                dto.lastSeen = p.lastSeen;
                dto.online = (p.lastSeen > 0) && (now - p.lastSeen) < Settings.PEER_TTL_MS;
                out.add(dto);
            }
            return gson.toJson(Dto.ok(out));
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private Object postPeer(Request req, Response res) {
        res.type("application/json");
        Dto.AddPeerRequest body = parse(req.body(), Dto.AddPeerRequest.class);
        if (body == null) return gson.toJson(Dto.fail("Bad body"));
        if (body.ip == null || body.ip.trim().isEmpty()) return gson.toJson(Dto.fail("Missing ip"));
        if (body.p2pPort <= 0) return gson.toJson(Dto.fail("Invalid p2pPort"));
        String name = body.name == null || body.name.trim().isEmpty() ? "Unknown" : body.name.trim();
        String placeholder = "manual-" + UUID.randomUUID();
        try {
            peerDao.upsertPeer(placeholder, name, body.ip.trim(), body.p2pPort, System.currentTimeMillis());
            return gson.toJson(Dto.ok(true));
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private Object getConversations(Request req, Response res) {
        res.type("application/json");
        try {
            List<Conversation> convs = conversationDao.listConversations();
            List<Dto.ConversationDto> out = new ArrayList<>();
            for (Conversation c : convs) {
                Dto.ConversationDto dto = new Dto.ConversationDto();
                dto.convId = c.convId;
                dto.convType = c.convType;
                dto.peerNodeId = c.peerNodeId;
                dto.roomId = c.roomId;
                dto.title = c.title;
                dto.createdAt = c.createdAt;
                dto.lastMsgTs = c.lastMsgTs;
                out.add(dto);
            }
            return gson.toJson(Dto.ok(out));
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private Object getMessages(Request req, Response res) {
        res.type("application/json");
        String convId = q(req, "convId");
        String roomId = q(req, "roomId");
        String peerNodeId = q(req, "peerNodeId");
        long beforeTs = qLong(req, "beforeTs", System.currentTimeMillis() + 1);
        int limit = qInt(req, "limit", 50);
        limit = Math.max(1, Math.min(limit, 200));

        try {
            String resolvedConvId = resolveConvId(convId, roomId, peerNodeId);
            if (resolvedConvId == null) return gson.toJson(Dto.fail("Missing convId/roomId/peerNodeId"));
            List<Message> list = messageDao.listMessages(resolvedConvId, beforeTs, limit);
            Collections.reverse(list);
            List<Dto.MessageDto> out = new ArrayList<>();
            for (Message m : list) out.add(toMessageDto(m));
            return gson.toJson(Dto.ok(out));
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private Object postSendPrivate(Request req, Response res) {
        res.type("application/json");
        Dto.SendPrivateRequest body = parse(req.body(), Dto.SendPrivateRequest.class);
        if (body == null) return gson.toJson(Dto.fail("Bad body"));
        if (body.content == null || body.content.trim().isEmpty()) return gson.toJson(Dto.fail("Empty content"));
        String content = body.content;
        try {
            if (body.peerNodeId != null && !body.peerNodeId.trim().isEmpty()) {
                messageService.sendPrivate(body.peerNodeId.trim(), content);
                return gson.toJson(Dto.ok(true));
            }
            if (body.ip != null && !body.ip.trim().isEmpty() && body.port != null && body.port > 0) {
                messageService.sendPrivate(body.ip.trim() + ":" + body.port, content);
                return gson.toJson(Dto.ok(true));
            }
            return gson.toJson(Dto.fail("Missing peerNodeId or ip+port"));
        } catch (Exception e) {
            return gson.toJson(Dto.fail("Send failed: " + e.getMessage()));
        }
    }

    private Object postSendRoom(Request req, Response res) {
        res.type("application/json");
        Dto.SendRoomRequest body = parse(req.body(), Dto.SendRoomRequest.class);
        if (body == null) return gson.toJson(Dto.fail("Bad body"));
        if (body.roomId == null || body.roomId.trim().isEmpty()) return gson.toJson(Dto.fail("Missing roomId"));
        if (body.content == null || body.content.trim().isEmpty()) return gson.toJson(Dto.fail("Empty content"));
        try {
            groupMessageService.sendRoomMessage(body.roomId.trim(), body.content);
            return gson.toJson(Dto.ok(true));
        } catch (Exception e) {
            return gson.toJson(Dto.fail("Send failed: " + e.getMessage()));
        }
    }

    private Object getRooms(Request req, Response res) {
        res.type("application/json");
        try {
            List<Room> rooms = roomDao.listRooms();
            List<Dto.RoomDto> out = new ArrayList<>();
            for (Room r : rooms) {
                Dto.RoomDto dto = new Dto.RoomDto();
                dto.roomId = r.roomId;
                dto.roomName = r.roomName;
                dto.createdAt = r.createdAt;
                dto.policy = r.policy;
                out.add(dto);
            }
            return gson.toJson(Dto.ok(out));
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private Object postRoom(Request req, Response res) {
        res.type("application/json");
        Dto.CreateRoomRequest body = parse(req.body(), Dto.CreateRoomRequest.class);
        if (body == null) return gson.toJson(Dto.fail("Bad body"));
        if (body.roomName == null || body.roomName.trim().isEmpty()) return gson.toJson(Dto.fail("Missing roomName"));
        String policy = body.policy == null || body.policy.trim().isEmpty() ? "open" : body.policy.trim();
        try {
            String roomId = roomService.createRoom(body.roomName.trim(), policy, body.token);
            Dto.CreateRoomResponse out = new Dto.CreateRoomResponse();
            out.roomId = roomId;
            return gson.toJson(Dto.ok(out));
        } catch (Exception e) {
            return gson.toJson(Dto.fail("Create failed: " + e.getMessage()));
        }
    }

    private Object postJoinRoom(Request req, Response res) {
        res.type("application/json");
        Dto.JoinRoomRequest body = parse(req.body(), Dto.JoinRoomRequest.class);
        if (body == null) return gson.toJson(Dto.fail("Bad body"));
        if (body.roomId == null || body.roomId.trim().isEmpty()) return gson.toJson(Dto.fail("Missing roomId"));
        if (body.inviterIp == null || body.inviterIp.trim().isEmpty()) return gson.toJson(Dto.fail("Missing inviterIp"));
        if (body.inviterPort <= 0) return gson.toJson(Dto.fail("Invalid inviterPort"));
        try {
            roomMembershipService.joinRoom(body.roomId.trim(), body.inviterIp.trim(), body.inviterPort, body.token);
            return gson.toJson(Dto.ok(true));
        } catch (Exception e) {
            return gson.toJson(Dto.fail("Join failed: " + e.getMessage()));
        }
    }

    private Object getRoomMembers(Request req, Response res) {
        res.type("application/json");
        String roomId = q(req, "roomId");
        if (roomId == null || roomId.trim().isEmpty()) return gson.toJson(Dto.fail("Missing roomId"));
        try {
            List<RoomMember> members = roomMemberDao.listMembers(roomId.trim());
            List<Dto.RoomMemberDto> out = new ArrayList<>();
            for (RoomMember m : members) {
                Dto.RoomMemberDto dto = new Dto.RoomMemberDto();
                dto.roomId = m.roomId;
                dto.nodeId = m.memberNodeId;
                dto.name = m.memberName;
                dto.ip = m.lastKnownIp;
                dto.p2pPort = m.lastKnownP2pPort;
                dto.lastSeen = m.lastSeen;
                out.add(dto);
            }
            return gson.toJson(Dto.ok(out));
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private Object postSyncRoom(Request req, Response res) {
        res.type("application/json");
        Dto.SyncRoomRequest body = parse(req.body(), Dto.SyncRoomRequest.class);
        if (body == null || body.roomId == null || body.roomId.trim().isEmpty()) return gson.toJson(Dto.fail("Missing roomId"));
        syncService.syncRoomAsync(body.roomId.trim());
        return gson.toJson(Dto.ok(true));
    }

    private Object getPoll(Request req, Response res) {
        res.type("application/json");
        String convId = q(req, "convId");
        String roomId = q(req, "roomId");
        String peerNodeId = q(req, "peerNodeId");
        long sinceTs = qLong(req, "sinceTs", 0);
        int limit = qInt(req, "limit", 200);
        limit = Math.max(1, Math.min(limit, 500));

        try {
            String resolvedConvId = resolveConvId(convId, roomId, peerNodeId);
            if (resolvedConvId == null) return gson.toJson(Dto.fail("Missing convId/roomId/peerNodeId"));
            List<Message> list = messageDao.listMessagesUpdatedAfter(sinceTs, resolvedConvId, limit);
            long max = sinceTs;
            List<Dto.MessageDto> out = new ArrayList<>();
            for (Message m : list) {
                out.add(toMessageDto(m));
                max = Math.max(max, m.updatedAt);
            }
            Dto.PollResponse pr = new Dto.PollResponse();
            pr.maxTs = max;
            pr.messages = out;
            return gson.toJson(Dto.ok(pr));
        } catch (SQLException e) {
            return gson.toJson(Dto.fail("DB error"));
        }
    }

    private String resolveConvId(String convId, String roomId, String peerNodeId) throws SQLException {
        if (convId != null && !convId.trim().isEmpty()) return convId.trim();
        long now = System.currentTimeMillis();
        if (roomId != null && !roomId.trim().isEmpty()) {
            Room room = roomDao.getById(roomId.trim());
            String title = room != null && room.roomName != null ? room.roomName : roomId.trim();
            Conversation conv = conversationDao.getOrCreateRoom(roomId.trim(), title, now);
            return conv != null ? conv.convId : null;
        }
        if (peerNodeId != null && !peerNodeId.trim().isEmpty()) {
            Peer peer = peerDao.getPeerByNodeId(peerNodeId.trim());
            String title = peer != null && peer.name != null ? peer.name : peerNodeId.trim();
            Conversation conv = conversationDao.getOrCreatePrivate(peerNodeId.trim(), title, now);
            return conv != null ? conv.convId : null;
        }
        return null;
    }

    private Dto.MessageDto toMessageDto(Message m) throws SQLException {
        Dto.MessageDto dto = new Dto.MessageDto();
        dto.msgId = m.msgId;
        dto.convId = m.convId;
        dto.roomId = m.roomId;
        dto.chatType = m.chatType;
        dto.direction = m.direction;
        dto.fromNodeId = m.fromNodeId;
        dto.fromName = resolveName(m.roomId, m.fromNodeId);
        dto.content = m.content;
        dto.contentType = m.contentType;
        dto.ts = m.ts;
        dto.updatedAt = m.updatedAt;
        dto.status = m.status;
        return dto;
    }

    private String resolveName(String roomId, String nodeId) throws SQLException {
        if (nodeId == null) return "";
        if (nodeId.equals(identity.nodeId)) return identity.displayName;
        if (roomId != null && !roomId.isEmpty()) {
            RoomMember rm = roomMemberDao.getMember(roomId, nodeId);
            if (rm != null && rm.memberName != null && !rm.memberName.isEmpty()) return rm.memberName;
        }
        Peer p = peerDao.getPeerByNodeId(nodeId);
        if (p != null && p.name != null && !p.name.isEmpty()) return p.name;
        return nodeId;
    }

    private String q(Request req, String key) {
        String v = req.queryParams(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private long qLong(Request req, String key, long def) {
        String v = req.queryParams(key);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int qInt(Request req, String key, int def) {
        String v = req.queryParams(key);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private <T> T parse(String body, Class<T> clazz) {
        if (body == null || body.isEmpty()) return null;
        try {
            return gson.fromJson(body, clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
