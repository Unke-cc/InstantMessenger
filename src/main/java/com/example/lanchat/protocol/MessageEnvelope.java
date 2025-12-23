package com.example.lanchat.protocol;

import com.google.gson.JsonElement;

public class MessageEnvelope {
    public int protocolVersion;
    public String type;
    public String msgId;
    public NodeInfo from;
    public long ts;
    public long clock;
    public JsonElement payload;

    public static class NodeInfo {
        public String nodeId;
        public String name;

        public NodeInfo() {
        }

        public NodeInfo(String nodeId, String name) {
            this.nodeId = nodeId;
            this.name = name;
        }
    }
}
