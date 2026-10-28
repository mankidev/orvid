package com.orvid.search;

import android.content.SharedPreferences;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

public final class Filters {

    public static final int DURATION_ANY = 0;
    public static final int DURATION_SHORT = 1;
    public static final int DURATION_MEDIUM = 2;
    public static final int DURATION_LONG = 3;

    public static final int SORT_RELEVANCE = 0;
    public static final int SORT_LATEST = 1;
    public static final int SORT_LONGEST = 2;
    public static final int SORT_VIEWS = 3;

    public static final int TYPE_ALL = 0;
    public static final int TYPE_VIDEOS = 1;
    public static final int TYPE_SHORTS = 2;

    public static final int DATE_ANY = 0;
    public static final int DATE_WEEK = 1;
    public static final int DATE_MONTH = 2;
    public static final int DATE_YEAR = 3;


    public int date;
    public int type;
    public int duration;
    public int sort;
    public boolean hideAdult;
    public boolean hideJunk = true;
    private static final long JUNK_SHORT_S = 15;
    private static final long JUNK_LONG_S = 3 * 3600;
    public static volatile Filters current;

    private static final java.util.regex.Pattern LGBT_WORDS = java.util.regex.Pattern.compile(
            "\\b(gay|gays|twink|twinks|homo|homosexual|male on male|man on man|men on men|guy on guy|dude on dude|boy on boy|"
                    + "bareback gay|gaysex|gay sex|str8 guy|bi guys|bisexual men|frat boys?|muscle hunk|hunks|"
                    + "trans|transgender|transsexual|tranny|trannies|shemale|shemales|she male|ladyboy|ladyboys|tgirl|tgirls|t girl|"
                    + "ts|ts girl|travesti|travestis|futanari|futa|newhalf|femboy|femboys|sissy|crossdresser|crossdressers|"
                    + "lesbian|lesbians|lesbo|lesbos|lesbi|lezbiyen|girl on girl|girls on girls|woman on woman|women on women|"
                    + "queer|lgbt|lgbtq)\\b");

    private static boolean lgbtSource(Source s) {
        return s.orientation != null && !s.orientation.isEmpty() && !"straight".equals(s.orientation);
    }

    public boolean sourceFits(Source s) {
        return !s.adult || !lgbtSource(s);
    }

    public boolean serviceOn(int serviceId) {
        if (serviceId == org.schabi.newpipe.extractor.ServiceList.YouTube.getServiceId()) return youtube;
        if (serviceId == com.orvid.dm.Dailymotion.SERVICE_ID) return dailymotion;
        if (serviceId == com.orvid.vk.Vk.SERVICE_ID) return vk;
        Source src = Source.BY_SERVICE.get(serviceId);
        return src == null || on(src);
    }

    public static boolean straight(StreamInfoItem item) {
        Source src = Source.BY_SERVICE.get(item.getServiceId());
        if (src == null || !src.adult) return true;
        if (lgbtSource(src)) return false;
        String title = Query.fold(item.getName() == null ? "" : item.getName()).replace('-', ' ');
        return !LGBT_WORDS.matcher(title).find();
    }

    public boolean youtube = true;
    public boolean dailymotion = true;
    public boolean vk = true;
    private final java.util.Map<String, Boolean> sources = new java.util.HashMap<>();

    public boolean on(Source s) {
        Boolean v = sources.get(s.key);
        return v == null ? s.defaultOn : v;
    }

    public void set(Source s, boolean on) {
        sources.put(s.key, on);
    }

    public Filters copy() {
        Filters f = new Filters();
        f.date = date;
        f.type = type;
        f.duration = duration;
        f.sort = sort;
        f.hideAdult = hideAdult;
        f.hideJunk = hideJunk;
        f.youtube = youtube;
        f.dailymotion = dailymotion;
        f.vk = vk;
        f.sources.putAll(sources);
        return f;
    }

    public static Filters load(SharedPreferences p) {
        Filters f = new Filters();
        f.type = p.getInt("f_type", TYPE_ALL);
        f.date = p.getInt("f_date", DATE_ANY);
        f.duration = p.getInt("f_duration", DURATION_ANY);
        f.sort = p.getInt("f_sort", SORT_RELEVANCE);
        f.youtube = p.getBoolean("f_youtube", true);
        f.hideAdult = !Source.adultUnlocked;
        f.dailymotion = p.getBoolean("f_dailymotion", true);
        f.vk = p.getBoolean("f_vk", true);
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (e.getKey().startsWith("f_src_") && e.getValue() instanceof Boolean
                    && !Source.ADULT_KEYS.contains(e.getKey().substring(6))) {
                f.sources.put(e.getKey().substring(6), (Boolean) e.getValue());
            }
        }
        if (p.contains("f_twitch")) f.sources.put("twitch", p.getBoolean("f_twitch", true));
        if (p.contains("f_peertube")) f.sources.put("peertube", p.getBoolean("f_peertube", true));
        return f;
    }

    public String key() {
        StringBuilder b = new StringBuilder().append(date).append(',').append(type).append(',').append(duration)
                .append(',').append(sort).append(',').append(youtube).append(dailymotion)
                .append(vk).append(hideAdult).append(hideJunk);
        for (java.util.Map.Entry<String, Boolean> e : new java.util.TreeMap<>(sources).entrySet()) {
            b.append(',').append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    public void save(SharedPreferences p) {
        p.edit().putInt("f_date", date).putInt("f_type", type).putInt("f_duration", duration).putInt("f_sort", sort)
                .putBoolean("f_youtube", youtube).putBoolean("f_dailymotion", dailymotion).putBoolean("f_vk", vk).apply();
        SharedPreferences.Editor e = p.edit().remove("f_twitch").remove("f_peertube");
        for (java.util.Map.Entry<String, Boolean> s : sources.entrySet()) {
            if (Source.ADULT_KEYS.contains(s.getKey())) e.remove("f_src_" + s.getKey());
            else e.putBoolean("f_src_" + s.getKey(), s.getValue());
        }
        e.apply();
    }

    public String youtubeParams() {
        List<Byte> b = new ArrayList<>();
        int ytSort = sort == SORT_VIEWS ? 3 : 0;
        if (ytSort != 0) {
            b.add((byte) 0x08);
            b.add((byte) ytSort);
        }
        List<Byte> inner = new ArrayList<>();
        if (date != DATE_ANY) {
            inner.add((byte) 0x08);
            inner.add((byte) (date == DATE_WEEK ? 3 : date == DATE_MONTH ? 4 : 5));
        }
        if (type == TYPE_VIDEOS || type == TYPE_SHORTS) {
            inner.add((byte) 0x10);
            inner.add((byte) (type == TYPE_SHORTS ? 9 : 1));
        }
        int ytDuration = type == TYPE_SHORTS ? 0
                : duration == DURATION_SHORT ? 1 : duration == DURATION_LONG ? 2 : duration == DURATION_MEDIUM ? 3 : 0;
        if (ytDuration != 0) {
            inner.add((byte) 0x18);
            inner.add((byte) ytDuration);
        }
        if (!inner.isEmpty()) {
            b.add((byte) 0x12);
            b.add((byte) inner.size());
            b.addAll(inner);
        }
        if (b.isEmpty()) return null;
        byte[] raw = new byte[b.size()];
        for (int i = 0; i < raw.length; i++) raw[i] = b.get(i);
        return Base64.getEncoder().encodeToString(raw);
    }

    public String dailymotionParams() {
        StringBuilder sb = new StringBuilder();
        if (sort == SORT_LATEST) sb.append("&sort=recent");
        else sb.append("&sort=visited");
        sb.append(hideAdult ? "&family_filter=true" : "&family_filter=false");
        long since = since();
        if (since > 0) sb.append("&created_after=").append(since / 1000);
        if (type == TYPE_SHORTS) sb.append("&shorter_than=3");
        else if (duration == DURATION_SHORT) sb.append("&longer_than=1&shorter_than=4");
        else if (duration == DURATION_MEDIUM) sb.append("&longer_than=4&shorter_than=20");
        else if (duration == DURATION_LONG) sb.append("&longer_than=20");
        else sb.append("&longer_than=1");
        return sb.toString();
    }

    private static long epoch(StreamInfoItem item) {
        return item.getUploadDate() == null ? Long.MIN_VALUE : item.getUploadDate().getInstant().getEpochSecond();
    }

    public long since() {
        long day = 24L * 60 * 60 * 1000;
        if (date == DATE_WEEK) return System.currentTimeMillis() - 7 * day;
        if (date == DATE_MONTH) return System.currentTimeMillis() - 31 * day;
        if (date == DATE_YEAR) return System.currentTimeMillis() - 366 * day;
        return 0;
    }


    public boolean accepts(StreamInfoItem item) {
        if (!serviceOn(item.getServiceId())) return false;
        if (NotInterested.hides(item)) return false;
        if (!straight(item)) return false;
        long since = since();
        if (since > 0) {
            long when = epoch(item);
            if (when == Long.MIN_VALUE || when * 1000 < since) return false;
        }
        long d = item.getDuration();
        if (hideJunk && d > 0 && (d > JUNK_LONG_S || d < JUNK_SHORT_S && type != TYPE_SHORTS)) return false;
        if (type == TYPE_SHORTS) return d <= 0 || d <= 180;
        if (type == TYPE_VIDEOS && d > 0 && d <= 60) return false;
        if (d <= 0 || duration == DURATION_ANY) return true;
        if (duration == DURATION_SHORT) return d < 240;
        if (duration == DURATION_MEDIUM) return d >= 240 && d <= 1200;
        return d > 1200;
    }

    public List<StreamInfoItem> arrange(Query q, List<List<StreamInfoItem>> sources, double[] weights) {
        return arrange(q, sources, weights, null);
    }

    static List<StreamInfoItem> onTopic(Query q, List<StreamInfoItem> ranked) {
        List<String> keys = Ranker.keyTerms(q.terms);
        if (keys.isEmpty()) keys = q.terms;
        if (keys.isEmpty() && q.phrases.isEmpty()) return ranked;
        int need = keys.size() <= 2 ? keys.size() : (int) Math.ceil(keys.size() * 0.6);
        List<StreamInfoItem> out = new ArrayList<>();
        for (StreamInfoItem item : ranked) {
            if (Ranker.vouched(item)) {
                out.add(item);
                continue;
            }
            String title = Query.fold(item.getName() == null ? "" : item.getName());
            boolean phrases = true;
            for (String ph : q.phrases) phrases &= title.contains(Query.fold(ph));
            if (!phrases) continue;
            List<String> words = Query.words(title);
            int hits = 0;
            for (String k : keys) {
                for (String w : words) {
                    if (Ranker.sameWord(k, w) || Query.wordMatches(k, w)) {
                        hits++;
                        break;
                    }
                }
            }
            if (hits >= need) out.add(item);
        }
        return out.isEmpty() ? ranked : out;
    }

    public List<StreamInfoItem> arrange(Query q, List<List<StreamInfoItem>> sources, double[] weights, boolean[] tagged) {
        List<List<StreamInfoItem>> kept = new ArrayList<>();
        for (List<StreamInfoItem> list : sources) {
            List<StreamInfoItem> k = new ArrayList<>();
            for (StreamInfoItem i : list) if (accepts(i)) k.add(i);
            kept.add(k);
        }
        List<StreamInfoItem> ranked = Movies.first(q, Ranker.rank(q, kept, weights, tagged));
        if (sort == SORT_RELEVANCE) return ranked;
        int strict = Ranker.Ranked.strict(ranked);
        List<StreamInfoItem> head = onTopic(q, new ArrayList<>(ranked.subList(0, strict)));
        java.util.Set<StreamInfoItem> onTopic = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        onTopic.addAll(head);
        List<StreamInfoItem> tail = new ArrayList<>();
        for (StreamInfoItem i : ranked.subList(0, strict)) if (!onTopic.contains(i)) tail.add(i);
        tail.addAll(ranked.subList(strict, ranked.size()));
        java.util.Comparator<StreamInfoItem> by = sort == SORT_LONGEST
                ? (x, y) -> Long.compare(y.getDuration(), x.getDuration())
                : sort == SORT_VIEWS ? (x, y) -> Long.compare(y.getViewCount(), x.getViewCount())
                : (x, y) -> Long.compare(epoch(y), epoch(x));
        Collections.sort(head, by);
        Collections.sort(tail, by);
        return Ranker.Ranked.of(head, tail);
    }
}
