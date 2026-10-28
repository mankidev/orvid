package com.orvid.web;

import java.util.regex.Pattern;

public final class WebSites {

    public static final class Site {
        public final int serviceId;
        public final String name;
        final String first;
        final String later;
        final String links;
        private final Pattern match;

        Site(int serviceId, String name, String first, String later, String links) {
            this.serviceId = serviceId;
            this.name = name;
            this.first = first;
            this.later = later;
            this.links = links;
            this.match = Pattern.compile(links, Pattern.CASE_INSENSITIVE);
        }

        public boolean handles(String url) {
            return url != null && match.matcher(url).find();
        }

        public String searchUrl(String query, int page) throws java.io.UnsupportedEncodingException {
            String q = java.net.URLEncoder.encode(query, "UTF-8");
            String qp = q.replace("+", "-");
            String qs = q.replace("+", "%20");
            return (page > 1 && later != null ? later : first).replace("{q}", q).replace("{qp}", qp)
                    .replace("{qs}", qs).replace("{p}", Integer.toString(page));
        }

        public boolean pages() {
            return later != null;
        }
    }

    public static final Site SPANKBANG = new Site(130, "SpankBang", "https://spankbang.com/s/{q}/",
            "https://spankbang.com/s/{q}/{p}/", "spankbang\\.com/[a-z0-9]+/video/");
    public static final Site FOURKPORN = new Site(131, "4kporn", "https://4kporn.xxx/search/{qp}/",
            "https://4kporn.xxx/search/{qp}/{p}/", "4kporn\\.xxx/videos/\\d+/");
    public static final Site JABLE = new Site(132, "Jable", "https://jable.tv/search/{qp}/", null,
            "jable\\.tv/videos/[a-z0-9-]+/");
    public static final Site MISSAV = new Site(133, "MissAV", "https://missav.ws/en/search/{qs}",
            "https://missav.ws/en/search/{qs}?page={p}", "missav\\.ws/(?:dm\\d+/)?(?:[a-z]{2}(?:-[a-z]+)?/)?[a-z]{2,6}-\\d{2,5}");

    public static final Site[] ALL = {SPANKBANG, FOURKPORN, JABLE, MISSAV};

    private WebSites() {
    }

    public static Site forUrl(String url) {
        for (Site s : ALL) if (s.handles(url)) return s;
        return null;
    }

    public static java.util.List<org.schabi.newpipe.extractor.stream.StreamInfoItem> search(
            android.content.Context context, Site site, String query, int page) throws java.io.IOException {
        return WebSearch.run(context, site.serviceId, site.name, site.searchUrl(query, page), site.links);
    }
}
