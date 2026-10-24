package com.orvid.cast;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class CastChannel implements Closeable {

    static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    static final String SENDER = "sender-castor";
    static final String RECEIVER = "receiver-0";

    private static final int MAX_FRAME = 1 << 20;
    private static final long PING_MS = 5000;
    private static final long DEAD_MS = 16000;

    interface Listener {
        void onMessage(String namespace, String source, JsonObject payload);

        void onClosed(Exception reason);
    }

    static volatile java.util.function.Consumer<String> trace;
    private final Listener listener;
    private SSLSocket socket;
    private OutputStream out;
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "orvid-ping");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastHeard;
    private volatile boolean closed;

    CastChannel(Listener listener) {
        this.listener = listener;
    }

    void connect(String host, int port) throws IOException {
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{new TrustReceiver()}, null);
            socket = (SSLSocket) tls.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(host, port), 6000);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            out = socket.getOutputStream();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        lastHeard = System.currentTimeMillis();
        Thread reader = new Thread(this::readLoop, "orvid-read");
        reader.setDaemon(true);
        reader.start();
        send(RECEIVER, NS_CONNECTION, type("CONNECT"));
        pinger.scheduleWithFixedDelay(this::ping, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
    }

    void send(String destination, String namespace, JsonObject payload) throws IOException {
        if (closed) throw new IOException("cast connection closed");
        byte[] frame = encode(SENDER, destination, namespace, JsonWriter.string(payload));
        if (trace != null && !"PING".equals(payload.getString("type", ""))) trace.accept("-> " + JsonWriter.string(payload));
        synchronized (this) {
            out.write(frame);
            out.flush();
        }
    }

    static JsonObject type(String type) {
        JsonObject o = new JsonObject();
        o.put("type", type);
        return o;
    }

    private void ping() {
        if (closed) return;
        if (System.currentTimeMillis() - lastHeard > DEAD_MS) {
            fail(new IOException("TV stopped answering"));
            return;
        }
        try {
            send(RECEIVER, NS_HEARTBEAT, type("PING"));
        } catch (IOException e) {
            fail(e);
        }
    }

    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (!closed) {
                int length = in.readInt();
                if (length <= 0 || length > MAX_FRAME) throw new IOException("bad cast frame");
                byte[] frame = new byte[length];
                in.readFully(frame);
                lastHeard = System.currentTimeMillis();
                Message m = decode(frame);
                if (m.payload == null) continue;
                if (trace != null && !m.payload.contains("PONG") && !m.payload.contains("PING")) trace.accept("<- " + m.payload);
                JsonObject json;
                try {
                    json = JsonParser.object().from(m.payload);
                } catch (Exception e) {
                    continue;
                }
                String t = json.getString("type", "");
                if (NS_HEARTBEAT.equals(m.namespace) && "PING".equals(t)) {
                    send(m.source, NS_HEARTBEAT, type("PONG"));
                    continue;
                }
                if (NS_CONNECTION.equals(m.namespace) && "CLOSE".equals(t) && RECEIVER.equals(m.source)) {
                    throw new IOException("TV closed the connection");
                }
                listener.onMessage(m.namespace, m.source, json);
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    private void fail(Exception e) {
        if (closed) return;
        close();
        listener.onClosed(e);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pinger.shutdownNow();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    boolean isOpen() {
        return !closed;
    }

    static final class Message {
        String source;
        String destination;
        String namespace;
        String payload;
    }

    static byte[] encode(String source, String destination, String namespace, String payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varintField(body, 1, 0);
        stringField(body, 2, source);
        stringField(body, 3, destination);
        stringField(body, 4, namespace);
        varintField(body, 5, 0);
        stringField(body, 6, payload);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream(b.length + 4);
        frame.write(b.length >>> 24);
        frame.write(b.length >>> 16);
        frame.write(b.length >>> 8);
        frame.write(b.length);
        frame.write(b, 0, b.length);
        return frame.toByteArray();
    }

    static Message decode(byte[] b) throws IOException {
        Message m = new Message();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long key = varint(b, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                varint(b, pos);
            } else if (wire == 2) {
                int len = (int) varint(b, pos);
                if (len < 0 || pos[0] + len > b.length) throw new IOException("bad cast field");
                String s = field == 7 ? null : new String(b, pos[0], len, StandardCharsets.UTF_8);
                pos[0] += len;
                if (field == 2) m.source = s;
                else if (field == 3) m.destination = s;
                else if (field == 4) m.namespace = s;
                else if (field == 6) m.payload = s;
            } else {
                throw new IOException("unexpected wire type " + wire);
            }
        }
        return m;
    }

    private static void varintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, (long) field << 3);
        writeVarint(out, value);
    }

    private static void stringField(ByteArrayOutputStream out, int field, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, (long) field << 3 | 2);
        writeVarint(out, raw.length);
        out.write(raw, 0, raw.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) (v & 0x7F | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long varint(byte[] b, int[] pos) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= b.length) throw new IOException("truncated varint");
            int x = b[pos[0]++];
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return result;
        }
        throw new IOException("varint too long");
    }

    private static final class TrustReceiver implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
