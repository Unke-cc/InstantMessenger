package com.example.lanchat.demo;

import com.example.lanchat.core.Settings;
import com.example.lanchat.service.MessageService;
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
        MessageService messageService = new MessageService(identity.nodeId, identity.displayName, transport);
        transport.onMessage((remote, env) -> {
            messageService.onMessage(remote, env);
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

        System.out.println("Commands:");
        System.out.println("/me");
        System.out.println("/peers");
        System.out.println("/addpeer <ip> <port> <name?>");
        System.out.println("/send <peerNodeId|ip:port> <text>");
        System.out.println("/senddup <peerNodeId|ip:port> <text>");
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
            if ("/convs".equalsIgnoreCase(line)) {
                List<Conversation> convs = conversationDao.listConversations();
                for (Conversation c : convs) {
                    System.out.println(c.convId + " / " + c.title + " / " + c.lastMsgTs + " / " + c.peerNodeId);
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
