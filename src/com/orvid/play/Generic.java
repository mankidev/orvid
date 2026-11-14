package com.orvid.play;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Generic {

    private static final Pattern IFRAME = Pattern.compile("<iframe\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern SOURCE = Pattern.compile("<(?:source|video)\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE = Pattern.compile("[\"']?file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']");
    private static final Pattern OG_VIDEO = Pattern.compile("property=[\"']og:video(?::secure_url|:url)?[\"']\\s+content=[\"']([^\"']+)[\"']");
    private static final Pattern MEDIA = Pattern.compile("(https?:(?:\\\\?/){2}[^\"'\\s<>]+)");
    private static final Pattern KVS = Pattern.compile("video_url\\s*:\\s*'([^']+)'");
    private static final Pattern LICENSE = Pattern.compile("license_code\\s*:\\s*'([^']+)'");
    private static final Pattern TITLE = Pattern.compile("property=[\"']og:title[\"']\\s+content=[\"']([^\"']*)[\"']|<title[^>]*>([^<]*)</title>",
            Pattern.CASE_INSENSITIVE);

    private Generic() {
    }

    public static String linkIn(String text) {
        if (text == null) return null;
        String t = text.trim();
        Matcher f = IFRAME.matcher(t);
        if (f.find()) return absolute(f.group(1), null);
        if (t.startsWith("//")) t = "https:" + t;
        if (!t.contains(" ") && LINK.matcher(t).matches()) return t;
        return null;
    }

    public static Direct resolve(String circuit, String url) throws IOException {
        String path = url.split("[?#]")[0].toLowerCase();
        if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm")) {
            return new Direct("", "", url, path.endsWith(".m3u8"), null);
        }
        String page = url;
        String referer = null;
        String title = "";
        for (int depth = 0; depth < 2; depth++) {
            Map<String, String> headers = new HashMap<>();
            if (referer != null) headers.put("Referer", referer);
            String html = com.orvid.adult.Web.get(circuit, page, "embed", headers);
            if (title.isEmpty()) title = title(html);
            String[] stream = stream(html, page);
            if (stream != null) {
                Direct d = new Direct(title, "", stream[0], stream[0].contains(".m3u8"), page);
                if (d.hls && stream[1] != null) d.fallback = stream[1];
                Storyboard.Spec sprite = Storyboard.timeline(html, page);
                if (sprite != null) d.sprite(sprite);
                return d;
            }
            Matcher f = IFRAME.matcher(html);
            if (!f.find()) break;
            referer = page;
            page = absolute(f.group(1), page);
        }
        throw new IOException("no stream in embed");
    }

    static String[] stream(String html, String page) {
        List<String> found = new ArrayList<>();
        Matcher kvs = KVS.matcher(html);
        Matcher lic = LICENSE.matcher(html);
        if (kvs.find()) {
            String u = kvs.group(1);
            if (u.startsWith("function/") && lic.find()) u = com.orvid.adult.HtmlTube.kvsReal(u, lic.group(1));
            if (u != null) found.add(u);
        }
        for (Pattern p : new Pattern[]{SOURCE, FILE, OG_VIDEO, MEDIA}) {
            Matcher m = p.matcher(html);
            while (m.find()) found.add(m.group(1));
        }
        String hls = null;
        String mp4 = null;
        for (String raw : found) {
            String u = absolute(raw.replace("\\/", "/").replace("&amp;", "&"), page);
            if (!u.startsWith("http")) continue;
            if (hls == null && u.contains(".m3u8")) hls = u;
            if (mp4 == null && (u.contains(".mp4") || u.contains("/get_file/"))) mp4 = u;
        }
        if (hls != null) return new String[]{hls, mp4};
        return mp4 == null ? null : new String[]{mp4, null};
    }

    private static String title(String html) {
        Matcher t = TITLE.matcher(html);
        if (!t.find()) return "";
        String v = t.group(1) != null ? t.group(1) : t.group(2);
        return com.orvid.adult.Web.unescape(v.trim());
    }

    private static String absolute(String u, String base) {
        if (u.startsWith("//")) return "https:" + u;
        if (base == null || u.startsWith("http")) return u;
        return URI.create(base).resolve(u).toString();
    }
}
