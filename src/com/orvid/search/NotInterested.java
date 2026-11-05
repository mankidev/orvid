package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NotInterested {

    private static final Set<String> URLS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CHANNELS = ConcurrentHashMap.newKeySet();
    private static final List<List<String>> TITLES = new CopyOnWriteArrayList<>();

    private NotInterested() {
    }

    public static void add(StreamInfoItem item) {
        if (item.getUrl() != null) URLS.add(item.getUrl());
        String channel = channel(item);
        if (!channel.isEmpty()) CHANNELS.add(channel);
        List<String> keys = Ranker.keyTerms(Query.parse(item.getName() == null ? "" : item.getName()).terms);
        if (keys.size() >= 3) TITLES.add(keys);
    }

    public static boolean hides(StreamInfoItem item) {
        if (Known.hides(item)) return true;
        if (URLS.isEmpty()) return false;
        if (item.getUrl() != null && URLS.contains(item.getUrl())) return true;
        String channel = channel(item);
        if (!channel.isEmpty() && CHANNELS.contains(channel)) return true;
        List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
        for (List<String> keys : TITLES) {
            int need = Math.max(3, (int) Math.ceil(keys.size() * 0.6));
            int shared = 0;
            for (String k : keys) {
                for (String w : words) {
                    if (Ranker.sameWord(k, w)) {
                        shared++;
                        break;
                    }
                }
                if (shared >= need) return true;
            }
        }
        return false;
    }

    private static String channel(StreamInfoItem item) {
        return Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName()).trim();
    }
}
