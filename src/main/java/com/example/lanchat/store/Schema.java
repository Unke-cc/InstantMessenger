package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Schema {
    
    public static void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Identity table
            stmt.execute("CREATE TABLE IF NOT EXISTS identity (" +
                    "node_id TEXT PRIMARY KEY, " +
                    "display_name TEXT, " +
                    "p2p_port INTEGER, " +
                    "web_port INTEGER, " +
                    "created_at INTEGER, " +
                    "last_startup INTEGER" +
                    ")");
            
            // Peers table
            stmt.execute("CREATE TABLE IF NOT EXISTS peers (" +
                    "peer_node_id TEXT PRIMARY KEY, " +
                    "peer_name TEXT, " +
                    "last_ip TEXT, " +
                    "last_p2p_port INTEGER, " +
                    "last_seen INTEGER, " +
                    "remark TEXT" +
                    ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS conversations (" +
                    "conv_id TEXT PRIMARY KEY, " +
                    "conv_type TEXT, " +
                    "peer_node_id TEXT, " +
                    "room_id TEXT, " +
                    "title TEXT, " +
                    "created_at INTEGER, " +
                    "last_msg_ts INTEGER" +
                    ")");
            try {
                stmt.execute("ALTER TABLE conversations ADD COLUMN room_id TEXT");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_conversations_last_msg_ts ON conversations(last_msg_ts DESC)");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uniq_conversations_private_peer ON conversations(conv_type, peer_node_id)");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uniq_conversations_room ON conversations(conv_type, room_id)");
            } catch (SQLException ignored) {
            }

            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "msg_id TEXT PRIMARY KEY, " +
                    "conv_id TEXT, " +
                    "chat_type TEXT, " +
                    "room_id TEXT, " +
                    "direction TEXT, " +
                    "from_node_id TEXT, " +
                    "to_node_id TEXT, " +
                    "content TEXT, " +
                    "content_type TEXT, " +
                    "ts INTEGER, " +
                    "updated_at INTEGER, " +
                    "clock_value TEXT, " +
                    "status TEXT" +
                    ")");
            try {
                stmt.execute("ALTER TABLE messages ADD COLUMN chat_type TEXT");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("ALTER TABLE messages ADD COLUMN room_id TEXT");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("ALTER TABLE messages ADD COLUMN updated_at INTEGER");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("UPDATE messages SET updated_at = ts WHERE updated_at IS NULL");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_conv_ts ON messages(conv_id, ts DESC)");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_room_ts ON messages(room_id, ts DESC)");
            } catch (SQLException ignored) {
            }

            stmt.execute("CREATE TABLE IF NOT EXISTS seen_messages (" +
                    "msg_id TEXT PRIMARY KEY, " +
                    "first_seen_ts INTEGER" +
                    ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS rooms (" +
                    "room_id TEXT PRIMARY KEY, " +
                    "room_name TEXT, " +
                    "created_at INTEGER, " +
                    "policy TEXT, " +
                    "room_key_hash TEXT" +
                    ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS room_members (" +
                    "room_id TEXT, " +
                    "member_node_id TEXT, " +
                    "member_name TEXT, " +
                    "last_known_ip TEXT, " +
                    "last_known_p2p_port INTEGER, " +
                    "last_seen INTEGER, " +
                    "PRIMARY KEY(room_id, member_node_id)" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_room_members_room_id ON room_members(room_id)");

            stmt.execute("CREATE TABLE IF NOT EXISTS room_member_events (" +
                    "event_id TEXT PRIMARY KEY, " +
                    "room_id TEXT, " +
                    "op TEXT, " +
                    "member_node_id TEXT, " +
                    "clock_value TEXT, " +
                    "ts INTEGER" +
                    ")");
        }
    }
}
