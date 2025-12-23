package com.example.lanchat.transport;

import com.example.lanchat.store.IdentityDao.Identity;
import com.example.lanchat.store.PeerDao;
import com.example.lanchat.util.LruTtlSet;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class TcpServer {

    private final int port;
    private final Identity identity;
    private final PeerDao peerDao;
    private final LruTtlSet seenMsgIds;
    private final ExecutorService ioPool;
    private final Connection.MessageHandler messageHandler;
    private final Connection.HandshakeListener handshakeListener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;

    public TcpServer(
            int port,
            Identity identity,
            PeerDao peerDao,
            LruTtlSet seenMsgIds,
            ExecutorService ioPool,
            Connection.MessageHandler messageHandler,
            Connection.HandshakeListener handshakeListener
    ) {
        this.port = port;
        this.identity = identity;
        this.peerDao = peerDao;
        this.seenMsgIds = seenMsgIds;
        this.ioPool = ioPool;
        this.messageHandler = messageHandler;
        this.handshakeListener = handshakeListener;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        System.out.println("TCP Server listening on " + port);
        ioPool.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                System.out.println("Inbound connected: " + socket.getRemoteSocketAddress());
                Connection conn = new Connection(socket, identity, peerDao, seenMsgIds, messageHandler, handshakeListener);
                ioPool.submit(conn::runReadLoop);
            } catch (IOException e) {
                if (running.get()) {
                    // ignore
                }
                break;
            }
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
    }
}

