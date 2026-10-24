package com.orvid.cast;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import java.net.URI;
import java.util.List;

public final class CastMedia {

    private CastMedia() {
    }

    public static JsonObject build(CastItem m, List<CastItem.Track> tracks, CastProxy server, String host) throws Exception {
        String url = server.proxyUrl(m.url, host);
        JsonObject media = new JsonObject();
        media.put("contentId", url);
        media.put("contentUrl", url);
        media.put("contentType", m.contentType());
        media.put("streamType", m.live ? "LIVE" : "BUFFERED");
        if (m.durationMs > 0 && !m.live) media.put("duration", m.durationMs / 1000.0);
        if (m.hls) {
            String[] formats = hlsFormats(m.url, server);
            if (formats[0] != null) media.put("hlsSegmentFormat", formats[0]);
            if (formats[1] != null) media.put("hlsVideoSegmentFormat", formats[1]);
        }
        JsonObject meta = new JsonObject();
        meta.put("metadataType", 0);
        meta.put("title", m.title.isEmpty() ? "Orvid" : m.title);
        if (!m.author.isEmpty()) meta.put("subtitle", m.author);
        if (m.thumbnail != null) {
            JsonArray images = new JsonArray();
            JsonObject image = new JsonObject();
            image.put("url", server.proxyUrl(m.thumbnail, host));
            images.add(image);
            meta.put("images", images);
        }
        media.put("metadata", meta);
        JsonArray list = new JsonArray();
        for (int i = 0; i < tracks.size(); i++) {
            CastItem.Track s = tracks.get(i);
            JsonObject t = new JsonObject();
            t.put("trackId", i + 1);
            t.put("type", "TEXT");
            t.put("subtype", "SUBTITLES");
            t.put("trackContentId", server.subtitleUrl(s, host));
            t.put("trackContentType", "text/vtt");
            t.put("name", s.name);
            if (!s.language.isEmpty()) t.put("language", s.language);
            list.add(t);
        }
        if (!list.isEmpty()) {
            media.put("tracks", list);
            JsonObject style = new JsonObject();
            style.put("backgroundColor", "#000000B3");
            style.put("foregroundColor", "#FFFFFFFF");
            style.put("edgeType", "NONE");
            style.put("fontScale", 1.0);
            style.put("fontGenericFamily", "SANS_SERIF");
            media.put("textTrackStyle", style);
        }
        return media;
    }

    static String[] hlsFormats(String url, CastProxy server) {
        String[] out = new String[2];
        try {
            String master = text(server, url);
            if (!master.contains("#EXT-X-STREAM-INF")) {
                out[0] = segmentFormat(master, url, server);
                return out;
            }
            String[] lines = master.split("\n");
            String variant = null;
            String audioGroup = null;
            for (int i = 0; i < lines.length - 1; i++) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF") && (variant == null || lines[i].contains("avc1"))) {
                    variant = lines[i + 1].trim();
                    audioGroup = attribute(lines[i], "AUDIO");
                    if (lines[i].contains("avc1")) break;
                }
            }
            String audioUri = null;
            for (String l : lines) {
                if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO") && attribute(l, "URI") != null
                        && (audioGroup == null || audioGroup.equals(attribute(l, "GROUP-ID")))) {
                    audioUri = attribute(l, "URI");
                    break;
                }
            }
            String video = variant == null ? null : segmentFormat(text(server, resolve(url, variant)), resolve(url, variant), server);
            if (audioUri == null) {
                out[0] = video;
                return out;
            }
            String audio = segmentFormat(text(server, resolve(url, audioUri)), resolve(url, audioUri), server);
            out[0] = audio;
            out[1] = "fmp4".equals(video) ? "fmp4" : null;
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String text(CastProxy server, String url) throws Exception {
        java.net.HttpURLConnection c = server.open(url);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (c.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + c.getResponseCode());
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String segmentFormat(String playlist, String base, CastProxy server) throws Exception {
        if (playlist.contains("#EXT-X-MAP")) return "fmp4";
        String first = null;
        for (String l : playlist.split("\n")) {
            l = l.trim();
            if (!l.isEmpty() && !l.startsWith("#")) {
                first = l;
                break;
            }
        }
        if (first == null) return null;
        java.net.HttpURLConnection c = server.open(resolve(base, first));
        c.setRequestProperty("Range", "bytes=0-1023");
        byte[] head;
        try (java.io.InputStream in = c.getInputStream()) {
            byte[] buf = new byte[1024];
            int n = 0;
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
            head = java.util.Arrays.copyOf(buf, n);
        }
        int at = 0;
        if (head.length > 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            at = 10 + ((head[6] & 0x7F) << 21 | (head[7] & 0x7F) << 14 | (head[8] & 0x7F) << 7 | (head[9] & 0x7F));
        }
        if (at < head.length && head[at] == 0x47) return "ts";
        if (at + 1 < head.length && (head[at] & 0xFF) == 0xFF && (head[at + 1] & 0xF6) == 0xF0) return "aac";
        if (at + 2 < head.length && (head[at] == 'I' || (head[at] & 0xFF) == 0xFF && (head[at + 1] & 0xE0) == 0xE0)) return "mp3";
        return null;
    }

    private static String attribute(String line, String name) {
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("[:,]" + name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!a.find()) return null;
        return a.group(2) != null ? a.group(2) : a.group(1);
    }

    private static String resolve(String base, String ref) {
        return ref.startsWith("http") ? ref : URI.create(base).resolve(ref).toString();
    }
}
