package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Refine {

    private static final int SAMPLE = 150;
    private static final int WORDS = 30;
    private static final int MIN_ITEMS = 2;
    private static final double MAX_SHARE = 0.8;
    private static final int MANY_SOURCES = 3;

    static final Set<String> NOISE = new HashSet<>(java.util.Arrays.asList(
            "the", "and", "for", "with", "from", "your", "you", "how", "what", "why", "when", "who", "this", "that",
            "these", "those", "into", "onto", "over", "about", "after", "before", "out", "off", "can", "will", "just",
            "get", "got", "make", "made", "using", "use", "new", "best", "top", "vs", "via", "its", "our", "their",
            "his", "her", "him", "they", "them", "has", "have", "had", "does", "did", "very", "more", "most", "part",
            "video", "videos", "official", "full", "episode", "ep", "live", "watch", "shorts", "short",
            "ve", "ile", "bir", "bu", "su", "o", "icin", "gibi", "nasil", "neden", "ne", "en", "cok", "daha", "ama",
            "da", "de", "mi", "mu", "ki", "olan", "oldu", "olarak", "kadar", "sonra", "once", "yeni", "tam", "bolum",
            "amp", "quot", "mp4", "each", "every", "some", "much", "many", "other"));

    private Refine() {
    }

    public static final class Choice {
        public final String label;
        public final String query;

        Choice(String label, String query) {
            this.label = label;
            this.query = query;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Choice && ((Choice) o).query.equals(query) && ((Choice) o).label.equals(label);
        }

        @Override
        public int hashCode() {
            return query.hashCode();
        }
    }

    public static List<Choice> of(Query q, List<StreamInfoItem> items) {
        List<Choice> out = new ArrayList<>();
        if (q == null || q.raw == null || q.plainQuery == null || q.terms.isEmpty() || items.isEmpty()) return out;
        String raw = q.raw.trim().replaceAll("\\s+", " ");
        String plain = q.plainQuery.trim().replaceAll("\\s+", " ");
        Map<String, Integer> count = new HashMap<>();
        Map<String, Set<Integer>> sources = new HashMap<>();
        Map<String, String> shown = new HashMap<>();
        int n = Math.min(SAMPLE, items.size());
        for (int i = 0; i < n; i++) {
            StreamInfoItem item = items.get(i);
            Set<String> seen = new HashSet<>();
            for (String w : Ranker.keyTerms(Ranker.parsed(item).titleWords)) {
                if (own(q, w) || NOISE.contains(w) || CommonWords.has(w) || Filters.lgbtWord(w) || !seen.add(w)) continue;
                count.merge(w, 1, Integer::sum);
                sources.computeIfAbsent(w, k -> new HashSet<>()).add(item.getServiceId());
            }
            String name = item.getName() == null ? "" : item.getName().replace("\u0130", "i").toLowerCase(Locale.ROOT);
            for (String word : Query.words(name)) {
                String key = Query.fold(word);
                if (count.containsKey(key)) shown.putIfAbsent(key, word);
            }
        }
        Set<Integer> all = new HashSet<>();
        for (Set<Integer> s : sources.values()) all.addAll(s);
        int minSources = all.size() >= MANY_SOURCES ? 2 : 1;
        List<String> common = new ArrayList<>();
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() >= MIN_ITEMS && e.getValue() <= n * MAX_SHARE && sources.get(e.getKey()).size() >= minSources
                    && !Expand.variant(q, e.getKey()) && !joined(q, e.getKey())) common.add(e.getKey());
        }
        common.sort((a, b) -> sources.get(b).size() != sources.get(a).size()
                ? sources.get(b).size() - sources.get(a).size() : count.get(b) - count.get(a));
        for (String phrase : Expand.of(q, items)) {
            if (!Filters.lgbtWord(phrase)) out.add(new Choice(phrase, phrase));
        }
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        for (String w : common) {
            if (picked.size() >= WORDS) break;
            boolean plural = false;
            for (String p : picked) plural |= w.equals(p + "s") || p.equals(w + "s") || w.equals(p + "es") || p.equals(w + "es");
            if (!plural) picked.add(w);
        }
        for (String w : picked) {
            String word = shown.getOrDefault(w, w);
            out.add(new Choice(plain + " " + word, raw + " +" + word));
        }
        return out;
    }

    private static boolean joined(Query q, String w) {
        if (q.terms.size() < 2) return false;
        for (String t : q.terms) if (!w.contains(t)) return false;
        return true;
    }

    static boolean own(Query q, String w) {
        for (String t : q.terms) if (Ranker.sameWord(t, w)) return true;
        for (String t : q.excluded) if (Ranker.sameWord(t, w)) return true;
        for (String t : q.required) if (Ranker.sameWord(t, w)) return true;
        return false;
    }
}
