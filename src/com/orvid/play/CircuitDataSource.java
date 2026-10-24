package com.orvid.play;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class CircuitDataSource extends BaseDataSource {

    private static final long CHUNK = 10L * 1024 * 1024;
    private static final long HEAD_PART = 512L * 1024;
    private static final int PARTS = 3;
    private static final long PARALLEL_MAX = 48L * 1024 * 1024;
    private static final long PART_WAIT_MS = 60000;
    static final int CAPTION_RETRIES = 4;
    private static final java.util.concurrent.ExecutorService PARALLEL =
            java.util.concurrent.Executors.newCachedThreadPool();

    private static final long AHEAD = 4L * 1024 * 1024;
    private static final long PARKED_MS = 30000;
    private static final int PARKED_MAX = 4;

    private static final class Parked {
        HttpURLConnection connection;
        InputStream stream;
        long next;
        long serverEnd;
        long total;
        Map<String, List<String>> headers;
        String effective;
        long at;

        void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final java.util.LinkedHashMap<String, Parked> PARKED = new java.util.LinkedHashMap<>();

    private static Parked unpark(String url) {
        synchronized (PARKED) {
            return PARKED.remove(url);
        }
    }

    private static void park(String url, Parked p) {
        List<Parked> stale = new java.util.ArrayList<>();
        synchronized (PARKED) {
            Parked old = PARKED.put(url, p);
            if (old != null) stale.add(old);
            long now = System.currentTimeMillis();
            for (java.util.Iterator<Parked> it = PARKED.values().iterator(); it.hasNext(); ) {
                Parked q = it.next();
                if (q != p && (now - q.at > PARKED_MS || PARKED.size() > PARKED_MAX)) {
                    stale.add(q);
                    it.remove();
                }
            }
        }
        for (Parked q : stale) q.close();
    }

    static boolean aheadHost(String url) {
        String host = Uri.parse(url).getHost();
        return host != null && host.endsWith(".googlevideo.com");
    }

    static boolean parallelHost(String url) {
        String host = Uri.parse(url).getHost();
        if (host == null) return false;
        return host.endsWith(".cloudfront.net") || host.endsWith(".ttvnw.net") || host.endsWith(".twitchcdn.net");
    }

    private static final java.util.concurrent.atomic.AtomicLong RECEIVED = new java.util.concurrent.atomic.AtomicLong();

    public static long received() {
        return RECEIVED.get();
    }

    public static final class Factory implements DataSource.Factory {
        private final String circuit;
        private final String userAgent;
        private final String referer;
        private String cookie;
        private int timeoutMs;

        public Factory(String circuit, String userAgent) {
            this(circuit, userAgent, null);
        }

        public Factory(String circuit, String userAgent, String referer) {
            this.circuit = circuit;
            this.userAgent = userAgent;
            this.referer = referer;
        }

        @Override
        public DataSource createDataSource() {
            CircuitDataSource d = new CircuitDataSource(circuit, userAgent);
            d.referer = referer;
            d.cookie = cookie;
            d.timeoutMs = timeoutMs;
            return d;
        }

        public Factory cookie(String value) {
            cookie = value;
            return this;
        }

        public Factory timeout(int ms) {
            timeoutMs = ms;
            return this;
        }
    }

    private final String circuit;
    private final String userAgent;
    private String referer;
    private String cookie;
    private int timeoutMs;
    private DataSpec spec;
    private HttpURLConnection connection;
    private InputStream stream;
    private long position;
    private long end;
    private long chunkEnd;
    private Map<String, List<String>> headers = Collections.emptyMap();
    private boolean opened;
    private long chunkSize = CHUNK;
    private long partsFrom = -1;
    private long[] partStart;
    private java.util.List<Part> parts;
    private byte[] memory;
    private long memoryEnd;

    private CircuitDataSource(String circuit, String userAgent) {
        super(true);
        this.circuit = circuit;
        this.userAgent = userAgent;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        spec = dataSpec;
        transferInitializing(dataSpec);
        position = dataSpec.position;
        end = dataSpec.length == C.LENGTH_UNSET ? -1 : dataSpec.position + dataSpec.length;
        partsFrom = -1;
        parts = null;
        memory = null;
        if (SubTranslate.handles(dataSpec.uri.toString())) {
            byte[] vtt = SubTranslate.vtt(circuit, dataSpec.uri.toString(), userAgent, referer, cookie);
            memory = vtt;
            memoryEnd = vtt.length;
            end = vtt.length;
            chunkEnd = 0;
            knownTotal = vtt.length;
            opened = true;
            transferStarted(dataSpec);
            return Math.max(0, vtt.length - position);
        }
        Warm.Entry warm = Warm.get(dataSpec.uri.toString());
        if (warm != null && position < warm.head.length) {
            memory = warm.head;
            memoryEnd = end > 0 ? Math.min(end, memory.length) : memory.length;
            chunkSize = CHUNK;
            chunkEnd = 0;
            opened = true;
            transferStarted(dataSpec);
            knownTotal = warm.total;
            Diag.mark("media warm @" + position / 1024 + "KB of " + memory.length / 1024 + "KB");
            if (dataSpec.length != C.LENGTH_UNSET) return dataSpec.length;
            if (warm.total > 0) {
                end = warm.total;
                return warm.total - position;
            }
            return C.LENGTH_UNSET;
        }
        Parked parked = unpark(dataSpec.uri.toString());
        if (parked != null) {
            if (parked.next == position && System.currentTimeMillis() - parked.at < PARKED_MS && (end < 0 || end <= parked.serverEnd)) {
                connection = parked.connection;
                stream = parked.stream;
                chunkEnd = parked.serverEnd;
                headers = parked.headers;
                effective = parked.effective;
                effectiveFor = dataSpec.uri;
                if (parked.total > 0) knownTotal = parked.total;
                opened = true;
                transferStarted(dataSpec);
                Diag.mark("media " + kind(dataSpec.uri.toString()) + " @" + position / 1024 + "KB from open stream");
                if (dataSpec.length != C.LENGTH_UNSET) return dataSpec.length;
                if (knownTotal > 0) {
                    end = knownTotal;
                    return knownTotal - position;
                }
                return C.LENGTH_UNSET;
            }
            parked.close();
        }
        boolean parallel = dataSpec.position == 0 && dataSpec.length == C.LENGTH_UNSET
                && parallelHost(dataSpec.uri.toString());
        chunkSize = parallel ? HEAD_PART : CHUNK;
        long total;
        long began = System.currentTimeMillis();
        try {
            total = connect();
        } catch (IOException e) {
            if (!(e instanceof HttpDataSource.InvalidResponseCodeException)) {
                Diag.mark("media FAIL " + kind(dataSpec.uri.toString()) + " after " + (System.currentTimeMillis() - began) + "ms: " + e.getMessage());
            }
            throw e;
        }
        if (total > 0) knownTotal = total;
        if (parallel && total > HEAD_PART * 2 && total <= PARALLEL_MAX && chunkEnd == HEAD_PART) startParts(total);
        opened = true;
        transferStarted(dataSpec);
        if (dataSpec.length != C.LENGTH_UNSET) return dataSpec.length;
        if (total > 0) {
            end = total;
            return total - position;
        }
        return C.LENGTH_UNSET;
    }

    private String effective;
    private Uri effectiveFor;

    static boolean caption(String url) {
        return url.contains("youtube.com/api/timedtext");
    }

    static String captionCircuit(String circuit) {
        return circuit + "/cc";
    }

    private int request(String url, long chunkLast) throws IOException {
        String via = caption(url) ? captionCircuit(circuit) : circuit;
        try {
            connection = Fetch.on(via, () -> Fetch.open(url));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (timeoutMs > 0) {
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
        }
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Range", "bytes=" + position + "-" + chunkLast);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (referer != null) {
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Origin", referer.replaceAll("/$", ""));
        }
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        for (Map.Entry<String, String> h : spec.httpRequestHeaders.entrySet()) {
            connection.setRequestProperty(h.getKey(), h.getValue());
        }
        if (com.orvid.dm.Dailymotion.needsUnblock(url)) {
            for (Map.Entry<String, String> h : com.orvid.dm.Dailymotion.unblockHeaders().entrySet()) {
                connection.setRequestProperty(h.getKey(), h.getValue());
            }
        }
        return connection.getResponseCode();
    }

    private long connect() throws IOException {
        closeConnection();
        long last = end > 0 ? end - 1 : -1;
        long chunkLast = position + chunkSize - 1;
        if (last >= 0 && last < chunkLast) chunkLast = last;
        if (end > 0 && end - position < AHEAD && aheadHost(spec.uri.toString())) {
            chunkLast = position + AHEAD - 1;
            if (knownTotal > 0) chunkLast = Math.min(chunkLast, knownTotal - 1);
            chunkLast = Math.max(chunkLast, last);
        }
        chunkEnd = chunkLast + 1;
        boolean reused = effective != null && effectiveFor == spec.uri;
        String url = reused ? effective : spec.uri.toString();
        long began = System.currentTimeMillis();
        int code = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            code = request(url, chunkLast);
            for (int hop = 0; hop < 5 && code >= 300 && code < 400; hop++) {
                String location = connection.getHeaderField("Location");
                if (location == null) break;
                String next = java.net.URI.create(url).resolve(location).toString().replaceFirst("^http://", "https://");
                closeConnection();
                url = next;
                code = request(url, chunkLast);
            }
            if (code == 200 || code == 206 || !reused) break;
            closeConnection();
            reused = false;
            effective = null;
            url = spec.uri.toString();
        }
        for (int retry = 0; code == 429 && caption(url) && retry < CAPTION_RETRIES; retry++) {
            closeConnection();
            Fetch.rotate(captionCircuit(circuit));
            Diag.mark("caption 429, new circuit");
            code = request(url, chunkLast);
        }
        if (code == 200 || code == 206) {
            effective = url;
            effectiveFor = spec.uri;
        }
        headers = connection.getHeaderFields();
        if (code != 206 && code != 200) {
            Diag.mark("media HTTP " + code + " " + kind(url));
            closeConnection();
            throw new HttpDataSource.InvalidResponseCodeException(code, null, null, headers, spec, new byte[0]);
        }
        long total = -1;
        String range = connection.getHeaderField("Content-Range");
        if (range != null && range.contains("/")) {
            String tail = range.substring(range.lastIndexOf('/') + 1).trim();
            if (!tail.equals("*")) total = Long.parseLong(tail);
        }
        if (code == 200) {
            chunkEnd = Long.MAX_VALUE;
            long len = connection.getContentLengthLong();
            if (len > 0 && position == 0) total = len;
            if (position > 0) {
                InputStream in = connection.getInputStream();
                long skip = position;
                while (skip > 0) {
                    long n = in.skip(skip);
                    if (n <= 0) throw new IOException("cannot skip");
                    skip -= n;
                }
            }
        }
        stream = connection.getInputStream();
        Diag.mark("media " + kind(url) + " @" + position / 1024 + "KB " + code + " "
                + (System.currentTimeMillis() - began) + "ms");
        return total;
    }

    private static String kind(String url) {
        int i = url.indexOf("itag=");
        if (i < 0) i = url.indexOf("/itag/");
        if (i < 0) return url.contains("m3u8") || url.contains("manifest") ? "playlist" : "file";
        int s = url.charAt(i) == '/' ? i + 6 : i + 5;
        int e = s;
        while (e < url.length() && Character.isDigit(url.charAt(e))) e++;
        return "itag" + url.substring(s, e);
    }

    private static final class Part {
        final long from;
        final byte[] data;
        int filled;
        int round;
        IOException failed;
        java.util.concurrent.Future<?> task;

        Part(long from, long to) {
            this.from = from;
            data = new byte[(int) (to - from)];
        }

        synchronized int begin() {
            return round;
        }

        synchronized long next() {
            return from + filled;
        }

        synchronized void add(int owner, byte[] chunk, int n) throws IOException {
            if (owner != round) throw new IOException("part replaced");
            if (filled + n > data.length) throw new IOException("long part");
            System.arraycopy(chunk, 0, data, filled, n);
            filled += n;
            notifyAll();
        }

        synchronized void fail(int owner, IOException e) {
            if (owner != round) return;
            failed = e;
            notifyAll();
        }

        synchronized int await(int at, long until) throws IOException {
            while (filled <= at && failed == null) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return -1;
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("part wait interrupted");
                }
            }
            if (filled > at) return filled;
            return -1;
        }

        synchronized void restart() {
            round++;
            failed = null;
        }
    }

    private void startParts(long total) {
        String url = spec.uri.toString();
        partsFrom = HEAD_PART;
        long size = (total - HEAD_PART + PARTS - 1) / PARTS;
        partStart = new long[PARTS + 1];
        parts = new java.util.ArrayList<>();
        for (int i = 0; i < PARTS; i++) {
            long from = HEAD_PART + i * size;
            long to = Math.min(total, from + size);
            partStart[i] = from;
            partStart[i + 1] = to;
            Part part = new Part(from, to);
            parts.add(part);
            final String c = i == 0 ? circuit : circuit + "#p" + i;
            part.task = PARALLEL.submit(() -> fill(c, url, part));
        }
        Diag.mark("media parallel " + PARTS + " parts of " + (total - HEAD_PART) / 1024 + "KB");
    }

    private void fill(String c, String url, Part part) {
        int owner = part.begin();
        try {
            Fetch.on(c, () -> {
                long from = part.next();
                long to = part.from + part.data.length;
                HttpURLConnection h = Fetch.open(url);
                h.setRequestProperty("User-Agent", userAgent);
                h.setRequestProperty("Accept-Encoding", "identity");
                h.setRequestProperty("Range", "bytes=" + from + "-" + (to - 1));
                int code = h.getResponseCode();
                if (code != 206) throw new IOException("part HTTP " + code);
                try (InputStream in = h.getInputStream()) {
                    byte[] chunk = new byte[65536];
                    for (int n = in.read(chunk); n > 0; n = in.read(chunk)) part.add(owner, chunk, n);
                }
                if (part.next() != to) throw new IOException("short part");
                return null;
            });
        } catch (IOException e) {
            part.fail(owner, e);
        } catch (Exception e) {
            part.fail(owner, new IOException(e));
        }
    }

    private int readParts(byte[] buffer, int offset, int length) throws IOException {
        int i = 0;
        while (i < PARTS && position >= partStart[i + 1]) i++;
        if (i >= PARTS) return C.RESULT_END_OF_INPUT;
        Part part = parts.get(i);
        int at = (int) (position - part.from);
        long until = System.currentTimeMillis() + PART_WAIT_MS;
        int have = part.await(at, until);
        if (have < 0) {
            Diag.mark("media part " + i + " failed, retrying on main circuit");
            part.task.cancel(true);
            part.restart();
            String url = spec.uri.toString();
            part.task = PARALLEL.submit(() -> fill(circuit, url, part));
            have = part.await(at, until + PART_WAIT_MS);
            if (have < 0) throw part.failed != null ? part.failed : new IOException("part timed out");
        }
        int n = Math.min(length, have - at);
        System.arraycopy(part.data, at, buffer, offset, n);
        position += n;
        bytesTransferred(n);
        RECEIVED.addAndGet(n);
        return n;
    }

    private static final int KEEP_MAX = 2 * 1024 * 1024;
    private java.io.ByteArrayOutputStream kept;
    private byte[] keptBase;
    private long keptFrom = -1;
    private long knownTotal = -1;

    private void keep(byte[] buffer, int offset, int n, long at) {
        if (keptFrom == -2) return;
        String url = spec.uri.toString();
        if (kept == null) {
            Warm.Entry have = Warm.get(url);
            long known = have == null ? 0 : have.head.length;
            if (at != known || known >= KEEP_MAX || caption(url) || url.contains(".m3u8")) {
                keptFrom = -2;
                return;
            }
            kept = new java.io.ByteArrayOutputStream();
            keptBase = have == null ? null : have.head;
            keptFrom = at;
        }
        long next = keptFrom + kept.size();
        if (at != next) {
            keptFrom = -2;
            return;
        }
        int take = (int) Math.min(n, KEEP_MAX - next);
        if (take > 0) kept.write(buffer, offset, take);
    }

    private void storeKept() {
        if (kept == null || kept.size() == 0 || spec == null) return;
        byte[] add = kept.toByteArray();
        byte[] head = add;
        if (keptBase != null) {
            head = java.util.Arrays.copyOf(keptBase, keptBase.length + add.length);
            System.arraycopy(add, 0, head, keptBase.length, add.length);
        }
        Warm.keep(spec.uri.toString(), head, knownTotal);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        long at = position;
        boolean cached = memory != null && position < memoryEnd;
        int n = readInner(buffer, offset, length);
        if (n > 0 && !cached) keep(buffer, offset, n, at);
        return n;
    }

    private int readInner(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (end > 0 && position >= end) return C.RESULT_END_OF_INPUT;
        if (memory != null) {
            if (position < memoryEnd) {
                int n = (int) Math.min(length, memoryEnd - position);
                System.arraycopy(memory, (int) position, buffer, offset, n);
                position += n;
                bytesTransferred(n);
        RECEIVED.addAndGet(n);
                return n;
            }
            memory = null;
        }
        if (parts != null && position >= partsFrom) return readParts(buffer, offset, length);
        if (position >= chunkEnd || stream == null) connect();
        if (end > 0) length = (int) Math.min(length, end - position);
        int n = stream.read(buffer, offset, length);
        if (n < 0) {
            if (chunkEnd != Long.MAX_VALUE && (end < 0 || position < end)) {
                connect();
                n = stream.read(buffer, offset, length);
                if (n < 0) return C.RESULT_END_OF_INPUT;
            } else {
                return C.RESULT_END_OF_INPUT;
            }
        }
        position += n;
        bytesTransferred(n);
        RECEIVED.addAndGet(n);
        return n;
    }

    @Override
    public Uri getUri() {
        if (spec == null) return null;
        return effective != null && effectiveFor == spec.uri ? Uri.parse(effective) : spec.uri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return headers;
    }

    private void closeConnection() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            stream = null;
        }
        connection = null;
    }

    @Override
    public void close() {
        if (stream != null && spec != null && end > 0 && position == end && position < chunkEnd
                && chunkEnd != Long.MAX_VALUE && aheadHost(spec.uri.toString())) {
            Parked p = new Parked();
            p.connection = connection;
            p.stream = stream;
            p.next = position;
            p.serverEnd = chunkEnd;
            p.total = knownTotal;
            p.headers = headers;
            p.effective = effective;
            p.at = System.currentTimeMillis();
            park(spec.uri.toString(), p);
            stream = null;
            connection = null;
        }
        storeKept();
        kept = null;
        keptBase = null;
        keptFrom = -1;
        memory = null;
        closeConnection();
        if (parts != null) {
            for (Part p : parts) p.task.cancel(true);
            parts = null;
        }
        if (opened) {
            opened = false;
            transferEnded();
        }
        spec = null;
    }
}
