package com.orvid.yt;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Related {

    private static final String NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false";
    private static final String CLIENT_NAME = "MWEB";
    private static final String CLIENT_ID = "2";
    private static final String CLIENT_VERSION = "2.20261014.01.00";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0";

    public static volatile boolean safeSearch;

    private Related() {
    }

    public static List<StreamInfoItem> fetch(String circuit, String videoId) throws IOException {
        return page(circuit, videoId).items;
    }

    public static Search.Page page(String circuit, String videoId) throws IOException {
        JsonObject body = new JsonObject();
        body.put("videoId", videoId);
        return masked(circuit, NEXT_URL, body, "related", NEXT_MASK, Related::toPage);
    }

    public static Search.Page more(String circuit, String continuation) throws IOException {
        JsonObject body = new JsonObject();
        body.put("continuation", continuation);
        return masked(circuit, NEXT_URL, body, "related more", NEXT_MORE_MASK, Related::toPage);
    }

    private static Search.Page toPage(String text) throws IOException {
        Object root = parse(text);
        Search.Page p = new Search.Page();
        collect(root, p.items);
        p.continuation = Search.token(root);
        return p;
    }

    static Object parse(String text) throws IOException {
        try {
            return JsonParser.any().from(text);
        } catch (Exception e) {
            throw new IOException("answer is not JSON");
        }
    }

    static final String VIDEO_FIELDS = "videoWithContextRenderer(videoId,headline,shortBylineText,lengthText,"
            + "shortViewCountText,publishedTimeText,thumbnail,channelThumbnail)";
    private static final String NEXT_MASK = "contents.singleColumnWatchNextResults.results.results.contents"
            + ".itemSectionRenderer.contents(continuationItemRenderer," + VIDEO_FIELDS + ")";
    private static final String NEXT_MORE_MASK = "onResponseReceivedEndpoints.appendContinuationItemsAction"
            + ".continuationItems(continuationItemRenderer," + VIDEO_FIELDS + ")";

    interface Parse {
        Search.Page apply(String text) throws IOException;
    }

    static Search.Page masked(String circuit, String url, JsonObject body, String label, String mask, Parse parse)
            throws IOException {
        Search.Page p = parse.apply(post(circuit, url, body, label, mask));
        if (!p.items.isEmpty() || p.continuation != null) return p;
        Diag.mark(label + ": nothing under the field mask, asking for the full answer");
        return parse.apply(post(circuit, url, body, label, null));
    }

    static String post(String circuit, String url, JsonObject body, String label) throws IOException {
        return post(circuit, url, body, label, null);
    }

    static String post(String circuit, String url, JsonObject body, String label, String mask) throws IOException {
        String visitor = Visionos.visitorFor(circuit);
        JsonObject client = new JsonObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("hl", "en");
        client.put("gl", "US");
        if (visitor != null) client.put("visitorData", visitor);
        JsonObject context = new JsonObject();
        context.put("client", client);
        if (safeSearch) {
            JsonObject user = new JsonObject();
            user.put("enableSafetyMode", true);
            context.put("user", user);
        }
        body.put("context", context);
        byte[] raw = JsonWriter.string(body).getBytes(StandardCharsets.UTF_8);

        long began = System.currentTimeMillis();
        String text;
        try {
            text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestMethod("POST");
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept-Encoding", "gzip");
                c.setRequestProperty("X-YouTube-Client-Name", CLIENT_ID);
                c.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
                if (visitor != null) c.setRequestProperty("X-Goog-Visitor-Id", visitor);
                if (mask != null) c.setRequestProperty("X-Goog-FieldMask", mask);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(raw.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(raw);
                }
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
                return Fetch.readBody(c);
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        Diag.mark(label + " " + text.length() / 1024 + "KB " + (System.currentTimeMillis() - began) + "ms");
        return text;
    }

    static void collect(Object node, List<StreamInfoItem> out) {
        if (node instanceof JsonObject) {
            JsonObject o = (JsonObject) node;
            JsonObject v = o.getObject("videoWithContextRenderer", null);
            if (v != null) {
                StreamInfoItem item = item(v);
                if (item != null) out.add(item);
                return;
            }
            JsonObject lockup = o.getObject("lockupViewModel", null);
            if (lockup != null) {
                StreamInfoItem item = lockup(lockup);
                if (item != null) out.add(item);
                return;
            }
            JsonObject shorts = o.getObject("shortsLockupViewModel", null);
            if (shorts != null) {
                StreamInfoItem item = shorts(shorts);
                if (item != null) out.add(item);
                return;
            }
            for (Object child : o.values()) collect(child, out);
        } else if (node instanceof JsonArray) {
            for (Object child : (JsonArray) node) collect(child, out);
        }
    }

    private static StreamInfoItem item(JsonObject v) {
        String id = v.getString("videoId", null);
        if (id == null) return null;
        StreamInfoItem item = new StreamInfoItem(ServiceList.YouTube.getServiceId(),
                "https://www.youtube.com/watch?v=" + id, text(v.getObject("headline")), StreamType.VIDEO_STREAM);
        item.setUploaderName(text(v.getObject("shortBylineText")));
        String channel = channelId(v.getObject("shortBylineText"));
        if (channel != null) item.setUploaderUrl("https://www.youtube.com/channel/" + channel);
        item.setDuration(seconds(text(v.getObject("lengthText"))));
        String views = text(v.getObject("shortViewCountText"));
        item.setViewCount(com.orvid.search.Counts.views(views, "en"));
        String when = text(v.getObject("publishedTimeText"));
        item.setTextualUploadDate(when);
        long age = com.orvid.search.Counts.ageSeconds(when);
        if (age >= 0) {
            item.setUploadDate(new org.schabi.newpipe.extractor.localization.DateWrapper(
                    java.time.Instant.now().minusSeconds(age), true));
        }
        item.setThumbnails(images(v.getObject("thumbnail").getArray("thumbnails")));
        item.setUploaderAvatars(images(v.getObject("channelThumbnail")
                .getObject("channelThumbnailWithLinkRenderer").getObject("thumbnail").getArray("thumbnails")));
        return item;
    }

    private static StreamInfoItem lockup(JsonObject l) {
        String id = l.getString("contentId", null);
        if (id == null || !"LOCKUP_CONTENT_TYPE_VIDEO".equals(l.getString("contentType", null))) return null;
        JsonObject meta = l.getObject("metadata").getObject("lockupMetadataViewModel");
        String title = meta.getObject("title").getString("content", "");
        StreamInfoItem item = new StreamInfoItem(ServiceList.YouTube.getServiceId(),
                "https://www.youtube.com/watch?v=" + id, title, StreamType.VIDEO_STREAM);
        JsonArray rows = meta.getObject("metadata").getObject("contentMetadataViewModel").getArray("metadataRows");
        for (int r = 0; r < rows.size(); r++) {
            JsonArray parts = rows.getObject(r).getArray("metadataParts");
            for (int k = 0; k < parts.size(); k++) {
                String text = parts.getObject(k).getObject("text").getString("content", "");
                if (r == 0 && k == 0) {
                    item.setUploaderName(text);
                } else if (text.toLowerCase(java.util.Locale.ROOT).contains("view")) {
                    item.setViewCount(com.orvid.search.Counts.views(text, "en"));
                } else if (item.getTextualUploadDate() == null && com.orvid.search.Counts.ageSeconds(text) >= 0) {
                    item.setTextualUploadDate(text);
                    item.setUploadDate(new org.schabi.newpipe.extractor.localization.DateWrapper(
                            java.time.Instant.now().minusSeconds(com.orvid.search.Counts.ageSeconds(text)), true));
                }
            }
        }
        String channel = meta.getObject("image").getObject("decoratedAvatarViewModel").getObject("rendererContext")
                .getObject("commandContext").getObject("onTap").getObject("innertubeCommand")
                .getObject("browseEndpoint").getString("browseId", null);
        if (channel != null && channel.startsWith("UC")) item.setUploaderUrl("https://www.youtube.com/channel/" + channel);
        JsonObject thumb = l.getObject("contentImage").getObject("thumbnailViewModel");
        JsonArray overlays = thumb.getArray("overlays");
        for (int i = 0; i < overlays.size(); i++) {
            JsonArray badges = overlays.getObject(i).getObject("thumbnailBottomOverlayViewModel").getArray("badges");
            for (int b = 0; b < badges.size(); b++) {
                long d = seconds(badges.getObject(b).getObject("thumbnailBadgeViewModel").getString("text", ""));
                if (d > 0) item.setDuration(d);
            }
        }
        item.setThumbnails(images(thumb.getObject("image").getArray("sources")));
        return item;
    }

    private static StreamInfoItem shorts(JsonObject s) {
        String entity = s.getString("entityId", "");
        String id = entity.startsWith("shorts-shelf-item-") ? entity.substring("shorts-shelf-item-".length()) : null;
        if (id == null || id.length() != 11) return null;
        JsonObject overlay = s.getObject("overlayMetadata");
        StreamInfoItem item = new StreamInfoItem(ServiceList.YouTube.getServiceId(),
                "https://www.youtube.com/watch?v=" + id, overlay.getObject("primaryText").getString("content", ""),
                StreamType.VIDEO_STREAM);
        item.setShortFormContent(true);
        item.setViewCount(com.orvid.search.Counts.views(overlay.getObject("secondaryText").getString("content", ""), "en"));
        String label = s.getString("accessibilityText", "");
        String[] parts = label.split(", ");
        if (parts.length >= 4) item.setUploaderName(parts[parts.length - 2]);
        List<Image> thumbs = new ArrayList<>();
        thumbs.add(new Image("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg", 360, 480, Image.ResolutionLevel.MEDIUM));
        item.setThumbnails(thumbs);
        return item;
    }

    private static String channelId(JsonObject byline) {
        JsonArray runs = byline == null ? null : byline.getArray("runs", null);
        if (runs == null) return null;
        for (int i = 0; i < runs.size(); i++) {
            String id = runs.getObject(i).getObject("navigationEndpoint").getObject("browseEndpoint").getString("browseId", null);
            if (id != null && id.startsWith("UC")) return id;
        }
        return null;
    }

    private static List<Image> images(JsonArray list) {
        List<Image> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonObject t = list.getObject(i);
            String url = t.getString("url", null);
            if (url == null) continue;
            if (url.startsWith("//")) url = "https:" + url;
            int h = t.getInt("height", Image.HEIGHT_UNKNOWN);
            out.add(new Image(url, h, t.getInt("width", Image.WIDTH_UNKNOWN), Image.ResolutionLevel.fromHeight(h)));
        }
        return out;
    }

    private static String text(JsonObject o) {
        if (o == null) return "";
        String simple = o.getString("simpleText", null);
        if (simple != null) return simple;
        StringBuilder sb = new StringBuilder();
        JsonArray runs = o.getArray("runs");
        for (int i = 0; i < runs.size(); i++) sb.append(runs.getObject(i).getString("text", ""));
        return sb.toString();
    }

    private static long seconds(String clock) {
        if (clock.isEmpty()) return -1;
        long total = 0;
        for (String part : clock.split(":")) {
            try {
                total = total * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return total;
    }
}
