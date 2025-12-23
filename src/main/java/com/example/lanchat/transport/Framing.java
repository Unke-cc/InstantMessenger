package com.example.lanchat.transport;

import com.example.lanchat.core.Settings;
import com.example.lanchat.protocol.MessageEnvelope;
import com.google.gson.Gson;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class Framing {

    private static final Gson GSON = new Gson();

    private Framing() {
    }

    public static BufferedInputStream wrapIn(InputStream in) {
        return new BufferedInputStream(in);
    }

    public static BufferedOutputStream wrapOut(OutputStream out) {
        return new BufferedOutputStream(out);
    }

    public static MessageEnvelope readMessage(BufferedInputStream in) throws IOException, FrameTooLargeException {
        byte[] line = readLineBytes(in, Settings.MAX_MESSAGE_BYTES);
        if (line == null) return null;
        String json = new String(line, StandardCharsets.UTF_8);
        return GSON.fromJson(json, MessageEnvelope.class);
    }

    public static void writeMessage(BufferedOutputStream out, MessageEnvelope env) throws IOException, FrameTooLargeException {
        String json = GSON.toJson(env);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Settings.MAX_MESSAGE_BYTES) {
            throw new FrameTooLargeException(bytes.length);
        }
        out.write(bytes);
        out.write('\n');
        out.flush();
    }

    private static byte[] readLineBytes(BufferedInputStream in, int maxBytes) throws IOException, FrameTooLargeException {
        int b;
        int count = 0;
        byte[] buf = new byte[Math.min(1024, maxBytes)];
        while (true) {
            b = in.read();
            if (b == -1) {
                if (count == 0) return null;
                break;
            }
            if (b == '\n') {
                break;
            }
            if (count >= maxBytes) {
                throw new FrameTooLargeException(count + 1);
            }
            if (count == buf.length) {
                int newLen = Math.min(buf.length * 2, maxBytes);
                byte[] nb = new byte[newLen];
                System.arraycopy(buf, 0, nb, 0, buf.length);
                buf = nb;
            }
            buf[count++] = (byte) b;
        }
        if (count == 0) return new byte[0];
        byte[] out = new byte[count];
        System.arraycopy(buf, 0, out, 0, count);
        return out;
    }

    public static class FrameTooLargeException extends Exception {
        public final int sizeBytes;

        public FrameTooLargeException(int sizeBytes) {
            super("Frame too large: " + sizeBytes);
            this.sizeBytes = sizeBytes;
        }
    }
}

