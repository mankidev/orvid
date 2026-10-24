package com.orvid.tw;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;

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

public final class Twitch {

    public static final int SERVICE_ID = 103;
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final Pattern VOD = Pattern.compile("twitch\\.tv/videos/(\\d+)");
    private static final Pattern CLIP = Pattern.compile(
            "(?:clips\\.twitch\\.tv/(?:embed\\?clip=)?|twitch\\.tv/[^/]+/clip/)([A-Za-z0-9_-]+)");

    private Twitch() {
    }

    public static final class Page {
        public final List<StreamInfoItem> items = new ArrayList<>();
        public String next;
    }

    public static final class Media {
        public String title = "";
        public String author = "";
        public long views = -1;
        public String hls;
        public String master;
        public String mp4;
    }

    public static String vodId(String url) {
        Matcher m = VOD.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String clipSlug(String url) {
        Matcher m = CLIP.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String q(String s) {
        return JsonWriter.string(s);
    }

    private static final long MAX_LENGTH_SECONDS = 3 * 3600;

    public static Page search(String circuit, String query, String cursor) throws IOException {
        String target = cursor == null ? "{index: VOD}" : "{index: VOD, cursor: " + q(cursor) + "}";
        JsonObject data = gql(circuit, "query { searchFor(userQuery: " + q(query)
                + ", platform: \"web\", options: {targets: [" + target + "]}) { videos { cursor items { id title"
                + " lengthSeconds viewCount createdAt previewThumbnailURL(width: 640, height: 360)"
                + " owner { displayName } } } } }", "twitch search");
        JsonObject videos = data.getObject("searchFor").getObject("videos");
        Page p = new Page();
        JsonArray items = videos.getArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject v = items.getObject(i);
            String id = v.getString("id", null);
            if (id == null || v.getLong("lengthSeconds", 0) > MAX_LENGTH_SECONDS) continue;
            StreamInfoItem item = new StreamInfoItem(SERVICE_ID, "https://www.twitch.tv/videos/" + id,
                    v.getString("title", ""), StreamType.VIDEO_STREAM);
            item.setUploaderName(v.getObject("owner").getString("displayName", ""));
            item.setDuration(v.getLong("lengthSeconds", -1));
            item.setViewCount(v.getLong("viewCount", -1));
            String created = v.getString("createdAt", null);
            com.orvid.search.Dates.set(item, created);
            List<Image> thumbs = new ArrayList<>();
            String thumb = v.getString("previewThumbnailURL", null);
            if (thumb != null) thumbs.add(new Image(thumb, 360, 640, Image.ResolutionLevel.MEDIUM));
            item.setThumbnails(thumbs);
            p.items.add(item);
        }
        String next = videos.getString("cursor", null);
        p.next = next == null || next.isEmpty() || items.isEmpty() ? null : next;
        return p;
    }

    public static Media vod(String circuit, String id) throws IOException {
        JsonObject data = gql(circuit, "query { video(id: " + q(id) + ") { title viewCount owner { displayName } }"
                + " videoPlaybackAccessToken(id: " + q(id) + ", params: {platform: \"web\","
                + " playerBackend: \"mediaplayer\", playerType: \"site\"}) { value signature } }", "twitch vod");
        JsonObject token = data.getObject("videoPlaybackAccessToken");
        if (token.getString("value", null) == null) throw new IOException("video unavailable");
        Media m = new Media();
        JsonObject video = data.getObject("video");
        m.title = video.getString("title", "");
        m.views = video.getLong("viewCount", -1);
        m.author = video.getObject("owner").getString("displayName", "");
        m.hls = "https://usher.ttvnw.net/vod/" + id + ".m3u8?sig=" + token.getString("signature", "")
                + "&token=" + URLEncoder.encode(token.getString("value", ""), "UTF-8")
                + "&allow_source=true&player=twitchweb&playlist_include_framerate=true&allow_audio_only=true";
        m.master = master(circuit, m.hls);
        return m;
    }

    public static Media clip(String circuit, String slug) throws IOException {
        JsonObject data = gql(circuit, "query { clip(slug: " + q(slug) + ") { title viewCount"
                + " broadcaster { displayName } videoQualities { quality sourceURL }"
                + " playbackAccessToken(params: {platform: \"web\", playerBackend: \"mediaplayer\","
                + " playerType: \"site\"}) { signature value } } }", "twitch clip");
        JsonObject clip = data.getObject("clip");
        JsonArray qualities = clip.getArray("videoQualities");
        if (qualities.isEmpty()) throw new IOException("clip unavailable");
        JsonObject token = clip.getObject("playbackAccessToken");
        String best = null;
        int bestHeight = -1;
        for (int i = 0; i < qualities.size(); i++) {
            JsonObject q = qualities.getObject(i);
            int h;
            try {
                h = Integer.parseInt(q.getString("quality", "0"));
            } catch (NumberFormatException e) {
                h = 0;
            }
            int cap = Fetch.torMode() ? 720 : 1080;
            if (h <= cap && h > bestHeight) {
                bestHeight = h;
                best = q.getString("sourceURL", null);
            }
        }
        if (best == null) best = qualities.getObject(qualities.size() - 1).getString("sourceURL", null);
        Media m = new Media();
        m.title = clip.getString("title", "");
        m.views = clip.getLong("viewCount", -1);
        m.author = clip.getObject("broadcaster").getString("displayName", "");
        m.mp4 = best + "?sig=" + token.getString("signature", "")
                + "&token=" + URLEncoder.encode(token.getString("value", ""), "UTF-8");
        return m;
    }

    private static final Pattern BANDWIDTH = Pattern.compile("[:,]BANDWIDTH=(\\d+)");

    private static String master(String circuit, String url) throws IOException {
        String text;
        try {
            text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                int code = c.getResponseCode();
                if (code == 403) throw new IOException("Captcha needed (twitch edge blocked this exit)");
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    return new String(Streams.readAll(in), StandardCharsets.UTF_8);
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        return startNear(text, Fetch.torMode() ? 1_500_000 : 3_500_000);
    }

    static String startNear(String master, long target) {
        String[] lines = master.split("\n");
        StringBuilder head = new StringBuilder();
        List<String[]> variants = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.length) {
                variants.add(new String[] {line, lines[++i].trim()});
            } else if (variants.isEmpty() && !line.isEmpty()) {
                head.append(line).append('\n');
            }
        }
        int pick = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < variants.size(); i++) {
            String inf = variants.get(i)[0];
            if (inf.contains("audio_only")) continue;
            Matcher m = BANDWIDTH.matcher(inf);
            if (!m.find()) continue;
            long bw = Long.parseLong(m.group(1));
            long d = bw <= target ? target - bw : (bw - target) * 4;
            if (d < best) {
                best = d;
                pick = i;
            }
        }
        if (pick > 0) variants.add(0, variants.remove(pick));
        StringBuilder out = new StringBuilder(head);
        for (String[] v : variants) out.append(v[0]).append('\n').append(v[1]).append('\n');
        return out.toString();
    }

    private static JsonObject gql(String circuit, String query, String label) throws IOException {
        JsonObject body = new JsonObject();
        body.put("query", query);
        byte[] raw = JsonWriter.string(body).getBytes(StandardCharsets.UTF_8);
        long began = System.currentTimeMillis();
        String text;
        try {
            text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(GQL);
                c.setRequestMethod("POST");
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                c.setRequestProperty("Client-ID", CLIENT_ID);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept-Encoding", "gzip");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(raw.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(raw);
                }
                int code = c.getResponseCode();
                String answer = Fetch.readBody(c);
                if (code >= 400) throw new IOException("HTTP " + code);
                return answer;
            });
        } catch (IOException e) {
            Diag.mark(label + " FAIL " + e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        Diag.mark(label + " " + text.length() / 1024 + "KB " + (System.currentTimeMillis() - began) + "ms");
        try {
            JsonObject root = JsonParser.object().from(text);
            if (root.has("errors") && !root.has("data")) throw new IOException("twitch error");
            return root.getObject("data");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("twitch answer is not JSON");
        }
    }
}
