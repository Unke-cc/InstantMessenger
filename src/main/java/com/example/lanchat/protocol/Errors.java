package com.example.lanchat.protocol;

import com.google.gson.JsonObject;
import java.util.UUID;

public final class Errors {

    public static final String UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    public static final String BAD_MESSAGE = "BAD_MESSAGE";
    public static final String TOO_LARGE = "TOO_LARGE";

    private Errors() {
    }

    public static MessageEnvelope buildError(MessageEnvelope.NodeInfo from, String code, String message) {
        MessageEnvelope env = new MessageEnvelope();
        env.protocolVersion = 1;
        env.type = MessageType.ERROR;
        env.msgId = UUID.randomUUID().toString();
        env.from = from;
        env.ts = System.currentTimeMillis();

        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        env.payload = payload;
        return env;
    }
}

