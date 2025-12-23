package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class RoomEventDao {

    public boolean insertIgnore(String eventId, String roomId, String op, String memberNodeId, String clockValue, long ts) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT OR IGNORE INTO room_member_events (event_id, room_id, op, member_node_id, clock_value, ts) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.setString(2, roomId);
            ps.setString(3, op);
            ps.setString(4, memberNodeId);
            ps.setString(5, clockValue);
            ps.setLong(6, ts);
            int changed = ps.executeUpdate();
            return changed > 0;
        }
    }
}

