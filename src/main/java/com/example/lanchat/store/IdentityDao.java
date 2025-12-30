package com.example.lanchat.store;

import java.sql.*;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

public class IdentityDao {

    public static class Identity {
        public String nodeId;
        public String displayName;
        public String passwordHash;
        public int p2pPort;
        public int webPort;
    }

    public boolean isRegistered() throws SQLException {
        Connection conn = Db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM identity")) {
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    public Identity register(String name, String password, int p2pPort, int webPort) throws SQLException {
        Connection conn = Db.getConnection();
        String nodeId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String hash = hashPassword(password);

        String sql = "INSERT INTO identity (node_id, display_name, password_hash, p2p_port, web_port, created_at, last_startup) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nodeId);
            ps.setString(2, name);
            ps.setString(3, hash);
            ps.setInt(4, p2pPort);
            ps.setInt(5, webPort);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        }

        Identity id = new Identity();
        id.nodeId = nodeId;
        id.displayName = name;
        id.passwordHash = hash;
        id.p2pPort = p2pPort;
        id.webPort = webPort;
        return id;
    }

    public Identity login(String name, String password) throws SQLException {
        Connection conn = Db.getConnection();
        String hash = hashPassword(password);
        
        String sql = "SELECT * FROM identity WHERE display_name = ? AND password_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Identity id = new Identity();
                    id.nodeId = rs.getString("node_id");
                    id.displayName = rs.getString("display_name");
                    id.passwordHash = rs.getString("password_hash");
                    id.p2pPort = rs.getInt("p2p_port");
                    id.webPort = rs.getInt("web_port");
                    updateLastStartup(conn, id.nodeId);
                    return id;
                }
            }
        }
        return null;
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
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
                id.passwordHash = rs.getString("password_hash");
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
        
        // If not exists, we return a temporary one or null?
        // To keep the rest of the app working, we might need a placeholder identity
        // but it won't have a password_hash yet.
        Identity id = new Identity();
        id.nodeId = UUID.randomUUID().toString();
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
