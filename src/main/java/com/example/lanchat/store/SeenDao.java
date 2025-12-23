package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class SeenDao {

    public boolean markSeen(String msgId, long now) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT OR IGNORE INTO seen_messages (msg_id, first_seen_ts) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, msgId);
            ps.setLong(2, now);
            int changed = ps.executeUpdate();
            return changed > 0;
        }
    }
}

