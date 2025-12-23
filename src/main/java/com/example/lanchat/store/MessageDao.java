package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class MessageDao {

    public static class Message {
        public String msgId;
        public String convId;
        public String direction;
        public String fromNodeId;
        public String toNodeId;
        public String content;
        public String contentType;
        public long ts;
        public String clockValue;
        public String status;
    }

    public void insert(Message m) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT INTO messages (msg_id, conv_id, direction, from_node_id, to_node_id, content, content_type, ts, clock_value, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, m.msgId);
            ps.setString(2, m.convId);
            ps.setString(3, m.direction);
            ps.setString(4, m.fromNodeId);
            ps.setString(5, m.toNodeId);
            ps.setString(6, m.content);
            ps.setString(7, m.contentType);
            ps.setLong(8, m.ts);
            ps.setString(9, m.clockValue);
            ps.setString(10, m.status);
            ps.executeUpdate();
        }
    }

    public void updateStatus(String msgId, String status) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "UPDATE messages SET status = ? WHERE msg_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, msgId);
            ps.executeUpdate();
        }
    }

    public List<Message> listMessages(String convId, long beforeTs, int limit) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM messages WHERE conv_id = ? AND ts < ? ORDER BY ts DESC LIMIT ?";
        List<Message> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, convId);
            ps.setLong(2, beforeTs);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
        }
        return out;
    }

    public List<Message> listLatestMessages(String convId, int limit) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM messages WHERE conv_id = ? ORDER BY ts DESC LIMIT ?";
        List<Message> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, convId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
        }
        return out;
    }

    private Message mapRow(ResultSet rs) throws SQLException {
        Message m = new Message();
        m.msgId = rs.getString("msg_id");
        m.convId = rs.getString("conv_id");
        m.direction = rs.getString("direction");
        m.fromNodeId = rs.getString("from_node_id");
        m.toNodeId = rs.getString("to_node_id");
        m.content = rs.getString("content");
        m.contentType = rs.getString("content_type");
        m.ts = rs.getLong("ts");
        m.clockValue = rs.getString("clock_value");
        m.status = rs.getString("status");
        return m;
    }
}

