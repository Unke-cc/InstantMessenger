package com.example.lanchat.protocol;

public final class MessageType {
    public static final String PRESENCE = "PRESENCE";
    public static final String HELLO = "HELLO";
    public static final String ERROR = "ERROR";
    public static final String CHAT = "CHAT";
    public static final String ACK = "ACK";
    public static final String CHAT_TEST = "CHAT_TEST";
    public static final String JOIN_REQUEST = "JOIN_REQUEST";
    public static final String JOIN_ACCEPT = "JOIN_ACCEPT";
    public static final String MEMBER_EVENT = "MEMBER_EVENT";
    public static final String SYNC_REQUEST = "SYNC_REQUEST";
    public static final String SYNC_RESPONSE = "SYNC_RESPONSE";
    public static final String INVITE = "INVITE";

    private MessageType() {
    }
}
