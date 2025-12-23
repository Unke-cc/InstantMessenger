package com.example.lanchat.store;

import com.example.lanchat.core.Settings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Db {
    
    private static Connection connection;
    private static String currentDbName;

    public static synchronized void init(String dbName) throws SQLException {
        currentDbName = dbName;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC Driver not found", e);
        }
        
        // Enable WAL mode and busy timeout for better concurrency
        String url = "jdbc:sqlite:" + dbName;
        connection = DriverManager.getConnection(url);
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");
        }
        
        Schema.createTables(connection);
    }

    public static synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            if (currentDbName == null) {
                // Fallback to default if not initialized (though Launcher should call init)
                init(Settings.DB_NAME);
            } else {
                init(currentDbName);
            }
        }
        return connection;
    }
    
    public static synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}
