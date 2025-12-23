package com.example.lanchat.discovery;

import com.example.lanchat.core.Settings;
import com.example.lanchat.protocol.MessageEnvelope;
import com.example.lanchat.service.PeerDirectory;
import com.example.lanchat.store.IdentityDao.Identity;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DiscoveryService {

    private final Identity identity;
    private final PeerDirectory peerDirectory;
    private final Gson gson;
    
    private volatile boolean running = false;
    private Thread sendThread;
    private Thread recvThread;
    private DatagramSocket socket;
    
    // De-duplication cache: msgId -> timestamp
    private final ConcurrentHashMap<String, Long> msgIdCache = new ConcurrentHashMap<>();

    public DiscoveryService(Identity identity, PeerDirectory peerDirectory) {
        this.identity = identity;
        this.peerDirectory = peerDirectory;
        this.gson = new Gson();
    }

    public void start() {
        if (running) return;
        running = true;

        try {
            // Use DatagramChannel to access SO_REUSEPORT if available
            DatagramChannel channel = DatagramChannel.open();
            
            // Allow address reuse (essential)
            if (channel.supportedOptions().contains(StandardSocketOptions.SO_REUSEADDR)) {
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            }
            
            // Allow port reuse (essential for multiple processes on same machine)
            if (channel.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
                channel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            }

            channel.bind(new java.net.InetSocketAddress(Settings.BROADCAST_PORT));
            socket = channel.socket();
            socket.setBroadcast(true);
            
        } catch (Exception e) {
            System.err.println("Failed to bind UDP port " + Settings.BROADCAST_PORT + ": " + e.getMessage());
            // Retry with standard socket if channel failed (unlikely but safe fallback)
            try {
                if (socket == null || socket.isClosed()) {
                    socket = new DatagramSocket(null);
                    socket.setReuseAddress(true);
                    socket.bind(new java.net.InetSocketAddress(Settings.BROADCAST_PORT));
                    socket.setBroadcast(true);
                }
            } catch (Exception ex) {
                 System.err.println("Fallback bind failed: " + ex.getMessage());
                 running = false;
                 return;
            }
        }

        sendThread = new Thread(this::sendLoop, "Discovery-Sender");
        recvThread = new Thread(this::recvLoop, "Discovery-Receiver");
        
        sendThread.start();
        recvThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (sendThread != null) sendThread.interrupt();
        if (recvThread != null) recvThread.interrupt();
    }

    private void sendLoop() {
        while (running) {
            try {
                sendPresence();
                Thread.sleep(Settings.BROADCAST_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void sendPresence() {
        try {
            MessageEnvelope msg = new MessageEnvelope();
            msg.msgId = UUID.randomUUID().toString();
            msg.from = new MessageEnvelope.NodeInfo(identity.nodeId, identity.displayName);
            msg.ts = System.currentTimeMillis();
            msg.payload = new MessageEnvelope.Payload(identity.p2pPort, identity.webPort);
            
            String json = gson.toJson(msg);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddr, Settings.BROADCAST_PORT);
            
            if (socket != null && !socket.isClosed()) {
                socket.send(packet);
            }
        } catch (Exception e) {
            // e.printStackTrace();
        }
    }

    private void recvLoop() {
        byte[] buffer = new byte[4096];
        while (running) {
            try {
                if (socket == null || socket.isClosed()) break;
                
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                handleMessage(json, packet.getAddress().getHostAddress());
                
            } catch (IOException e) {
                if (running) {
                    // System.err.println("Receive error: " + e.getMessage());
                }
            }
        }
    }

    private void handleMessage(String json, String senderIp) {
        try {
            MessageEnvelope msg = gson.fromJson(json, MessageEnvelope.class);
            if (msg == null || !"PRESENCE".equals(msg.type)) return;
            
            if (identity.nodeId.equals(msg.from.nodeId)) return;
            
            if (msgIdCache.containsKey(msg.msgId)) return;
            msgIdCache.put(msg.msgId, System.currentTimeMillis());
            
            peerDirectory.addPeer(msg.from.nodeId, msg.from.name, senderIp, msg.payload.p2pPort);
            
        } catch (Exception e) {
            System.err.println("Invalid message received: " + e.getMessage());
        }
    }
}
