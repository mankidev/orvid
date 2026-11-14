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
    private Storyboard coarse;
    private boolean prefetched;
    private volatile boolean stopped;

    private static final int COARSE_SHEETS = 2;
    private static final int PREFETCH_SHEETS = 12;
    private static final int PREFETCH_SINGLES = 30;
    private static final long PREFETCH_DELAY_MS = 3000;

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
        return FrameCount.count(n -> exists(spec, n, circuit));
    }

    private static final java.util.regex.Pattern TIMELINE = java.util.regex.Pattern.compile(
            "timeline_screens_url\\s*:\\s*'([^']*\\{time\\}[^']*)'");
    private static final java.util.regex.Pattern TIMELINE_COUNT = java.util.regex.Pattern.compile(
            "timeline_screens_count\\s*:\\s*'(\\d+)'");
    private static final java.util.regex.Pattern FRAME_SIZE = java.util.regex.Pattern.compile("/(\\d{2,4})x(\\d{2,4})/");

    public static Spec timeline(String html, String page) {
        if (html == null) return null;
        java.util.regex.Matcher u = TIMELINE.matcher(html);
        java.util.regex.Matcher n = TIMELINE_COUNT.matcher(html);
        if (!u.find() || !n.find()) return null;
        int count;
        try {
            count = Integer.parseInt(n.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (count < 3) return null;
        String url = u.group(1).replace("\\/", "/");
        if (url.startsWith("//")) url = "https:" + url;
        else if (!url.startsWith("http") && page != null) url = java.net.URI.create(page).resolve(url).toString();
        if (!url.startsWith("http")) return null;
        java.util.regex.Matcher size = FRAME_SIZE.matcher(url);
        int w = 182;
        int h = 100;
        if (size.find()) {
            w = Integer.parseInt(size.group(1));
            h = Integer.parseInt(size.group(2));
        }
        Spec spec = new Spec(url.replace("{time}", "$M"), w, h, 1, 1, count);
        spec.start = 1;
        return spec;
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
        Storyboard rough = null;
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
                Storyboard sb = new Storyboard(u, w, h, n, c, r, durationMs, circuit);
                if (best == null || w > best.width) best = sb;
                boolean few = sb.sheets() <= COARSE_SHEETS;
                if (rough == null || (few ? rough.sheets() > COARSE_SHEETS || w > rough.width : sb.sheets() < rough.sheets())) rough = sb;
            } catch (NumberFormatException ignored) {
            }
        }
        if (best != null && rough != best && rough.sheets() < best.sheets()) best.coarse = rough;
        return best;
    }

    private int sheets() {
        int perSheet = cols * rows;
        return (count + perSheet - 1) / perSheet;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    public void prefetch() {
        if (prefetched) return;
        prefetched = true;
        if (coarse != null && coarse.sheets() <= COARSE_SHEETS) {
            for (int i = coarse.sheets() - 1; i >= 0; i--) coarse.load(coarse.sheetUrl(i), null);
        }
        MAIN.postDelayed(() -> {
            if (stopped) return;
            int sheets = sheets();
            boolean singles = cols * rows == 1;
            if (sheets > PREFETCH_SHEETS && !singles) return;
            int take = Math.min(sheets, singles ? PREFETCH_SINGLES : PREFETCH_SHEETS);
            for (int i = take - 1; i >= 0; i--) load(sheetUrl(take == sheets ? i : i * (sheets - 1) / (take - 1)), null);
        }, PREFETCH_DELAY_MS);
    }

    private Bitmap nearest(int sheet, int index) {
        if (cols * rows > 1) return null;
        int sheets = sheets();
        for (int d = 1; d < sheets; d++) {
            Bitmap bmp = sheet - d >= 0 ? SHEETS.get(sheetUrl(sheet - d)) : null;
            if (bmp == null && sheet + d < sheets) bmp = SHEETS.get(sheetUrl(sheet + d));
            if (bmp != null) return crop(bmp, index);
        }
        return null;
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
            Bitmap frame = crop(cached, index);
            if (frame != null) callback.frame(frame);
            return;
        }
        final boolean[] sharp = new boolean[1];
        if (coarse != null) {
            coarse.frameAt(ms, bmp -> {
                if (!sharp[0]) callback.frame(bmp);
            });
        } else {
            Bitmap near = nearest(sheet, index);
            if (near != null) callback.frame(near);
        }
        load(sheetUrl, () -> {
            Bitmap bmp = sheet(sheetUrl);
            Bitmap frame = bmp == null ? null : crop(bmp, index);
            if (frame == null) return;
            sharp[0] = true;
            callback.frame(frame);
        });
    }

    private String sheetUrl(int sheet) {
        return url.replace("$M", Integer.toString(sheet + start));
    }

    private static Bitmap sheet(String sheetUrl) {
        Bitmap bmp = SHEETS.get(sheetUrl);
        if (bmp != null) return bmp;
        byte[] data = FILES.get(sheetUrl);
        if (data == null) return null;
        bmp = decode(data);
        if (bmp != null) SHEETS.put(sheetUrl, bmp);
        return bmp;
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private String via(String sheetUrl) {
        String host = java.net.URI.create(sheetUrl).getHost();
        return host != null && host.endsWith("ytimg.com") ? com.orvid.image.Images.circuit(sheetUrl) : circuit;
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
                String first = via(sheetUrl);
                byte[] data;
                try {
                    data = Fetch.on(first, () -> referer == null ? Fetch.bytes(sheetUrl) : Fetch.bytes(sheetUrl, referer));
                } catch (Exception e) {
                    if (first.equals(circuit)) throw e;
                    data = Fetch.on(circuit, () -> referer == null ? Fetch.bytes(sheetUrl) : Fetch.bytes(sheetUrl, referer));
                }
                FILES.put(sheetUrl, data);
                Bitmap bmp = decode(data);
                if (bmp != null) SHEETS.put(sheetUrl, bmp);
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
        boolean full = width > 0 && height > 0 && sheet.getWidth() % width == 0 && sheet.getHeight() % height == 0;
        int w = full ? width : sheet.getWidth() / cols;
        int h = full ? height : Math.round(w / aspect());
        int x = (index % cols) * w;
        int y = (index / cols) * h;
        if (w <= 0 || h <= 0 || x + w > sheet.getWidth() || y + h > sheet.getHeight()) return null;
        return Bitmap.createBitmap(sheet, x, y, w, h);
    }
}
