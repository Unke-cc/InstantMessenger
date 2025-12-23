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
        }
    }
}
