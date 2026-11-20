package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Spell {

    private static final int DEPTH = 30;
    private static final int MIN_LENGTH = 5;
    private static final int TWO_EDITS_FROM = 9;
    private static final int MIN_ITEMS = 3;
    private static final int MIN_SOURCES = 2;
    private static final int DOMINANCE = 5;

    private Spell() {
    }

    static Map<String, String> aliases(Query q, List<List<StreamInfoItem>> sources) {
        List<String> candidates = new java.util.ArrayList<>();
        for (String t : q.terms) {
            if (t.length() >= MIN_LENGTH && !t.chars().allMatch(Character::isDigit)) candidates.add(t);
        }
        if (candidates.isEmpty()) return Collections.emptyMap();
        int scanned = 0;
        int[] seen = new int[candidates.size()];
        for (List<StreamInfoItem> list : sources) {
            for (int i = 0; i < Math.min(DEPTH, list.size()); i++) {
                Ranker.Parsed p = Ranker.parsed(list.get(i));
                scanned++;
                for (int c = 0; c < seen.length; c++) {
                    if (has(p.titleWords, candidates.get(c)) || has(p.channelWords, candidates.get(c))) seen[c]++;
                }
            }
        }
        List<String> rare = new java.util.ArrayList<>();
        for (int c = 0; c < seen.length; c++) if (seen[c] * DOMINANCE <= scanned) rare.add(candidates.get(c));
        if (rare.isEmpty()) return Collections.emptyMap();
        candidates = rare;
        Map<String, Integer> items = new HashMap<>();
        Map<String, Set<Integer>> from = new HashMap<>();
        for (int src = 0; src < sources.size(); src++) {
            List<StreamInfoItem> list = sources.get(src);
            for (int i = 0; i < Math.min(DEPTH, list.size()); i++) {
                Ranker.Parsed p = Ranker.parsed(list.get(i));
                Set<String> words = new HashSet<>(p.titleWords);
                words.addAll(p.channelWords);
                for (String w : words) {
                    items.merge(w, 1, Integer::sum);
                    from.computeIfAbsent(w, k -> new HashSet<>()).add(src);
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String t : candidates) {
            String best = null;
            int bestCount = 0;
            int limit = t.length() >= TWO_EDITS_FROM ? 2 : 1;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                String w = e.getKey();
                int n = e.getValue();
                if (n < MIN_ITEMS || n <= bestCount || from.get(w).size() < MIN_SOURCES) continue;
                if (w.length() < MIN_LENGTH - 1 || Query.isStop(w) || q.terms.contains(w) || Query.wordMatches(t, w)) continue;
                if (!sameStart(t, w) || distance(t, w, limit) > limit) continue;
                best = w;
                bestCount = n;
            }
            if (best == null) continue;
            int own = 0;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                if (Query.wordMatches(t, e.getKey())) own += e.getValue();
            }
            if (own * DOMINANCE <= bestCount) out.put(t, best);
        }
        return out.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(out);
    }

    private static boolean has(List<String> words, String term) {
        for (String w : words) if (Query.wordMatches(term, w)) return true;
        return false;
    }

    private static boolean sameStart(String a, String b) {
        if (a.charAt(0) == b.charAt(0)) return true;
        return a.length() > 1 && b.length() > 1 && a.charAt(0) == b.charAt(1) && a.charAt(1) == b.charAt(0);
    }

    static int distance(String a, String b, int limit) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > limit) return limit + 1;
        int[] prev2 = new int[m + 1];
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > limit) return limit + 1;
            int[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }
}
