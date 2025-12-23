package com.example.lanchat.web;

import spark.Request;
import spark.Response;

public final class LocalOnlyFilter {

    private LocalOnlyFilter() {
    }

    public static void enforce(Request req, Response res) {
        String ip = req.ip();
        if (isLocalIp(ip)) return;
        if (isLocalHost(req.host())) return;
        res.status(403);
        res.type("application/json");
        throw new LocalOnlyRejectedException();
    }

    private static boolean isLocalIp(String ip) {
        if (ip == null) return false;
        if ("127.0.0.1".equals(ip)) return true;
        if ("::1".equals(ip)) return true;
        if ("0:0:0:0:0:0:0:1".equals(ip)) return true;
        return false;
    }

    private static boolean isLocalHost(String hostHeader) {
        if (hostHeader == null) return false;
        String host = hostHeader;
        int idx = host.indexOf(':');
        if (idx >= 0) host = host.substring(0, idx);
        host = host.trim().toLowerCase();
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    public static final class LocalOnlyRejectedException extends RuntimeException {
    }
}
