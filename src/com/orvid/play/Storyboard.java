package com.orvid.play;

import com.orvid.net.Streams;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.orvid.net.Fetch;

import java.util.concurrent.ExecutorService;

public final class Storyboard implements Frames {

    public interface Callback {
        void frame(Bitmap bitmap);
    }

    private static final ExecutorService POOL = new java.util.concurrent.ThreadPoolExecutor(3, 3, 30,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);
                }
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, byte[]> FILES = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };
    private static final LruCache<String, Bitmap> SHEETS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final java.util.Map<String, java.util.List<Runnable>> LOADING = new java.util.HashMap<>();

    private final String url;
    private final int width;
    private final int height;
    private final int count;
    private final int cols;
    private final int rows;
    private final long durationMs;
    private final String circuit;
    private int start;
    private String referer;

    private Storyboard(String url, int width, int height, int count, int cols, int rows, long durationMs, String circuit) {
        this.url = url;
        this.width = width;
        this.height = height;
        this.count = count;
        this.cols = cols;
        this.rows = rows;
        this.durationMs = durationMs;
        this.circuit = circuit;
    }

    public static final class Spec {
        final String url;
        final int frameWidth;
        final int frameHeight;
        final int cols;
        final int rows;
        final int count;
        int start;
        public String referer;

        public Spec withCount(int n) {
            Spec s = new Spec(url, frameWidth, frameHeight, cols, rows, n);
            s.start = start;
            s.referer = referer;
            return s;
        }

        public Spec(String url, int frameWidth, int frameHeight, int cols, int rows, int count) {
            this.url = url;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.cols = cols;
            this.rows = rows;
            this.count = count;
        }
    }

    public static Storyboard grid(Spec spec, long durationMs, String circuit) {
        if (spec == null || spec.url == null || durationMs <= 0 || spec.count <= 0) return null;
        Storyboard sb = new Storyboard(spec.url, spec.frameWidth, spec.frameHeight, spec.count, spec.cols, spec.rows, durationMs, circuit);
        sb.start = spec.start;
        sb.referer = spec.referer;
        return sb;
    }

    private static final java.util.regex.Pattern KVS_SHOT = java.util.regex.Pattern.compile(
            "^(https?://.+/(?:videos_screenshots|vth)/\\d+/\\d+/)(\\d+)x(\\d+)/\\d+\\.jpg");

    private static boolean exists(Spec spec, int n, String circuit) {
        final String u = spec.url.replace("$M", Integer.toString(n + spec.start - 1));
        try {
            int code = Fetch.on(circuit, () -> {
                java.net.HttpURLConnection h = Fetch.open(u);
                h.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                h.setRequestProperty("Range", "bytes=0-0");
                if (spec.referer != null) h.setRequestProperty("Referer", spec.referer);
                int c = h.getResponseCode();
                try (java.io.InputStream in = c < 400 ? h.getInputStream() : h.getErrorStream()) {
                    if (in != null) Streams.readAll(in);
                }
                return c;
            });
            return code == 200 || code == 206;
        } catch (Exception e) {
            return false;
        }
    }

    public static int probeCount(Spec spec, String circuit) {
        if (!exists(spec, 3, circuit)) return 0;
        int lo = 3;
        int hi = 6;
        while (hi <= 64 && exists(spec, hi, circuit)) {
            lo = hi;
            hi *= 2;
        }
        hi = Math.min(hi, 65);
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (exists(spec, mid, circuit)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    public static Spec screens(String cover) {
        if (cover == null) return null;
        java.util.regex.Matcher m = KVS_SHOT.matcher(cover);
        if (!m.find()) return null;
        int w = Integer.parseInt(m.group(2));
        int h = Integer.parseInt(m.group(3));
        Spec spec = new Spec(m.group(1) + w + "x" + h + "/$M.jpg", w, h, 1, 1, 10);
        spec.start = 1;
        return spec;
    }

    public static Storyboard parse(String spec, long durationMs, String circuit) {
        if (spec == null || spec.isEmpty() || durationMs <= 0) return null;
        String[] parts = spec.split("\\|");
        if (parts.length < 2) return null;
        String base = parts[0].startsWith("http") ? parts[0] : "https://i.ytimg.com/" + parts[0];
        Storyboard best = null;
        for (int level = 1; level < parts.length; level++) {
            String[] a = parts[level].split("#");
            if (a.length != 8) continue;
            try {
                int w = Integer.parseInt(a[0]);
                int h = Integer.parseInt(a[1]);
                int n = Integer.parseInt(a[2]);
                int c = Integer.parseInt(a[3]);
                int r = Integer.parseInt(a[4]);
                if (w <= 0 || n <= 0 || c <= 0 || r <= 0) continue;
                String u = base.replace("$L", Integer.toString(level - 1)).replace("$N", a[6]) + "&sigh=" + a[7];
                if (best == null || w > best.width) best = new Storyboard(u, w, h, n, c, r, durationMs, circuit);
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    public long duration() {
        return durationMs;
    }

    public float aspect() {
        return width / (float) height;
    }

    public void frameAt(long ms, Callback callback) {
        int k = (int) Math.min(count - 1, Math.max(0, ms * count / durationMs));
        int perSheet = cols * rows;
        int sheet = k / perSheet;
        int index = k % perSheet;
        int sheets = (count + perSheet - 1) / perSheet;
        if (sheet + 1 < sheets) load(sheetUrl(sheet + 1), null);
        if (sheet > 0) load(sheetUrl(sheet - 1), null);
        String sheetUrl = sheetUrl(sheet);
        Bitmap cached = sheet(sheetUrl);
        if (cached != null) {
            callback.frame(crop(cached, index));
        } else {
            load(sheetUrl, () -> {
                Bitmap bmp = sheet(sheetUrl);
                if (bmp != null) callback.frame(crop(bmp, index));
            });
        }
    }

    private String sheetUrl(int sheet) {
        return url.replace("$M", Integer.toString(sheet + start));
    }

    private static Bitmap sheet(String sheetUrl) {
        Bitmap bmp = SHEETS.get(sheetUrl);
        if (bmp != null) return bmp;
        byte[] data = FILES.get(sheetUrl);
        if (data == null) return null;
        bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp != null) SHEETS.put(sheetUrl, bmp);
        return bmp;
    }

    private void load(String sheetUrl, Runnable done) {
        if (FILES.get(sheetUrl) != null) {
            if (done != null) done.run();
            return;
        }
        synchronized (LOADING) {
            java.util.List<Runnable> waiting = LOADING.get(sheetUrl);
            if (waiting != null) {
                if (done != null) waiting.add(done);
                return;
            }
            waiting = new java.util.ArrayList<>();
            if (done != null) waiting.add(done);
            LOADING.put(sheetUrl, waiting);
        }
        POOL.execute(() -> {
            try {
                byte[] data = Fetch.on(circuit, () -> referer == null ? Fetch.bytes(sheetUrl) : Fetch.bytes(sheetUrl, referer));
                FILES.put(sheetUrl, data);
            } catch (Exception ignored) {
            }
            java.util.List<Runnable> waiting;
            synchronized (LOADING) {
                waiting = LOADING.remove(sheetUrl);
            }
            if (waiting == null || waiting.isEmpty() || FILES.get(sheetUrl) == null) return;
            MAIN.post(() -> {
                for (Runnable r : waiting) r.run();
            });
        });
    }

    private Bitmap crop(Bitmap sheet, int index) {
        int w = Math.min(width, sheet.getWidth() / cols);
        int h = Math.min(height, sheet.getHeight() / rows);
        int x = (index % cols) * w;
        int y = (index / cols) * h;
        if (x + w > sheet.getWidth() || y + h > sheet.getHeight()) return sheet;
        return Bitmap.createBitmap(sheet, x, y, w, h);
    }
}
