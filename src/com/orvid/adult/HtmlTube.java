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
    private volatile String laterPage;
    private final Pattern videoUrl;
    private volatile String cardMarker;
    private volatile Pattern href;
    private volatile Pattern title;
    private volatile Pattern thumb;
    private volatile Pattern duration;
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

    private String segment;
    private volatile boolean configured = true;
    private volatile boolean pageKnown = true;
    private volatile long failedAt;
    private static final long AUTO_RETRY_MS = 30 * 60 * 1000L;

    public static HtmlTube auto(int serviceId, String host, String path, String segment) {
        String site = "https://" + host;
        String first = site + (path.contains("={q}") ? path : path.replace("{q}", "{qp}"));
        String match = AutoLayout.hostPattern(host) + "/" + Pattern.quote(segment) + "/[^\\s\"?#]+";
        HtmlTube t = new HtmlTube(serviceId, site, first, first, match, "\u0000", "(?!)", "(?!)", "(?!)", null, null);
        t.segment = segment;
        t.configured = false;
        t.pageKnown = false;
        return t;
    }

    public String match() {
        return videoUrl.pattern();
    }

    private void adopt(String circuit, String html) throws IOException {
        if (configured) return;
        synchronized (this) {
            if (configured) return;
            if (System.currentTimeMillis() - failedAt < AUTO_RETRY_MS) throw new IOException("unknown layout");
            AutoLayout l = AutoLayout.detect(host(), segment, html);
            if (l == null) {
                try {
                    l = AutoLayout.detect(host(), segment, Web.get(circuit, site + "/", "tube home " + host(), null, timeoutMs));
                } catch (IOException e) {
                    failedAt = System.currentTimeMillis();
                    throw e;
                }
            }
            if (l == null) {
                failedAt = System.currentTimeMillis();
                throw new IOException("unknown layout");
            }
            href = Pattern.compile(l.href);
            title = Pattern.compile(l.title);
            thumb = Pattern.compile(l.thumb);
            duration = l.duration == null ? null : Pattern.compile(l.duration);
            cardMarker = l.card;
            configured = true;
        }
    }

    private void learnPaging(String html, String q, String qPath) {
        if (pageKnown) return;
        String later = AutoLayout.laterPage(html, firstPage, site, q, qPath);
        if (later == null) return;
        laterPage = later;
        paged = true;
        pageKnown = true;
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
        adopt(circuit, html);
        if (page == 1 && !byLink) learnPaging(html, q, qPath);
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

    private static final Pattern KVS_SEARCH = Pattern.compile("https?://[^/]+/search/\\{qp?\\}/");
    private static final Pattern TAG_WORD = Pattern.compile("[a-z0-9]{3,30}");
    private static final int TAG_UNKNOWN = 0;
    private static final int TAG_TAGS = 1;
    private static final int TAG_CATEGORIES = 2;
    private volatile int tagPath = TAG_UNKNOWN;

    public String tagFor(String query) {
        if (!KVS_SEARCH.matcher(firstPage).matches()) return null;
        com.orvid.search.Query q = com.orvid.search.Query.parse(query);
        if (q.terms.size() != 1 || !q.phrases.isEmpty() || !q.excluded.isEmpty() || q.channel != null) return null;
        String word = q.terms.get(0);
        return TAG_WORD.matcher(word).matches() ? word : null;
    }

    public List<StreamInfoItem> tagged(String circuit, String tag) throws IOException {
        String base = site.replaceAll("/+$", "");
        int path = tagPath;
        if (path != TAG_CATEGORIES) {
            try {
                List<StreamInfoItem> items = listing(circuit, base + "/tags/" + tag + "/").items;
                tagPath = TAG_TAGS;
                return items;
            } catch (IOException e) {
                if (!"HTTP 404".equals(e.getMessage()) || path == TAG_TAGS) throw e;
            }
        }
        List<StreamInfoItem> items = listing(circuit, base + "/categories/" + tag + "/").items;
        tagPath = TAG_CATEGORIES;
        return items;
    }

    public Page listing(String circuit, String url) throws IOException {
        String html = Web.get(circuit, url, "tube listing " + host(), null, timeoutMs);
        Page p = new Page();
        parse(html, p);
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
            if (th.find()) thumbs.add(new Image(absolute(Web.unescape(th.group(1))), 180, 320, Image.ResolutionLevel.MEDIUM));
            item.setThumbnails(thumbs);
            p.items.add(item);
        }
        return cards;
    }

    private static final Pattern KEYWORDS = Pattern.compile(
            "<meta\\s+name=[\"']keywords[\"']\\s+content=[\"']([^\"']*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_LINK = Pattern.compile(
            "href=[\"'](?:https?://[^/\"']+)?/(?:tags|categories)/([^/\"'?#]+)/?[\"']");
    private static final Set<String> TAG_NOISE = new HashSet<>(java.util.Arrays.asList(
            "porn", "porno", "pornos", "sex", "xxx", "video", "videos", "hd", "1080p", "720p", "4k", "free", "tube",
            "movie", "movies", "clip", "clips", "full", "online", "watch", "hot", "new", "best", "default", "site", "keywords"));
    private static final int TAG_GAP = 1500;
    private static final int TAG_TAIL = 8 * 1024;
    private static final int TAG_LIMIT = 256 * 1024;
    private static final int MAX_TAGS = 20;
    private static final int TAG_CACHE = 64;
    private static final java.util.Map<String, List<String>> TAGS = new java.util.LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, List<String>> e) {
            return size() > TAG_CACHE;
        }
    };

    public List<String> tags(String circuit, String url) throws IOException {
        synchronized (TAGS) {
            List<String> known = TAGS.get(url);
            if (known != null) return known;
        }
        String html = Web.getUntil(circuit, pageFor(url), "tube tags " + URI.create(site).getHost(), null,
                TAG_LINK, TAG_TAIL, timeoutMs, true, TAG_LIMIT);
        List<String> out = pageTags(html);
        synchronized (TAGS) {
            TAGS.put(url, out);
        }
        return out;
    }

    static List<String> pageTags(String html) {
        int from = 0;
        Matcher h = H1.matcher(html);
        if (h.find()) from = h.start();
        String heading = h.find(0) ? Web.unescape(h.group(1)).toLowerCase(java.util.Locale.ROOT) : "";
        List<List<String>> blocks = new ArrayList<>();
        List<String> block = null;
        Matcher l = TAG_LINK.matcher(html);
        l.region(from, html.length());
        int last = -1;
        while (l.find()) {
            if (l.group(1).indexOf('.') >= 0) continue;
            if (block == null || l.start() - last > TAG_GAP) {
                block = new ArrayList<>();
                blocks.add(block);
            }
            last = l.start();
            String slug;
            try {
                slug = java.net.URLDecoder.decode(l.group(1).replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                slug = l.group(1);
            }
            addTag(block, slug.replaceAll("[-_+]+", " "));
        }
        List<String> meta = new ArrayList<>();
        Matcher k = KEYWORDS.matcher(html);
        if (k.find()) {
            for (String t : Web.unescape(k.group(1)).split(",")) addTag(meta, t);
        }
        List<String> linked = new ArrayList<>();
        for (List<String> b : blocks) {
            boolean fits = false;
            for (String t : b) fits |= meta.contains(t) || !heading.isEmpty() && heading.contains(t);
            if (fits) for (String t : b) if (!linked.contains(t)) linked.add(t);
        }
        if (linked.isEmpty() && meta.isEmpty() && !blocks.isEmpty()) linked.addAll(blocks.get(0));
        List<String> out = new ArrayList<>(meta);
        for (String t : linked) if (!out.contains(t)) out.add(t);
        return out.size() > MAX_TAGS ? new ArrayList<>(out.subList(0, MAX_TAGS)) : out;
    }

    private static void addTag(List<String> out, String raw) {
        StringBuilder b = new StringBuilder();
        for (String w : raw.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
            if (w.isEmpty() || TAG_NOISE.contains(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        String t = b.toString();
        if (t.length() >= 2 && !out.contains(t)) out.add(t);
    }

    public Media resolve(String circuit, String url) throws IOException {
        if (!handles(url)) throw new IOException("not a video link");
        String html = Web.getUntil(circuit, pageFor(url), "tube video " + URI.create(site).getHost(), null,
                iframe != null ? iframe : STREAM_MARKER, iframe != null ? 2048 : 24 * 1024, timeoutMs);
        List<String> seen = TAG_LINK.matcher(html).find() ? pageTags(html) : java.util.Collections.<String>emptyList();
        if (!seen.isEmpty()) {
            synchronized (TAGS) {
                TAGS.putIfAbsent(url, seen);
            }
        }
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
        if (m.mp4 == null && segment != null) {
            String[] found = com.orvid.play.Generic.stream(html, url);
            if (found != null) m.mp4 = found[0];
            else embedded(circuit, html, m);
        }
        if (m.mp4 == null) throw new IOException("no stream");
        m.sprite = com.orvid.play.Storyboard.timeline(html, url);
        if (followRedirect) {
            m.mp4 = unwrap(circuit, m.mp4, m.referer);
            m.qualities.clear();
        }
        return m;
    }

    private static final Pattern FRAME = Pattern.compile("<iframe\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_FRAME = Pattern.compile("embed|player|/e/|/v/|video", Pattern.CASE_INSENSITIVE);

    private void embedded(String circuit, String html, Media m) {
        Matcher f = FRAME.matcher(html);
        String any = null;
        String player = null;
        while (f.find() && player == null) {
            String src = absolute(Web.unescape(f.group(1)));
            if (!src.startsWith("http")) continue;
            if (PLAYER_FRAME.matcher(src).find()) player = src;
            else if (any == null) any = src;
        }
        String frame = player != null ? player : any;
        if (frame == null) return;
        try {
            com.orvid.play.Direct d = com.orvid.play.Generic.resolve(circuit, frame);
            m.mp4 = d.url;
            m.referer = d.referer;
        } catch (IOException ignored) {
        }
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
