package com.example.lanchat;

import com.example.lanchat.core.Settings;
import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.protocol.MessageType;
import com.example.lanchat.service.TransportService;
import com.example.lanchat.store.Db;
import com.example.lanchat.store.IdentityDao;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.transport.PeerInfo;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.Scanner;

public class DemoMain {

    public static void main(String[] args) throws Exception {
        int p2pPort = Settings.DEFAULT_P2P_PORT;
        String name = "User-" + (System.currentTimeMillis() % 1000);
        if (args.length > 0) {
            p2pPort = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            name = args[1];
        }

        String dbName = "lanchat_" + p2pPort + ".db";
        try {
            Db.init(dbName);
        } catch (SQLException e) {
            System.err.println("DB init failed: " + e.getMessage());
            return;
        }

        Identity identity;
        try {
            IdentityDao identityDao = new IdentityDao();
            identity = identityDao.loadOrCreateIdentity(name, p2pPort, Settings.DEFAULT_WEB_PORT);
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        System.out.println("Node ID: " + identity.nodeId);
        System.out.println("Name:    " + identity.displayName);
        System.out.println("P2P Port:" + identity.p2pPort);

        TransportService transport = new TransportService(identity);
        transport.onMessage(DemoMain::printInbound);
        transport.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            transport.stop();
            Db.close();
        }));

        System.out.println("Commands:");
        System.out.println("/send <ip> <port> <text>");
        System.out.println("/senddup <ip> <port> <text>");
        System.out.println("/quit");

        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            if ("/quit".equalsIgnoreCase(line)) {
                System.exit(0);
                return;
            }
            if (line.startsWith("/senddup ")) {
                String[] parts = line.split(" ", 4);
                if (parts.length < 4) continue;
                String ip = parts[1];
                int port = Integer.parseInt(parts[2]);
                String text = parts[3];
                MessageEnvelope env = buildChat(text);
                transport.sendToAddr(ip, port, env);
                transport.sendToAddr(ip, port, env);
                continue;
            }
            if (line.startsWith("/send ")) {
                String[] parts = line.split(" ", 4);
                if (parts.length < 4) continue;
                String ip = parts[1];
                int port = Integer.parseInt(parts[2]);
                String text = parts[3];
                transport.sendToAddr(ip, port, buildChat(text));
                continue;
            }
        }
    }

    private static MessageEnvelope buildChat(String text) {
        MessageEnvelope env = new MessageEnvelope();
        env.protocolVersion = 1;
        env.type = MessageType.CHAT_TEST;
        JsonObject payload = new JsonObject();
        payload.addProperty("text", text);
        env.payload = payload;
        return env;
    }

    private static void printInbound(PeerInfo remotePeerInfo, MessageEnvelope message) {
        String text = "";
        if (message.payload != null && message.payload.isJsonObject()) {
            JsonObject obj = message.payload.getAsJsonObject();
            if (obj.has("text")) {
                text = obj.get("text").getAsString();
            }
        }
        String fromName = message.from != null && message.from.name != null ? message.from.name : remotePeerInfo.name;
        System.out.println(fromName + ": " + text);
    }
}

