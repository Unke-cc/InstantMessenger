package com.example.lanchat.web;

import com.example.lanchat.core.Settings;
import com.example.lanchat.service.GroupMessageService;
import com.example.lanchat.service.MessageService;
import com.example.lanchat.service.RoomMembershipService;
import com.example.lanchat.service.RoomService;
import com.example.lanchat.service.SyncService;
import com.example.lanchat.service.FileService;
import com.example.lanchat.store.ConversationDao;
import com.example.lanchat.store.ConversationDao.Conversation;
import com.example.lanchat.store.FileDao;
import com.example.lanchat.store.FileDao.FileMeta;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
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
    private Identity identity;
    private boolean loggedIn = false;
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
    private final FileDao fileDao;
    private final FileService fileService;

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
            SyncService syncService,
            FileDao fileDao,
            FileService fileService
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
        this.fileDao = fileDao;
        this.fileService = fileService;
        // Start as not logged in to force use of the login page
        this.loggedIn = false;
    }

    private Object getAuthStatus(Request req, Response res) throws SQLException {
        res.type("application/json");
        Dto.AuthStatusDto status = new Dto.AuthStatusDto();
        status.registered = identityDao.isRegistered();
        status.loggedIn = this.loggedIn;
        if (this.loggedIn && this.identity != null) {
            Dto.MeDto me = new Dto.MeDto();
            me.nodeId = identity.nodeId;
            me.name = identity.displayName;
            me.p2pPort = identity.p2pPort;
            me.webPort = identity.webPort;
            status.me = me;
        }
        return gson.toJson(Dto.ok(status));
    }

    private Object postLogin(Request req, Response res) throws SQLException {
        res.type("application/json");
        Dto.LoginRequest loginReq = gson.fromJson(req.body(), Dto.LoginRequest.class);
        if (loginReq == null || loginReq.name == null || loginReq.password == null) {
            return gson.toJson(Dto.fail("Missing credentials"));
        }

        Identity id = identityDao.login(loginReq.name, loginReq.password);
        if (id != null) {
            // Update the existing identity object so other services see the change
            this.identity.nodeId = id.nodeId;
            this.identity.displayName = id.displayName;
            this.identity.passwordHash = id.passwordHash;
            this.identity.p2pPort = id.p2pPort;
            this.identity.webPort = id.webPort;
            
            this.loggedIn = true;
            return gson.toJson(Dto.ok("Login success"));
        } else {
            return gson.toJson(Dto.fail("Invalid nickname or password"));
        }
    }

    private Object postRegister(Request req, Response res) throws SQLException {
        res.type("application/json");
        Dto.RegisterRequest regReq = gson.fromJson(req.body(), Dto.RegisterRequest.class);
        if (regReq == null || regReq.name == null || regReq.password == null) {
            return gson.toJson(Dto.fail("Missing information"));
        }

        // Validate nickname
        String name = regReq.name.trim();
        if (name.length() < 2 || name.length() > 20) {
            return gson.toJson(Dto.fail("Nickname length must be between 2 and 20 characters"));
        }
        if (!name.matches("^[\\u4e00-\\u9fa5a-zA-Z0-9_]+$")) {
            return gson.toJson(Dto.fail("Nickname contains invalid characters (allowed: Chinese, English, Numbers, Underscore)"));
        }

        if (identityDao.isRegistered()) {
            return gson.toJson(Dto.fail("Already registered on this node"));
        }

        // We use current ports from the placeholder identity
        int p2pPort = this.identity.p2pPort;
        int webPort = this.identity.webPort;
        
        Identity id = identityDao.register(name, regReq.password, p2pPort, webPort);
        
        // Update the existing identity object
        this.identity.nodeId = id.nodeId;
        this.identity.displayName = id.displayName;
        this.identity.passwordHash = id.passwordHash;
        this.identity.p2pPort = id.p2pPort;
        this.identity.webPort = id.webPort;
        
        this.loggedIn = true;
        return gson.toJson(Dto.ok("Registration success"));
    }

    private Object postLogout(Request req, Response res) {
        res.type("application/json");
        this.loggedIn = false;
        return gson.toJson(Dto.ok("Logged out"));
    }

    public void register() {
        Spark.before("/api/*", (req, res) -> {
            String path = req.pathInfo();
            if (path.startsWith("/api/auth/")) return;
            if (!loggedIn) {
                Spark.halt(401, gson.toJson(Dto.fail("Unauthorized. Please login.")));
            }
        });

        Spark.get("/api/auth/status", this::getAuthStatus);
        Spark.post("/api/auth/login", this::postLogin);
        Spark.post("/api/auth/register", this::postRegister);
        Spark.post("/api/auth/logout", this::postLogout);

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
        Spark.post("/api/rooms/invite", this::postInviteMembers);
        Spark.get("/api/rooms/members", this::getRoomMembers);
        Spark.post("/api/rooms/sync", this::postSyncRoom);

        Spark.get("/api/files", this::getFiles);
        Spark.post("/api/files/upload/init", this::postFileUploadInit);
        Spark.post("/api/files/upload/chunk", this::postFileUploadChunk);
        Spark.post("/api/files/upload/complete", this::postFileUploadComplete);
        Spark.get("/api/files/download/:fileId", this::getFileDownload);

        Spark.get("/api/poll", this::getPoll);
    }

    private Object getFiles(Request req, Response res) throws SQLException {
        res.type("application/json");
        List<FileMeta> metas = fileDao.listActiveFiles();
        List<Dto.FileDto> out = new ArrayList<>();
        for (FileMeta m : metas) {
            Dto.FileDto dto = new Dto.FileDto();
            dto.fileId = m.fileId;
            dto.fileName = m.fileName;
            dto.fileSize = m.fileSize;
            dto.fileHash = m.fileHash;
            dto.ownerNodeId = m.ownerNodeId;
            dto.status = m.status;
            dto.createdAt = m.createdAt;
            dto.expiresAt = m.expiresAt;
            dto.contentType = m.contentType;
            dto.downloadUrl = "/api/files/download/" + m.fileId;
            out.add(dto);
        }
        return gson.toJson(Dto.ok(out));
    }

    private Object postFileUploadInit(Request req, Response res) throws SQLException {
        res.type("application/json");
        Dto.FileUploadInitRequest body = gson.fromJson(req.body(), Dto.FileUploadInitRequest.class);
        if (body == null || body.fileName == null) return gson.toJson(Dto.fail("Missing fileName"));

        FileMeta meta = fileService.initUpload(body.fileName, body.fileSize, body.contentType, body.fileHash, identity.nodeId);
        
        Dto.FileUploadInitResponse resp = new Dto.FileUploadInitResponse();
        resp.fileId = meta.fileId;
        resp.chunkSize = fileService.getChunkSize();
        resp.missingChunks = new ArrayList<>(); // For now, all chunks are missing
        int numChunks = (int) Math.ceil((double) meta.fileSize / resp.chunkSize);
        for (int i = 0; i < numChunks; i++) resp.missingChunks.add(i);

        return gson.toJson(Dto.ok(resp));
    }

    private Object postFileUploadChunk(Request req, Response res) throws Exception {
        res.type("application/json");
        String fileId = req.queryParams("fileId");
        String chunkIndexStr = req.queryParams("chunkIndex");
        if (fileId == null || chunkIndexStr == null) return gson.toJson(Dto.fail("Missing params"));

        int chunkIndex = Integer.parseInt(chunkIndexStr);
        byte[] data = req.bodyAsBytes();
        fileService.saveChunk(fileId, chunkIndex, data);

        Dto.FileUploadChunkResponse resp = new Dto.FileUploadChunkResponse();
        resp.success = true;
        resp.chunkIndex = chunkIndex;
        return gson.toJson(Dto.ok(resp));
    }

    private Object postFileUploadComplete(Request req, Response res) throws Exception {
        res.type("application/json");
        // Try both query param and body for flexibility
        String fileId = req.queryParams("fileId");
        if (fileId == null) {
            java.util.Map<String, String> body = gson.fromJson(req.body(), java.util.Map.class);
            if (body != null) fileId = body.get("fileId");
        }
        
        if (fileId == null) return gson.toJson(Dto.fail("Missing fileId"));

        boolean success = fileService.completeUpload(fileId);
        if (success) {
            return gson.toJson(Dto.ok(true));
        } else {
            return gson.toJson(Dto.fail("Upload completion failed (hash mismatch or missing chunks)"));
        }
    }

    private Object getFileDownload(Request req, Response res) throws Exception {
        String fileId = req.params(":fileId");
        File file = fileService.getFile(fileId);
        if (file == null || !file.exists()) {
            res.status(404);
            return "File not found";
        }

        FileMeta meta = fileDao.getById(fileId);
        res.type(meta.contentType != null ? meta.contentType : "application/octet-stream");
        res.header("Content-Disposition", "attachment; filename=\"" + meta.fileName + "\"");
        res.header("Content-Length", String.valueOf(file.length()));

        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = res.raw().getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }
        return res.raw();
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

    private Object postInviteMembers(Request req, Response res) {
        res.type("application/json");
        Dto.InviteMembersRequest body = parse(req.body(), Dto.InviteMembersRequest.class);
        if (body == null) return gson.toJson(Dto.fail("Bad body"));
        if (body.roomId == null || body.roomId.trim().isEmpty()) return gson.toJson(Dto.fail("Missing roomId"));
        if (body.members == null || body.members.isEmpty()) return gson.toJson(Dto.fail("Missing members"));
        try {
            roomMembershipService.inviteMembers(body.roomId.trim(), body.members);
            return gson.toJson(Dto.ok(true));
        } catch (Exception e) {
            return gson.toJson(Dto.fail("Invite failed: " + e.getMessage()));
        }
    }

    private Object getRoomMembers(Request req, Response res) {
        res.type("application/json");
        String roomId = q(req, "roomId");
        if (roomId == null || roomId.trim().isEmpty()) return gson.toJson(Dto.fail("Missing roomId"));
        try {
            long now = System.currentTimeMillis();
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
                dto.role = m.role;
                dto.online = (m.lastSeen > 0) && (now - m.lastSeen) < Settings.PEER_TTL_MS;
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
