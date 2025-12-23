package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConversationDao {

    public static class Conversation {
        public String convId;
        public String convType;
        public String peerNodeId;
        public String title;
        public long createdAt;
        public long lastMsgTs;
    }

    public Conversation getByPeer(String convType, String peerNodeId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM conversations WHERE conv_type = ? AND peer_node_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, convType);
            ps.setString(2, peerNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    public Conversation getOrCreatePrivate(String peerNodeId, String title, long now) throws SQLException {
        Conversation existing = getByPeer("PRIVATE", peerNodeId);
        if (existing != null) return existing;

        Connection conn = Db.getConnection();
        String convId = UUID.randomUUID().toString();
        String sql = "INSERT OR IGNORE INTO conversations (conv_id, conv_type, peer_node_id, title, created_at, last_msg_ts) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, convId);
            ps.setString(2, "PRIVATE");
            ps.setString(3, peerNodeId);
            ps.setString(4, title);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
        return getByPeer("PRIVATE", peerNodeId);
    }

    public void updateLastMsgTs(String convId, long lastMsgTs) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "UPDATE conversations SET last_msg_ts = ? WHERE conv_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastMsgTs);
            ps.setString(2, convId);
            ps.executeUpdate();
        }
    }

    public List<Conversation> listConversations() throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM conversations ORDER BY last_msg_ts DESC";
        List<Conversation> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapRow(rs));
            }
        }
        return out;
    }

    private Conversation mapRow(ResultSet rs) throws SQLException {
        Conversation c = new Conversation();
        c.convId = rs.getString("conv_id");
        c.convType = rs.getString("conv_type");
        c.peerNodeId = rs.getString("peer_node_id");
        c.title = rs.getString("title");
        c.createdAt = rs.getLong("created_at");
        c.lastMsgTs = rs.getLong("last_msg_ts");
        return c;
    }
}

