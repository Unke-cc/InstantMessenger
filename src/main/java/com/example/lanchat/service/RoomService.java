package com.example.lanchat.service;

import com.example.lanchat.store.RoomDao;
import com.example.lanchat.store.RoomDao.Room;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.RoomMemberDao;
import com.example.lanchat.store.RoomMemberDao.RoomMember;
import java.sql.SQLException;
import java.util.UUID;

public class RoomService {

    private final Identity identity;
    private final RoomDao roomDao;
    private final RoomMemberDao roomMemberDao;
    private final ConversationService conversationService;

    public RoomService(Identity identity) {
        this.identity = identity;
        this.roomDao = new RoomDao();
        this.roomMemberDao = new RoomMemberDao();
        this.conversationService = new ConversationService();
    }

    public String createRoom(String roomName, String policy, String token) throws SQLException {
        String roomId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Room r = new Room();
        r.roomId = roomId;
        r.roomName = roomName;
        r.createdAt = now;
        r.policy = policy == null ? "open" : policy;
        r.roomKeyHash = token;
        roomDao.insert(r);
        conversationService.getOrCreateRoomConv(roomId, roomName, now);

        RoomMember self = new RoomMember();
        self.roomId = roomId;
        self.memberNodeId = identity.nodeId;
        self.memberName = identity.displayName;
        self.lastKnownIp = null;
        self.lastKnownP2pPort = identity.p2pPort;
        self.lastSeen = now;
        self.role = "OWNER";
        roomMemberDao.upsert(self);

        return roomId;
    }
}
