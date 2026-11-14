package com.orvid.adult;

import com.orvid.net.Fetch;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HtmlTube {

    public static final class Page {
        public final List<StreamInfoItem> items = new ArrayList<>();
        public String next;
    }

    public static final class Media {
        public String title = "";
        public String mp4;
        public String referer;
        public final java.util.TreeMap<Integer, String> qualities = new java.util.TreeMap<>();
        public com.orvid.play.Storyboard.Spec sprite;
    }

    public final int serviceId;
    private final String site;
    private final String firstPage;
    private final String laterPage;
    private final Pattern videoUrl;
    private final String cardMarker;
    private final Pattern href;
    private final Pattern title;
    private final Pattern thumb;
    private final Pattern duration;
    private final Pattern views;
    private Pattern iframe;
    private Pattern mustContain;
    private Pattern mustSkip;
    private boolean paged;

    public HtmlTube paged() {
        paged = true;
        return this;
    }

    public HtmlTube skip(String pattern) {
        mustSkip = Pattern.compile(pattern);
        return this;
    }

    private String linkPrefix = "";

    public HtmlTube linkPrefix(String value) {
        linkPrefix = value;
        return this;
    }
    private String referer;

    public HtmlTube iframe(String pattern) {
        iframe = Pattern.compile(pattern);
        return this;
    }

    public HtmlTube only(String pattern) {
        mustContain = Pattern.compile(pattern);
        return this;
    }

    private int timeoutMs = 15000;
    private int maxHeight = Integer.MAX_VALUE;

    public HtmlTube timeout(int ms) {
        timeoutMs = ms;
        return this;
    }

    public HtmlTube maxHeight(int value) {
        maxHeight = value;
        return this;
    }

    private boolean followRedirect;
    private Pattern embedFrom;
    private String embedTo;

    public HtmlTube embed(String from, String to) {
        embedFrom = Pattern.compile(from);
        embedTo = to;
        return this;
    }

    private String pageFor(String url) {
        if (embedFrom == null) return url;
        Matcher e = embedFrom.matcher(url);
        return e.find() ? embedTo.replace("$1", e.group(1)) : url;
    }

    public HtmlTube followRedirect() {
        followRedirect = true;
        return this;
    }

    public HtmlTube referer(String value) {
        referer = value;
        return this;
    }

    private static final Pattern SOURCE = Pattern.compile("<source\\b[^>]*>");
    private static final Pattern KVS = Pattern.compile("\\b(video_(?:alt_)?url\\d*)\\s*:\\s*'([^']+)'");
    private static final Pattern STREAM_MARKER = Pattern.compile(
            "<source\\b[^>]*(?:type=[\"']video|\\.mp4|\\.m3u8)|video_(?:alt_)?url\\d*\\s*:\\s*'|dataEncodings\\s*=");
    private static final Pattern ENCODING = Pattern.compile(
            "\"quality\"\\s*:\\s*\"(\\d{3,4})\"\\s*,\\s*\"filename\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern LICENSE = Pattern.compile("license_code\\s*:\\s*'([^']+)'");
    private static final Pattern SRC = Pattern.compile("\\bsrc=[\"']([^\"']+)[\"']");
    private static final Pattern LABEL = Pattern.compile("\\b(?:title|label|res|size)=[\"']([^\"']+)[\"']");
    private static final Pattern OG_TITLE = Pattern.compile("property=\"og:title\"\\s+content=\"([^\"]*)\"");
    private static final Pattern H1 = Pattern.compile("<h1[^>]*>\\s*([^<]+)<");

    public HtmlTube(int serviceId, String site, String firstPage, String laterPage, String videoUrl, String cardMarker,
                    String href, String title, String thumb, String duration, String views) {
        this.serviceId = serviceId;
        this.site = site;
        this.firstPage = firstPage;
        this.laterPage = laterPage;
        this.videoUrl = Pattern.compile(videoUrl);
        this.cardMarker = cardMarker;
        this.href = Pattern.compile(href);
        this.title = Pattern.compile(title);
        this.thumb = Pattern.compile(thumb);
        this.duration = duration == null ? null : Pattern.compile(duration);
        this.views = views == null ? null : Pattern.compile(views);
    }

    public boolean handles(String url) {
        return url != null && videoUrl.matcher(url).find();
    }

    private static final Pattern PAGE_TITLE = Pattern.compile("<title[^>]*>([^<]{1,80})", Pattern.CASE_INSENSITIVE);

    public String host() {
        return URI.create(site).getHost();
    }

    public Page search(String circuit, String query, String next) throws IOException {
        boolean byLink = next != null && next.startsWith("http");
        int page = next == null ? 1 : byLink ? 2 : Integer.parseInt(next);
        String q = URLEncoder.encode(query, "UTF-8");
        String qPath = q.replace("+", "-");
        String url = byLink ? next : (page > 1 ? laterPage : firstPage).replace("{q}", q).replace("{qp}", qPath)
                .replace("{p}", Integer.toString(page));
        String html = Web.get(circuit, url, "tube search " + URI.create(site).getHost(), null, timeoutMs);
        Page p = new Page();
        String[] cards = parse(html, p);
        if (p.items.isEmpty() && page == 1 && searchFallback != null) {
            html = Web.get(circuit, searchFallback.replace("{q}", q).replace("{qp}", qPath),
                    "tube fallback " + URI.create(site).getHost(), null, timeoutMs);
            cards = parse(html, p);
            Matcher pt = PAGE_TITLE.matcher(html);
            com.orvid.net.Trace.page("fallback HTML " + html.length() / 1024 + " KB, " + (cards.length - 1) + " \u2192 " + p.items.size()
                    + (pt.find() ? ", \"" + Web.unescape(pt.group(1).trim()) + "\"" : ""));
            return p;
        }
        Matcher pt = PAGE_TITLE.matcher(html);
        com.orvid.net.Trace.page("HTML " + html.length() / 1024 + " KB, " + (cards.length - 1) + " \u2192 " + p.items.size()
                + (pt.find() ? ", \"" + Web.unescape(pt.group(1).trim()) + "\"" : ""));
        if (p.items.isEmpty()) return p;
        if (!paged && !laterPage.contains("{p}")) {
            p.next = relNext(html, url, q, qPath);
        } else if (paged || linksTo(html, q, qPath, page + 1)
                || linksTo(html, q, query.trim(), page + 1)
                || linksTo(html, q, query.trim().replace(" ", "%20"), page + 1)) {
            p.next = Integer.toString(page + 1);
        }
        return p;
    }

    private static final Pattern REL_NEXT = Pattern.compile("<(?:a|link)\\b[^>]*\\brel=[\"']?next\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_HREF = Pattern.compile("\\bhref=[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);

    static String relNext(String html, String from, String q, String qPath) {
        Matcher tag = REL_NEXT.matcher(html);
        if (!tag.find()) return null;
        Matcher href = NEXT_HREF.matcher(tag.group());
        if (!href.find()) return null;
        try {
            String to = URI.create(from).resolve(Web.unescape(href.group(1).trim())).toString();
            String low = to.toLowerCase(java.util.Locale.ROOT);
            if (!to.startsWith("http") || to.equals(from)) return null;
            if (!low.contains(q.toLowerCase(java.util.Locale.ROOT)) && !low.contains(qPath.toLowerCase(java.util.Locale.ROOT))) return null;
            return to;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean linksTo(String html, String q, String qPath, int page) {
        String nextUrl = laterPage.replace("{q}", q).replace("{qp}", qPath).replace("{p}", Integer.toString(page));
        String nextPath = nextUrl.startsWith(site) ? nextUrl.substring(site.length()) : nextUrl;
        return html.contains(nextPath) || html.contains(nextUrl) || html.contains(nextPath.replace("&", "&amp;"));
    }

    private String searchFallback;

    public HtmlTube searchFallback(String url) {
        searchFallback = url;
        return this;
    }

    private String[] parse(String html, Page p) {
        Set<String> seen = new HashSet<>();
        String[] cards = html.split("(?=" + Pattern.quote(cardMarker) + ")");
        for (int i = 1; i < cards.length; i++) {
            String b = cards[i];
            if (mustContain != null && !mustContain.matcher(b).find()) continue;
            if (mustSkip != null && mustSkip.matcher(b).find()) continue;
            Matcher h = href.matcher(b);
            if (!h.find()) continue;
            String link = absolute(linkPrefix + h.group(1));
            if (!seen.add(link) || !handles(link)) continue;
            Matcher t = title.matcher(b);
            if (!t.find()) continue;
            StreamInfoItem item = new StreamInfoItem(serviceId, link, Web.unescape(t.group(1)), StreamType.VIDEO_STREAM);
            item.setUploaderName("");
            long secs = -1;
            if (duration != null) {
                Matcher d = duration.matcher(b);
                if (d.find()) secs = Web.duration(d.group(1));
            }
            item.setDuration(secs);
            long v = -1;
            if (views != null) {
                Matcher m = views.matcher(b);
                if (m.find()) v = Web.number(m.group(1));
            }
            item.setViewCount(v);
            List<Image> thumbs = new ArrayList<>();
            Matcher th = thumb.matcher(b);
            if (th.find()) thumbs.add(new Image(absolute(th.group(1)), 180, 320, Image.ResolutionLevel.MEDIUM));
            item.setThumbnails(thumbs);
            p.items.add(item);
        }
        return cards;
    }

    public Media resolve(String circuit, String url) throws IOException {
        if (!handles(url)) throw new IOException("not a video link");
        String html = Web.getUntil(circuit, pageFor(url), "tube video " + URI.create(site).getHost(), null,
                iframe != null ? iframe : STREAM_MARKER, iframe != null ? 2048 : 24 * 1024, timeoutMs);
        Media m = new Media();
        m.referer = referer != null ? referer : url;
        Matcher t = OG_TITLE.matcher(html);
        if (t.find()) m.title = trimSiteName(Web.unescape(t.group(1)));
        else {
            Matcher h = H1.matcher(html);
            if (h.find()) m.title = Web.unescape(h.group(1));
        }
        if (iframe != null) {
            Matcher f = iframe.matcher(html);
            if (!f.find()) throw new IOException("no player");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Referer", url);
            html = Web.get(circuit, absolute(f.group(1)), "tube player " + URI.create(site).getHost(), headers, timeoutMs)
                    .replace("\\\"", "\"").replace("\\/", "/");
        }
        int cap = Math.min(maxHeight, Fetch.torMode() ? 720 : 1080);
        int best = -1;
        String lowest = null;
        int lowestQ = Integer.MAX_VALUE;
        Matcher s = SOURCE.matcher(html);
        while (s.find()) {
            String tag = s.group();
            Matcher src = SRC.matcher(tag);
            if (!src.find()) continue;
            int q = quality(tag);
            if (q <= 0) q = 480;
            String u = absolute(Web.unescape(src.group(1)));
            if (q <= cap && q > best) {
                best = q;
                m.mp4 = u;
            }
            if (!m.qualities.containsKey(q)) m.qualities.put(q, u);
            if (q < lowestQ) {
                lowestQ = q;
                lowest = u;
            }
        }
        Matcher lic = LICENSE.matcher(html);
        String license = lic.find() ? lic.group(1) : null;
        Matcher kvs = KVS.matcher(html);
        while (kvs.find()) {
            String u = kvs.group(2);
            if (u.startsWith("function/")) {
                u = license == null ? null : kvsReal(u, license);
                if (u == null) continue;
            }
            if (!u.contains("/get_file/") && !u.contains(".mp4") && !u.contains(".m3u8")) continue;
            Matcher label = Pattern.compile(Pattern.quote(kvs.group(1)) + "_text\\s*:\\s*'([^']*)'").matcher(html);
            int q = 480;
            if (label.find()) {
                Matcher n = Pattern.compile("(\\d{3,4})").matcher(label.group(1));
                if (n.find()) q = Integer.parseInt(n.group(1));
            }
            u = absolute(u);
            if (q <= cap && q > best) {
                best = q;
                m.mp4 = u;
            }
            if (!m.qualities.containsKey(q)) m.qualities.put(q, u);
            if (q < lowestQ) {
                lowestQ = q;
                lowest = u;
            }
        }
        Matcher enc = ENCODING.matcher(html);
        while (enc.find()) {
            String u = absolute(enc.group(2).replace("\\/", "/"));
            if (!u.contains(".mp4")) continue;
            int q = Integer.parseInt(enc.group(1));
            if (q <= cap && q > best) {
                best = q;
                m.mp4 = u;
            }
            if (!m.qualities.containsKey(q)) m.qualities.put(q, u);
            if (q < lowestQ) {
                lowestQ = q;
                lowest = u;
            }
        }
        if (m.mp4 == null) m.mp4 = lowest;
        if (m.mp4 == null) throw new IOException("no stream");
        m.sprite = com.orvid.play.Storyboard.timeline(html, url);
        if (followRedirect) {
            m.mp4 = unwrap(circuit, m.mp4, m.referer);
            m.qualities.clear();
        }
        return m;
    }

    private static final int MEDIA_HOP_TIMEOUT_MS = 12000;
    private static final long DEAD_HOST_MS = 10 * 60 * 1000L;
    private static final java.util.Map<String, long[]> DEAD = new java.util.HashMap<>();

    private static boolean dead(String host) {
        synchronized (DEAD) {
            long[] d = DEAD.get(host);
            return d != null && d[0] >= 2 && System.currentTimeMillis() - d[1] < DEAD_HOST_MS;
        }
    }

    private static boolean markDead(String host) {
        synchronized (DEAD) {
            long now = System.currentTimeMillis();
            long[] d = DEAD.get(host);
            if (d == null || now - d[1] >= DEAD_HOST_MS) d = new long[]{0, now};
            d[0]++;
            d[1] = now;
            DEAD.put(host, d);
            return d[0] >= 2;
        }
    }

    private String unwrap(String circuit, String url, String referer) throws IOException {
        String current = url;
        int hop = 0;
        try {
            for (; hop < 4; hop++) {
                final String at = current;
                if (hop > 0 && dead(URI.create(at).getHost())) {
                    throw new com.orvid.net.Hedge.Final("media server unreachable over Tor: " + URI.create(at).getHost());
                }
                final int wait = hop == 0 ? timeoutMs : Math.min(timeoutMs, MEDIA_HOP_TIMEOUT_MS);
                String next = Fetch.on(circuit, () -> {
                    java.net.HttpURLConnection c = Fetch.open(at);
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(wait);
                    c.setReadTimeout(wait);
                    c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                    c.setRequestProperty("Range", "bytes=0-0");
                    if (referer != null) c.setRequestProperty("Referer", referer);
                    int code = c.getResponseCode();
                    String to = c.getHeaderField("Location");
                    c.disconnect();
                    if (code >= 300 && code < 400 && to != null) return URI.create(at).resolve(to.replace(" ", "%20")).toString();
                    if (code >= 400) throw new IOException("HTTP " + code);
                    return null;
                });
                if (next == null) break;
                current = next;
            }
        } catch (Exception e) {
            if (e instanceof Fetch.Cancelled) throw (Fetch.Cancelled) e;
            if (e instanceof com.orvid.net.Hedge.Final) throw (com.orvid.net.Hedge.Final) e;
            com.orvid.net.Diag.mark("tube redirect FAIL " + e.getMessage());
            if (String.valueOf(e.getMessage()).startsWith("HTTP 4")) throw new IOException("media link refused: " + e.getMessage());
            if (hop > 0) {
                String host = URI.create(current).getHost();
                String why = "media server unreachable over Tor: " + host;
                if (markDead(host)) throw new com.orvid.net.Hedge.Final(why);
                throw new IOException(why);
            }
            return url;
        }
        com.orvid.net.Diag.mark("tube media " + URI.create(current).getHost());
        return current;
    }

    private String trimSiteName(String title) {
        String name = URI.create(site).getHost().replaceFirst("^www\\.", "");
        String label = name.substring(0, name.indexOf('.')).toLowerCase();
        String[] parts = title.split("\\s+[|\\u2013\\u2014-]\\s+");
        if (parts.length < 2) return title;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String low = parts[i].toLowerCase();
            boolean edge = i == 0 || i == parts.length - 1;
            if (edge && (low.contains(label) || low.equals("free porn videos") || low.equals("porn video"))) continue;
            if (out.length() > 0) out.append(" - ");
            out.append(parts[i]);
        }
        return out.length() == 0 ? title : out.toString();
    }

    public static String kvsReal(String videoUrl, String license) {
        if (!videoUrl.startsWith("function/0/")) return null;
        String rest = videoUrl.substring("function/0/".length());
        int scheme = rest.indexOf("://");
        if (scheme < 0) return null;
        int pathStart = rest.indexOf('/', scheme + 3);
        int query = rest.indexOf('?', pathStart);
        String head = rest.substring(0, pathStart);
        String path = query < 0 ? rest.substring(pathStart) : rest.substring(pathStart, query);
        String tail = query < 0 ? "" : rest.substring(query);
        String[] parts = path.split("/", -1);
        if (parts.length < 4 || parts[3].length() < 32) return null;
        int[] token = kvsToken(license);
        if (token == null || token.length < 32) return null;
        String hash = parts[3].substring(0, 32);
        int[] idx = new int[32];
        for (int i = 0; i < 32; i++) idx[i] = i;
        int accum = 0;
        for (int src = 31; src >= 0; src--) {
            accum += token[src];
            int dest = (src + accum) % 32;
            int t = idx[src];
            idx[src] = idx[dest];
            idx[dest] = t;
        }
        StringBuilder h = new StringBuilder();
        for (int i : idx) h.append(hash.charAt(i));
        parts[3] = h + parts[3].substring(32);
        return head + String.join("/", parts) + tail;
    }

    private static int[] kvsToken(String license) {
        String l = license.replace("$", "");
        if (!l.matches("\\d+")) return null;
        String mod = l.replace('0', '1');
        int center = mod.length() / 2;
        long front = Long.parseLong(mod.substring(0, center + 1));
        long back = Long.parseLong(mod.substring(center));
        String m = Long.toString(4 * Math.abs(front - back));
        if (m.length() > center + 1) m = m.substring(0, center + 1);
        int[] out = new int[m.length() * 4];
        int k = 0;
        for (int i = 0; i < m.length(); i++) {
            int cur = m.charAt(i) - '0';
            for (int off = 0; off < 4; off++) {
                if (i + off >= l.length()) return null;
                out[k++] = (l.charAt(i + off) - '0' + cur) % 10;
            }
        }
        return out;
    }

    private static int quality(String tag) {
        Matcher l = LABEL.matcher(tag);
        while (l.find()) {
            String v = l.group(1).trim().toUpperCase();
            Matcher n = Pattern.compile("(\\d{3,4})P?").matcher(v);
            if (n.find()) return Integer.parseInt(n.group(1));
            if (v.equals("HQ") || v.equals("HD")) return 720;
            if (v.equals("LQ") || v.equals("SD")) return 360;
        }
        return 0;
    }

    private String absolute(String u) {
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return site + u;
        return u;
    }
}
