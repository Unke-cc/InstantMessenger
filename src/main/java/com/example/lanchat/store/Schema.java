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
                    "title TEXT, " +
                    "created_at INTEGER, " +
                    "last_msg_ts INTEGER" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_conversations_last_msg_ts ON conversations(last_msg_ts DESC)");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uniq_conversations_private_peer ON conversations(conv_type, peer_node_id)");

            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "msg_id TEXT PRIMARY KEY, " +
                    "conv_id TEXT, " +
                    "direction TEXT, " +
                    "from_node_id TEXT, " +
                    "to_node_id TEXT, " +
                    "content TEXT, " +
                    "content_type TEXT, " +
                    "ts INTEGER, " +
                    "clock_value TEXT, " +
                    "status TEXT" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_conv_ts ON messages(conv_id, ts DESC)");

            stmt.execute("CREATE TABLE IF NOT EXISTS seen_messages (" +
                    "msg_id TEXT PRIMARY KEY, " +
                    "first_seen_ts INTEGER" +
                    ")");
        }
    }
}
