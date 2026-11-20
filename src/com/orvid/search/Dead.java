package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class Dead {

    private static final Set<String> KEYS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger VERSION = new AtomicInteger();

    private Dead() {
    }

    public static void add(String url) {
        String key = key(url);
        if (key != null && KEYS.add(key)) VERSION.incrementAndGet();
    }

    public static boolean has(StreamInfoItem item) {
        if (KEYS.isEmpty()) return false;
        String key = key(item.getUrl());
        return key != null && KEYS.contains(key);
    }

    public static int version() {
        return VERSION.get();
    }

    private static String key(String url) {
        if (url == null || url.isEmpty()) return null;
        String id = com.orvid.yt.Visionos.videoId(url);
        return id != null ? "yt:" + id : url;
    }
}
