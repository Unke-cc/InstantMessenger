package com.example.lanchat.service;

import com.example.lanchat.store.FileDao;
import com.example.lanchat.store.FileDao.FileMeta;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.UUID;

public class FileService {
    private final FileDao fileDao;
    private final Path storagePath;
    private final Path tempPath;
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB

    public FileService(FileDao fileDao) {
        this.fileDao = fileDao;
        this.storagePath = Paths.get("storage", "files");
        this.tempPath = Paths.get("storage", "temp");
        try {
            Files.createDirectories(storagePath);
            Files.createDirectories(tempPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not create storage directories", e);
        }
    }

    public FileMeta initUpload(String fileName, long fileSize, String contentType, String fileHash, String ownerNodeId) throws SQLException {
        FileMeta meta = new FileMeta();
        meta.fileId = UUID.randomUUID().toString();
        meta.fileName = fileName;
        meta.fileSize = fileSize;
        meta.fileHash = fileHash;
        meta.ownerNodeId = ownerNodeId;
        meta.status = "UPLOADING";
        meta.createdAt = System.currentTimeMillis();
        meta.expiresAt = meta.createdAt + (7 * 24 * 60 * 60 * 1000); // 7 days default
        meta.contentType = contentType;

        fileDao.insert(meta);
        return meta;
    }

    public void saveChunk(String fileId, int chunkIndex, byte[] data) throws IOException {
        Path chunkDir = tempPath.resolve(fileId);
        Files.createDirectories(chunkDir);
        Path chunkFile = chunkDir.resolve(String.valueOf(chunkIndex));
        Files.write(chunkFile, data);
    }

    public boolean completeUpload(String fileId) throws SQLException, IOException {
        FileMeta meta = fileDao.getById(fileId);
        if (meta == null) return false;

        Path chunkDir = tempPath.resolve(fileId);
        Path finalFile = storagePath.resolve(fileId);

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(finalFile))) {
            int chunkIndex = 0;
            while (true) {
                Path chunkFile = chunkDir.resolve(String.valueOf(chunkIndex));
                if (!Files.exists(chunkFile)) break;
                Files.copy(chunkFile, out);
                chunkIndex++;
            }
        }

        // Verify size
        if (Files.size(finalFile) != meta.fileSize) {
            Files.delete(finalFile);
            fileDao.updateStatus(fileId, "FAILED_SIZE_MISMATCH");
            return false;
        }

        // Verify hash
        String actualHash = calculateSHA256(finalFile);
        if (meta.fileHash != null && !meta.fileHash.equalsIgnoreCase(actualHash)) {
            Files.delete(finalFile);
            fileDao.updateStatus(fileId, "FAILED_HASH_MISMATCH");
            return false;
        }

        fileDao.updateStatus(fileId, "COMPLETED");
        // Clean up chunks
        deleteDirectory(chunkDir);
        return true;
    }

    public File getFile(String fileId) throws SQLException {
        FileMeta meta = fileDao.getById(fileId);
        if (meta == null || !"COMPLETED".equals(meta.status)) return null;
        return storagePath.resolve(fileId).toFile();
    }

    private String calculateSHA256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        }
    }

    public int getChunkSize() {
        return CHUNK_SIZE;
    }
}
