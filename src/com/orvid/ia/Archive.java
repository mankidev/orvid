package com.orvid.ia;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Archive {

    public static final int SERVICE_ID = 105;
    private static final int PAGE = 20;
    private static final Pattern DETAILS = Pattern.compile("archive\\.org/(?:details|embed)/([A-Za-z0-9._-]+)");
    private static final String[] FIELDS = {"identifier", "title", "creator", "downloads", "publicdate", "runtime"};

    private Archive() {
    }

    public static final class Page {
        public final List<StreamInfoItem> items = new ArrayList<>();
        public String next;
    }

    public static final class Media {
        public String title = "";
        public String author = "";
        public String description = "";
        public long views = -1;
        public String mp4;
    }

    public static String itemId(String url) {
        if (url == null) return null;
        Matcher m = DETAILS.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static Page search(String circuit, String query, String next) throws IOException {
        int page = next == null ? 1 : Integer.parseInt(next);
        StringBuilder url = new StringBuilder("https://archive.org/advancedsearch.php?q=")
                .append(URLEncoder.encode("(" + query + ") AND mediatype:movies AND -collection:tvarchive", "UTF-8"));
        for (String f : FIELDS) url.append("&fl%5B%5D=").append(f);
        url.append("&rows=").append(PAGE).append("&page=").append(page).append("&output=json");
        JsonObject response = get(circuit, url.toString(), "archive search").getObject("response");
        Page p = new Page();
        JsonArray docs = response.getArray("docs");
        for (int i = 0; i < docs.size(); i++) {
            JsonObject d = docs.getObject(i);
            String id = d.getString("identifier", null);
            if (id == null) continue;
            StreamInfoItem item = new StreamInfoItem(SERVICE_ID, "https://archive.org/details/" + id,
                    text(d.get("title")), StreamType.VIDEO_STREAM);
            item.setUploaderName(text(d.get("creator")));
            item.setViewCount(d.getLong("downloads", -1));
            item.setDuration(seconds(text(d.get("runtime"))));
            String date = d.getString("publicdate", null);
            com.orvid.search.Dates.set(item, date);
            List<Image> thumbs = new ArrayList<>();
            thumbs.add(new Image("https://archive.org/services/img/" + id, 180, 320, Image.ResolutionLevel.MEDIUM));
            item.setThumbnails(thumbs);
            p.items.add(item);
        }
        if (!docs.isEmpty() && page * PAGE < response.getLong("numFound", 0)) p.next = Integer.toString(page + 1);
        return p;
    }

    public static Media resolve(String circuit, String id) throws IOException {
        JsonObject root = get(circuit, "https://archive.org/metadata/" + id, "archive item");
        JsonObject meta = root.getObject("metadata");
        if (meta.isEmpty()) throw new IOException("video unavailable");
        Media m = new Media();
        m.title = text(meta.get("title"));
        m.author = text(meta.get("creator"));
        m.description = text(meta.get("description")).replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").trim();
        String host = root.getString("d1", "archive.org");
        String dir = root.getString("dir", null);
        int cap = Fetch.torMode() ? 720 : 1080;
        String best = null;
        int bestScore = -1;
        JsonArray files = root.getArray("files");
        for (int i = 0; i < files.size(); i++) {
            JsonObject f = files.getObject(i);
            String name = f.getString("name", "");
            String format = f.getString("format", "");
            boolean mp4 = name.toLowerCase().endsWith(".mp4");
            boolean webm = name.toLowerCase().endsWith(".webm");
            if (!mp4 && !webm) continue;
            int h = parseInt(f.get("height"));
            int score;
            if (h > 0 && h <= cap) score = 1000 + h;
            else if (h > cap) score = 500 - h / 10;
            else score = format.contains("h.264") || format.contains("512Kb") ? 400 : 300;
            if (mp4) score += 5;
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        if (best == null) throw new IOException("no stream");
        StringBuilder path = new StringBuilder();
        for (String part : best.split("/")) {
            if (path.length() > 0) path.append('/');
            path.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
        }
        m.mp4 = dir != null ? "https://" + host + dir + "/" + path
                : "https://archive.org/download/" + id + "/" + path;
        return m;
    }

    private static String text(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof JsonArray) {
            JsonArray a = (JsonArray) value;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.size(); i++) {
                Object v = a.get(i);
                if (v == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(v);
            }
            return sb.toString();
        }
        return value == null ? "" : value.toString();
    }

    private static int parseInt(Object value) {
        try {
            return value == null ? 0 : (int) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long seconds(String runtime) {
        if (runtime == null || runtime.isEmpty()) return -1;
        try {
            if (runtime.contains(":")) {
                long total = 0;
                for (String part : runtime.trim().split(":")) total = total * 60 + (long) Double.parseDouble(part);
                return total;
            }
            Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(min|minutes|sec|seconds|s)?").matcher(runtime);
            if (!m.find()) return -1;
            double n = Double.parseDouble(m.group(1));
            return m.group(2) != null && m.group(2).startsWith("min") ? (long) (n * 60) : (long) n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static JsonObject get(String circuit, String url, String label) throws IOException {
        long began = System.currentTimeMillis();
        String text;
        try {
            text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                if (code == 403 || code == 429) throw new IOException("Captcha needed (HTTP " + code + ")");
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    return new String(Streams.readAll(in), StandardCharsets.UTF_8);
                }
            });
        } catch (IOException e) {
            Diag.mark(label + " FAIL " + e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        Diag.mark(label + " " + text.length() / 1024 + "KB " + (System.currentTimeMillis() - began) + "ms");
        try {
            return JsonParser.object().from(text);
        } catch (Exception e) {
            throw new IOException("archive answer is not JSON");
        }
    }
}
