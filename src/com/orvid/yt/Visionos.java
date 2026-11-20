package com.orvid.yt;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Visionos {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private static final String CLIENT_NAME = "VISIONOS";
    private static final String CLIENT_ID = "101";
    private static final String CLIENT_VERSION = "1.02";
    private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String VISITOR_URL = "https://www.youtube.com/sw.js_data";
    private static final Pattern VISITOR = Pattern.compile("\"(Cg[A-Za-z0-9_%-]{20,})\"");
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static final Map<String, String> VISITORS = new HashMap<>();
    private static final Deque<String> READY = new ArrayDeque<>();
    private static final ExecutorService WARMER = Executors.newSingleThreadExecutor(com.orvid.net.Focus.background("orvid-yt-pool"));
    private static final int POOL = 4;
    private static final Deque<String> PLAY = new ArrayDeque<>();
    private static final int PLAY_POOL = 2;
    private static final long PLAY_REFILL_DELAY_MS = 8000;

    private Visionos() {
    }

    public static final class Format {
        public int itag;
        public String mime;
        public int width;
        public int height;
        public int fps;
        public long bitrate;
        public long contentLength;
        public long averageBitrate;
        public long durationMs;
        public int sampleRate;
        public String audioTrackId;
        public String audioTrackName;
        public boolean audioDefault;
        public String initRange;
        public String indexRange;
        public String url;

        public boolean isOriginalAudio() {
            return audioTrackId == null || audioTrackId.endsWith(".4");
        }

        public boolean isVideo() {
            return mime.startsWith("video/");
        }

        public String codec() {
            int i = mime.indexOf("codecs=\"");
            return i < 0 ? "" : mime.substring(i + 8, mime.indexOf('"', i + 8));
        }
    }

    public static final class Caption {
        public String language;
        public String name;
        public boolean auto;
        public String url;
    }

    public static final class Video {
        public String id;
        public String title;
        public String author;
        public String channelId;
        public String description;
        public final List<String> keywords = new ArrayList<>();
        public long views = -1;
        public long lengthSeconds;
        public String storyboard;
        public boolean live;
        public String hls;
        public final List<Format> formats = new ArrayList<>();
        public final List<Caption> captions = new ArrayList<>();
    }

    public static String videoId(String url) {
        Matcher m = VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public interface Warmer {
        void warm(String circuit) throws Exception;
    }

    private static final List<Warmer> WARMERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addWarmer(Warmer w) {
        WARMERS.add(w);
    }

    private static final String DIRECT_CIRCUIT = "v:direct";

    public static String takeCircuit() {
        if (!Fetch.torMode()) return DIRECT_CIRCUIT;
        String c;
        synchronized (READY) {
            c = READY.poll();
        }
        refill();
        return c != null ? c : Fetch.newCircuit("v");
    }

    public static String takePlayCircuit() {
        if (!Fetch.torMode()) return DIRECT_CIRCUIT;
        String c;
        synchronized (PLAY) {
            c = PLAY.poll();
        }
        if (c == null) return takeCircuit();
        WARMER.execute(() -> {
            try {
                Thread.sleep(PLAY_REFILL_DELAY_MS);
            } catch (InterruptedException ignored) {
            }
        });
        refill();
        return c;
    }

    public static void refill() {
        if (!Fetch.torMode()) return;
        WARMER.execute(() -> {
            if (!fillReady(1)) return;
            while (true) {
                synchronized (PLAY) {
                    if (PLAY.size() >= PLAY_POOL) break;
                }
                String c = Fetch.newCircuit("p");
                try {
                    visitor(c);
                } catch (Exception e) {
                    break;
                }
                synchronized (PLAY) {
                    PLAY.add(c);
                }
            }
            fillReady(POOL);
        });
    }

    private static final ExecutorService SIDE_WARMER = Executors.newSingleThreadExecutor(com.orvid.net.Focus.background("orvid-side-pool"));

    private static boolean fillReady(int size) {
        while (true) {
            synchronized (READY) {
                if (READY.size() >= size) return true;
            }
            String c = Fetch.newCircuit("v");
            try {
                visitor(c);
            } catch (Exception e) {
                return false;
            }
            synchronized (READY) {
                READY.add(c);
            }
            SIDE_WARMER.execute(() -> {
                for (Warmer w : WARMERS) {
                    try {
                        w.warm(c);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    public static void dropPool() {
        synchronized (PLAY) {
            for (String c : PLAY) {
                synchronized (VISITORS) {
                    VISITORS.remove(c);
                }
            }
            PLAY.clear();
        }
        synchronized (READY) {
            for (String c : READY) {
                synchronized (VISITORS) {
                    VISITORS.remove(c);
                }
            }
            READY.clear();
        }
    }

    public static Video fetch(String circuit, String videoId) throws IOException {
        try {
            return fetchOnce(circuit, videoId);
        } catch (AgeRestricted e) {
            throw e;
        } catch (Unplayable e) {
            synchronized (VISITORS) {
                VISITORS.remove(circuit);
            }
            return fetchOnce(circuit, videoId);
        }
    }

    public static final class AgeRestricted extends IOException {
        AgeRestricted() {
            super("age restricted");
        }
    }

    public static final class Removed extends com.orvid.net.Hedge.Final {
        Removed(String reason) {
            super(reason);
        }
    }

    private static final java.util.regex.Pattern REMOVED = java.util.regex.Pattern.compile(
            "removed by the uploader|has been removed|been terminated|no longer available|isn't available anymore|is private|has been deleted");

    public static boolean removed(String reason) {
        return reason != null && REMOVED.matcher(reason.toLowerCase(Locale.ROOT)).find();
    }

    private static final class Unplayable extends IOException {
        Unplayable(String reason) {
            super(reason);
        }
    }

    private static Video fetchOnce(String circuit, String videoId) throws IOException {
        String visitor = visitor(circuit);
        JsonObject client = new JsonObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("deviceMake", "Apple");
        client.put("deviceModel", "RealityDevice17,1");
        client.put("osName", "visionOS");
        client.put("osVersion", "26.5.23O471");
        client.put("hl", "en");
        client.put("gl", "US");
        client.put("visitorData", visitor);
        JsonObject context = new JsonObject();
        context.put("client", client);
        JsonObject body = new JsonObject();
        body.put("context", context);
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        String json = JsonWriter.string(body);
        JsonObject root = playerAnswer(request(circuit, PLAYER_URL, json, visitor, PLAYER_MASK));
        if (root.getObject("playabilityStatus").getString("status", null) == null) {
            com.orvid.net.Diag.mark("visionos player: nothing under the field mask, asking for the full answer");
            root = playerAnswer(request(circuit, PLAYER_URL, json, visitor, null));
        }
        JsonObject status = root.getObject("playabilityStatus");
        String state = status.getString("status", "");
        if (!"OK".equals(state)) {
            String reason = status.getString("reason", "");
            if ("LOGIN_REQUIRED".equals(state) && reason.toLowerCase(Locale.ROOT).contains("age")) {
                throw new AgeRestricted();
            }
            if (("ERROR".equals(state) || "LOGIN_REQUIRED".equals(state))
                    && removed(reason)) {
                com.orvid.search.Dead.add("https://www.youtube.com/watch?v=" + videoId);
                throw new Removed(state + ": " + reason);
            }
            throw new Unplayable(state + ": " + reason);
        }
        Video v = new Video();
        JsonObject details = root.getObject("videoDetails");
        v.id = videoId;
        v.title = details.getString("title", "");
        v.author = details.getString("author", "");
        v.channelId = details.getString("channelId", null);
        v.description = details.getString("shortDescription", "");
        JsonArray words = details.getArray("keywords");
        for (int i = 0; i < words.size(); i++) {
            String w = words.getString(i, "");
            if (!w.isEmpty()) v.keywords.add(w);
        }
        try {
            v.views = Long.parseLong(details.getString("viewCount", "-1"));
            v.lengthSeconds = Long.parseLong(details.getString("lengthSeconds", "0"));
        } catch (NumberFormatException ignored) {
        }
        v.live = details.getBoolean("isLive", false) || details.getBoolean("isLiveContent", false)
                && v.lengthSeconds == 0;
        v.storyboard = root.getObject("storyboards").getObject("playerStoryboardSpecRenderer").getString("spec", null);
        JsonObject streaming = root.getObject("streamingData");
        String hls = streaming.getString("hlsManifestUrl", null);
        v.hls = hls == null || hls.isEmpty() ? null : hls;
        addFormats(v, streaming.getArray("formats"));
        addFormats(v, streaming.getArray("adaptiveFormats"));
        JsonArray tracks = root.getObject("captions").getObject("playerCaptionsTracklistRenderer")
                .getArray("captionTracks");
        for (int i = 0; i < tracks.size(); i++) {
            JsonObject t = tracks.getObject(i);
            String base = t.getString("baseUrl", null);
            if (base == null || !base.startsWith("https://")) continue;
            Caption c = new Caption();
            c.language = t.getString("languageCode", "");
            c.auto = "asr".equals(t.getString("kind", ""));
            JsonObject name = t.getObject("name");
            String simple = name.getString("simpleText", null);
            if (simple == null) {
                StringBuilder sb = new StringBuilder();
                JsonArray runs = name.getArray("runs");
                for (int r = 0; r < runs.size(); r++) sb.append(runs.getObject(r).getString("text", ""));
                simple = sb.toString();
            }
            c.name = simple.isEmpty() ? c.language : simple;
            c.url = base.replaceAll("&fmt=[^&]*", "") + "&fmt=vtt";
            v.captions.add(c);
        }
        String spoken = null;
        for (Caption c : v.captions) if (c.auto) spoken = c.language;
        if (spoken != null) {
            final String lang = spoken;
            v.captions.sort((x, y) -> rank(x, lang) - rank(y, lang));
        }
        return v;
    }

    private static int rank(Caption c, String spoken) {
        if (!c.language.equals(spoken)) return 2;
        return c.auto ? 1 : 0;
    }

    private static void addFormats(Video v, JsonArray list) {
        for (int i = 0; i < list.size(); i++) {
            JsonObject f = list.getObject(i);
            String url = f.getString("url", null);
            if (url == null || !url.startsWith("https://")) continue;
            Format format = new Format();
            format.itag = f.getInt("itag", 0);
            format.mime = f.getString("mimeType", "");
            format.width = f.getInt("width", 0);
            format.height = f.getInt("height", 0);
            format.fps = f.getInt("fps", 0);
            format.bitrate = f.getLong("bitrate", 0);
            try {
                format.contentLength = Long.parseLong(f.getString("contentLength", "0"));
            } catch (NumberFormatException e) {
                format.contentLength = 0;
            }
            format.averageBitrate = f.getLong("averageBitrate", format.bitrate);
            try {
                format.durationMs = Long.parseLong(f.getString("approxDurationMs", "0"));
                format.sampleRate = Integer.parseInt(f.getString("audioSampleRate", "0"));
            } catch (NumberFormatException ignored) {
            }
            format.initRange = range(f.getObject("initRange", null));
            format.indexRange = range(f.getObject("indexRange", null));
            JsonObject track = f.getObject("audioTrack", null);
            if (track != null) {
                format.audioTrackId = track.getString("id", null);
                format.audioTrackName = track.getString("displayName", null);
                format.audioDefault = track.getBoolean("audioIsDefault", false);
            }
            format.url = url;
            v.formats.add(format);
        }
    }

    private static String range(JsonObject r) {
        if (r == null) return null;
        String start = r.getString("start", null);
        String end = r.getString("end", null);
        return start == null || end == null ? null : start + "-" + end;
    }

    public static final class Prepared {
        public final String circuit;
        public final Video video;
        final long deadline;

        Prepared(String circuit, Video video) {
            this.circuit = circuit;
            this.video = video;
            this.deadline = expiry(video);
        }
    }

    private static final Map<String, Future<Prepared>> PREPARED = new HashMap<>();
    private static final ExecutorService PREFETCH = Executors.newFixedThreadPool(2);

    private static final ExecutorService URGENT = Executors.newCachedThreadPool();

    public static Future<Prepared> prepare(String videoId) {
        return prepare(videoId, false);
    }

    public static Future<Prepared> prepareNow(String videoId) {
        return prepare(videoId, true);
    }

    private static Future<Prepared> prepare(String videoId, boolean now) {
        synchronized (PREPARED) {
            Future<Prepared> known = PREPARED.get(videoId);
            if (known != null) {
                boolean stuck = now && known instanceof com.orvid.net.Queued && ((com.orvid.net.Queued<?>) known).waiting();
                if (stuck) known.cancel(false);
                else if (!known.isDone() && now && known instanceof com.orvid.net.Queued) {
                    Future<Prepared> race = race(videoId, known);
                    PREPARED.put(videoId, race);
                    return race;
                } else if (!known.isDone()) return known;
                try {
                    Prepared p = known.get();
                    if (System.currentTimeMillis() < p.deadline) return known;
                } catch (Exception ignored) {
                }
            }
            String circuit = takePlayCircuit();
            com.orvid.net.Queued<Prepared> f = new com.orvid.net.Queued<>(() ->
                    new Prepared(circuit, Fetch.retrying(circuit, c -> fetch(c, videoId))));
            (now ? URGENT : PREFETCH).execute(f);
            PREPARED.put(videoId, f);
            return f;
        }
    }

    private static Future<Prepared> race(String videoId, Future<Prepared> running) {
        java.util.concurrent.CompletableFuture<Prepared> first = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        String circuit = takePlayCircuit();
        com.orvid.net.Queued<Prepared> fresh = new com.orvid.net.Queued<>(() ->
                new Prepared(circuit, Fetch.retrying(circuit, c -> fetch(c, videoId))));
        URGENT.execute(fresh);
        for (Future<Prepared> f : java.util.Arrays.asList(running, fresh)) {
            URGENT.execute(() -> {
                try {
                    first.complete(f.get());
                } catch (Exception e) {
                    if (failed.incrementAndGet() == 2) first.completeExceptionally(e);
                }
            });
        }
        return first;
    }

    public static void forget(String videoId) {
        synchronized (PREPARED) {
            PREPARED.remove(videoId);
        }
    }

    private static long expiry(Video v) {
        long fallback = System.currentTimeMillis() + 30 * 60 * 1000;
        for (Format f : v.formats) {
            int at = f.url.indexOf("expire=");
            if (at < 0) continue;
            int from = at + 7;
            int to = from;
            while (to < f.url.length() && Character.isDigit(f.url.charAt(to))) to++;
            try {
                return Long.parseLong(f.url.substring(from, to)) * 1000 - 10 * 60 * 1000;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public static void ensureVisitor(String circuit) throws IOException {
        visitor(circuit);
    }

    static String visitorFor(String circuit) {
        synchronized (VISITORS) {
            return VISITORS.get(circuit);
        }
    }

    private static String visitor(String circuit) throws IOException {
        synchronized (VISITORS) {
            String known = VISITORS.get(circuit);
            if (known != null) return known;
        }
        String page = request(circuit, VISITOR_URL, null, null);
        Matcher m = VISITOR.matcher(page);
        if (!m.find()) throw new IOException("no visitor id");
        String visitor = URLDecoder.decode(m.group(1).replace("+", "%2B"), "UTF-8");
        synchronized (VISITORS) {
            VISITORS.put(circuit, visitor);
        }
        return visitor;
    }

    private static final String PLAYER_MASK = "playabilityStatus(status,reason),"
            + "videoDetails(title,author,channelId,shortDescription,keywords,viewCount,lengthSeconds,isLive,isLiveContent),"
            + "storyboards.playerStoryboardSpecRenderer.spec,streamingData(hlsManifestUrl,formats,adaptiveFormats),"
            + "captions.playerCaptionsTracklistRenderer.captionTracks";

    private static JsonObject playerAnswer(String answer) throws IOException {
        try {
            return JsonParser.object().from(answer);
        } catch (Exception e) {
            throw new IOException("player answer is not JSON");
        }
    }

    private static String request(String circuit, String url, String json, String visitor) throws IOException {
        return request(circuit, url, json, visitor, null);
    }

    private static String request(String circuit, String url, String json, String visitor, String mask) throws IOException {
        long began = System.currentTimeMillis();
        String label = url.contains("/player") ? "visionos player" : "visionos visitor";
        try {
            String text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept-Language", "en");
                if (json != null) {
                    byte[] raw = json.getBytes(StandardCharsets.UTF_8);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("X-YouTube-Client-Name", CLIENT_ID);
                    c.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
                    if (visitor != null) c.setRequestProperty("X-Goog-Visitor-Id", visitor);
                    if (mask != null) c.setRequestProperty("X-Goog-FieldMask", mask);
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(raw.length);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(raw);
                    }
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                if (in != null) {
                    try (InputStream s = in) {
                        byte[] chunk = new byte[16384];
                        for (int n = s.read(chunk); n > 0; n = s.read(chunk)) buf.write(chunk, 0, n);
                    }
                }
                if (code >= 400) throw new IOException("HTTP " + code);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            });
            Diag.mark(label + " " + text.length() / 1024 + "KB " + (System.currentTimeMillis() - began) + "ms");
            return text;
        } catch (IOException e) {
            Diag.mark(label + " FAIL " + e.getMessage() + " " + (System.currentTimeMillis() - began) + "ms");
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
