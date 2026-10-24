package com.orvid.pt;

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

public final class PeerTube {

    public static final int SERVICE_ID = 104;
    private static final String SEPIA = "https://sepiasearch.org/api/v1/search/videos";
    private static final int PAGE = 20;
    private static final Pattern WATCH = Pattern.compile(
            "https://([a-z0-9.-]+\\.[a-z]{2,})/(?:videos/watch|w)/([0-9a-fA-F-]{36}|[A-Za-z0-9]{22})(?:[/?#]|$)");

    private PeerTube() {
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
        public String hls;
        public String mp4;
    }

    public static boolean handles(String url) {
        return url != null && WATCH.matcher(url).find();
    }

    public static Page search(String circuit, String query, String next, boolean hideAdult) throws IOException {
        int start = next == null ? 0 : Integer.parseInt(next);
        String url = SEPIA + "?search=" + URLEncoder.encode(query, "UTF-8") + "&start=" + start + "&count=" + PAGE
                + "&sort=-match&nsfw=" + (hideAdult ? "false" : "both");
        JsonObject root = get(circuit, url, "peertube search");
        Page p = new Page();
        JsonArray data = root.getArray("data");
        for (int i = 0; i < data.size(); i++) {
            JsonObject v = data.getObject(i);
            if (v.getBoolean("isLive", false)) continue;
            String watch = v.getString("url", null);
            if (!handles(watch)) continue;
            StreamInfoItem item = new StreamInfoItem(SERVICE_ID, watch, v.getString("name", ""), StreamType.VIDEO_STREAM);
            JsonObject channel = v.getObject("channel");
            String author = channel.getString("displayName", null);
            if (author == null || author.isEmpty()) author = v.getObject("account").getString("displayName", "");
            item.setUploaderName(author);
            item.setDuration(v.getLong("duration", -1));
            item.setViewCount(v.getLong("views", -1));
            String published = v.getString("publishedAt", null);
            com.orvid.search.Dates.set(item, published);
            List<Image> thumbs = new ArrayList<>();
            String preview = v.getString("previewUrl", null);
            String thumb = v.getString("thumbnailUrl", null);
            if (preview != null) thumbs.add(new Image(preview, 480, 850, Image.ResolutionLevel.MEDIUM));
            if (thumb != null) thumbs.add(new Image(thumb, 156, 280, Image.ResolutionLevel.LOW));
            item.setThumbnails(thumbs);
            p.items.add(item);
        }
        long total = root.getLong("total", 0);
        if (!data.isEmpty() && start + PAGE < total) p.next = Integer.toString(start + PAGE);
        return p;
    }

    public static Media resolve(String circuit, String watchUrl) throws IOException {
        Matcher m = WATCH.matcher(watchUrl);
        if (!m.find()) throw new IOException("not a PeerTube link");
        JsonObject v = get(circuit, "https://" + m.group(1) + "/api/v1/videos/" + m.group(2), "peertube video");
        Media out = new Media();
        out.title = v.getString("name", "");
        out.description = v.getString("description", "");
        out.views = v.getLong("views", -1);
        JsonObject channel = v.getObject("channel");
        out.author = channel.getString("displayName", v.getObject("account").getString("displayName", ""));
        JsonArray playlists = v.getArray("streamingPlaylists");
        for (int i = 0; i < playlists.size() && out.hls == null; i++) {
            out.hls = playlists.getObject(i).getString("playlistUrl", null);
        }
        int cap = Fetch.torMode() ? 720 : 1080;
        int best = -1;
        List<JsonArray> fileSets = new ArrayList<>();
        fileSets.add(v.getArray("files"));
        for (int i = 0; i < playlists.size(); i++) fileSets.add(playlists.getObject(i).getArray("files"));
        for (JsonArray files : fileSets) {
            for (int i = 0; i < files.size(); i++) {
                JsonObject f = files.getObject(i);
                int h = f.getObject("resolution").getInt("id", 0);
                String url = f.getString("fileUrl", null);
                if (url == null || h <= 0 || h > cap || h <= best) continue;
                best = h;
                out.mp4 = url;
            }
        }
        if (out.hls == null && out.mp4 == null) throw new IOException("no stream");
        out.hls = repair(out.hls);
        out.mp4 = repair(out.mp4);
        return out;
    }

    private static String repair(String url) {
        return url == null ? null : url.replace("/streaming-playlistshls/", "/streaming-playlists/hls/");
    }

    private static JsonObject get(String circuit, String url, String label) throws IOException {
        long began = System.currentTimeMillis();
        String text;
        try {
            text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                c.setRequestProperty("Accept", "application/json");
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
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
            throw new IOException("peertube answer is not JSON");
        }
    }
}
