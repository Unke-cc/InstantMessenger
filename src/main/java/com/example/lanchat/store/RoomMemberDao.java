package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class RoomMemberDao {

    public static class RoomMember {
        public String roomId;
        public String memberNodeId;
        public String memberName;
        public String lastKnownIp;
        public int lastKnownP2pPort;
        public long lastSeen;
    }

    public void upsert(RoomMember m) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT INTO room_members (room_id, member_node_id, member_name, last_known_ip, last_known_p2p_port, last_seen) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(room_id, member_node_id) DO UPDATE SET " +
                "member_name = excluded.member_name, " +
                "last_known_ip = COALESCE(excluded.last_known_ip, room_members.last_known_ip), " +
                "last_known_p2p_port = CASE WHEN excluded.last_known_p2p_port > 0 THEN excluded.last_known_p2p_port ELSE room_members.last_known_p2p_port END, " +
                "last_seen = CASE WHEN excluded.last_seen > 0 THEN excluded.last_seen ELSE room_members.last_seen END";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, m.roomId);
            ps.setString(2, m.memberNodeId);
            ps.setString(3, m.memberName);
            ps.setString(4, m.lastKnownIp);
            ps.setInt(5, m.lastKnownP2pPort);
            ps.setLong(6, m.lastSeen);
            ps.executeUpdate();
        }
    }

    public boolean isMember(String roomId, String memberNodeId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT 1 FROM room_members WHERE room_id = ? AND member_node_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, memberNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public RoomMember getMember(String roomId, String memberNodeId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM room_members WHERE room_id = ? AND member_node_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, memberNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    public void removeMember(String roomId, String memberNodeId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "DELETE FROM room_members WHERE room_id = ? AND member_node_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, memberNodeId);
            ps.executeUpdate();
        }
    }

    public List<RoomMember> listMembers(String roomId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM room_members WHERE room_id = ? ORDER BY member_name ASC";
        List<RoomMember> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        }
        return out;
    }

    private RoomMember mapRow(ResultSet rs) throws SQLException {
        RoomMember m = new RoomMember();
        m.roomId = rs.getString("room_id");
        m.memberNodeId = rs.getString("member_node_id");
        m.memberName = rs.getString("member_name");
        m.lastKnownIp = rs.getString("last_known_ip");
        m.lastKnownP2pPort = rs.getInt("last_known_p2p_port");
        m.lastSeen = rs.getLong("last_seen");
        return m;
    }
}

