package com.example.lanchat.demo;

import com.example.lanchat.core.Settings;
import com.example.lanchat.service.GroupMessageService;
import com.example.lanchat.service.LamportClock;
import com.example.lanchat.service.MessageService;
import com.example.lanchat.service.RoomMembershipService;
import com.example.lanchat.service.RoomService;
import com.example.lanchat.service.TransportService;
import com.example.lanchat.store.ConversationDao;
import com.example.lanchat.store.ConversationDao.Conversation;
import com.example.lanchat.store.Db;
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
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class ChatCliMain {

    public static void main(String[] args) throws Exception {
        int p2pPort = Settings.DEFAULT_P2P_PORT;
        String name = "User-" + (System.currentTimeMillis() % 1000);
        String dbName = null;

        if (args.length > 0) p2pPort = Integer.parseInt(args[0]);
        if (args.length > 1) name = args[1];
        if (args.length > 2) dbName = args[2];
        if (dbName == null) dbName = "lanchat_" + p2pPort + ".db";

        Db.init(dbName);

        IdentityDao identityDao = new IdentityDao();
        Identity identity = identityDao.loadOrCreateIdentity(name, p2pPort, Settings.DEFAULT_WEB_PORT);

        TransportService transport = new TransportService(identity);
        LamportClock clock = new LamportClock();
        MessageService messageService = new MessageService(identity, clock, transport);
        RoomService roomService = new RoomService(identity);
        RoomMembershipService roomMembershipService = new RoomMembershipService(identity, clock, transport);
        GroupMessageService groupMessageService = new GroupMessageService(identity, clock, transport);
        transport.onMessage((remote, env) -> {
            if (env != null) {
                clock.observe(env.clock);
            }
            messageService.onMessage(remote, env);
            roomMembershipService.onMessage(remote, env);
            groupMessageService.onMessage(remote, env);
            if (env == null || env.type == null) return;
            if ("CHAT".equals(env.type) && env.payload != null && env.payload.isJsonObject()) {
                JsonObject payload = env.payload.getAsJsonObject();
                String chatType = payload.has("chatType") ? payload.get("chatType").getAsString() : "";
                if ("PRIVATE".equals(chatType)) {
                    String content = payload.has("content") ? payload.get("content").getAsString() : "";
                    String fromName = env.from != null && env.from.name != null ? env.from.name : remote.name;
                    System.out.println(fromName + ": " + content);
                }
            }
        });
        transport.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            transport.stop();
            Db.close();
        }));

        PeerDao peerDao = new PeerDao();
        ConversationDao conversationDao = new ConversationDao();
        MessageDao messageDao = new MessageDao();
        RoomDao roomDao = new RoomDao();
        RoomMemberDao roomMemberDao = new RoomMemberDao();

        System.out.println("Commands:");
        System.out.println("/me");
        System.out.println("/peers");
        System.out.println("/addpeer <ip> <port> <name?>");
        System.out.println("/send <peerNodeId|ip:port> <text>");
        System.out.println("/senddup <peerNodeId|ip:port> <text>");
        System.out.println("/mkroom <roomName>");
        System.out.println("/rooms");
        System.out.println("/join <roomId> <inviterIp:port> <token?>");
        System.out.println("/members <roomId>");
        System.out.println("/rsend <roomId> <text>");
        System.out.println("/rhistory <roomId> <n>");
        System.out.println("/convs");
        System.out.println("/history <convId> <n>");
        System.out.println("/quit");

        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            if ("/quit".equalsIgnoreCase(line)) {
                System.exit(0);
                return;
            }
            if ("/me".equalsIgnoreCase(line)) {
                System.out.println(identity.nodeId + " / " + identity.displayName + " / " + identity.p2pPort);
                continue;
            }
            if ("/peers".equalsIgnoreCase(line)) {
                List<Peer> peers = peerDao.listAllPeers();
                for (Peer p : peers) {
                    System.out.println(p);
                }
                continue;
            }
            if (line.startsWith("/addpeer ")) {
                String[] parts = line.split(" ", 4);
                if (parts.length < 3) continue;
                String ip = parts[1];
                int port = Integer.parseInt(parts[2]);
                String peerName = parts.length >= 4 ? parts[3] : "Unknown";
                String placeholderNodeId = "manual-" + UUID.randomUUID();
                peerDao.upsertPeer(placeholderNodeId, peerName, ip, port, System.currentTimeMillis());
                System.out.println("Added peer: " + peerName + " @ " + ip + ":" + port + " (" + placeholderNodeId + ")");
                continue;
            }
            if (line.startsWith("/senddup ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) continue;
                String target = parts[1];
                String text = parts[2];
                try {
                    String fixedMsgId = UUID.randomUUID().toString();
                    messageService.sendPrivateWithMsgId(target, text, fixedMsgId);
                    messageService.sendPrivateWithMsgId(target, text, fixedMsgId);
                } catch (Exception e) {
                    System.err.println("Send failed: " + e.getMessage());
                }
                continue;
            }
            if (line.startsWith("/send ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) continue;
                String target = parts[1];
                String text = parts[2];
                try {
                    messageService.sendPrivate(target, text);
                } catch (Exception e) {
                    System.err.println("Send failed: " + e.getMessage());
                }
                continue;
            }
            if (line.startsWith("/mkroom ")) {
                String[] parts = line.split(" ", 2);
                if (parts.length < 2) continue;
                String roomName = parts[1].trim();
                if (roomName.isEmpty()) continue;
                try {
                    String roomId = roomService.createRoom(roomName, "open", null);
                    System.out.println("Created room: " + roomId + " (" + roomName + ")");
                } catch (Exception e) {
                    System.err.println("Create room failed: " + e.getMessage());
                }
                continue;
            }
            if ("/rooms".equalsIgnoreCase(line)) {
                try {
                    List<Room> rooms = roomDao.listRooms();
                    for (Room r : rooms) {
                        System.out.println(r.roomId + " / " + r.roomName + " / " + r.policy + " / " + r.createdAt);
                    }
                } catch (Exception e) {
                    System.err.println("List rooms failed: " + e.getMessage());
                }
                continue;
            }
            if (line.startsWith("/join ")) {
                String[] parts = line.split(" ", 4);
                if (parts.length < 3) continue;
                String roomId = parts[1];
                String inviter = parts[2];
                String token = parts.length >= 4 ? parts[3] : null;
                if (!inviter.contains(":")) continue;
                String[] ap = inviter.split(":", 2);
                String ip = ap[0];
                int port = Integer.parseInt(ap[1]);
                try {
                    roomMembershipService.joinRoom(roomId, ip, port, token);
                    System.out.println("Joined room: " + roomId);
                } catch (Exception e) {
                    System.err.println("Join failed: " + e.getMessage());
                }
                continue;
            }
            if (line.startsWith("/members ")) {
                String[] parts = line.split(" ", 2);
                if (parts.length < 2) continue;
                String roomId = parts[1];
                try {
                    List<RoomMember> members = roomMemberDao.listMembers(roomId);
                    for (RoomMember m : members) {
                        System.out.println(m.memberNodeId + " / " + m.memberName + " / " + m.lastKnownIp + ":" + m.lastKnownP2pPort + " / " + m.lastSeen);
                    }
                } catch (Exception e) {
                    System.err.println("List members failed: " + e.getMessage());
                }
                continue;
            }
            if (line.startsWith("/rsend ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) continue;
                String roomId = parts[1];
                String text = parts[2];
                try {
                    groupMessageService.sendRoomMessage(roomId, text);
                } catch (Exception e) {
                    System.err.println("Room send failed: " + e.getMessage());
                }
                continue;
            }
            if (line.startsWith("/rhistory ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) continue;
                String roomId = parts[1];
                int n = Integer.parseInt(parts[2]);
                try {
                    List<Message> msgs = messageDao.listLatestRoomMessages(roomId, n);
                    Collections.reverse(msgs);
                    for (Message m : msgs) {
                        System.out.println(m.ts + " " + m.direction + " " + m.status + " " + m.fromNodeId + " : " + m.content);
                    }
                } catch (Exception e) {
                    System.err.println("Room history failed: " + e.getMessage());
                }
                continue;
            }
            if ("/convs".equalsIgnoreCase(line)) {
                List<Conversation> convs = conversationDao.listConversations();
                for (Conversation c : convs) {
                    System.out.println(c.convId + " / " + c.convType + " / " + c.title + " / " + c.lastMsgTs + " / " + c.peerNodeId + " / " + c.roomId);
                }
                continue;
            }
            if (line.startsWith("/history ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) continue;
                String convId = parts[1];
                int n = Integer.parseInt(parts[2]);
                List<Message> msgs = messageDao.listLatestMessages(convId, n);
                Collections.reverse(msgs);
                for (Message m : msgs) {
                    System.out.println(m.ts + " " + m.direction + " " + m.status + " " + m.fromNodeId + " -> " + m.toNodeId + " : " + m.content);
                }
            }
        }
    }
}
