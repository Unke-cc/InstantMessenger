package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class RoomCursorDao {

    public String getCursor(String roomId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT last_clock_value FROM room_cursor WHERE room_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString("last_clock_value");
                    return v == null || v.isBlank() ? "0" : v;
                }
            }
        }
        initCursor(roomId, "0");
        return "0";
    }

    public void initCursor(String roomId, String clockValue) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT OR IGNORE INTO room_cursor (room_id, last_clock_value, updated_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, clockValue == null || clockValue.isBlank() ? "0" : clockValue);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public void updateCursorMonotonic(String roomId, String newClockValue) throws SQLException {
        String v = newClockValue == null || newClockValue.isBlank() ? "0" : newClockValue;
        Connection conn = Db.getConnection();
        String sql = "INSERT INTO room_cursor (room_id, last_clock_value, updated_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(room_id) DO UPDATE SET " +
                "last_clock_value = excluded.last_clock_value, " +
                "updated_at = excluded.updated_at " +
                "WHERE CAST(excluded.last_clock_value AS INTEGER) > CAST(room_cursor.last_clock_value AS INTEGER)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, v);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}

