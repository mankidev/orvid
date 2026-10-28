package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

public abstract class Source {

    public static final class Page {
        public final List<StreamInfoItem> items = new ArrayList<>();
        public String next;
        public boolean skipped;

        public Page() {
        }

        public Page(List<StreamInfoItem> items, String next) {
            this.items.addAll(items);
            this.next = next;
        }
    }

    public final String key;
    public final String name;
    public final int icon;
    public final int serviceId;
    public final double weight;
    public final boolean defaultOn;
    public final boolean adult;
    public String iconFile;

    public static volatile boolean adultUnlocked;
    static final java.util.Map<Integer, Source> BY_SERVICE = new java.util.concurrent.ConcurrentHashMap<>();
    public String script;
    public String orientation;
    private final java.util.Set<String> topics = new java.util.HashSet<>();
    private static final java.util.Set<String> GENERIC = new java.util.HashSet<>(java.util.Arrays.asList(
            "porn", "porno", "tube", "sex", "sexy", "xxx", "video", "videos", "vid", "vids", "movie", "movies", "clip",
            "clips", "free", "hot", "top", "best", "new", "fuck", "fap", "lust", "home", "files", "you", "the"));

    public Source topics(java.util.Collection<String> values) {
        for (String v : values) topics.add(Query.fold(v).trim());
        return this;
    }

    public boolean about(String term) {
        if (term.length() < 3 || GENERIC.contains(term)) return false;
        if (topics.contains(term)) return true;
        return adult && Query.fold(name).replaceAll("[^\\p{L}\\p{N}]", "").contains(term);
    }

    static boolean about(String term, StreamInfoItem item) {
        Source s = BY_SERVICE.get(item.getServiceId());
        return s != null && s.about(term);
    }

    public Source script(String value) {
        script = value;
        return this;
    }

    public int priority(Query q) {
        if (script != null) return 2;
        return adult ? 5 : 4;
    }

    static final java.util.Set<String> ADULT_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    protected Source(String key, String name, int icon, int serviceId, double weight, boolean defaultOn, boolean adult) {
        this.key = key;
        this.name = name;
        this.icon = icon;
        this.serviceId = serviceId;
        this.weight = weight;
        this.defaultOn = defaultOn;
        this.adult = adult;
        if (adult) ADULT_KEYS.add(key);
        BY_SERVICE.put(serviceId, this);
    }

    public abstract Page search(String circuit, String query, Filters filters, String next) throws Exception;

    public boolean usesWebView() {
        return false;
    }

    public boolean slow() {
        return false;
    }

    public boolean browses() {
        return false;
    }

    public boolean wanted(Filters f, Query q) {
        if (!q.siteIs(key, name) || f.type == Filters.TYPE_SHORTS) return false;
        if (adult && (f.hideAdult || !adultUnlocked)) return false;
        if (adult && !f.sourceFits(this)) return false;
        if (script != null && !script.equals(q.script())) return false;
        return f.on(this);
    }
}
