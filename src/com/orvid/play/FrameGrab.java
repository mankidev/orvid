package com.orvid.play;

import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

public final class FrameGrab implements Frames {

    private static final int BUCKETS = 120;
    private static final int BLOCK = 128 * 1024;
    private static final int BLOCKS_MAX = 96;
    private static final int FRAME_WIDTH = 320;
    private static final long IDLE_MS = 20000;
    private static final int SEGMENT_MAX = 12 * 1024 * 1024;
    private static final int SEGMENT_HEAD = 768 * 1024;
    private static final int PREFETCH = 16;
    private static final long PREFETCH_DELAY_MS = 3000;
    private static final int[] PREFETCH_ORDER = prefetchOrder();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final String url;
    private final boolean hls;
    private final String referer;
    private final String cookie;
    private final String circuit;
    private final long durationMs;
    private final float aspect;
    private final java.util.concurrent.ThreadPoolExecutor worker = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "orvid-frame-grab");
                t.setDaemon(true);
                return t;
            });
    private final LruCache<Integer, Bitmap> frames = new LruCache<Integer, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Object lock = new Object();
    private int wanted = -1;
    private int prefetched;
    private Storyboard.Callback waiting;
    private boolean running;
    private volatile boolean broken;

    private MediaMetadataRetriever retriever;
    private long usedAt;
    private final Runnable idle = this::closeIfIdle;

    private List<Segment> segments;
    private byte[] init;

    private static final class Segment {
        final String url;
        final long start;
        final long offset;
        final long length;

        Segment(String url, long start, long offset, long length) {
            this.url = url;
            this.start = start;
            this.offset = offset;
            this.length = length;
        }
    }

    public FrameGrab(Direct d, String circuit, long durationMs, float aspect) {
        this.url = d.url;
        this.hls = d.hls;
        this.referer = d.referer;
        this.cookie = d.cookie;
        this.circuit = circuit;
        this.durationMs = durationMs;
        this.aspect = aspect;
        MAIN.postDelayed(this::startPrefetch, PREFETCH_DELAY_MS);
    }

    private static int[] prefetchOrder() {
        List<Integer> order = new ArrayList<>();
        for (int parts = 2; order.size() < PREFETCH; parts *= 2) {
            for (int i = 1; i < parts && order.size() < PREFETCH; i += 2) {
                int bucket = i * BUCKETS / parts;
                if (!order.contains(bucket)) order.add(bucket);
            }
        }
        int[] out = new int[order.size()];
        for (int i = 0; i < out.length; i++) out[i] = order.get(i);
        return out;
    }

    @Override
    public void stop() {
        broken = true;
    }

    private void startPrefetch() {
        synchronized (lock) {
            if (running || broken) return;
            running = true;
        }
        worker.execute(this::drain);
    }

    private int nextPrefetch() {
        while (prefetched < PREFETCH_ORDER.length) {
            int bucket = PREFETCH_ORDER[prefetched++];
            if (frames.get(bucket) == null) return bucket;
        }
        return -1;
    }

    private Bitmap nearest(int bucket) {
        for (int d = 1; d < BUCKETS; d++) {
            Bitmap b = bucket - d >= 0 ? frames.get(bucket - d) : null;
            if (b == null && bucket + d < BUCKETS) b = frames.get(bucket + d);
            if (b != null) return b;
        }
        return null;
    }

    @Override
    public long duration() {
        return durationMs;
    }

    @Override
    public float aspect() {
        return aspect;
    }

    @Override
    public void frameAt(long ms, Storyboard.Callback callback) {
        if (broken) return;
        int bucket = (int) Math.min(BUCKETS - 1, Math.max(0, ms * BUCKETS / durationMs));
        Bitmap cached = frames.get(bucket);
        if (cached != null) {
            callback.frame(cached);
            return;
        }
        Bitmap near = nearest(bucket);
        if (near != null) callback.frame(near);
        synchronized (lock) {
            wanted = bucket;
            waiting = callback;
            if (running) return;
            running = true;
        }
        worker.execute(this::drain);
    }

    private void drain() {
        while (true) {
            int bucket;
            Storyboard.Callback callback;
            synchronized (lock) {
                if (broken) {
                    running = false;
                    return;
                }
                if (wanted >= 0) {
                    bucket = wanted;
                    callback = waiting;
                    wanted = -1;
                    waiting = null;
                } else {
                    bucket = nextPrefetch();
                    callback = null;
                    if (bucket < 0) {
                        running = false;
                        return;
                    }
                }
            }
            Bitmap bmp = frames.get(bucket);
            if (bmp == null) {
                long at = (bucket * 2L + 1) * durationMs / (BUCKETS * 2L);
                long began = android.os.SystemClock.uptimeMillis();
                try {
                    bmp = hls ? hlsFrame(at) : fileFrame(at);
                } catch (Exception e) {
                    Diag.mark("frame grab FAIL " + e);
                    if (e instanceof Unsupported) broken = true;
                }
                if (bmp != null) {
                    frames.put(bucket, bmp);
                    Diag.mark("frame grab " + at / 1000 + "s " + (android.os.SystemClock.uptimeMillis() - began) + "ms");
                }
            }
            final Bitmap shown = bmp;
            if (shown != null && callback != null) MAIN.post(() -> callback.frame(shown));
        }
    }

    private static final class Unsupported extends IOException {
        Unsupported(String message) {
            super(message);
        }
    }

    private Bitmap fileFrame(long ms) throws IOException {
        if (retriever == null) {
            retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(new RangeSource());
            } catch (RuntimeException e) {
                close();
                throw new Unsupported("retriever " + e.getMessage());
            }
        }
        touch();
        return grab(retriever, ms * 1000);
    }

    private Bitmap hlsFrame(long ms) throws IOException {
        if (segments == null) segments = playlist(url, 0);
        if (segments.isEmpty()) throw new Unsupported("no segments");
        Segment s = segments.get(0);
        for (Segment c : segments) {
            if (c.start <= ms) s = c;
            else break;
        }
        boolean whole = s.length > 0 && s.length <= SEGMENT_HEAD;
        if (!whole) {
            try {
                Bitmap head = segmentFrame(get(s.url, s.offset, SEGMENT_HEAD));
                if (head != null) return head;
            } catch (IOException e) {
                Diag.mark("frame grab head FAIL " + e.getMessage());
            }
        }
        return segmentFrame(get(s.url, s.offset, s.length));
    }

    private Bitmap segmentFrame(byte[] body) throws IOException {
        byte[] data = body;
        if (init != null) {
            data = new byte[init.length + body.length];
            System.arraycopy(init, 0, data, 0, init.length);
            System.arraycopy(body, 0, data, init.length, body.length);
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(new BytesSource(data));
            return grab(r, 0);
        } catch (RuntimeException e) {
            throw new IOException("segment " + e.getMessage());
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Bitmap grab(MediaMetadataRetriever r, long us) {
        int height = Math.round(FRAME_WIDTH / aspect);
        Bitmap bmp;
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            bmp = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_WIDTH, height);
        } else {
            bmp = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bmp != null && bmp.getWidth() > FRAME_WIDTH) {
                Bitmap small = Bitmap.createScaledBitmap(bmp, FRAME_WIDTH, Math.round(FRAME_WIDTH * bmp.getHeight() / (float) bmp.getWidth()), true);
                bmp.recycle();
                bmp = small;
            }
        }
        return bmp;
    }

    private List<Segment> playlist(String at, int depth) throws IOException {
        String text = new String(get(at, 0, -1), java.nio.charset.StandardCharsets.UTF_8);
        if (!text.startsWith("#EXTM3U")) throw new Unsupported("not a playlist");
        String[] lines = text.split("\r?\n");
        if (text.contains("#EXT-X-STREAM-INF")) {
            if (depth > 2) throw new Unsupported("nested playlist");
            String pick = null;
            long best = Long.MAX_VALUE;
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                long bw = attrLong(lines[i], "BANDWIDTH");
                String next = i + 1 < lines.length ? lines[i + 1].trim() : "";
                if (next.isEmpty() || next.startsWith("#")) continue;
                if (pick == null || bw > 0 && bw < best) {
                    best = bw > 0 ? bw : best;
                    pick = next;
                }
            }
            if (pick == null) throw new Unsupported("no variant");
            return playlist(resolve(at, pick), depth + 1);
        }
        List<Segment> out = new ArrayList<>();
        long start = 0;
        long duration = 0;
        long rangeLength = -1;
        long rangeOffset = 0;
        long nextOffset = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE")) throw new Unsupported("encrypted");
            if (line.startsWith("#EXT-X-MAP")) {
                String uri = attr(line, "URI");
                if (uri != null) {
                    long[] range = byteRange(attr(line, "BYTERANGE"), 0);
                    init = get(resolve(at, uri), range[1], range[0]);
                }
            } else if (line.startsWith("#EXTINF:")) {
                String v = line.substring(8);
                int comma = v.indexOf(',');
                try {
                    duration = Math.round(Double.parseDouble(comma >= 0 ? v.substring(0, comma) : v) * 1000);
                } catch (NumberFormatException e) {
                    duration = 0;
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                long[] range = byteRange(line.substring(17), nextOffset);
                rangeLength = range[0];
                rangeOffset = range[1];
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(new Segment(resolve(at, line), start, rangeLength >= 0 ? rangeOffset : 0, rangeLength));
                if (rangeLength >= 0) nextOffset = rangeOffset + rangeLength;
                start += duration;
                duration = 0;
                rangeLength = -1;
            }
        }
        return out;
    }

    private static long[] byteRange(String value, long fallbackOffset) {
        if (value == null) return new long[]{-1, 0};
        String[] p = value.replace("\"", "").split("@");
        long length = Long.parseLong(p[0].trim());
        long offset = p.length > 1 ? Long.parseLong(p[1].trim()) : fallbackOffset;
        return new long[]{length, offset};
    }

    private static String attr(String line, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[,:]" + name + "=(\"[^\"]*\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        String v = m.group(1);
        return v.startsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    private static long attrLong(String line, String name) {
        String v = attr(line, name);
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String resolve(String base, String ref) {
        return java.net.URI.create(base).resolve(ref.replace(" ", "%20")).toString();
    }

    private HttpURLConnection request(String target, long offset, long length) throws IOException {
        HttpURLConnection c = Fetch.open(target);
        c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (length > 0 || offset > 0) {
            c.setRequestProperty("Range", "bytes=" + offset + "-" + (length > 0 ? Long.toString(offset + length - 1) : ""));
        }
        if (referer != null) {
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Origin", referer.replaceAll("/$", ""));
        }
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        return c;
    }

    private byte[] get(String target, long offset, long length) throws IOException {
        try {
            return Fetch.on(circuit, () -> {
                HttpURLConnection c = request(target, offset, length);
                int code = c.getResponseCode();
                if (code >= 400) {
                    Fetch.readBody(c);
                    throw new IOException("HTTP " + code);
                }
                boolean partial = code == 206;
                try (InputStream in = c.getInputStream()) {
                    if (!partial && offset > 0) skip(in, offset);
                    return read(in, length > 0 ? length : SEGMENT_MAX);
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("short body");
                s = 1;
            }
            n -= s;
        }
    }

    private static byte[] read(InputStream in, long max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while (buf.size() < max && (n = in.read(chunk, 0, (int) Math.min(chunk.length, max - buf.size()))) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private void touch() {
        usedAt = android.os.SystemClock.uptimeMillis();
        MAIN.removeCallbacks(idle);
        MAIN.postDelayed(idle, IDLE_MS);
    }

    private void closeIfIdle() {
        worker.execute(() -> {
            if (android.os.SystemClock.uptimeMillis() - usedAt >= IDLE_MS) close();
        });
    }

    private void close() {
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        retriever = null;
    }

    private static final class BytesSource extends MediaDataSource {
        private final byte[] data;

        BytesSource(byte[] data) {
            this.data = data;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) {
            if (position >= data.length) return -1;
            int n = (int) Math.min(size, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, n);
            return n;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public void close() {
        }
    }

    private final class RangeSource extends MediaDataSource {
        private long size = -1;
        private final LruCache<Long, byte[]> blocks = new LruCache<>(BLOCKS_MAX);

        @Override
        public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
            if (size < 0) getSize();
            if (size >= 0 && position >= size) return -1;
            long index = position / BLOCK;
            byte[] block = block(index);
            int inBlock = (int) (position - index * BLOCK);
            if (inBlock >= block.length) return -1;
            int n = Math.min(length, block.length - inBlock);
            System.arraycopy(block, inBlock, buffer, offset, n);
            return n;
        }

        private byte[] block(long index) throws IOException {
            byte[] b = blocks.get(index);
            if (b != null) return b;
            long from = index * BLOCK;
            try {
                b = Fetch.on(circuit, () -> {
                    HttpURLConnection c = request(url, from, BLOCK);
                    int code = c.getResponseCode();
                    if (code != 206 && !(code == 200 && from == 0)) {
                        Fetch.readBody(c);
                        throw new Unsupported("range HTTP " + code);
                    }
                    if (size < 0) size = total(c);
                    try (InputStream in = c.getInputStream()) {
                        return read(in, BLOCK);
                    }
                });
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
            blocks.put(index, b);
            return b;
        }

        private long total(HttpURLConnection c) {
            String range = c.getHeaderField("Content-Range");
            if (range != null && range.contains("/")) {
                try {
                    return Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return -1;
        }

        @Override
        public long getSize() throws IOException {
            if (size < 0) block(0);
            return size;
        }

        @Override
        public void close() {
        }
    }
}
