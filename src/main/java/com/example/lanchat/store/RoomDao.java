package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class RoomDao {

    public static class Room {
        public String roomId;
        public String roomName;
        public long createdAt;
        public String policy;
        public String roomKeyHash;
    }

    public void insert(Room room) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT INTO rooms (room_id, room_name, created_at, policy, room_key_hash) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room.roomId);
            ps.setString(2, room.roomName);
            ps.setLong(3, room.createdAt);
            ps.setString(4, room.policy);
            ps.setString(5, room.roomKeyHash);
            ps.executeUpdate();
        }
    }

    public void upsert(Room room) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT INTO rooms (room_id, room_name, created_at, policy, room_key_hash) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(room_id) DO UPDATE SET " +
                "room_name = excluded.room_name, " +
                "policy = excluded.policy, " +
                "room_key_hash = excluded.room_key_hash";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room.roomId);
            ps.setString(2, room.roomName);
            ps.setLong(3, room.createdAt);
            ps.setString(4, room.policy);
            ps.setString(5, room.roomKeyHash);
            ps.executeUpdate();
        }
    }

    public Room getById(String roomId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM rooms WHERE room_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    public List<Room> listRooms() throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM rooms ORDER BY created_at DESC";
        List<Room> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapRow(rs));
        }
        return out;
    }

    private Room mapRow(ResultSet rs) throws SQLException {
        Room r = new Room();
        r.roomId = rs.getString("room_id");
        r.roomName = rs.getString("room_name");
        r.createdAt = rs.getLong("created_at");
        r.policy = rs.getString("policy");
        r.roomKeyHash = rs.getString("room_key_hash");
        return r;
    }
}

