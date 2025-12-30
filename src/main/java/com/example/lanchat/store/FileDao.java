package com.example.lanchat.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class FileDao {

    public static class FileMeta {
        public String fileId;
        public String fileName;
        public long fileSize;
        public String fileHash;
        public String ownerNodeId;
        public String status;
        public long createdAt;
        public long expiresAt;
        public String contentType;
    }

    public void insert(FileMeta file) throws SQLException {
        String sql = "INSERT INTO files (file_id, file_name, file_size, file_hash, owner_node_id, status, created_at, expires_at, content_type) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = Db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, file.fileId);
            stmt.setString(2, file.fileName);
            stmt.setLong(3, file.fileSize);
            stmt.setString(4, file.fileHash);
            stmt.setString(5, file.ownerNodeId);
            stmt.setString(6, file.status);
            stmt.setLong(7, file.createdAt);
            stmt.setLong(8, file.expiresAt);
            stmt.setString(9, file.contentType);
            stmt.executeUpdate();
        }
    }

    public void updateStatus(String fileId, String status) throws SQLException {
        String sql = "UPDATE files SET status = ? WHERE file_id = ?";
        try (Connection conn = Db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, fileId);
            stmt.executeUpdate();
        }
    }

    public FileMeta getById(String fileId) throws SQLException {
        String sql = "SELECT * FROM files WHERE file_id = ?";
        try (Connection conn = Db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, fileId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return fromResultSet(rs);
                }
            }
        }
        return null;
    }

    public List<FileMeta> listActiveFiles() throws SQLException {
        String sql = "SELECT * FROM files WHERE status = 'COMPLETED' AND expires_at > ? ORDER BY created_at DESC";
        List<FileMeta> list = new ArrayList<>();
        try (Connection conn = Db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(fromResultSet(rs));
                }
            }
        }
        return list;
    }

    private FileMeta fromResultSet(ResultSet rs) throws SQLException {
        FileMeta f = new FileMeta();
        f.fileId = rs.getString("file_id");
        f.fileName = rs.getString("file_name");
        f.fileSize = rs.getLong("file_size");
        f.fileHash = rs.getString("file_hash");
        f.ownerNodeId = rs.getString("owner_node_id");
        f.status = rs.getString("status");
        f.createdAt = rs.getLong("created_at");
        f.expiresAt = rs.getLong("expires_at");
        f.contentType = rs.getString("content_type");
        return f;
    }
}
