package com.orvid.more;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;
import com.orvid.play.Direct;
import com.orvid.search.Source;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class Site {

    public final int serviceId;
    public final String name;
    private final Pattern url;

    protected Site(int serviceId, String name, String urlPattern) {
        this.serviceId = serviceId;
        this.name = name;
        this.url = Pattern.compile(urlPattern);
    }

    public abstract Source.Page search(String circuit, String query, String next) throws IOException;

    public abstract Direct resolve(String circuit, String url) throws IOException;

    public boolean handles(String url) {
        return id(url) != null;
    }

    protected String id(String pageUrl) {
        if (pageUrl == null) return null;
        Matcher m = url.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }

    protected StreamInfoItem item(String url, String title, String author, long seconds, long views, String thumb) {
        StreamInfoItem item = new StreamInfoItem(serviceId, url, clean(title), StreamType.VIDEO_STREAM);
        item.setUploaderName(author == null ? "" : clean(author));
        item.setDuration(seconds);
        item.setViewCount(views);
        if (thumb != null && !thumb.isEmpty()) {
            List<Image> thumbs = new ArrayList<>();
            thumbs.add(new Image(https(thumb), 180, 320, Image.ResolutionLevel.MEDIUM));
            item.setThumbnails(thumbs);
        }
        return item;
    }



    private static final java.util.concurrent.ExecutorService LOOKUPS = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "orvid-duration");
        t.setDaemon(true);
        return t;
    });

    protected interface Seconds {
        long of(StreamInfoItem item) throws Exception;
    }

    protected static void durations(List<StreamInfoItem> items, long budgetMs, Seconds lookup) {
        List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        com.orvid.net.Fetch.Scope scope = com.orvid.net.Fetch.scope();
        for (StreamInfoItem item : items) {
            if (item.getDuration() > 0) continue;
            jobs.add(LOOKUPS.submit(() -> {
                try {
                    long s = com.orvid.net.Fetch.within(scope, () -> lookup.of(item));
                    if (s > 0) item.setDuration(s);
                } catch (Exception ignored) {
                }
            }));
        }
        long end = System.currentTimeMillis() + budgetMs;
        for (java.util.concurrent.Future<?> f : jobs) {
            try {
                f.get(Math.max(0, end - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                f.cancel(true);
            }
        }
    }

    private static final long DEAD_HOST_MS = 10 * 60_000L;
    private static final java.util.Map<String, Long> DEAD_HOSTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, java.util.Set<String>> FAILED_HOSTS = new java.util.concurrent.ConcurrentHashMap<>();

    protected static void dropUnreachable(List<StreamInfoItem> items, java.util.Map<StreamInfoItem, String> media) {
        items.removeIf(item -> unreachable(media.get(item)));
    }

    private static String host(String url) {
        try {
            return url == null ? null : new java.net.URL(url).getHost();
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    private static boolean unreachable(String url) {
        String host = host(url);
        Long until = host == null ? null : DEAD_HOSTS.get(host);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        DEAD_HOSTS.remove(host, until);
        return false;
    }

    private static void reached(String url, String circuit, Exception error) {
        String host = host(url);
        if (host == null) return;
        if (error == null) {
            FAILED_HOSTS.remove(host);
            return;
        }
        if (!refused(error)) return;
        java.util.Set<String> circuits = FAILED_HOSTS.computeIfAbsent(host, h -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        circuits.add(String.valueOf(circuit));
        if (Fetch.torMode() && circuits.size() < 2) return;
        DEAD_HOSTS.put(host, System.currentTimeMillis() + DEAD_HOST_MS);
        FAILED_HOSTS.remove(host);
        Diag.mark("unreachable media host " + host);
    }

    private static boolean refused(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException) return true;
            String m = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (t instanceof java.net.SocketTimeoutException && m.contains("connect")) return true;
            if (m.startsWith("unable to tunnel through proxy")) return true;
        }
        return false;
    }

    protected static long mp4Seconds(String circuit, String url) throws Exception {
        if (unreachable(url)) throw new IOException("unreachable host");
        long offset = 0;
        for (int hop = 0; hop < MP4_HOPS; hop++) {
            final long from = offset;
            byte[] head = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                c.setRequestProperty("Range", "bytes=" + from + "-" + (from + 4095));
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (from == 0) {
                    try {
                        c.connect();
                        reached(url, circuit, null);
                    } catch (IOException e) {
                        reached(url, circuit, e);
                        throw e;
                    }
                }
                if (c.getResponseCode() != 206 && from > 0) throw new IOException("no range");
                if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) {
                    return Streams.readN(in, 4096);
                }
            });
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(head);
            int at = 0;
            while (at + 8 <= head.length) {
                long size = b.getInt(at) & 0xFFFFFFFFL;
                String type = new String(head, at + 4, 4, StandardCharsets.ISO_8859_1);
                if (size == 1 && at + 16 <= head.length) size = b.getLong(at + 8);
                if (type.equals("moov")) {
                    long seconds = mvhd(head, at);
                    if (seconds >= 0) return seconds;
                    break;
                }
                if (size < 8) return -1;
                if (at + size > head.length) {
                    offset += at + size;
                    break;
                }
                at += (int) size;
            }
            if (at + 8 > head.length) offset += at;
        }
        return -1;
    }

    private static final int MP4_HOPS = 4;

    private static long mvhd(byte[] head, int from) {
        for (int i = from; i + 36 <= head.length; i++) {
            if (head[i] != 'm' || head[i + 1] != 'v' || head[i + 2] != 'h' || head[i + 3] != 'd') continue;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(head);
            boolean wide = head[i + 4] == 1;
            long scale = b.getInt(i + (wide ? 24 : 16)) & 0xFFFFFFFFL;
            long length = wide ? b.getLong(i + 28) : b.getInt(i + 20) & 0xFFFFFFFFL;
            return scale > 0 ? Math.round((double) length / scale) : -1;
        }
        return -1;
    }

    protected static long isoSeconds(String duration) {
        Matcher m = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?").matcher(duration == null ? "" : duration);
        if (!m.find()) return -1;
        double s = 0;
        if (m.group(1) != null) s += Long.parseLong(m.group(1)) * 3600;
        if (m.group(2) != null) s += Long.parseLong(m.group(2)) * 60;
        if (m.group(3) != null) s += Double.parseDouble(m.group(3));
        return Math.round(s);
    }

    protected static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    protected static String https(String url) {
        return url != null && url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    protected static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    protected static long clock(String s) {
        if (s == null || s.isEmpty()) return -1;
        long total = 0;
        try {
            for (String part : s.trim().split(":")) total = total * 60 + Long.parseLong(part.trim());
            return total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    protected static long number(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return -1;
        try {
            return (long) Double.parseDouble(v.toString().replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    protected JsonObject json(String circuit, String url) throws IOException {
        return parse(text(circuit, url, null, null));
    }

    protected JsonArray array(String circuit, String url) throws IOException {
        String body = text(circuit, url, null, null);
        try {
            return JsonParser.array().from(body);
        } catch (Exception e) {
            throw new IOException(name + " answer is not JSON");
        }
    }

    protected JsonObject post(String circuit, String url, String type, String body) throws IOException {
        return parse(text(circuit, url, type, body));
    }

    private JsonObject parse(String body) throws IOException {
        try {
            return JsonParser.object().from(body);
        } catch (Exception e) {
            throw new IOException(name + " answer is not JSON");
        }
    }

    protected String text(String circuit, String url, String type, String body, String... headers) throws IOException {
        long began = System.currentTimeMillis();
        String result;
        try {
            result = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
                if (body != null) {
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", type);
                    c.setDoOutput(true);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                if (code == 403 || code == 429) throw new IOException("Captcha needed (HTTP " + code + ")");
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    return new String(Streams.readAll(in), StandardCharsets.UTF_8);
                }
            });
        } catch (IOException e) {
            Diag.mark(name + " FAIL " + e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        Diag.mark(name + " " + result.length() / 1024 + "KB " + (System.currentTimeMillis() - began) + "ms");
        return result;
    }
}
