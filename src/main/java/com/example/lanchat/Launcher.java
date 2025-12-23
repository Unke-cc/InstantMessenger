package com.example.lanchat;

import com.example.lanchat.core.Settings;
import com.example.lanchat.discovery.DiscoveryService;
import com.example.lanchat.service.GroupMessageService;
import com.example.lanchat.service.LamportClock;
import com.example.lanchat.service.MessageService;
import com.example.lanchat.service.PeerDirectory;
import com.example.lanchat.service.RoomMembershipService;
import com.example.lanchat.service.RoomService;
import com.example.lanchat.service.SyncService;
import com.example.lanchat.service.TransportService;
import com.example.lanchat.store.Db;
import com.example.lanchat.store.IdentityDao;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.web.ApiRoutes;
import com.example.lanchat.web.WebServer;
import com.example.lanchat.store.ConversationDao;
import com.example.lanchat.store.MessageDao;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.store.RoomDao;
import com.example.lanchat.store.RoomMemberDao;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.Scanner;
import java.util.UUID;

public class Launcher {

    public static void main(String[] args) {
        System.out.println("Starting LAN Chat Node (Web UI)...");

        int p2pPort = Settings.DEFAULT_P2P_PORT;
        String name = "User-" + (System.currentTimeMillis() % 1000);
        int webPort = Settings.DEFAULT_WEB_PORT;
        String dbName = null;

        if (args.length > 0) {
            p2pPort = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            name = args[1];
        }
        if (args.length > 2) {
            webPort = Integer.parseInt(args[2]);
        }
        if (args.length > 3) {
            dbName = args[3];
        }
        
        if (dbName == null || dbName.isBlank()) {
            dbName = "lanchat_" + p2pPort + ".db";
        }

        try {
            Db.init(dbName);
        } catch (SQLException e) {
            System.err.println("Failed to init DB: " + e.getMessage());
            return;
        }

        Identity identity;
        try {
            IdentityDao identityDao = new IdentityDao();
            identity = identityDao.loadOrCreateIdentity(name, p2pPort, webPort);
            
            System.out.println("Node ID: " + identity.nodeId);
            System.out.println("Name:    " + identity.displayName);
            System.out.println("P2P Port:" + identity.p2pPort);
            System.out.println("Web Port:" + identity.webPort);
            
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        TransportService transport = new TransportService(identity);
        LamportClock clock = new LamportClock();
        MessageService messageService = new MessageService(identity, clock, transport);
        RoomService roomService = new RoomService(identity);
        RoomMembershipService roomMembershipService = new RoomMembershipService(identity, clock, transport);
        GroupMessageService groupMessageService = new GroupMessageService(identity, clock, transport);
        SyncService syncService = new SyncService(identity, clock, transport);

        transport.onMessage((remote, env) -> {
            if (env != null) clock.observe(env.clock);
            messageService.onMessage(remote, env);
            roomMembershipService.onMessage(remote, env);
            groupMessageService.onMessage(remote, env);
            syncService.onMessage(remote, env);

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

        try {
            transport.start();
        } catch (Exception e) {
            System.err.println("Failed to start transport: " + e.getMessage());
            return;
        }

        PeerDirectory peerDirectory = new PeerDirectory();
        
        DiscoveryService discoveryService = new DiscoveryService(identity, peerDirectory);
        discoveryService.start();

        roomMembershipService.onJoinAccepted(syncService::syncRoomAsync);
        syncService.syncAllRoomsAsync();

        ApiRoutes apiRoutes = new ApiRoutes(
                identity,
                new IdentityDao(),
                new PeerDao(),
                new ConversationDao(),
                new MessageDao(),
                new RoomDao(),
                new RoomMemberDao(),
                messageService,
                roomService,
                roomMembershipService,
                groupMessageService,
                syncService
        );
        WebServer webServer = new WebServer(identity.webPort, apiRoutes);
        webServer.start();
        System.out.println("Web UI: http://localhost:" + identity.webPort + "/");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            try {
                webServer.close();
            } catch (Exception ignored) {
            }
            try {
                transport.stop();
            } catch (Exception ignored) {
            }
            discoveryService.stop();
            peerDirectory.shutdown();
            syncService.shutdown();
            Db.close();
        }));

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Enter command (add <ip> <port> <name> or quit):");
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if ("quit".equalsIgnoreCase(line.trim())) {
                    System.exit(0);
                } else if (line.startsWith("add ")) {
                    String[] parts = line.split(" ");
                    if (parts.length >= 3) {
                        String ip = parts[1];
                        int port = Integer.parseInt(parts[2]);
                        String pname = parts.length > 3 ? parts[3] : "Unknown";
                        peerDirectory.addPeer(UUID.randomUUID().toString(), pname, ip, port);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
