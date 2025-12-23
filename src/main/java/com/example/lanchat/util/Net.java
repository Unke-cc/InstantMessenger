package com.example.lanchat.util;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class Net {

    private Net() {
    }

    public static void safeClose(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    public static void safeClose(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    public static String formatRemote(Socket socket) {
        if (socket == null) return "unknown";
        if (socket.getRemoteSocketAddress() instanceof InetSocketAddress a) {
            return a.getAddress().getHostAddress() + ":" + a.getPort();
        }
        return String.valueOf(socket.getRemoteSocketAddress());
    }
}

