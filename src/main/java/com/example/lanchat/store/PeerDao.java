package com.example.lanchat.store;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PeerDao {

    public static class Peer {
        public String nodeId;
        public String name;
        public String ip;
        public int p2pPort;
        public long lastSeen;
        public String remark;
        
        @Override
        public String toString() {
            return String.format("%s (%s) @ %s:%d [LastSeen: %d]", name, nodeId, ip, p2pPort, lastSeen);
        }
    }

    public void upsertPeer(String nodeId, String name, String ip, int p2pPort, long lastSeen) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "INSERT INTO peers (peer_node_id, peer_name, last_ip, last_p2p_port, last_seen) VALUES (?, ?, ?, ?, ?) " +
                     "ON CONFLICT(peer_node_id) DO UPDATE SET " +
                     "peer_name = excluded.peer_name, " +
                     "last_ip = excluded.last_ip, " +
                     "last_p2p_port = excluded.last_p2p_port, " +
                     "last_seen = excluded.last_seen";
                     
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nodeId);
            ps.setString(2, name);
            ps.setString(3, ip);
            ps.setInt(4, p2pPort);
            ps.setLong(5, lastSeen);
            ps.executeUpdate();
        }
    }

    public List<Peer> listOnlinePeers(long now, long ttlMs) throws SQLException {
        Connection conn = Db.getConnection();
        long threshold = now - ttlMs;
        String sql = "SELECT * FROM peers WHERE last_seen > ? ORDER BY last_seen DESC";
        
        List<Peer> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<Peer> listAllPeers() throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM peers ORDER BY last_seen DESC";
        
        List<Peer> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public Peer getPeerByNodeId(String nodeId) throws SQLException {
        Connection conn = Db.getConnection();
        String sql = "SELECT * FROM peers WHERE peer_node_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }
    
    private Peer mapRow(ResultSet rs) throws SQLException {
        Peer p = new Peer();
        p.nodeId = rs.getString("peer_node_id");
        p.name = rs.getString("peer_name");
        p.ip = rs.getString("last_ip");
        p.p2pPort = rs.getInt("last_p2p_port");
        p.lastSeen = rs.getLong("last_seen");
        p.remark = rs.getString("remark");
        return p;
    }
}
