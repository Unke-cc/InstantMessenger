package com.example.lanchat;

import com.example.lanchat.core.Settings;
import com.example.lanchat.discovery.DiscoveryService;
import com.example.lanchat.service.PeerDirectory;
import com.example.lanchat.store.Db;
import com.example.lanchat.store.IdentityDao;
import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.PeerDao.Peer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Executors;

public class Launcher {

    public static void main(String[] args) {
        System.out.println("Starting LAN Chat Node...");

        int p2pPort = Settings.DEFAULT_P2P_PORT;
        String name = "User-" + (System.currentTimeMillis() % 1000);

        if (args.length > 0) {
            p2pPort = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            name = args[1];
        }
        
        // Use unique DB per port for local testing
        String dbName = "lanchat_" + p2pPort + ".db";

        // 1. Initialize DB
        try {
            Db.init(dbName);
        } catch (SQLException e) {
            System.err.println("Failed to init DB: " + e.getMessage());
            return;
        }

        // 2. Load Identity
        Identity identity = null;
        try {
            IdentityDao identityDao = new IdentityDao();
            int webPort = Settings.DEFAULT_WEB_PORT; // simplified for now

            identity = identityDao.loadOrCreateIdentity(name, p2pPort, webPort);
            
            System.out.println("Node ID: " + identity.nodeId);
            System.out.println("Name:    " + identity.displayName);
            System.out.println("P2P Port:" + identity.p2pPort);
            
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        // 3. Start P2P Stub (to accept probes)
        startP2PStub(identity.p2pPort);

        // 4. Start Peer Directory
        PeerDirectory peerDirectory = new PeerDirectory();
        
        // 5. Start Discovery
        DiscoveryService discoveryService = new DiscoveryService(identity, peerDirectory);
        discoveryService.start();
        
        // 6. Bootstrap Probe
        peerDirectory.bootstrapProbe();

        // 7. Loop print
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            List<Peer> peers = peerDirectory.getOnlinePeers();
            System.out.println("\n--- Online Peers (" + peers.size() + ") ---");
            for (Peer p : peers) {
                System.out.println(p);
            }
        }, 2, 5, java.util.concurrent.TimeUnit.SECONDS);
        
        // 8. Wait for exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            discoveryService.stop();
            peerDirectory.shutdown();
            Db.close();
        }));
        
        // Keep main thread alive
        try {
            // Support manual add peer via stdin for "Manual Peer Add" requirement
            Scanner scanner = new Scanner(System.in);
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

    private static void startP2PStub(int port) {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                while (!ss.isClosed()) {
                    try (Socket s = ss.accept()) {
                        // Just accept and close to prove we are alive
                        s.close();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to bind P2P port " + port);
            }
        }).start();
    }
}
