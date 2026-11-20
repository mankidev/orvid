package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Expand {

    private static final int SAMPLE = 150;
    private static final int MIN_CHANNELS = 3;
    private static final int MAX = 6;
    private static final Pattern TAG = Pattern.compile("#([\\p{L}\\p{N}_]{4,40})");
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "challenge", "challenges", "compilation", "viral", "funny", "trending", "trend", "fyp", "foryou", "foryoupage",
            "fy", "fypage", "reels", "reel", "tiktok", "youtube", "youtubeshorts", "shortsvideo", "shortvideo", "viralvideo",
            "explore", "explorepage", "edit", "edits", "clip", "clips", "vlog", "music", "song", "subscribe", "like", "follow",
            "couple", "couples", "girl", "girls", "boy", "boys", "woman", "women", "man", "men", "guy", "guys", "kid", "kids",
            "strong", "tall", "short", "small", "big", "cute", "real", "first", "last", "time", "times", "day", "way", "ways",
            "and", "the", "with", "for", "from", "her", "his", "him", "she", "they", "amp", "quot", "should", "would",
            "could", "must", "but", "how", "lets", "let"));

    private Expand() {
    }

    public static List<String> of(Query q, List<StreamInfoItem> items) {
        List<String> out = new ArrayList<>();
        if (q == null || q.terms.isEmpty() || items.isEmpty()) return out;
        Map<String, Set<String>> phrases = new LinkedHashMap<>();
        Map<String, Set<String>> tags = new LinkedHashMap<>();
        Map<String, String> tagShown = new HashMap<>();
        int n = Math.min(SAMPLE, items.size());
        for (int i = 0; i < n; i++) {
            StreamInfoItem item = items.get(i);
            if (!Ranker.covers(q, item)) continue;
            String channel = Query.fold(item.getUploaderName() == null || item.getUploaderName().isEmpty()
                    ? String.valueOf(item.getUrl()) : item.getUploaderName());
            List<String> words = Ranker.parsed(item).titleWords;
            boolean[] whole = wholeQuery(q, words);
            for (int w = 0; q.terms.size() > 1 && w + 1 < words.size(); w++) {
                String a = words.get(w);
                String b = words.get(w + 1);
                boolean ownA = Refine.own(q, a);
                boolean ownB = Refine.own(q, b);
                if (ownA == ownB || whole[ownA ? w : w + 1]) continue;
                String other = ownA ? b : a;
                if (!usable(other) || variant(q, other)) continue;
                phrases.computeIfAbsent(a + " " + b, k -> new HashSet<>()).add(channel);
            }
            Matcher t = TAG.matcher(item.getName() == null ? "" : item.getName());
            while (t.find()) {
                String tag = Query.fold(t.group(1)).replace("_", "");
                if (!usable(tag) || Refine.own(q, tag) || variant(q, tag)) continue;
                int mentions = 0;
                for (String term : q.terms) if (tag.contains(term)) mentions++;
                if (mentions == q.terms.size()) continue;
                if (mentions > 0 && !tag.endsWith(q.terms.get(q.terms.size() - 1)) && !tag.startsWith(q.terms.get(0))) continue;
                tags.computeIfAbsent(tag, k -> new HashSet<>()).add(channel);
                tagShown.putIfAbsent(tag, t.group(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        Map<String, Integer> score = new HashMap<>();
        Set<String> compact = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : phrases.entrySet()) {
            if (e.getValue().size() < MIN_CHANNELS) continue;
            score.put(e.getKey(), e.getValue().size());
            compact.add(e.getKey().replace(" ", ""));
        }
        for (Map.Entry<String, Set<String>> e : tags.entrySet()) {
            if (e.getValue().size() < MIN_CHANNELS || compact.contains(e.getKey())) continue;
            score.put(tagShown.get(e.getKey()), e.getValue().size());
        }
        List<String> ranked = new ArrayList<>(score.keySet());
        ranked.sort((x, y) -> score.get(y) - score.get(x));
        for (String p : ranked) if (out.size() < MAX) out.add(p);
        return out;
    }

    private static boolean[] wholeQuery(Query q, List<String> words) {
        boolean[] out = new boolean[words.size()];
        int k = q.terms.size();
        for (int i = 0; i + k <= words.size(); i++) {
            boolean match = true;
            for (int j = 0; j < k && match; j++) match = Ranker.sameWord(q.terms.get(j), words.get(i + j));
            if (match) for (int j = 0; j < k; j++) out[i + j] = true;
        }
        return out;
    }

    static boolean variant(Query q, String w) {
        for (String t : q.terms) if (w.startsWith(t) && w.length() - t.length() <= 3 || t.startsWith(w)) return true;
        return false;
    }

    private static boolean usable(String w) {
        return w.length() >= 3 && !w.chars().allMatch(Character::isDigit) && !GENERIC.contains(w)
                && !Refine.NOISE.contains(w) && !CommonWords.has(w) && !Filters.lgbtWord(w)
                && !Ranker.keyTerms(java.util.Collections.singletonList(w)).isEmpty();
    }
}
