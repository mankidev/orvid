package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Ranker {

    private static final double LOOSE = -2;
    private static final double JITTER = 0.15;
    private static volatile long seed = new java.util.Random().nextLong();

    public static void reseed() {
        seed = new java.util.Random().nextLong();
    }

    private static double jitter(StreamInfoItem item) {
        long h = seed ^ (item.getUrl() == null ? 0 : item.getUrl().hashCode()) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return 1 + JITTER * ((h >>> 11) * 0x1.0p-53 * 2 - 1);
    }
    private static final int MIN_STRICT = 3;
    private static final double POPULARITY = 1.8;
    private static final double AGE_DAMP = 0.5;
    private static final double AGE_GRACE_DAYS = 7;
    private static final double RECENT_BONUS = 2.0;
    private static final double RECENT_HALF_DAYS = 60;

    static double ageDays(StreamInfoItem item) {
        if (item.getUploadDate() == null) return -1;
        long ms = System.currentTimeMillis() - item.getUploadDate().getInstant().toEpochMilli();
        return Math.max(0, ms / 86400000.0);
    }
    private static final double FOREIGN_SCRIPT = 8.0;
    private static final double MIXED_SCRIPT_SHARE = 0.15;
    private static final double SOURCE_FLOOR = 0.5;
    private static final long FEW_VIEWS = 1000;
    private static final double FEW_VIEWS_PENALTY = 3.0;
    private static final double EXACT_CHANNEL = 6.0;
    private static final double EXACT_TITLE = 5.0;
    private static final int FAR_GAP = 3;
    private static final int ORDER_TERMS = 3;
    private static final double ADJACENT = 2.0;
    private static final double NEAR = 1.0;
    private static final java.util.regex.Pattern TITLE_PARTS = java.util.regex.Pattern.compile("[-|:/()\\[\\]\"\u2013\u2014\u2022\u300c\u300d\u300e\u300f\u3010\u3011~]+");

    private static String compact(String folded) {
        return folded.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private Ranker() {
    }

    public static final class Scored {
        public final StreamInfoItem item;
        public final double score;
        final int order;
        int covered;
        int spread;
        boolean partial;

        Scored(StreamInfoItem item, double score, int order) {
            this.item = item;
            this.score = score;
            this.order = order;
        }
    }

    public static final class Ranked extends ArrayList<StreamInfoItem> {
        public final int strict;

        private Ranked(List<StreamInfoItem> items, int strict) {
            super(items);
            this.strict = Math.min(strict, items.size());
        }

        public static Ranked of(List<StreamInfoItem> head, List<StreamInfoItem> tail) {
            List<StreamInfoItem> all = new ArrayList<>(head);
            all.addAll(tail);
            return new Ranked(all, head.size());
        }

        public static int strict(List<StreamInfoItem> list) {
            return list instanceof Ranked ? ((Ranked) list).strict : list.size();
        }
    }

    public static List<StreamInfoItem> rank(Query q, List<List<StreamInfoItem>> sources, double[] weights) {
        return rank(q, sources, weights, null);
    }

    public static List<StreamInfoItem> rank(Query q, List<List<StreamInfoItem>> sources, double[] weights, boolean[] tagged) {
        q.alias = Spell.aliases(q, sources);
        Context ctx = context(q, blend(sources, 8));
        vote(q, ctx, sources, weights);
        anchorPerSource(q, ctx, sources);
        popularity(ctx, sources);
        echoes(ctx, sources);
        fit(q, ctx, sources);
        List<Scored> all = new ArrayList<>();
        int[] strictBySource = new int[sources.size()];
        List<List<Scored>> looseBySource = new ArrayList<>();
        for (int k = 0; k < sources.size(); k++) looseBySource.add(new ArrayList<Scored>());
        int order = 0;
        List<Scored> promoted = new ArrayList<>();
        for (int src = 0; src < sources.size(); src++) {
            List<StreamInfoItem> list = sources.get(src);
            boolean byTags = tagged != null && src < tagged.length && tagged[src];
            boolean literal = weights[src] < 1 && !byTags;
            boolean strictOnly = literal && (!ctx.anchored.isEmpty() || guessed(q, list));
            for (int rank = 0; rank < list.size(); rank++) {
                StreamInfoItem item = list.get(rank);
                double s = score(q, ctx, item, rank, weights[src]);
                if (strictOnly && !mentions(q, item)) s = Double.NEGATIVE_INFINITY;
                boolean partial = (literal || byTags) && (q.terms.size() > 1 || !q.repeats.isEmpty()) && mentions(q, item) && coverage(q, item) < required(q);
                if (s >= 0 && weights[src] < 1 && promotable(q, ctx, item)) {
                    Scored p = new Scored(item, s * jitter(item), order++);
                    p.covered = q.terms.size() - 1;
                    promoted.add(p);
                    continue;
                }
                if ((literal || byTags) && s >= 0 && (!mentions(q, item) || partial)) s = LOOSE;
                com.orvid.app.DevInfo.rank(item, src, rank, s);
                if (s >= 0) {
                    Scored kept = new Scored(item, s * jitter(item), order++);
                    kept.covered = coverage(q, item);
                    kept.spread = kept.covered >= q.terms.size() ? spread(q, parsed(item).titleWords) : 0;
                    all.add(kept);
                    strictBySource[src]++;
                } else if (s != Double.NEGATIVE_INFINITY) {
                    Scored loose = new Scored(item, s, order++);
                    loose.partial = partial;
                    looseBySource.get(src).add(loose);
                }
            }
        }
        Collections.sort(all, (a, b) -> a.covered != b.covered ? b.covered - a.covered
                : a.spread != b.spread ? a.spread - b.spread
                : a.score != b.score ? Double.compare(b.score, a.score) : a.order - b.order);
        List<StreamInfoItem> out = interleave(q, diversify(all, ctx), promoted);
        List<Scored> loose = new ArrayList<>();
        for (int k = 0; k < sources.size(); k++) {
            boolean primary = weights[k] >= 1;
            if (strictBySource[k] < MIN_STRICT && primary && out.size() < MIN_STRICT) {
                loose.addAll(looseBySource.get(k));
            }
        }
        Collections.sort(loose, (a, b) -> a.order - b.order);
        for (Scored s : loose) out.add(s.item);
        List<StreamInfoItem> head = collapse(out);
        java.util.Set<StreamInfoItem> taken = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        taken.addAll(out);
        List<StreamInfoItem> tail = new ArrayList<>();
        java.util.Map<StreamInfoItem, Double> trust = new java.util.IdentityHashMap<>();
        double[] shares = new double[looseBySource.size()];
        java.util.Arrays.fill(shares, -1);
        for (int r = 0; ; r++) {
            boolean more = false;
            for (int k = 0; k < looseBySource.size(); k++) {
                List<Scored> list = looseBySource.get(k);
                if (r >= list.size()) continue;
                more = true;
                StreamInfoItem item = list.get(r).item;
                boolean byTags = tagged != null && k < tagged.length && tagged[k];
                if (weights[k] < 1 && !byTags && !q.terms.isEmpty() && !mentions(q, item)) continue;
                if (!taken.contains(item)) {
                    tail.add(item);
                    if (shares[k] < 0) shares[k] = mentionShare(q, sources.get(k));
                    trust.put(item, FIT_TRUST * fitOf(ctx, item) + shares[k]);
                }
            }
            if (!more) break;
        }
        java.util.Map<StreamInfoItem, Integer> covered = new java.util.IdentityHashMap<>();
        for (StreamInfoItem item : tail) covered.put(item, q.terms.isEmpty() ? 0 : coverage(q, item));
        Collections.sort(tail, (x, y) -> !covered.get(x).equals(covered.get(y)) ? covered.get(y) - covered.get(x)
                : Double.compare(trust.get(y), trust.get(x)));
        List<StreamInfoItem> withTail = new ArrayList<>(head);
        withTail.addAll(tail);
        return new Ranked(collapse(withTail), head.size());
    }

    private static final int GUESS_MIN = 4;
    private static final double GUESS_SHARE = 0.3;

    private static double mentionShare(Query q, List<StreamInfoItem> list) {
        if (q.terms.isEmpty() || list.isEmpty()) return 0;
        int hits = 0;
        for (StreamInfoItem item : list) if (mentions(q, item)) hits++;
        return hits / (double) list.size();
    }

    static boolean guessed(Query q, List<StreamInfoItem> list) {
        if (q.terms.isEmpty() || list.size() < GUESS_MIN) return false;
        int hits = 0;
        for (StreamInfoItem item : list) if (mentions(q, item)) hits++;
        return hits < list.size() * GUESS_SHARE;
    }

    private static final int ALL_TERMS_UP_TO = 3;

    static int required(Query q) {
        int n = q.terms.size();
        return n <= ALL_TERMS_UP_TO ? n : (n * 2 + 2) / 3;
    }

    private static final java.util.Set<StreamInfoItem> VOUCHED =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    public static void vouch(java.util.Collection<StreamInfoItem> items) {
        VOUCHED.addAll(items);
    }

    static final class Parsed {
        final String name;
        final String uploader;
        final String title;
        final String channel;
        final List<String> titleWords;
        final List<String> channelWords;
        private String duplicate;

        Parsed(StreamInfoItem item) {
            name = item.getName();
            uploader = item.getUploaderName();
            title = Query.fold(name == null ? "" : name);
            channel = Query.fold(uploader == null ? "" : uploader);
            titleWords = Collections.unmodifiableList(Query.words(title));
            channelWords = Collections.unmodifiableList(Query.words(channel));
        }

        private List<List<String>> parts;
        private String script;
        private String shareScript;
        private double share;

        String duplicateKey() {
            if (duplicate == null) duplicate = Ranker.duplicateKey(name);
            return duplicate;
        }

        List<List<String>> parts() {
            if (parts == null) {
                List<List<String>> out = new ArrayList<>();
                for (String part : TITLE_PARTS.split(title)) {
                    List<String> got = new ArrayList<>();
                    for (String w : Query.words(part)) if (!TITLE_NOISE.contains(w) && !Query.isStop(w)) got.add(w);
                    out.add(got);
                }
                parts = out;
            }
            return parts;
        }

        private String compactChannel;
        String channelKey;

        String compactChannel() {
            if (compactChannel == null) compactChannel = compact(channel);
            return compactChannel;
        }

        String script() {
            if (script == null) script = Query.script(name == null ? "" : name);
            return script;
        }

        double foreignShare(String wanted) {
            if (!wanted.equals(shareScript)) {
                share = Query.foreignShare(name == null ? "" : name, wanted);
                shareScript = wanted;
            }
            return share;
        }
    }

    private static final java.util.Map<StreamInfoItem, Parsed> PARSED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static Parsed parsed(StreamInfoItem item) {
        Parsed p = PARSED.get(item);
        if (p == null || p.name != item.getName() || p.uploader != item.getUploaderName()) {
            p = new Parsed(item);
            PARSED.put(item, p);
        }
        return p;
    }

    static boolean vouched(StreamInfoItem item) {
        return VOUCHED.contains(item);
    }

    public static boolean covers(Query q, StreamInfoItem item) {
        return !q.terms.isEmpty() && coverage(q, item) >= q.terms.size();
    }

    public static boolean covers(Query q, String title) {
        if (q.terms.isEmpty()) return false;
        List<String> words = Query.words(Query.fold(title == null ? "" : title));
        for (String t : q.terms) if (!has(q, words, t)) return false;
        return true;
    }

    static int coverage(Query q, StreamInfoItem item) {
        if (vouched(item)) return q.terms.size();
        List<String> words = parsed(item).titleWords;
        List<String> channel = parsed(item).channelWords;
        int n = 0;
        for (String t : q.terms) {
            Integer times = q.repeats.get(t);
            if (times != null) {
                if (occurrences(words, t) >= times) n++;
            } else if (has(q, words, t) || has(q, channel, t) || Query.acronym(t, words) || Source.about(t, item)
                    || joined(words, t, q.terms)) {
                n++;
            }
        }
        return n;
    }

    private static int occurrences(List<String> words, String term) {
        int n = 0;
        for (String w : words) if (Query.wordMatches(term, w)) n++;
        return n;
    }

    static boolean mentions(Query q, StreamInfoItem item) {
        if (q.terms.isEmpty() || vouched(item)) return true;
        List<String> words = parsed(item).titleWords;
        List<String> channel = parsed(item).channelWords;
        for (String t : q.terms) {
            if (has(q, words, t) || has(q, channel, t) || Query.acronym(t, words) || Source.about(t, item)) return true;
        }
        return false;
    }

    private static final double SOURCE_LEVEL = 0.5;
    private static final int SOURCE_SAMPLE = 5;

    static void popularity(Context ctx, List<List<StreamInfoItem>> sources) {
        List<Double> logs = new ArrayList<>();
        List<List<StreamInfoItem>> known = new ArrayList<>();
        List<double[]> values = new ArrayList<>();
        for (List<StreamInfoItem> list : sources) {
            List<StreamInfoItem> items = new ArrayList<>();
            List<Double> vs = new ArrayList<>();
            for (StreamInfoItem item : list) {
                if (item.getViewCount() < 0) continue;
                double log = Math.log10(item.getViewCount() + 1);
                double age = ageDays(item);
                if (age >= 0) log -= AGE_DAMP * Math.log10((age + AGE_GRACE_DAYS) / AGE_GRACE_DAYS);
                items.add(item);
                vs.add(log);
                logs.add(log);
            }
            known.add(items);
            double[] v = new double[vs.size()];
            for (int i = 0; i < v.length; i++) v[i] = vs.get(i);
            values.add(v);
        }
        if (logs.isEmpty()) return;
        Collections.sort(logs);
        double middle = logs.get(logs.size() / 2);
        ctx.unknownPopularity = middle;
        for (int src = 0; src < known.size(); src++) {
            List<StreamInfoItem> items = known.get(src);
            double[] v = values.get(src);
            double shift = v.length >= SOURCE_SAMPLE ? SOURCE_LEVEL * (median(v) - middle) : 0;
            for (int i = 0; i < items.size(); i++) ctx.popularity.put(items.get(i), v[i] - shift);
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static final java.util.regex.Pattern NOISE = java.util.regex.Pattern.compile(
            "\\b(?:hd|fhd|uhd|4k|8k|1080p|720p|480p|360p|full|video|official|new)\\b");

    static String duplicateKey(String title) {
        String t = Query.fold(title == null ? "" : title);
        t = NOISE.matcher(t).replaceAll(" ");
        return t.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static List<StreamInfoItem> collapse(List<StreamInfoItem> items) {
        List<StreamInfoItem> out = new ArrayList<>(items.size());
        java.util.Map<String, List<StreamInfoItem>> seen = new java.util.HashMap<>();
        for (StreamInfoItem item : items) {
            String key = parsed(item).duplicateKey();
            if (key.length() < 12) {
                out.add(item);
                continue;
            }
            List<StreamInfoItem> same = seen.computeIfAbsent(key, k -> new ArrayList<>());
            boolean duplicate = false;
            for (StreamInfoItem other : same) {
                if (sameLength(other, item, key.length())) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            same.add(item);
            out.add(item);
        }
        return out;
    }

    private static final double ECHO = 2.5;
    private static final int ECHO_MAX = 2;

    static void echoes(Context ctx, List<List<StreamInfoItem>> sources) {
        java.util.Map<String, List<StreamInfoItem>> byKey = new java.util.HashMap<>();
        java.util.Map<StreamInfoItem, Integer> from = new java.util.IdentityHashMap<>();
        for (int src = 0; src < sources.size(); src++) {
            for (StreamInfoItem item : sources.get(src)) {
                String key = parsed(item).duplicateKey();
                if (key.length() < 12) continue;
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
                from.put(item, src);
            }
        }
        for (java.util.Map.Entry<String, List<StreamInfoItem>> e : byKey.entrySet()) {
            List<StreamInfoItem> same = e.getValue();
            if (same.size() < 2) continue;
            int length = e.getKey().length();
            for (StreamInfoItem item : same) {
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                for (StreamInfoItem other : same) {
                    if (other == item || sameLength(other, item, length)) seen.add(from.get(other));
                }
                if (seen.size() >= 2) ctx.echoes.put(item, seen.size());
            }
        }
    }

    private static boolean sameLength(StreamInfoItem a, StreamInfoItem b, int keyLength) {
        long x = a.getDuration();
        long y = b.getDuration();
        if (x <= 0 || y <= 0) return keyLength >= 25;
        return Math.abs(x - y) <= Math.max(3, Math.min(x, y) / 100);
    }

    static List<StreamInfoItem> blend(List<List<StreamInfoItem>> sources, int perSource) {
        List<StreamInfoItem> out = new ArrayList<>();
        for (int r = 0; r < perSource; r++) {
            for (List<StreamInfoItem> list : sources) if (r < list.size()) out.add(list.get(r));
        }
        return out;
    }

    private static final double SOURCE_FIT = 3.0;
    private static final double FIT_SLACK = 0.25;
    private static final double FIT_TRUST = 2.0;
    private static final int FIT_SAMPLE = 20;

    static void fit(Query q, Context ctx, List<List<StreamInfoItem>> sources) {
        if (q.terms.isEmpty()) return;
        for (List<StreamInfoItem> list : sources) {
            if (list.isEmpty()) continue;
            int n = Math.min(list.size(), FIT_SAMPLE);
            int hits = 0;
            for (int i = 0; i < n; i++) {
                StreamInfoItem item = list.get(i);
                if (mentions(q, item) && coverage(q, item) >= q.terms.size()) hits++;
            }
            ctx.fit.put(list.get(0).getServiceId(), hits / (double) n);
        }
    }

    static double fitOf(Context ctx, StreamInfoItem item) {
        Double f = ctx.fit.get(item.getServiceId());
        return f == null ? 0 : f;
    }

    private static final double FIT_STRONG = 0.6;
    private static final int PROMOTE_AFTER = 10;
    private static final int PROMOTE_EVERY = 4;

    static boolean promotable(Query q, Context ctx, StreamInfoItem item) {
        int n = q.terms.size();
        if (n < 3 || !q.repeats.isEmpty() || !q.required.isEmpty() || !q.phrases.isEmpty() || !q.inTitle.isEmpty()) return false;
        if (fitOf(ctx, item) < FIT_STRONG || !mentions(q, item)) return false;
        return coverage(q, item) == n - 1;
    }

    static List<StreamInfoItem> interleave(Query q, List<StreamInfoItem> ranked, List<Scored> promoted) {
        if (promoted.isEmpty()) return ranked;
        Collections.sort(promoted, (a, b) -> a.score != b.score ? Double.compare(b.score, a.score) : a.order - b.order);
        List<StreamInfoItem> out = new ArrayList<>(ranked.size() + promoted.size());
        int next = 0;
        int full = 0;
        for (StreamInfoItem item : ranked) {
            if (coverage(q, item) < q.terms.size()) {
                while (next < promoted.size()) out.add(promoted.get(next++).item);
            }
            out.add(item);
            full++;
            if (full >= PROMOTE_AFTER && (full - PROMOTE_AFTER) % PROMOTE_EVERY == 0 && next < promoted.size()) {
                out.add(promoted.get(next++).item);
            }
        }
        while (next < promoted.size()) out.add(promoted.get(next++).item);
        return out;
    }

    static List<StreamInfoItem> diversify(List<Scored> sorted, Context ctx) {
        List<Scored> pool = new ArrayList<>(sorted);
        List<StreamInfoItem> out = new ArrayList<>(pool.size());
        java.util.Map<String, Integer> channels = new java.util.HashMap<>();
        while (!pool.isEmpty()) {
            int pick = 0;
            int n = out.size();
            if (n >= 2) {
                int a = out.get(n - 1).getServiceId();
                int b = out.get(n - 2).getServiceId();
                if (a == b && pool.get(0).item.getServiceId() == a) {
                    double best = pool.get(0).score;
                    for (int i = 1; i < Math.min(pool.size(), 8); i++) {
                        Scored s = pool.get(i);
                        if (s.item.getServiceId() != a && s.covered >= pool.get(0).covered && s.score >= best * 0.7 - 1
                                && fitOf(ctx, s.item) >= fitOf(ctx, pool.get(0).item) - FIT_SLACK) {
                            pick = i;
                            break;
                        }
                    }
                }
            }
            if (pick == 0 && channels.getOrDefault(channelKey(pool.get(0).item), 0) >= SAME_CHANNEL) {
                double best = pool.get(0).score;
                for (int i = 1; i < Math.min(pool.size(), 8); i++) {
                    Scored s = pool.get(i);
                    if (channels.getOrDefault(channelKey(s.item), 0) < SAME_CHANNEL && s.covered >= pool.get(0).covered
                            && s.score >= best * 0.6) {
                        pick = i;
                        break;
                    }
                }
            }
            StreamInfoItem chosen = pool.remove(pick).item;
            channels.merge(channelKey(chosen), 1, Integer::sum);
            out.add(chosen);
        }
        return out;
    }

    private static final int SAME_CHANNEL = 2;

    private static String channelKey(StreamInfoItem item) {
        Parsed p = parsed(item);
        if (p.channelKey == null) {
            String channel = item.getUploaderName();
            p.channelKey = item.getServiceId() + "/" + (channel == null || channel.isEmpty() ? item.getUrl() : p.channel);
        }
        return p.channelKey;
    }

    static void anchorPerSource(Query q, Context ctx, List<List<StreamInfoItem>> sources) {
        java.util.Set<String> anchored = new java.util.HashSet<>();
        boolean any = false;
        for (List<StreamInfoItem> list : sources) {
            int n = Math.min(list.size(), 15);
            if (n < 4) continue;
            any = true;
            for (String term : q.terms) {
                int hit = 0;
                for (int i = 0; i < n; i++) {
                    StreamInfoItem item = list.get(i);
                    List<String> words = parsed(item).titleWords;
                    List<String> channel = parsed(item).channelWords;
                    if (has(q, words, term) || has(q, channel, term) || Query.acronym(term, words)) hit++;
                }
                if (hit * 5 >= n * 3) anchored.add(term);
            }
        }
        if (!any) return;
        ctx.anchored.clear();
        ctx.anchored.addAll(anchored);
    }

    private static final int VOTE_DEPTH = 10;
    private static final int VOTE_MIN = 2;
    private static final double YOUTUBE_VOTE = 3.0;
    private static final double TOPIC_SHARE = 0.1;
    private static final double LONE_SHARE = 0.25;
    private static final int TOPIC_WORDS = 6;
    private static final double YOUTUBE_TOPIC = 0.1;
    private static final double STRONG_TOPIC = 0.25;
    private static final double OFF_TOPIC = 3.0;
    private static final int AMBIGUOUS_TERMS = 2;

    static void vote(Query q, Context ctx, List<List<StreamInfoItem>> sources, double[] weights) {
        java.util.Map<String, Double> share = new java.util.HashMap<>();
        java.util.Map<String, Integer> support = new java.util.HashMap<>();
        java.util.Map<String, Double> youtube = null;
        double total = 0;
        for (int src = 0; src < sources.size(); src++) {
            List<StreamInfoItem> list = sources.get(src);
            java.util.Map<String, Double> mass = new java.util.HashMap<>();
            double sum = 0;
            int used = 0;
            for (int rank = 0; rank < list.size() && used < VOTE_DEPTH; rank++) {
                StreamInfoItem item = list.get(rank);
                if (!mentions(q, item)) continue;
                used++;
                double w = 1.0 / (1 + rank * 0.3);
                sum += w;
                for (String word : new java.util.HashSet<>(keyTerms(parsed(item).titleWords))) {
                    if (Query.isStop(word) || queryForm(q, word)) continue;
                    mass.merge(word, w, Double::sum);
                }
            }
            if (used < VOTE_MIN) continue;
            if (src == 0) {
                youtube = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, Double> e : mass.entrySet()) youtube.put(e.getKey(), e.getValue() / sum);
            }
            double voice = (src < weights.length ? weights[src] : 0.5) * (src == 0 ? YOUTUBE_VOTE : 1);
            total += voice;
            for (java.util.Map.Entry<String, Double> e : mass.entrySet()) {
                share.merge(e.getKey(), voice * e.getValue() / sum, Double::sum);
                support.merge(e.getKey(), 1, Integer::sum);
            }
        }
        if (total == 0) return;
        List<java.util.Map.Entry<String, Double>> ranked = new ArrayList<>(share.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> topic = new ArrayList<>();
        ctx.topicShare.clear();
        for (java.util.Map.Entry<String, Double> e : ranked) {
            double part = e.getValue() / total;
            if (topic.size() >= TOPIC_WORDS || part < TOPIC_SHARE) break;
            if (support.get(e.getKey()) < 2 && part < LONE_SHARE) continue;
            if (youtube != null && youtube.getOrDefault(e.getKey(), 0.0) < YOUTUBE_TOPIC) continue;
            topic.add(e.getKey());
            ctx.topicShare.put(e.getKey(), part);
        }
        if (topic.isEmpty()) return;
        ctx.topic.clear();
        ctx.topic.addAll(topic);
    }

    private static boolean queryForm(Query q, String word) {
        for (String t : q.terms) if (sameWord(t, word)) return true;
        return false;
    }

    static final class Context {
        final java.util.Set<String> anchored = new java.util.HashSet<>();
        final List<String> topic = new ArrayList<>();
        final java.util.Map<String, Double> topicShare = new java.util.HashMap<>();
        final java.util.Set<String> trustedChannels = new java.util.HashSet<>();
        final java.util.Map<StreamInfoItem, Double> popularity = new java.util.IdentityHashMap<>();
        final java.util.Map<StreamInfoItem, Integer> echoes = new java.util.IdentityHashMap<>();
        final java.util.Map<Integer, Double> fit = new java.util.HashMap<>();
        double unknownPopularity;
    }

    static Context context(Query q, List<StreamInfoItem> primary) {
        Context c = new Context();
        int n = Math.min(primary.size(), 24);
        if (n == 0) {
            c.anchored.addAll(q.terms);
            return c;
        }
        java.util.Map<String, Integer> df = new java.util.HashMap<>();
        int[] covered = new int[q.terms.size()];
        for (int i = 0; i < n; i++) {
            StreamInfoItem item = primary.get(i);
            List<String> words = parsed(item).titleWords;
            List<String> channel = parsed(item).channelWords;
            boolean any = false;
            for (int t = 0; t < q.terms.size(); t++) {
                String term = q.terms.get(t);
                if (has(q, words, term) || has(q, channel, term) || Query.acronym(term, words)) {
                    covered[t]++;
                    any = true;
                }
            }
            if (any && item.getUploaderName() != null) c.trustedChannels.add(Query.fold(item.getUploaderName()));
            for (String w : new java.util.HashSet<>(words)) {
                if (w.length() < 3 || q.terms.contains(w) || Query.isStop(w)) continue;
                df.merge(w, 1, Integer::sum);
            }
        }
        for (int t = 0; t < q.terms.size(); t++) {
            if (covered[t] * 5 >= n * 3) c.anchored.add(q.terms.get(t));
        }
        List<java.util.Map.Entry<String, Integer>> common = new ArrayList<>(df.entrySet());
        common.sort((a, b) -> b.getValue() - a.getValue());
        for (java.util.Map.Entry<String, Integer> e : common) {
            if (c.topic.size() >= 6 || e.getValue() * 5 < n) break;
            c.topic.add(e.getKey());
        }
        return c;
    }

    static double score(Query q, Context ctx, StreamInfoItem item, int rank, double weight) {
        Parsed text = parsed(item);
        String title = text.title;
        String channel = text.channel;
        List<String> titleWords = text.titleWords;
        List<String> channelWords = text.channelWords;

        for (String x : q.excluded) {
            if (contains(titleWords, x) || contains(channelWords, x)) return Double.NEGATIVE_INFINITY;
        }
        if (q.channel != null && !channel.contains(q.channel)) return Double.NEGATIVE_INFINITY;
        for (String p : q.phrases) {
            if (!title.contains(p) && !channel.contains(p)) return Double.NEGATIVE_INFINITY;
        }
        for (String r : q.required) {
            if (!contains(titleWords, r) && !contains(channelWords, r)) return Double.NEGATIVE_INFINITY;
        }
        if (!q.required.isEmpty() && !q.anyOf) {
            for (String t : q.terms) {
                if (!has(q, titleWords, t) && !has(q, channelWords, t) && !joined(titleWords, t, q.terms)) {
                    return Double.NEGATIVE_INFINITY;
                }
            }
        }
        for (String t : q.inTitle) {
            if (!contains(titleWords, t)) return Double.NEGATIVE_INFINITY;
        }
        if ((q.beforeYear > 0 || q.afterYear > 0) && item.getUploadDate() != null) {
            int year = item.getUploadDate().offsetDateTime().getYear();
            if (q.beforeYear > 0 && year >= q.beforeYear || q.afterYear > 0 && year <= q.afterYear) {
                return Double.NEGATIVE_INFINITY;
            }
        }

        double s = 0;
        int matched = 0;
        int anchoredMatched = 0;
        boolean vouched = vouched(item);
        for (String t : q.terms) {
            boolean inTitle = has(q, titleWords, t) || Query.acronym(t, titleWords) || joined(titleWords, t, q.terms);
            boolean inChannel = has(q, channelWords, t) || !inTitle && (vouched || Source.about(t, item));
            if (inTitle) s += 3;
            if (inChannel) s += 2;
            if (inTitle || inChannel) {
                matched++;
                if (ctx.anchored.contains(t)) anchoredMatched++;
            }
        }
        int topicHits = 0;
        double topicWeight = 0;
        double strongest = ctx.topicShare.isEmpty() ? 1 : java.util.Collections.max(ctx.topicShare.values());
        for (String w : ctx.topic) {
            if (!titleWords.contains(w)) continue;
            topicHits++;
            topicWeight += ctx.topicShare.getOrDefault(w, strongest) / strongest;
        }
        s += Math.min(3, topicWeight) * 1.5;
        boolean offTopic = q.terms.size() <= AMBIGUOUS_TERMS && !ctx.topicShare.isEmpty() && topicHits == 0 && strongest >= STRONG_TOPIC;
        if (!q.terms.isEmpty()) {
            int need = q.anyOf ? 1 : Math.max(1, (ctx.anchored.size() + 1) / 2);
            boolean trusted = ctx.trustedChannels.contains(channel);
            if (!ctx.anchored.isEmpty() && anchoredMatched < need && !trusted && topicHits < 2) {
                return LOOSE - (matched * 0.1);
            }
            if (matched == q.terms.size()) s += 3;
            String joined = String.join(" ", q.terms);
            if (q.terms.size() > 1 && title.contains(joined)) s += 4;
            int inOrder = orderedGap(q, titleWords);
            if (inOrder == 0) s += ADJACENT;
            else if (inOrder > 0 && inOrder <= 2) s += NEAR;
            if (!offTopic && exactPart(q, item)) s += EXACT_TITLE;
        }
        if (!q.terms.isEmpty() && !offTopic && text.compactChannel().equals(String.join("", q.terms))) s += EXACT_CHANNEL;
        if (offTopic) s -= OFF_TOPIC;
        s += q.phrases.size() * 4;
        s += 6.0 * weight / (1 + rank * 0.35);
        Double popular = ctx.popularity.get(item);
        s += (popular != null ? popular : ctx.unknownPopularity) * POPULARITY;
        double age = ageDays(item);
        if (age >= 0) s += RECENT_BONUS * Math.pow(0.5, age / RECENT_HALF_DAYS);
        if (!q.terms.isEmpty()) s += SOURCE_FIT * fitOf(ctx, item);
        s *= SOURCE_FLOOR + (1 - SOURCE_FLOOR) * weight;
        Integer echo = ctx.echoes.get(item);
        if (echo != null) s += ECHO * Math.min(ECHO_MAX, echo - 1);
        if (item.getViewCount() >= 0 && item.getViewCount() < FEW_VIEWS) s -= FEW_VIEWS_PENALTY;
        String wanted = q.script();
        if (!text.script().equals(wanted)) {
            s = Math.max(0, s - FOREIGN_SCRIPT);
        } else {
            double share = text.foreignShare(wanted);
            if (share >= MIXED_SCRIPT_SHARE) s = Math.max(0, s - FOREIGN_SCRIPT * Math.min(1, share * 2));
        }
        return s;
    }

    static boolean exactPart(Query q, StreamInfoItem item) {
        if (item.getName() == null || q.plainQuery == null) return false;
        List<String> want = q.exactWords();
        if (want.isEmpty()) return false;
        for (List<String> part : parsed(item).parts()) if (part.equals(want)) return true;
        return false;
    }

    private static boolean has(Query q, List<String> words, String term) {
        if (contains(words, term)) return true;
        String alt = q.alias.get(term);
        return alt != null && contains(words, alt);
    }

    static int spread(Query q, List<String> words) {
        int any = gap(q, words);
        if (any < 0) return 0;
        int limit = farLimit(q);
        if (q.terms.size() > ORDER_TERMS) return any <= limit ? 0 : 2;
        int ordered = orderedGap(q, words);
        if (ordered >= 0 && ordered <= limit) return 0;
        return any <= limit ? 1 : 2;
    }

    private static boolean matches(Query q, String term, String word) {
        String alt = q.alias.get(term);
        return Query.wordMatches(term, word) || alt != null && Query.wordMatches(alt, word);
    }

    static int orderedGap(Query q, List<String> words) {
        int n = q.terms.size();
        if (n < 2 || !q.repeats.isEmpty() || words.size() > 200) return -1;
        int best = Integer.MAX_VALUE;
        for (int start = 0; start < words.size(); start++) {
            if (!matches(q, q.terms.get(0), words.get(start))) continue;
            int at = start;
            int t = 1;
            for (int i = start + 1; i < words.size() && t < n; i++) {
                if (matches(q, q.terms.get(t), words.get(i))) {
                    at = i;
                    t++;
                }
            }
            if (t < n) break;
            best = Math.min(best, at - start + 1);
        }
        return best == Integer.MAX_VALUE ? -1 : best - n;
    }

    public static int tier(Query q, StreamInfoItem item) {
        if (q.terms.isEmpty()) return 0;
        int covered = coverage(q, item);
        int missing = Math.max(0, q.terms.size() - covered);
        return missing * 3 + (missing == 0 ? spread(q, parsed(item).titleWords) : 0);
    }

    static int farLimit(Query q) {
        return FAR_GAP + 2 * Math.max(0, q.terms.size() - 2);
    }

    static int gap(Query q, List<String> words) {
        int n = q.terms.size();
        if (n < 2 || !q.repeats.isEmpty() || words.size() > 200) return -1;
        List<int[]> hits = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            String term = q.terms.get(t);
            String alt = q.alias.get(term);
            boolean any = false;
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (Query.wordMatches(term, w) || alt != null && Query.wordMatches(alt, w)) {
                    hits.add(new int[] {i, t});
                    any = true;
                }
            }
            if (!any) return -1;
        }
        hits.sort((a, b) -> a[0] - b[0]);
        int[] seen = new int[n];
        int have = 0;
        int best = Integer.MAX_VALUE;
        int left = 0;
        for (int[] h : hits) {
            if (seen[h[1]]++ == 0) have++;
            while (have == n) {
                int[] l = hits.get(left);
                best = Math.min(best, h[0] - l[0] + 1);
                if (--seen[l[1]] == 0) have--;
                left++;
            }
        }
        return Math.max(0, best - n);
    }

    private static boolean contains(List<String> words, String term) {
        for (String w : words) if (Query.wordMatches(term, w)) return true;
        return false;
    }

    private static boolean joined(List<String> words, String term, List<String> terms) {
        if (terms.size() < 2 || term.length() < 3) return false;
        for (String w : words) {
            if (w.length() > term.length() && w.contains(term) && madeOf(w, terms)) return true;
        }
        return false;
    }

    private static boolean madeOf(String word, List<String> terms) {
        if (word.isEmpty()) return true;
        for (String t : terms) {
            if (t.length() >= 2 && word.startsWith(t) && madeOf(word.substring(t.length()), terms)) return true;
        }
        return false;
    }

    private static final java.util.Set<String> TITLE_NOISE = new java.util.HashSet<>(java.util.Arrays.asList(
            "official", "video", "videos", "music", "audio", "lyrics", "lyric", "full", "hd", "4k", "8k", "hq", "uhd",
            "live", "new", "latest", "part", "episode", "ep", "ft", "feat", "vs", "remix", "mv", "trailer", "teaser",
            "clip", "song", "songs", "cover", "version", "edit", "shorts", "short", "best", "top", "vlog", "mix",
            "compilation", "original", "extended", "reaction", "review", "explained", "highlights", "season",
            "resmi", "klip", "sarki", "canli", "bolum", "tam", "yeni", "izle", "film", "dizi", "fragman",
            "youtube", "tiktok", "free", "watch", "you", "your", "this", "that", "from", "about", "what", "why",
            "is", "are", "was", "my", "me", "we", "it", "at", "by", "or", "be", "not", "all", "one", "day", "time"));

    public static List<String> keyTerms(List<String> terms) {
        List<String> out = new ArrayList<>();
        for (String t : terms) {
            if (t.length() < 3 || t.chars().allMatch(Character::isDigit) || TITLE_NOISE.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    static java.util.Map<String, String> numbered(List<String> words) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = 1; i < words.size(); i++) {
            String n = words.get(i);
            String w = words.get(i - 1);
            if (n.length() > 3 || !n.chars().allMatch(Character::isDigit)) continue;
            if (w.length() < 3 || w.chars().anyMatch(Character::isDigit) || TITLE_NOISE.contains(w) || Query.isStop(w)) continue;
            out.putIfAbsent(w, n);
        }
        return out;
    }

    private static String numberFor(java.util.Map<String, String> numbers, String word) {
        for (java.util.Map.Entry<String, String> e : numbers.entrySet()) if (sameWord(word, e.getKey())) return e.getValue();
        return null;
    }

    public static String relatedQuery(String title, String seed) {
        return relatedQuery(title, seed, Hints.NONE);
    }

    private static final int QUERY_TERMS = 3;

    public static String relatedQuery(String title, String seed, Hints hints) {
        Query q = Query.parse(title);
        List<String> keys = new ArrayList<>(keyTerms(q.terms));
        if (keys.isEmpty()) keys = new ArrayList<>(q.terms);
        for (String t : hints.tags) {
            if (keys.size() >= QUERY_TERMS) break;
            if (!keys.contains(t)) keys.add(t);
        }
        if (seed != null && !seed.trim().isEmpty()) {
            java.util.Set<String> wanted = new java.util.HashSet<>(Query.parse(seed).terms);
            List<String> first = new ArrayList<>();
            for (String k : keys) if (wanted.contains(k)) first.add(k);
            for (String k : keys) if (!wanted.contains(k)) first.add(k);
            keys = first;
        }
        if (keys.isEmpty()) return title.trim();
        java.util.Map<String, String> numbers = numbered(Query.words(Query.fold(title)));
        List<String> out = new ArrayList<>();
        for (String k : keys.subList(0, Math.min(5, keys.size()))) {
            out.add(k);
            String n = numberFor(numbers, k);
            if (n != null) out.add(n);
        }
        return String.join(" ", out);
    }

    private static final java.util.Set<String> ENDINGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "s", "es", "ler", "lar", "in", "nin", "un", "nun", "i", "u", "e", "a", "de", "da"));

    static boolean sameWord(String term, String word) {
        if (word.equals(term)) return true;
        String longer = word.length() > term.length() ? word : term;
        String shorter = longer == word ? term : word;
        return shorter.length() >= 4 && longer.startsWith(shorter) && ENDINGS.contains(longer.substring(shorter.length()));
    }

    private static final double NUMBER_MATCH = 3;
    private static final double NUMBER_CLASH = 6;

    public static List<StreamInfoItem> similar(String title, String channel, String selfUrl,
                                               List<StreamInfoItem> next, List<StreamInfoItem> search) {
        return similar(title, channel, selfUrl, next, search, false);
    }

    public static List<StreamInfoItem> similar(String title, String channel, String selfUrl,
                                               List<StreamInfoItem> next, List<StreamInfoItem> search,
                                               boolean trustNext) {
        return similar(title, channel, selfUrl, next, search, trustNext, 0);
    }

    public static List<StreamInfoItem> similar(String title, String channel, String selfUrl,
                                               List<StreamInfoItem> next, List<StreamInfoItem> search,
                                               boolean trustNext, int minimum) {
        return similar(title, channel, selfUrl, next, search, trustNext, minimum, null);
    }

    public static List<StreamInfoItem> similar(String title, String channel, String selfUrl,
                                               List<StreamInfoItem> next, List<StreamInfoItem> search,
                                               boolean trustNext, int minimum, List<StreamInfoItem> spare) {
        return similar(title, channel, selfUrl, next, search, trustNext, minimum, spare, Hints.NONE);
    }

    public static final class Hints {
        public static final Hints NONE = new Hints(Collections.<String>emptyList(), Collections.<String>emptyList());
        final List<String> tags;
        final List<String> about;

        Hints(List<String> tags, List<String> about) {
            this.tags = tags;
            this.about = about;
        }

        public boolean isEmpty() {
            return tags.isEmpty() && about.isEmpty();
        }

        @Override
        public String toString() {
            return "tags=" + tags + " about=" + about;
        }
    }

    private static final int MAX_TAG_TERMS = 12;
    private static final int MAX_ABOUT_TERMS = 8;
    private static final int ABOUT_LENGTH = 5;
    private static final int ABOUT_REPEATS = 2;
    private static final java.util.regex.Pattern HASHTAG = java.util.regex.Pattern.compile("#([\\p{L}\\p{N}_]{3,40})");
    private static final java.util.regex.Pattern LINK = java.util.regex.Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+|\\S+@\\S+|@[\\p{L}\\p{N}_.]+");
    private static final java.util.regex.Pattern CAMEL = java.util.regex.Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})|_");
    private static final java.util.Set<String> HINT_NOISE = new java.util.HashSet<>(java.util.Arrays.asList(
            "subscribe", "subscribers", "subscription", "follow", "instagram", "twitter", "facebook", "tiktok", "discord",
            "patreon", "twitch", "snapchat", "telegram", "whatsapp", "merch", "store", "shop", "sponsor", "sponsored",
            "channel", "channels", "like", "likes", "comment", "comments", "share", "link", "links", "business",
            "contact", "email", "mail", "website", "download", "copyright", "rights", "reserved", "credits", "credit",
            "thanks", "thank", "please", "social", "code", "check", "notification", "notifications", "bell", "support",
            "donate", "paypal", "playlist", "timestamps", "chapters", "intro", "outro", "inquiries", "enquiries",
            "abone", "olun", "takip", "edin", "begen", "yorum", "paylas", "kanal", "kanalima", "iletisim", "destek",
            "sponsorlu", "reklam", "bildirim", "linki", "linkler", "video", "videos", "more", "here", "below",
            "watch", "visit", "join", "click", "stream", "streams", "official", "music", "song",
            "every", "want", "need", "make", "makes", "made", "without", "same", "today", "find", "come", "along",
            "super", "really", "thing", "things", "going", "know", "also", "just", "into", "over", "after", "before",
            "first", "last", "some", "many", "much", "other", "them", "they", "their", "there", "these", "those",
            "which", "while", "will", "with", "would", "could", "should", "have", "been", "being", "here", "when",
            "where", "very", "only", "even", "well", "back", "still", "take", "takes", "used", "using", "help",
            "great", "good", "little", "right", "never", "always", "sure", "enough", "around", "through", "think",
            "perfect", "perfectly", "icin", "gibi", "daha", "cok", "olan", "olarak", "ama", "kadar", "sonra", "once",
            "onceden", "icinde", "buyuk", "kucuk", "nasil", "neden", "nerde", "nerede", "oldu", "olur", "son",
            "dakika", "simdi", "bugun", "herkes", "burada", "sizin", "bizim", "olsun", "diye", "tum", "her",
            "porn", "porno", "xxx", "1080p", "720p"));

    public static Hints hints(String title, List<String> tags, CharSequence description) {
        List<String> own = keyTerms(Query.parse(title == null ? "" : title).terms);
        String whole = compact(Query.fold(title == null ? "" : title));
        List<String> tagTerms = new ArrayList<>();
        if (tags != null) {
            for (String t : tags) {
                if (t != null) addTerms(tagTerms, own, whole, t, MAX_TAG_TERMS);
            }
        }
        String text = description == null ? "" : description.toString();
        java.util.regex.Matcher h = HASHTAG.matcher(text);
        while (h.find()) addTerms(tagTerms, own, whole, CAMEL.matcher(h.group(1)).replaceAll(" "), MAX_TAG_TERMS);
        text = HASHTAG.matcher(LINK.matcher(text).replaceAll(" ")).replaceAll(" ");
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        int at = 0;
        for (String w : keyTerms(Query.words(Query.fold(text)))) {
            at++;
            if (w.length() < ABOUT_LENGTH || Query.isStop(w) || HINT_NOISE.contains(w) || whole.contains(w)) continue;
            if (matchesAny(own, w) || matchesAny(tagTerms, w)) continue;
            int[] c = counts.computeIfAbsent(w, k -> new int[] {0, 0});
            if (c[0]++ == 0) c[1] = at;
        }
        List<java.util.Map.Entry<String, int[]>> ranked = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] >= ABOUT_REPEATS) ranked.add(e);
        }
        Collections.sort(ranked, (x, y) -> x.getValue()[0] != y.getValue()[0] ? y.getValue()[0] - x.getValue()[0]
                : x.getValue()[1] - y.getValue()[1]);
        List<String> about = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : ranked) {
            if (about.size() >= MAX_ABOUT_TERMS) break;
            if (!matchesAny(about, e.getKey())) about.add(e.getKey());
        }
        if (tagTerms.isEmpty() && about.isEmpty()) return Hints.NONE;
        return new Hints(Collections.unmodifiableList(tagTerms), Collections.unmodifiableList(about));
    }

    private static void addTerms(List<String> out, List<String> own, String whole, String text, int max) {
        for (String w : keyTerms(Query.words(Query.fold(text)))) {
            if (out.size() >= max) return;
            if (Query.isStop(w) || HINT_NOISE.contains(w) || whole.contains(w)) continue;
            if (matchesAny(own, w) || matchesAny(out, w)) continue;
            out.add(w);
        }
    }

    private static boolean matchesAny(List<String> terms, String w) {
        for (String t : terms) if (sameWord(t, w)) return true;
        return false;
    }

    private static int hits(List<String> terms, List<String> words) {
        int n = 0;
        for (String t : terms) {
            for (String w : words) {
                if (sameWord(t, w)) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    private static final double TAG_PASS = 0.5;
    private static final double TAG_PASS_CAP = 1.0;
    private static final double TAG_SCORE = 1.5;
    private static final double ABOUT_SCORE = 0.75;
    private static final double HINT_SCORE_CAP = 4.5;

    public static List<StreamInfoItem> similar(String title, String channel, String selfUrl,
                                               List<StreamInfoItem> next, List<StreamInfoItem> search,
                                               boolean trustNext, int minimum, List<StreamInfoItem> spare,
                                               Hints hints) {
        List<String> keys = keyTerms(Query.parse(title).terms);
        int needed = keys.size() >= 3 ? 2 : 1;
        java.util.Map<String, String> numbers = numbered(Query.words(Query.fold(title)));
        String ownChannel = channelNamed(channel, title) ? Query.fold(channel) : "";
        List<Scored> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(selfUrl);
        int order = 0;
        List<StreamInfoItem> weak = new ArrayList<>();
        List<Scored> loose = new ArrayList<>();
        List<List<StreamInfoItem>> sources = java.util.Arrays.asList(next, search);
        double[] weights = {1.0, 0.8};
        for (int src = 0; src < sources.size(); src++) {
            List<StreamInfoItem> list = sources.get(src);
            boolean trusted = src == 0 && trustNext;
            for (int rank = 0; rank < list.size(); rank++) {
                StreamInfoItem item = list.get(rank);
                if (!seen.add(item.getUrl()) || NotInterested.hides(item)) continue;
                List<String> words = parsed(item).titleWords;
                int overlap = 0;
                for (String t : keys) {
                    for (String w : words) {
                        if (sameWord(t, w)) {
                            overlap++;
                            break;
                        }
                    }
                }
                int tagHits = hits(hints.tags, words);
                int aboutHits = hits(hints.about, words);
                double strength = overlap + Math.min(TAG_PASS_CAP, tagHits * TAG_PASS);
                double hinted = Math.min(HINT_SCORE_CAP, tagHits * TAG_SCORE + aboutHits * ABOUT_SCORE);
                boolean sameChannel = !ownChannel.isEmpty()
                        && ownChannel.equals(Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName()));
                if (!sameChannel && strength < (trusted ? 1 : needed)) {
                    if (tagBased(item)) weak.add(item);
                    else if (src == 1 && (overlap > 0 || tagHits > 0)) {
                        loose.add(new Scored(item, overlap * 3 + hinted + 4.0 * weights[src] / (1 + rank * 0.3), order++));
                    }
                    continue;
                }
                double s = overlap * 3 + hinted + (sameChannel ? 4 : 0) + 4.0 * weights[src] / (1 + rank * 0.3);
                if (!numbers.isEmpty()) {
                    java.util.Map<String, String> theirs = numbered(words);
                    for (java.util.Map.Entry<String, String> e : numbers.entrySet()) {
                        String n = numberFor(theirs, e.getKey());
                        if (n == null) continue;
                        s += n.equals(e.getValue()) ? NUMBER_MATCH : -NUMBER_CLASH;
                    }
                }
                long views = item.getViewCount();
                if (views > 0) s += Math.log10(views + 1) * 0.25;
                all.add(new Scored(item, s, order++));
            }
        }
        Collections.sort(all, (a, b) -> a.covered != b.covered ? b.covered - a.covered
                : a.score != b.score ? Double.compare(b.score, a.score) : a.order - b.order);
        List<StreamInfoItem> out = new ArrayList<>(all.size() + loose.size());
        for (Scored s : all) out.add(s.item);
        Collections.sort(loose, (a, b) -> a.score != b.score ? Double.compare(b.score, a.score) : a.order - b.order);
        for (Scored s : loose) if (sameScript(title, s.item.getName())) out.add(s.item);
        if (spare != null) {
            for (StreamInfoItem w : weak) if (sameScript(title, w.getName())) spare.add(w);
            return out;
        }
        for (int i = 0; out.size() < minimum && i < weak.size(); i++) {
            if (sameScript(title, weak.get(i).getName())) out.add(weak.get(i));
        }
        return out;
    }

    public static boolean channelNamed(String channel, String title) {
        if (channel == null || title == null) return false;
        String name = Query.fold(channel.replaceFirst("(?i)\\s*-\\s*topic\\s*$", ""));
        List<String> parts = keyTerms(Query.words(name));
        if (parts.isEmpty()) return false;
        List<String> words = Query.words(Query.fold(title));
        for (String p : parts) {
            for (String w : words) if (sameWord(p, w)) return true;
        }
        String joined = compact(name);
        return joined.length() >= 4 && compact(Query.fold(title)).contains(joined);
    }

    public static boolean nearTopic(String title, String channel, StreamInfoItem item, List<StreamInfoItem> shown) {
        String itemChannel = Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName());
        if (!itemChannel.isEmpty() && channelNamed(item.getUploaderName(), title)) {
            if (itemChannel.equals(Query.fold(channel == null ? "" : channel))) return true;
            for (StreamInfoItem s : shown) {
                if (itemChannel.equals(Query.fold(s.getUploaderName() == null ? "" : s.getUploaderName()))) return true;
            }
        }
        java.util.Set<String> topic = new java.util.HashSet<>(keyTerms(Query.parse(title == null ? "" : title).terms));
        java.util.Set<String> channels = new java.util.HashSet<>(Query.words(Query.fold(channel == null ? "" : channel)));
        for (StreamInfoItem s : shown) {
            channels.addAll(Query.words(Query.fold(s.getUploaderName() == null ? "" : s.getUploaderName())));
        }
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (StreamInfoItem s : shown) {
            for (String w : new java.util.HashSet<>(keyTerms(Query.words(Query.fold(s.getName() == null ? "" : s.getName()))))) {
                if (!Query.isStop(w) && !channels.contains(w)) seen.merge(w, 1, Integer::sum);
            }
        }
        List<String> words = parsed(item).titleWords;
        for (String t : topic) {
            for (String w : words) if (sameWord(t, w)) return true;
        }
        int echoes = 0;
        for (java.util.Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() < 2 || topic.contains(e.getKey())) continue;
            for (String w : words) {
                if (sameWord(e.getKey(), w)) {
                    if (++echoes >= TOPIC_ECHOES) return true;
                    break;
                }
            }
        }
        return false;
    }

    private static final int TOPIC_ECHOES = 2;

    private static boolean tagBased(StreamInfoItem item) {
        com.orvid.ext.ExtSource ext = com.orvid.ext.Registry.forUrl(item.getUrl());
        return ext != null && ext.adult;
    }

    public static boolean sameScript(String title, String other) {
        if (title == null || other == null) return true;
        java.util.Set<String> a = scripts(title);
        java.util.Set<String> b = scripts(other);
        if (a.isEmpty() || b.isEmpty()) return true;
        for (String x : a) if (b.contains(x)) return true;
        return false;
    }

    private static final double SCRIPT_SHARE = 0.25;

    static java.util.Set<String> scripts(String text) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            String group = sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA ? "cjk" : sc.name();
            counts.merge(group, 1, Integer::sum);
            total++;
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= SCRIPT_SHARE * total) out.add(e.getKey());
        }
        return out;
    }

    public static List<int[]> highlights(Query q, String title) {
        List<int[]> spans = new ArrayList<>();
        if (title == null || q.terms.isEmpty()) return spans;
        String folded = Query.fold(title);
        if (folded.length() != title.length()) return spans;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+").matcher(folded);
        while (m.find()) {
            for (String t : q.terms) {
                if (Query.wordMatches(t, m.group())) {
                    spans.add(new int[] {m.start(), m.end()});
                    break;
                }
            }
        }
        return spans;
    }
}
