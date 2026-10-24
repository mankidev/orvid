package com.orvid.image;

import com.orvid.net.Streams;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.orvid.R;
import com.orvid.net.Fetch;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Images {

    private final File dir;
    private final LruCache<String, Bitmap> memory;
    private final ThreadPoolExecutor pool;
    private final ThreadPoolExecutor aheadPool;
    private final ThreadPoolExecutor decodePool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new java.util.concurrent.PriorityBlockingQueue<Runnable>());
    private static final int AHEAD_THREADS = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    public void clearDisk() {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    public Images(File dir) {
        this.dir = dir;
        int kilobytes = (int) (Runtime.getRuntime().maxMemory() / 1024 / 4);
        memory = new LruCache<String, Bitmap>(kilobytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        pool = new ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS, new java.util.concurrent.PriorityBlockingQueue<Runnable>());
        aheadPool = new ThreadPoolExecutor(AHEAD_THREADS, AHEAD_THREADS, 30, TimeUnit.SECONDS,
                new java.util.concurrent.PriorityBlockingQueue<Runnable>(), com.orvid.net.Focus.background("orvid-thumb-ahead"));
        aheadPool.allowCoreThreadTimeOut(true);
    }

    private static final String WARM_URL = "https://i.ytimg.com/vi/jNQXAC9IVRw/default.jpg";
    private static final int WARM_CONNECTIONS = 12;
    private static final long WARM_EVERY_MS = 60000;
    private volatile long warmedAt;
    private final java.util.concurrent.ExecutorService warmer = java.util.concurrent.Executors.newCachedThreadPool(
            com.orvid.net.Focus.background("orvid-warm"));

    public boolean busy() {
        return pool.getActiveCount() > 0 || !pool.getQueue().isEmpty();
    }

    private final java.util.concurrent.atomic.AtomicInteger visibleLoads = new java.util.concurrent.atomic.AtomicInteger();

    public boolean loadingVisible() {
        return visibleLoads.get() > 0;
    }

    public void warm() {
        long now = System.currentTimeMillis();
        if (now - warmedAt < WARM_EVERY_MS) return;
        warmedAt = now;
        for (int i = 0; i < WARM_CONNECTIONS; i++) {
            String circuit = "images-" + (i % IMAGE_CIRCUITS);
            warmer.execute(() -> {
                try {
                    Fetch.on(circuit, () -> download(WARM_URL, null));
                } catch (Exception ignored) {
                }
            });
        }
    }

    private final java.util.concurrent.atomic.AtomicLong order = new java.util.concurrent.atomic.AtomicLong();

    private final java.util.Set<String> ahead = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long BATCH_MS = 120;
    private static final int VISIBLE = 0;
    private static final int AVATAR = 1;
    private static final int AHEAD = 2;

    private final class Job implements Runnable, Comparable<Job> {
        final int priority;
        final long seq = order.incrementAndGet();
        final long batch = android.os.SystemClock.uptimeMillis() / BATCH_MS;
        final Runnable work;

        Job(int priority, Runnable work) {
            this.priority = priority;
            this.work = work;
        }

        @Override
        public void run() {
            work.run();
        }

        @Override
        public int compareTo(Job other) {
            if (priority != other.priority) return Integer.compare(priority, other.priority);
            if (priority == VISIBLE && batch != other.batch) return Long.compare(other.batch, batch);
            return Long.compare(seq, other.seq);
        }
    }

    public void load(final ImageView view, final String url, final int targetWidth, final String page) {
        load(view, url, targetWidth, page, VISIBLE);
    }

    public void loadAvatar(ImageView view, String url, int targetWidth) {
        load(view, url, targetWidth, null, AVATAR);
    }

    private void load(final ImageView view, final String url, final int targetWidth, final String page, final int priority) {
        if (url == null || url.isEmpty()) {
            String found = page == null ? null : pageImages.get(page);
            if (found != null && !found.isEmpty()) {
                load(view, found, targetWidth, page, priority);
                return;
            }
            view.setImageDrawable(null);
            if (found != null || page == null || !page.startsWith("https://") || priority == AVATAR) {
                view.setTag(R.id.image_key, null);
                return;
            }
            final String pending = "page:" + page;
            view.setTag(R.id.image_key, pending);
            pool.execute(new Job(priority, () -> {
                if (!pending.equals(view.getTag(R.id.image_key))) return;
                String image = pageImage(page);
                pageImages.put(page, image == null ? "" : image);
                if (image != null) main.post(() -> {
                    if (pending.equals(view.getTag(R.id.image_key))) load(view, image, targetWidth, page, priority);
                });
            }));
            return;
        }
        final String key = url + "@" + targetWidth;
        if (key.equals(view.getTag(R.id.image_key)) && view.getDrawable() != null) return;
        view.setTag(R.id.image_key, key);
        Bitmap cached = memory.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        java.util.Set<ImageView> fresh = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<ImageView> views = waiting.putIfAbsent(key, fresh);
        if (views != null) {
            views.add(view);
            Bitmap done = memory.get(key);
            if (done != null) view.setImageBitmap(done);
            return;
        }
        fresh.add(view);
        String wanted = smaller(url, targetWidth);
        boolean visible = priority == VISIBLE;
        if (visible) visibleLoads.incrementAndGet();
        (raw.get(wanted) != null ? decodePool : pool).execute(new Job(priority, () -> {
            try {
                if (!wanted(key)) return;
                long began = android.os.SystemClock.uptimeMillis();
                String preview = Fetch.torMode() ? preview(wanted) : null;
                if (preview != null && raw.get(wanted) == null) {
                    if (raw.get(preview) != null) showPreview(key, preview, targetWidth);
                    else pool.execute(new Job(priority, () -> showPreview(key, preview, targetWidth)));
                }
                byte[] bytes = obtain(wanted, page, targetWidth);
                if (bytes != null) com.orvid.app.DevInfo.thumb(page, bytes.length, android.os.SystemClock.uptimeMillis() - began);
                Bitmap bitmap = bytes == null ? null : decode(bytes, targetWidth);
                if (bitmap == null) return;
                memory.put(key, bitmap);
                deliver(key, bitmap);
                if (visible) com.orvid.net.Diag.mark("thumb " + (bytes.length / 1024) + "KB "
                        + (android.os.SystemClock.uptimeMillis() - began) + "ms");
            } finally {
                waiting.remove(key);
                if (visible) visibleLoads.decrementAndGet();
            }
        }));
    }

    private final java.util.Map<String, String> pageImages = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 500;
                }
            });
    private static final java.util.regex.Pattern OG_IMAGE = java.util.regex.Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']og:image(?::url)?[\"'][^>]*content=[\"']([^\"']+)|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"']og:image[\"']",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int PAGE_HEAD_MAX = 512 * 1024;

    private String pageImage(String page) {
        String url = page.replaceAll("#.*$", "");
        try {
            return Fetch.on(circuitFor(url), () -> {
                java.net.HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                c.setRequestProperty("Accept", "text/html");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (c.getResponseCode() >= 400) return null;
                StringBuilder head = new StringBuilder();
                java.io.InputStream body = c.getInputStream();
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) body = new java.util.zip.GZIPInputStream(body);
                try (java.io.Reader in = new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] buf = new char[8192];
                    for (int n = in.read(buf); n > 0 && head.length() < PAGE_HEAD_MAX; n = in.read(buf)) {
                        head.append(buf, 0, n);
                        java.util.regex.Matcher m = OG_IMAGE.matcher(head);
                        if (m.find()) {
                            String found = (m.group(1) != null ? m.group(1) : m.group(2)).replace("&amp;", "&");
                            if (found.startsWith("//")) found = "https:" + found;
                            return found.startsWith("http") && !found.toLowerCase(java.util.Locale.ROOT).contains("logo") ? found : null;
                        }
                        int end = head.indexOf("</head>");
                        if (end >= 0) return null;
                    }
                }
                return null;
            });
        } catch (Exception e) {
            return null;
        }
    }

    private void showPreview(String key, String preview, int targetWidth) {
        if (!wanted(key) || memory.get(key) != null) return;
        byte[] small = obtain(preview, null, 0);
        Bitmap first = small == null ? null : decode(small, targetWidth);
        if (first == null || !wanted(key)) return;
        java.util.Set<ImageView> views = waiting.get(key);
        if (views == null) return;
        java.util.List<ImageView> targets = new java.util.ArrayList<>(views);
        main.post(() -> {
            if (memory.get(key) != null) return;
            for (ImageView v : targets) {
                if (key.equals(v.getTag(R.id.image_key))) v.setImageBitmap(first);
            }
        });
    }

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<ImageView>> waiting = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean wanted(String key) {
        java.util.Set<ImageView> views = waiting.get(key);
        if (views == null) return false;
        views.removeIf(v -> !key.equals(v.getTag(R.id.image_key)));
        return !views.isEmpty();
    }

    private void deliver(String key, Bitmap bitmap) {
        java.util.Set<ImageView> views = waiting.get(key);
        if (views == null) return;
        java.util.List<ImageView> targets = new java.util.ArrayList<>(views);
        main.post(() -> {
            for (ImageView v : targets) {
                if (key.equals(v.getTag(R.id.image_key))) v.setImageBitmap(bitmap);
            }
        });
    }

    public void prefetch(String url, int targetWidth, String page) {
        prefetch(url, targetWidth, page, false);
    }

    public void prefetch(String url, int targetWidth, String page, boolean full) {
        if (url == null || url.isEmpty() || memory.get(url + "@" + targetWidth) != null) return;
        String sized = smaller(url, targetWidth);
        if (raw.get(sized) != null) return;
        String preview = full ? null : preview(sized);
        String wanted = preview != null ? preview : sized;
        String owner = preview != null ? null : page;
        if (raw.get(wanted) != null) return;
        if (!ahead.add(wanted)) return;
        aheadPool.execute(new Job(AHEAD, () -> {
            try {
                obtain(wanted, owner, targetWidth);
            } finally {
                ahead.remove(wanted);
            }
        }));
    }

    private static final java.util.regex.Pattern YT_LARGE =
            java.util.regex.Pattern.compile("^https://i\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/(?:hq720|hqdefault|sddefault|maxresdefault)\\.(?:jpg|webp).*");
    private static final java.util.regex.Pattern YT_ANY =
            java.util.regex.Pattern.compile("^https://i\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/(hq720|hqdefault|mqdefault|sddefault|maxresdefault)\\.(?:jpg|webp).*");

    private static String ytJpg(String id, String name) {
        return "https://i.ytimg.com/vi/" + id + "/" + name + ".jpg";
    }

    private static final java.util.Map<String, String> PREVIEWS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 2000;
                }
            });

    public static void previewOf(String full, String small) {
        PREVIEWS.put(full, small);
    }

    private static String preview(String url) {
        java.util.regex.Matcher m = YT_LARGE.matcher(url);
        if (m.matches()) return ytJpg(m.group(1), "mqdefault");
        return PREVIEWS.get(url);
    }

    private final LruCache<String, byte[]> raw = new LruCache<String, byte[]>(48 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return Math.max(1, value.length / 1024);
        }
    };
    private final java.util.concurrent.ConcurrentHashMap<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    private final CircuitHealth health = new CircuitHealth();

    private final LruCache<String, String> byPage = new LruCache<>(1000);

    private byte[] obtain(String wanted, String page, int targetWidth) {
        byte[] known = raw.get(wanted);
        if (known != null) return known;
        String other = page == null ? null : byPage.get(page);
        known = other == null ? null : raw.get(other);
        if (known != null && width(known) * 4 >= targetWidth * 3) return known;
        Object lock = locks.computeIfAbsent(wanted, k -> new Object());
        try {
            synchronized (lock) {
                known = raw.get(wanted);
                if (known != null) return known;
                String circuit = circuitFor(wanted);
                String referer = referer(page);
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    try {
                        long began = android.os.SystemClock.uptimeMillis();
                        byte[] body = Fetch.on(circuit, () -> fetch(wanted, referer));
                        if (body != null && body.length > 0 && decodable(body)) {
                            long now = android.os.SystemClock.uptimeMillis();
                            if (health.record(circuit, now - began, true, now)) Fetch.rotate(circuit);
                            raw.put(wanted, body);
                            if (page != null) byPage.put(page, wanted);
                            return body;
                        }
                    } catch (Gone gone) {
                        return null;
                    } catch (Throwable ignored) {
                    }
                    if (attempt + 1 < ATTEMPTS) Fetch.rotate(circuit);
                }
                return null;
            }
        } finally {
            locks.remove(wanted, lock);
        }
    }

    private static int width(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return bounds.outWidth;
    }

    private static boolean decodable(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return bounds.outWidth > 0;
    }

    private static final int IMAGE_CIRCUITS = 3;
    private static final int ATTEMPTS = 3;

    private static String referer(String page) {
        if (page == null) return null;
        android.net.Uri u = android.net.Uri.parse(page);
        if (u.getScheme() == null || u.getHost() == null || u.getHost().endsWith("youtube.com")) return null;
        return u.getScheme() + "://" + u.getHost() + "/";
    }

    private static byte[] download(String url, String referer) throws IOException {
        java.net.HttpURLConnection c = Fetch.open(url);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
        c.setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8");
        if (referer != null) c.setRequestProperty("Referer", referer);
        int code = c.getResponseCode();
        if (code == 404 || code == 410) throw new Gone(code);
        if (code >= 400) throw new IOException("HTTP " + code);
        try (java.io.InputStream in = c.getInputStream()) {
            return Streams.readAll(in);
        }
    }

    private static final class Gone extends IOException {
        Gone(int code) {
            super("HTTP " + code);
        }
    }

    private static String circuitFor(String url) {
        String host = android.net.Uri.parse(url).getHost();
        String spread = host != null && host.endsWith("ytimg.com") ? url : host;
        int slot = Math.floorMod(spread == null ? 0 : spread.hashCode(), IMAGE_CIRCUITS);
        return "images-" + slot;
    }

    private static final int MEDIUM_WIDTH = 520;

    private static final int SITE_WIDE = 640;
    private static final java.util.regex.Pattern MZ_SQUARE =
            java.util.regex.Pattern.compile("/\\d+x\\d+bb\\.(?:jpg|webp|png)$");
    private static final java.util.regex.Pattern TWIMG_MEDIA =
            java.util.regex.Pattern.compile("^https://pbs\\.twimg\\.com/(?:ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb|media)/[^?]+\\.jpg$");

    private static String sized(String url, int targetWidth) {
        boolean small = targetWidth <= 400;
        if (TWIMG_MEDIA.matcher(url).matches()) return url + "?format=jpg&name=900x900";
        if (url.contains(".coub.com/") && url.contains("/big_") && targetWidth <= SITE_WIDE) {
            return url.replace("/big_", small ? "/small_" : "/med_");
        }
        if (small && url.contains(".jtvnw.net/") && url.contains("-640x360.")) return url.replace("-640x360.", "-320x180.");
        if (small && url.contains(".bitchute.com/") && url.contains("_640x360.")) return url.replace("_640x360.", "_320x180.");
        if (small && url.startsWith("https://pi.tedcdn.com/") && url.endsWith("?w=480")) return url.substring(0, url.length() - 3) + "360";
        if (url.contains(".mzstatic.com/")) {
            java.util.regex.Matcher m = MZ_SQUARE.matcher(url);
            if (m.find()) {
                int side = Math.min(600, Math.max(200, targetWidth));
                return url.substring(0, m.start()) + "/" + side + "x" + side + "bb.webp";
            }
        }
        return null;
    }

    private static String smaller(String url, int targetWidth) {
        String site = sized(url, targetWidth);
        if (site != null) return site;
        if (targetWidth > MEDIUM_WIDTH) return url;
        java.util.regex.Matcher yt = YT_ANY.matcher(url);
        if (targetWidth > 400) {
            if (!yt.matches() || "mqdefault".equals(yt.group(2))) return url;
            return ytJpg(yt.group(1), "hqdefault");
        }
        if (url.contains(".pstatic.net/") && url.contains("type=o500x280")) return url.replaceFirst("type=o500x280[^&]*", "type=w320");
        if (url.contains(".porngo.com/") && url.contains("/medium@2x/")) return url.replace("/medium@2x/", "/medium/");
        if (!yt.matches() || "sddefault".equals(yt.group(2)) || "maxresdefault".equals(yt.group(2))) return url;
        return ytJpg(yt.group(1), "mqdefault");
    }

    private byte[] fetch(String url, String referer) throws IOException {
        try {
            return download(url, referer);
        } catch (IOException e) {
            if (!url.endsWith("/hq720.webp")) throw e;
            return download(url.replace("/vi_webp/", "/vi/").replace("/hq720.webp", "/hqdefault.jpg"), referer);
        }
    }

    private static Bitmap decode(byte[] bytes, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (targetWidth > 0 && bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        options.inSampleSize = sample;
        if (!"image/png".equals(bounds.outMimeType)) options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null || targetWidth <= 0 || decoded.getWidth() <= targetWidth * 1.15) return decoded;
        int height = Math.max(1, Math.round(decoded.getHeight() * (targetWidth / (float) decoded.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, targetWidth, height, true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }
}
