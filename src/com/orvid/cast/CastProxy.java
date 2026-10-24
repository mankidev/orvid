package com.orvid.cast;


import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CastProxy {

    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_BUFFERED = 32 * 1024 * 1024;
    private static final int MAX_PLAYLIST = 8 * 1024 * 1024;
    private static final long SUBTITLE_WAIT_S = 90;
    private static CastProxy instance;
    public static volatile java.util.function.Consumer<String> trace;

    public interface Files {
        long length(String uri) throws IOException;

        InputStream open(String uri, long offset) throws IOException;
    }

    public static volatile Files files = new Files() {
        @Override
        public long length(String uri) {
            return new java.io.File(URI.create(uri)).length();
        }

        @Override
        public InputStream open(String uri, long offset) throws IOException {
            java.io.FileInputStream in = new java.io.FileInputStream(new java.io.File(URI.create(uri)));
            in.getChannel().position(offset);
            return in;
        }
    };

    private final ServerSocket server;
    private final byte[] key = new byte[32];
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orvid-serve");
        t.setDaemon(true);
        return t;
    });
    private volatile Map<String, String> upstream = Collections.emptyMap();
    private volatile boolean ownSubtitles;
    private final Map<String, CastItem.Track> subtitles = new ConcurrentHashMap<>();
    private volatile String circuit;
    private final Map<String, String> ids = new ConcurrentHashMap<>();
    private final Map<String, String> urls = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Future<String>> rendered = new ConcurrentHashMap<>();

    private CastProxy() throws IOException {
        new SecureRandom().nextBytes(key);
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(0));
        Thread accept = new Thread(this::acceptLoop, "orvid-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public static synchronized CastProxy get() throws IOException {
        if (instance == null) instance = new CastProxy();
        return instance;
    }

    public int port() {
        return server.getLocalPort();
    }

    private CastItem current;

    public synchronized void use(CastItem media) {
        if (media == current) return;
        current = media;
        upstream = new HashMap<>(media.headers);
        circuit = media.circuit;
        ownSubtitles = !media.subtitles.isEmpty();
        subtitles.clear();
        rendered.clear();
        ids.clear();
        urls.clear();
    }

    public String subtitleUrl(CastItem.Track s, String host) {
        String id = sign(s.url);
        subtitles.put(id, s);
        return "http://" + host + ":" + port() + "/s/" + id + ".vtt";
    }

    public void prefetch(CastItem.Track s) {
        String id = sign(s.url);
        subtitles.put(id, s);
        render(id, s);
    }

    public String proxyUrl(String url, String host) {
        if (url.startsWith("file:") || url.startsWith("content:")) {
            return "http://" + host + ":" + port() + "/f/" + id(url) + "/video.mp4";
        }
        return "http://" + host + ":" + port() + path(url);
    }

    private String id(String url) {
        return ids.computeIfAbsent(url, u -> {
            byte[] raw = new byte[9];
            random.nextBytes(raw);
            String fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            urls.put(fresh, u);
            return fresh;
        });
    }

    public static String lanAddress(String towards) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(towards), 9);
            InetAddress local = probe.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress()) return local.getHostAddress();
        } catch (IOException ignored) {
        }
        return "127.0.0.1";
    }

    private String path(String url) {
        return path(url, null);
    }

    private String path(String url, String extension) {
        String id = id(url);
        String path = url.split("[?#]")[0];
        String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "");
        String suffix = extension != null ? "/segment." + extension
                : name.matches(".+\\.[A-Za-z0-9]{2,4}") ? "/" + name : "";
        return "/p/" + id + suffix;
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] cut = new byte[15];
            System.arraycopy(full, 0, cut, 0, cut.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(cut);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Future<String> render(String id, CastItem.Track s) {
        return rendered.computeIfAbsent(id, k -> pool.submit(() -> vtt(s.url)));
    }

    private String vtt(String url) throws IOException {
        if (com.orvid.play.SubTranslate.handles(url)) {
            return new String(com.orvid.play.SubTranslate.vtt(circuit, url, upstream.get("User-Agent"), upstream.get("Referer"),
                    upstream.get("Cookie")), StandardCharsets.UTF_8);
        }
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
        String text = new String(readAll(c.getInputStream(), MAX_PLAYLIST), StandardCharsets.UTF_8).replace("\uFEFF", "");
        if (text.trim().startsWith("WEBVTT")) return text;
        return "WEBVTT\n\n" + text.replace("\r\n", "\n")
                .replaceAll("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})", "$1.$2");
    }

    HttpURLConnection open(String url) throws IOException {
        Map<String, String> headers = upstream;
        try {
            return com.orvid.net.Fetch.on(circuit, () -> {
                HttpURLConnection c = com.orvid.net.Fetch.open(url);
                c.setRequestProperty("User-Agent", com.orvid.net.Fetch.USER_AGENT);
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                return c;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) return;
            String method = request[0];
            String target = request[1];
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            if ("OPTIONS".equals(method)) {
                respond(out, 204, "No Content", null, new byte[0], true);
                return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                respond(out, 405, "Method Not Allowed", "text/plain", new byte[0], true);
                return;
            }
            boolean body = "GET".equals(method);
            if (trace != null) trace.accept(method + " " + (target.length() > 60 ? target.substring(0, 60) : target) + " range=" + headers.get("range") + " origin=" + headers.get("origin"));
            String[] parts = target.split("\\?")[0].split("/");
            if (parts.length >= 3 && "s".equals(parts[1])) {
                subtitle(out, parts[2].replace(".vtt", ""), body);
            } else if (parts.length >= 3 && "f".equals(parts[1]) && urls.containsKey(parts[2])) {
                local(out, urls.get(parts[2]), headers.get("range"), body);
            } else if (parts.length >= 3 && "p".equals(parts[1])) {
                String url = urls.get(parts[2]);
                if (url == null) {
                    respond(out, 404, "Not Found", "text/plain", new byte[0], true);
                    return;
                }
                proxy(out, url, headers.get("range"), body);
            } else {
                respond(out, 404, "Not Found", "text/plain", new byte[0], true);
            }
        } catch (Exception ignored) {
        }
    }

    private void subtitle(OutputStream out, String id, boolean body) throws IOException {
        CastItem.Track s = subtitles.get(id);
        if (s == null) {
            respond(out, 404, "Not Found", "text/plain", new byte[0], true);
            return;
        }
        try {
            String vtt = render(id, s).get(SUBTITLE_WAIT_S, TimeUnit.SECONDS);
            byte[] raw = vtt.getBytes(StandardCharsets.UTF_8);
            respond(out, 200, "OK", "text/vtt; charset=utf-8", body ? raw : new byte[0], true, raw.length);
        } catch (Exception e) {
            rendered.remove(id);
            respond(out, 502, "Bad Gateway", "text/plain", new byte[0], true);
        }
    }

    private void proxy(OutputStream out, String url, String range, boolean body) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) c.setRequestProperty("Range", range);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (trace != null) trace.accept("   upstream " + code + " " + c.getContentType() + " len=" + c.getHeaderField("Content-Length") + " " + url.substring(0, Math.min(90, url.length())));
        String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
        boolean playlist = type.contains("mpegurl") || url.split("[?#]")[0].toLowerCase(Locale.ROOT).endsWith(".m3u8");
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (code >= 400) {
            if (in != null) in.close();
            respond(out, code, "Upstream Error", "text/plain", new byte[0], true);
            return;
        }
        if (playlist) {
            String text = new String(readAll(in, MAX_PLAYLIST), StandardCharsets.UTF_8);
            byte[] raw = rewrite(text, c.getURL().toString()).getBytes(StandardCharsets.UTF_8);
            respond(out, 200, "OK", "application/vnd.apple.mpegurl", body ? raw : new byte[0], true, raw.length);
            return;
        }
        in = new BufferedInputStream(in, 64 * 1024);
        String sniffed = type.isEmpty() || type.startsWith("application/octet-stream") || type.startsWith("binary/")
                || type.startsWith("image/") || type.startsWith("text/") ? sniff(in, url) : null;
        byte[] whole = null;
        if (c.getHeaderField("Content-Length") == null) whole = readAll(in, MAX_BUFFERED);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(code == 206 ? " Partial Content" : " OK").append("\r\n");
        head.append("Content-Type: ").append(sniffed != null ? sniffed : c.getContentType()).append("\r\n");
        if (whole != null) head.append("Content-Length: ").append(whole.length).append("\r\n");
        else copyHeader(c, head, "Content-Length");
        copyHeader(c, head, "Content-Range");
        head.append("Accept-Ranges: bytes\r\n");
        cors(head);
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        long began = System.currentTimeMillis();
        long sent = 0;
        try (InputStream src = in) {
            if (!body) {
                out.flush();
                return;
            }
            if (whole != null) {
                out.write(whole);
                sent = whole.length;
            } else {
                byte[] buf = new byte[64 * 1024];
                for (int n = src.read(buf); n > 0; n = src.read(buf)) {
                    out.write(buf, 0, n);
                    sent += n;
                }
            }
        } finally {
            if (trace != null && body) trace.accept("   sent " + sent + " bytes in " + (System.currentTimeMillis() - began) + " ms");
        }
        out.flush();
    }

    private String segmentExtension(String[] lines, String base) {
        for (String l : lines) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String url = resolve(base, l);
            try {
                HttpURLConnection c = open(url);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Range", "bytes=0-8191");
                try (InputStream in = new BufferedInputStream(c.getInputStream(), 16384)) {
                    String type = sniff(in, url);
                    return type.equals("video/mp2t") ? "ts" : type.equals("audio/aac") ? "aac"
                            : type.equals("video/webm") ? "webm" : "mp4";
                }
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private void local(OutputStream out, String uri, String range, boolean body) throws IOException {
        long length = files.length(uri);
        long from = 0;
        long to = length - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] r = range.substring(6).split(",")[0].split("-", -1);
            try {
                if (r[0].isEmpty()) {
                    from = Math.max(0, length - Long.parseLong(r[1].trim()));
                } else {
                    from = Long.parseLong(r[0].trim());
                    if (r.length > 1 && !r[1].trim().isEmpty()) to = Math.min(to, Long.parseLong(r[1].trim()));
                }
                partial = true;
            } catch (NumberFormatException ignored) {
            }
        }
        if (from > to || from >= length) {
            StringBuilder head = new StringBuilder("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */")
                    .append(length).append("\r\nContent-Length: 0\r\n");
            cors(head);
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        long count = to - from + 1;
        StringBuilder head = new StringBuilder();
        head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: video/mp4\r\n");
        head.append("Content-Length: ").append(count).append("\r\n");
        if (partial) head.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(length).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        cors(head);
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!body) return;
        try (InputStream in = files.open(uri, from)) {
            byte[] buf = new byte[64 * 1024];
            long left = count;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    private static String sniff(InputStream in, String url) throws IOException {
        in.mark(8192);
        byte[] head = new byte[4096];
        int n = 0;
        for (int r; n < head.length && (r = in.read(head, n, head.length - n)) > 0; ) n += r;
        in.reset();
        int at = 0;
        if (n > 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            at = 10 + ((head[6] & 0x7F) << 21 | (head[7] & 0x7F) << 14 | (head[8] & 0x7F) << 7 | (head[9] & 0x7F));
        }
        if (at < n && head[at] == 0x47) return "video/mp2t";
        if (at + 1 < n && (head[at] & 0xFF) == 0xFF && (head[at + 1] & 0xF6) == 0xF0) return "audio/aac";
        if (n > 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return "video/mp4";
        if (n > 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45) return "video/webm";
        return guessType(url);
    }

    private static String guessType(String url) {
        String path = url.split("[?#]")[0].toLowerCase(Locale.ROOT);
        if (path.endsWith(".ts")) return "video/mp2t";
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".aac")) return "audio/aac";
        return "video/mp4";
    }

    String rewrite(String playlist, String base) {
        String[] lines = playlist.replace("\r\n", "\n").split("\n");
        boolean master = playlist.contains("#EXT-X-STREAM-INF");
        boolean avc = false;
        if (master) {
            for (String l : lines) if (l.startsWith("#EXT-X-STREAM-INF") && l.contains("avc1")) avc = true;
        }
        String group = null;
        long best = -1;
        for (String l : lines) {
            if (!l.startsWith("#EXT-X-STREAM-INF") || avc && !l.contains("avc1")) continue;
            long bw = number(attribute(l, "BANDWIDTH"));
            if (bw > best) {
                best = bw;
                group = attribute(l, "AUDIO");
            }
        }
        String extension = master ? null : segmentExtension(lines, base);
        StringBuilder sb = new StringBuilder(playlist.length() + 1024);
        boolean skipNext = false;
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-I-FRAME-STREAM-INF")) continue;
            if (ownSubtitles && l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=SUBTITLES")) continue;
            if (ownSubtitles && l.startsWith("#EXT-X-STREAM-INF")) l = l.replaceAll(",SUBTITLES=\"[^\"]*\"", "");
            if (group != null && l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")
                    && !group.equals(attribute(l, "GROUP-ID"))) continue;
            if (l.startsWith("#EXT-X-STREAM-INF") && (avc && !l.contains("avc1")
                    || group != null && !group.equals(attribute(l, "AUDIO")))) {
                skipNext = true;
                continue;
            }
            if (l.startsWith("#")) {
                sb.append(rewriteUri(l, base)).append('\n');
                continue;
            }
            if (skipNext) {
                skipNext = false;
                continue;
            }
            sb.append(path(resolve(base, l), extension)).append('\n');
        }
        return sb.toString();
    }

    private static String attribute(String line, String name) {
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("[:,]" + name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!a.find()) return null;
        return a.group(2) != null ? a.group(2) : a.group(1);
    }

    private static long number(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String rewriteUri(String line, String base) {
        int at = line.indexOf("URI=\"");
        if (at < 0) return line;
        int end = line.indexOf('"', at + 5);
        if (end < 0) return line;
        String uri = line.substring(at + 5, end);
        if (uri.startsWith("data:") || uri.startsWith("skd:")) return line;
        return line.substring(0, at + 5) + path(resolve(base, uri)) + line.substring(end);
    }

    private static String resolve(String base, String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        return URI.create(base).resolve(ref).toString();
    }

    private static void copyHeader(HttpURLConnection c, StringBuilder head, String name) {
        String v = c.getHeaderField(name);
        if (v != null) head.append(name).append(": ").append(v).append("\r\n");
    }

    private static void cors(StringBuilder head) {
        head.append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                .append("Access-Control-Allow-Headers: *, Range, Content-Type\r\n")
                .append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
    }

    private static void respond(OutputStream out, int code, String reason, String type, byte[] body, boolean cors)
            throws IOException {
        respond(out, code, reason, type, body, cors, body.length);
    }

    private static void respond(OutputStream out, int code, String reason, String type, byte[] body, boolean cors,
                                int length) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (type != null) head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        if (cors) cors(head);
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (buf.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1).trim();
        }
        return null;
    }

    private static byte[] readAll(InputStream raw, int limit) throws IOException {
        try (InputStream in = raw) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[32768];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) {
                buf.write(chunk, 0, n);
                if (buf.size() > limit) throw new IOException("playlist too large");
            }
            return buf.toByteArray();
        }
    }
}
