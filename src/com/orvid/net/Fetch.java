package com.orvid.net;

import com.orvid.tor.Tor;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;

public final class Fetch extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    private static final int TIMEOUT_MS = 20000;
    private static final long RETRY_BUDGET_MS = 25000;

    private static final long TOR_WAIT_MS = 120000;
    private static final Fetch INSTANCE = new Fetch();
    private static final ThreadLocal<String> CIRCUIT = new ThreadLocal<>();
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    public static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    public static final class Scope {
        private volatile boolean cancelled;
        volatile boolean background;
        private final Scope parent;
        private final java.util.Set<HttpURLConnection> live =
                java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        private final java.util.Set<Scope> lanes =
                java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

        public Scope() {
            this(null);
        }

        private Scope(Scope parent) {
            this.parent = parent;
        }

        public Scope lane() {
            Scope lane = new Scope(this);
            lane.background = background;
            synchronized (live) {
                if (cancelled) lane.cancelled = true;
                else lanes.add(lane);
            }
            return lane;
        }

        public boolean cancelled() {
            return cancelled || parent != null && parent.cancelled();
        }

        public Scope background() {
            background = true;
            return this;
        }

        public void cancel() {
            java.util.List<HttpURLConnection> open;
            java.util.List<Scope> children;
            synchronized (live) {
                cancelled = true;
                open = new java.util.ArrayList<>(live);
                live.clear();
                children = new java.util.ArrayList<>(lanes);
                lanes.clear();
            }
            for (Scope child : children) child.cancel();
            for (HttpURLConnection c : open) {
                try {
                    c.disconnect();
                } catch (RuntimeException ignored) {
                }
            }
        }

        void add(HttpURLConnection c) throws IOException {
            synchronized (live) {
                if (cancelled()) throw new Cancelled();
                live.add(c);
            }
        }
    }

    public static Scope scope() {
        return SCOPE.get();
    }

    public static <T> T within(Scope scope, Callable<T> work) throws Exception {
        Scope previous = SCOPE.get();
        SCOPE.set(scope);
        try {
            if (scope != null && scope.cancelled()) throw new Cancelled();
            return work.call();
        } finally {
            if (previous == null) SCOPE.remove();
            else SCOPE.set(previous);
        }
    }
    private static final class Binding {
        int slot = -1;
        int avoid = -1;
        String owner;
        String host;
    }

    private static final Map<String, Binding> SLOTS = new LinkedHashMap<String, Binding>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Binding> eldest) {
            return size() > 512;
        }
    };
    private static final long PORT_HOLD_MS = 31 * 60 * 1000L;
    private static final int MAX_PORTS = 64;
    private static final String[] slotOwner = new String[MAX_PORTS];
    private static final long[] slotUsed = new long[MAX_PORTS];
    private static int nextSlot;
    private static volatile boolean torMode;

    private Fetch() {
    }

    public static Fetch get() {
        return INSTANCE;
    }

    public static void setTorMode(boolean on) {
        torMode = on;
    }

    public static boolean torMode() {
        return torMode;
    }

    public static String newCircuit(String prefix) {
        return prefix + ":" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    public static <T> T on(String circuit, Callable<T> work) throws Exception {
        String previous = CIRCUIT.get();
        CIRCUIT.set(circuit);
        try {
            return work.call();
        } finally {
            if (previous == null) CIRCUIT.remove();
            else CIRCUIT.set(previous);
        }
    }

    public interface Attempt<T> {
        T run(String circuit) throws Exception;
    }

    public static <T> T retrying(String circuit, Attempt<T> work) throws Exception {
        int tries = torMode ? 4 : 1;
        long began = System.currentTimeMillis();
        for (int i = 1; ; i++) {
            try {
                return on(circuit, () -> work.run(circuit));
            } catch (Exception e) {
                Scope scope = SCOPE.get();
                if (scope != null && scope.cancelled()) throw new Cancelled();
                if (i >= tries || !blocked(e) || System.currentTimeMillis() - began > RETRY_BUDGET_MS) throw e;
                Diag.mark("blocked on try " + i + ", new circuit: " + e.getClass().getSimpleName());
                Trace.note(Trace.Step.RETRY, i + 1);
                rotate(circuit);
            }
        }
    }

    public static boolean blocked(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            String msg = String.valueOf(t.getMessage());
            if (name.equals("ReCaptchaException") || name.equals("SignInConfirmNotBotException")) return true;
            if (msg.contains("Got HTML document") || msg.contains("not a bot") || msg.contains("Captcha needed")) return true;
            if (t instanceof javax.net.ssl.SSLException) return true;
            if (t instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    public static void rotate(String circuit) {
        synchronized (SLOTS) {
            Binding b = SLOTS.get(circuit);
            if (b == null) return;
            b.avoid = b.slot;
            b.slot = -1;
        }
    }

    public static final class TorUnavailable extends IOException {
        TorUnavailable() {
            super("Tor is not connected");
        }
    }

    public static String firstHost(String circuit) {
        if (circuit == null) return null;
        synchronized (SLOTS) {
            Binding b = SLOTS.get(circuit);
            return b == null ? null : b.host;
        }
    }

    static String site(String host) {
        if (host == null || host.isEmpty()) return null;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        String[] parts = h.split("\\.");
        if (parts.length <= 2) return h;
        int keep = parts[parts.length - 1].length() == 2 && parts[parts.length - 2].length() <= 3 ? 3 : 2;
        return String.join(".", java.util.Arrays.copyOfRange(parts, parts.length - keep, parts.length));
    }

    private static int slot(String circuit, String host, int ports) {
        if (circuit != null && circuit.startsWith(DACH)) {
            synchronized (SLOTS) {
                Binding b = SLOTS.get(circuit);
                if (b == null) {
                    b = new Binding();
                    SLOTS.put(circuit, b);
                }
                if (b.slot < 0) b.slot = Math.floorMod(nextSlot++, ports);
                return b.slot;
            }
        }
        String owner = site(host);
        String name = circuit != null ? circuit : "default:" + owner;
        int n = Math.min(ports, MAX_PORTS);
        long now = System.currentTimeMillis();
        synchronized (SLOTS) {
            Binding b = SLOTS.get(name);
            if (b == null) {
                b = new Binding();
                b.owner = owner != null ? owner : name;
                b.host = host;
                SLOTS.put(name, b);
            }
            if (b.slot < 0 || b.slot >= n) {
                b.slot = pick(b.owner, n, b.avoid, now);
                b.avoid = -1;
            }
            slotOwner[b.slot] = b.owner;
            slotUsed[b.slot] = now;
            return b.slot;
        }
    }

    private static int pick(String owner, int n, int avoid, long now) {
        int free = -1;
        int same = -1;
        int oldest = -1;
        for (int i = 0; i < n; i++) {
            if (i == avoid) continue;
            boolean expired = slotOwner[i] == null || now - slotUsed[i] > PORT_HOLD_MS;
            if (expired && (free < 0 || slotUsed[i] < slotUsed[free])) free = i;
            if (owner.equals(slotOwner[i]) && (same < 0 || slotUsed[i] < slotUsed[same])) same = i;
            if (oldest < 0 || slotUsed[i] < slotUsed[oldest]) oldest = i;
        }
        if (free >= 0) {
            if (slotOwner[free] != null && !owner.equals(slotOwner[free])) release(free);
            return free;
        }
        if (same >= 0) return same;
        if (oldest < 0) oldest = 0;
        Diag.mark("tor: port " + oldest + " shared by " + slotOwner[oldest] + " and " + owner);
        return oldest;
    }

    private static void release(int slot) {
        for (Binding b : SLOTS.values()) if (b.slot == slot) b.slot = -1;
    }

    public static final String DACH = "dach:";

    public static String dachCircuit() {
        return newCircuit("dach");
    }

    public static int torPortFor(String circuit) throws IOException {
        return torPortFor(circuit, null);
    }

    public static int torPortFor(String circuit, String host) throws IOException {
        Tor tor = torFor(circuit);
        try {
            if (tor.state() != Tor.State.READY) Trace.note(Trace.Step.TOR, 0);
            if (!tor.awaitReady(TOR_WAIT_MS)) throw new TorUnavailable();
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        }
        int[] ports = tor.ports();
        if (ports.length == 0) throw new TorUnavailable();
        return ports[slot(circuit, host, ports.length)];
    }

    private static Tor torFor(String circuit) {
        if (circuit == null || !circuit.startsWith(DACH)) return Tor.get();
        Tor.dach().ensureStarted();
        return Tor.dach();
    }

    private static Proxy route(String host) throws IOException {
        if (!torMode) return Proxy.NO_PROXY;
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", torPortFor(CIRCUIT.get(), host)));
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        long began = System.currentTimeMillis();
        String url = request.url();
        int q = url.indexOf('?');
        String shortUrl = (q > 0 ? url.substring(0, q) : url).replaceFirst("^https://", "");
        if (shortUrl.length() > 70) shortUrl = shortUrl.substring(0, 70);
        Diag.mark("net > " + request.httpMethod() + " " + shortUrl + " [" + CIRCUIT.get() + "]");
        try {
            Response r = executeInner(request);
            Diag.mark("net < " + r.responseCode() + " " + r.responseBody().length() / 1024 + "KB "
                    + (System.currentTimeMillis() - began) + "ms " + shortUrl);
            return r;
        } catch (IOException | RuntimeException e) {
            Diag.mark("net ! " + e.getClass().getSimpleName() + " " + e.getMessage() + " "
                    + (System.currentTimeMillis() - began) + "ms " + shortUrl);
            throw e;
        }
    }

    private Response executeInner(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection c = open(request.url());
        c.setRequestMethod(request.httpMethod());
        boolean userAgentSet = false;
        for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
            String name = h.getKey();
            if (name.equalsIgnoreCase("User-Agent")) userAgentSet = true;
            c.setRequestProperty(name, String.join(", ", h.getValue()));
        }
        if (!userAgentSet) c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept-Encoding", "gzip");

        byte[] body = request.dataToSend();
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
        }

        int code = c.getResponseCode();
        if (code == 429) {
            readBody(c);
            throw new ReCaptchaException("rate limited", request.url());
        }
        String text = "HEAD".equals(request.httpMethod()) ? "" : readBody(c);
        return new Response(code, c.getResponseMessage(), c.getHeaderFields(), text,
                c.getURL().toString());
    }

    static String ascii(String url) {
        int i = 0;
        while (i < url.length() && url.charAt(i) < 128) i++;
        if (i == url.length()) return url;
        StringBuilder b = new StringBuilder(url.length() * 2).append(url, 0, i);
        for (byte x : url.substring(i).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            if (x >= 0) b.append((char) x);
            else b.append('%').append(Character.toUpperCase(Character.forDigit((x >> 4) & 15, 16))).append(Character.toUpperCase(Character.forDigit(x & 15, 16)));
        }
        return b.toString();
    }

    public static HttpURLConnection open(String url) throws IOException {
        Scope scope = SCOPE.get();
        if (scope != null && scope.cancelled()) throw new Cancelled();
        Focus.await(scope);
        URL target = new URL(ascii(url));
        HttpURLConnection c = (HttpURLConnection) target.openConnection(route(target.getHost()));
        if (scope != null) scope.add(c);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    public static byte[] bytes(String url) throws IOException {
        return bytes(url, null);
    }

    public static byte[] bytes(String url, String referer) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (referer != null) c.setRequestProperty("Referer", referer);
        int code = c.getResponseCode();
        if (code >= 400) {
            readBody(c);
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    public static String readBody(HttpURLConnection c) throws IOException {
        InputStream in;
        try {
            in = c.getInputStream();
        } catch (IOException e) {
            in = c.getErrorStream();
            if (in == null) return "";
        }
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = s.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
