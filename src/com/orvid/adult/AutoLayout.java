package com.orvid.adult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AutoLayout {

    static final int MIN_LINKS = 6;
    static final int MIN_CARDS = 5;
    private static final int LOOK_BACK = 700;
    private static final int SAMPLE = 6;

    final String card;
    final String href;
    final String title;
    final String thumb;
    final String duration;
    final boolean relative;

    private AutoLayout(String card, String href, String title, String thumb, String duration, boolean relative) {
        this.card = card;
        this.href = href;
        this.title = title;
        this.thumb = thumb;
        this.duration = duration;
        this.relative = relative;
    }

    private static final Pattern OPENER = Pattern.compile("<(?:div|li|article|a)\\b[^>]*?\\s(class=\"[^\"]*\")");
    private static final String[] TITLES = {"title=\"([^\"]+)\"", "alt=\"([^\"]+)\"",
            "<h[1-6][^>]*>\\s*(?:<a[^>]*>\\s*)?([^<]{4,})<", "<p[^>]*>\\s*([^<]{6,})</p>"};
    private static final String IMAGE = "((?:https?:)?/[^\"\\s]+\\.(?:jpe?g|webp)(?:\\?[^\"\\s]*)?)[\"\\s]";
    private static final String[] THUMBS = {
            "data-original=\"" + IMAGE, "data-src=\"" + IMAGE, "data-webp=\"" + IMAGE, "\\bsrc=\"" + IMAGE,
            "(?:data-original|data-src|src)=\"((?:https?:)?/[^\"]+\\.(?:jpe?g|webp|png)[^\"]*)\""};
    static final String ANY_THUMB = THUMBS[THUMBS.length - 1];
    private static final String[] DURATIONS = {
            "class=\"duration\"[^>]*>\\s*(?:<[^>]+>\\s*)*([0-9:]{4,8})",
            "class=\"[^\"]*(?:duration|time)[^\"]*\"[^>]*>\\s*(?:<[^>]+>\\s*)*([0-9:]{4,8})",
            ">\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*<"};

    static String hostPattern(String host) {
        return "(?:www\\.)?" + Pattern.quote(host.replaceFirst("^www\\.", ""));
    }

    static AutoLayout detect(String host, String segment, String html) {
        String path = "/" + Pattern.quote(segment) + "/";
        String absolute = "https?://" + hostPattern(host) + path;
        Matcher links = Pattern.compile("href=\"((?:https?://" + hostPattern(host) + ")?" + path + "[^\"#?]+)\"").matcher(html);
        int count = 0;
        boolean relative = false;
        Map<String, Double> opens = new LinkedHashMap<>();
        while (links.find()) {
            if (count++ == 0) relative = !links.group(1).startsWith("http");
            String before = html.substring(Math.max(0, links.start() - LOOK_BACK), links.start());
            List<String> tags = new ArrayList<>();
            Matcher o = OPENER.matcher(before);
            while (o.find()) tags.add(o.group(1));
            if (tags.isEmpty()) continue;
            opens.merge(tags.get(tags.size() - 1), 1.0, Double::sum);
            for (int i = Math.max(0, tags.size() - 3); i < tags.size(); i++) opens.merge(tags.get(i), 0.01, Double::sum);
        }
        if (count < MIN_LINKS || opens.isEmpty()) return null;
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(opens.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        String card = null;
        for (int i = 0; i < Math.min(8, ranked.size()) && card == null; i++) {
            String t = ranked.get(i).getKey();
            if (t.contains("video") || t.contains("item") || t.contains("thumb") || t.contains("card")) card = t;
        }
        if (card == null) card = ranked.get(0).getKey();
        String href = "href=\"(" + (relative ? path : absolute) + "[^\"#?]+)\"";
        Pattern hrefPattern = Pattern.compile(href);
        String[] parts = html.split(Pattern.quote(card));
        List<String> cards = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) if (hrefPattern.matcher(parts[i]).find()) cards.add(parts[i]);
        if (cards.size() < MIN_CARDS) return null;
        List<String> sample = cards.subList(0, Math.min(SAMPLE, cards.size()));
        String title = null;
        for (String t : TITLES) {
            if (hits(t, sample) == sample.size()) {
                title = t;
                break;
            }
        }
        if (title == null) return null;
        String thumb = ANY_THUMB;
        for (String t : THUMBS) {
            if (hits(t, sample) >= sample.size() - 1) {
                thumb = t;
                break;
            }
        }
        String duration = null;
        for (String t : DURATIONS) {
            if (hits(t, sample) >= sample.size() - 1) {
                duration = t;
                break;
            }
        }
        return new AutoLayout(card, href, title, thumb, duration, relative);
    }

    private static int hits(String pattern, List<String> cards) {
        Pattern p = Pattern.compile(pattern);
        int n = 0;
        for (String c : cards) if (p.matcher(c).find()) n++;
        return n;
    }

    static String laterPage(String html, String firstPage, String site, String q, String qPath) {
        String typed = firstPage.contains("{qp}") ? qPath : q;
        String first = firstPage.replace("{q}", q).replace("{qp}", qPath);
        String firstPath = first.startsWith(site) ? first.substring(site.length()) : first;
        String sep = firstPage.contains("?") ? "&" : "?";
        if (firstPath.endsWith("/") && contains(html, firstPath + "2/")) return firstPage + "{p}/";
        if (Pattern.compile("from_videos(?:\\+from_albums)?[=:]0?2\\b").matcher(html).find()) return firstPage + sep + "from_videos={p}";
        if (firstPath.endsWith("/") && html.contains("href=\"page2.html\"")) return firstPage + "page{p}.html";
        if (contains(html, "/page/2/?s=" + typed)) return site + "/page/{p}/?s=" + (firstPage.contains("{qp}") ? "{qp}" : "{q}");
        if (Pattern.compile("[?&](?:amp;)?page=2\\b").matcher(html).find()) return firstPage + sep + "page={p}";
        return null;
    }

    private static boolean contains(String html, String s) {
        return html.contains(s) || html.contains(s.replace("&", "&amp;"));
    }
}
