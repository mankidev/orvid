package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Movies {

    static final Set<String> WORDS = new HashSet<>(Arrays.asList("film", "filmi", "filmini", "movie", "movies"));
    private static final int TOP = 20;
    private static final Map<String, Boolean> MARKED = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 2000;
        }
    });

    private Movies() {
    }

    public static void mark(String url) {
        if (url != null) MARKED.put(url, Boolean.TRUE);
    }

    public static boolean is(StreamInfoItem item) {
        return item != null && MARKED.containsKey(item.getUrl());
    }

    private static int extra(List<String> keys, StreamInfoItem item) {
        String name = (item.getName() == null ? "" : item.getName()).replaceAll("\\(\\d{4}\\)", "");
        int best = Integer.MAX_VALUE;
        for (String part : name.split(" - ")) {
            int n = 0;
            for (String w : Query.words(Query.fold(part))) {
                boolean known = false;
                for (String k : keys) known |= Ranker.sameWord(k, w) || Query.wordMatches(k, w);
                if (!known && !Query.STOP.contains(w)) n++;
            }
            best = Math.min(best, n);
        }
        return best;
    }

    static List<StreamInfoItem> first(Query q, List<StreamInfoItem> ranked) {
        if (!q.movie || ranked.isEmpty()) return ranked;
        List<String> keys = Ranker.keyTerms(q.terms);
        if (keys.isEmpty()) keys = q.terms;
        List<StreamInfoItem> top = new ArrayList<>();
        for (StreamInfoItem item : ranked) {
            if (top.size() >= TOP) break;
            if (!is(item)) continue;
            List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
            boolean all = !keys.isEmpty();
            for (String k : keys) {
                boolean hit = false;
                for (String w : words) {
                    if (Ranker.sameWord(k, w) || Query.wordMatches(k, w)) {
                        hit = true;
                        break;
                    }
                }
                all &= hit;
            }
            if (all) top.add(item);
        }
        if (top.isEmpty()) return ranked;
        final List<String> wanted = keys;
        top.sort((x, y) -> Integer.compare(extra(wanted, x), extra(wanted, y)));
        Set<StreamInfoItem> moved = Collections.newSetFromMap(new IdentityHashMap<>());
        moved.addAll(top);
        List<StreamInfoItem> head = new ArrayList<>(top);
        List<StreamInfoItem> tail = new ArrayList<>();
        int strict = Ranker.Ranked.strict(ranked);
        for (int i = 0; i < ranked.size(); i++) {
            StreamInfoItem item = ranked.get(i);
            if (moved.contains(item)) continue;
            if (i < strict) head.add(item);
            else tail.add(item);
        }
        return Ranker.Ranked.of(head, tail);
    }
}
