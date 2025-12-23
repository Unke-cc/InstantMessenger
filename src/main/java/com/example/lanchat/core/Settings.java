package com.example.lanchat.core;

public class Settings {
    // UDP Broadcast Port
    public static final int BROADCAST_PORT = 19001; // Can be configured
    // Default P2P TCP Port
    public static final int DEFAULT_P2P_PORT = 19000;
    // Default Web Port (for later)
    public static final int DEFAULT_WEB_PORT = 18080;
    
    // Discovery Intervals
    public static final long BROADCAST_INTERVAL_MS = 2000; // 2 seconds
    
    // Online Status
    public static final long PEER_TTL_MS = 20000; // 20 seconds
    
    // Database
    public static final String DB_NAME = "lanchat.db";
    
    // Probe settings
    public static final int PROBE_TIMEOUT_MS = 2000;
    public static final int PROBE_THREADS = 4;
}
