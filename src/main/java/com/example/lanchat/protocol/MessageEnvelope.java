package com.example.lanchat.protocol;

public class MessageEnvelope {
    public int protocolVersion = 1;
    public String type = "PRESENCE";
    public String msgId; // UUID
    public NodeInfo from;
    public long ts;
    public Payload payload;

    public static class NodeInfo {
        public String nodeId;
        public String name;
        
        public NodeInfo(String nodeId, String name) {
            this.nodeId = nodeId;
            this.name = name;
        }
    }

    public static class Payload {
        public int p2pPort;
        public int webPort;
        
        public Payload(int p2pPort, int webPort) {
            this.p2pPort = p2pPort;
            this.webPort = webPort;
        }
    }
}
