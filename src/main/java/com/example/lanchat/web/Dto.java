package com.example.lanchat.web;

import java.util.List;

public final class Dto {

    private Dto() {
    }

    public static final class ApiResponse<T> {
        public boolean ok;
        public T data;
        public String error;
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = true;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(String error) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = false;
        r.error = error;
        return r;
    }

    public static final class MeDto {
        public String nodeId;
        public String name;
        public int p2pPort;
        public int webPort;
    }

    public static final class UpdateMeRequest {
        public String name;
    }

    public static final class PeerDto {
        public String nodeId;
        public String name;
        public String ip;
        public int p2pPort;
        public long lastSeen;
        public boolean online;
    }

    public static final class AddPeerRequest {
        public String ip;
        public int p2pPort;
        public String name;
    }

    public static final class ConversationDto {
        public String convId;
        public String convType;
        public String peerNodeId;
        public String roomId;
        public String title;
        public long createdAt;
        public long lastMsgTs;
    }

    public static final class MessageDto {
        public String msgId;
        public String convId;
        public String roomId;
        public String chatType;
        public String direction;
        public String fromNodeId;
        public String fromName;
        public String content;
        public String contentType;
        public long ts;
        public long updatedAt;
        public String status;
    }

    public static final class SendPrivateRequest {
        public String peerNodeId;
        public String ip;
        public Integer port;
        public String content;
    }

    public static final class CreateRoomRequest {
        public String roomName;
        public String policy;
        public String token;
    }

    public static final class CreateRoomResponse {
        public String roomId;
    }

    public static final class JoinRoomRequest {
        public String roomId;
        public String inviterIp;
        public int inviterPort;
        public String token;
    }

    public static final class RoomDto {
        public String roomId;
        public String roomName;
        public long createdAt;
        public String policy;
    }

    public static final class RoomMemberDto {
        public String roomId;
        public String nodeId;
        public String name;
        public String ip;
        public int p2pPort;
        public long lastSeen;
    }

    public static final class SendRoomRequest {
        public String roomId;
        public String content;
    }

    public static final class SyncRoomRequest {
        public String roomId;
    }

    public static final class PollResponse {
        public long maxTs;
        public List<MessageDto> messages;
    }
}
