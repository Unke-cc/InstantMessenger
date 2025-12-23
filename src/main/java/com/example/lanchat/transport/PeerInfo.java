package com.example.lanchat.transport;

public class PeerInfo {
    public final String nodeId;
    public final String name;
    public final String ip;
    public final int p2pPort;

    public PeerInfo(String nodeId, String name, String ip, int p2pPort) {
        this.nodeId = nodeId;
        this.name = name;
        this.ip = ip;
        this.p2pPort = p2pPort;
    }

    @Override
    public String toString() {
        return name + " (" + nodeId + ") @ " + ip + ":" + p2pPort;
    }
}

