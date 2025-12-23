package com.example.lanchat.service;

import com.example.lanchat.core.Settings;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.store.PeerDao.Peer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PeerDirectory {

    private final PeerDao peerDao;
    private final ExecutorService probeExecutor;

    public PeerDirectory() {
        this.peerDao = new PeerDao();
        this.probeExecutor = Executors.newFixedThreadPool(Settings.PROBE_THREADS);
    }

    public void addPeer(String nodeId, String name, String ip, int p2pPort) {
        try {
            peerDao.upsertPeer(nodeId, name, ip, p2pPort, System.currentTimeMillis());
            System.out.println("Updated peer: " + name + " (" + ip + ":" + p2pPort + ")");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<Peer> getOnlinePeers() {
        try {
            return peerDao.listOnlinePeers(System.currentTimeMillis(), Settings.PEER_TTL_MS);
        } catch (SQLException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public void bootstrapProbe() {
        System.out.println("Starting bootstrap probe...");
        try {
            List<Peer> allPeers = peerDao.listAllPeers();
            for (Peer p : allPeers) {
                probeExecutor.submit(() -> probePeer(p));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void probePeer(Peer p) {
        // Skip if address is invalid or port is 0
        if (p.ip == null || p.p2pPort <= 0) return;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(p.ip, p.p2pPort), Settings.PROBE_TIMEOUT_MS);
            // If successful, update lastSeen
            System.out.println("Probe success: " + p.name + " at " + p.ip);
            addPeer(p.nodeId, p.name, p.ip, p.p2pPort);
        } catch (IOException e) {
            // Probe failed, ignore
            // System.out.println("Probe failed: " + p.name + " at " + p.ip);
        }
    }
    
    public void shutdown() {
        probeExecutor.shutdown();
        try {
            if (!probeExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                probeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            probeExecutor.shutdownNow();
        }
    }
}
