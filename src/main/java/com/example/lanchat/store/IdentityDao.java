package com.example.lanchat.store;

import java.sql.*;
import java.util.UUID;

public class IdentityDao {

    public static class Identity {
        public String nodeId;
        public String displayName;
        public int p2pPort;
        public int webPort;
    }

    public Identity loadOrCreateIdentity(String defaultName, int p2pPort, int webPort) throws SQLException {
        Connection conn = Db.getConnection();
        
        // Try to load
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM identity LIMIT 1")) {
            
            if (rs.next()) {
                Identity id = new Identity();
                id.nodeId = rs.getString("node_id");
                id.displayName = rs.getString("display_name");
                id.p2pPort = rs.getInt("p2p_port");
                id.webPort = rs.getInt("web_port");

                // Update last_startup
                updateLastStartup(conn, id.nodeId);
                if (id.p2pPort != p2pPort || id.webPort != webPort) {
                    updatePorts(conn, id.nodeId, p2pPort, webPort);
                    id.p2pPort = p2pPort;
                    id.webPort = webPort;
                }
                return id;
            }
        }
        
        // Create new
        String nodeId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        
        String sql = "INSERT INTO identity (node_id, display_name, p2p_port, web_port, created_at, last_startup) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nodeId);
            ps.setString(2, defaultName);
            ps.setInt(3, p2pPort);
            ps.setInt(4, webPort);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
        
        Identity id = new Identity();
        id.nodeId = nodeId;
        id.displayName = defaultName;
        id.p2pPort = p2pPort;
        id.webPort = webPort;
        return id;
    }

    public void updateDisplayName(String nodeId, String displayName) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "UPDATE identity SET display_name = ? WHERE node_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, displayName);
            ps.setString(2, nodeId);
            ps.executeUpdate();
        }
    }

    private void updateLastStartup(Connection conn, String nodeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE identity SET last_startup = ? WHERE node_id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, nodeId);
            ps.executeUpdate();
        }
    }

    private void updatePorts(Connection conn, String nodeId, int p2pPort, int webPort) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE identity SET p2p_port = ?, web_port = ? WHERE node_id = ?")) {
            ps.setInt(1, p2pPort);
            ps.setInt(2, webPort);
            ps.setString(3, nodeId);
            ps.executeUpdate();
        }
    }
}
