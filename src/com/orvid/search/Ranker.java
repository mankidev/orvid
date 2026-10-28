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
    private static final double POPULARITY = 1.5;
    private static final double FOREIGN_SCRIPT = 8.0;
    private static final double MIXED_SCRIPT_SHARE = 0.15;
    private static final double SOURCE_FLOOR = 0.5;
    private static final long FEW_VIEWS = 1000;
    private static final double FEW_VIEWS_PENALTY = 3.0;
    private static final double EXACT_CHANNEL = 6.0;

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
        Context ctx = context(q, blend(sources, 8));
        vote(q, ctx, sources, weights);
        anchorPerSource(q, ctx, sources);
        popularity(ctx, sources);
        List<Scored> all = new ArrayList<>();
        int[] strictBySource = new int[sources.size()];
        List<List<Scored>> looseBySource = new ArrayList<>();
        for (int k = 0; k < sources.size(); k++) looseBySource.add(new ArrayList<Scored>());
        int order = 0;
        for (int src = 0; src < sources.size(); src++) {
            List<StreamInfoItem> list = sources.get(src);
            boolean literal = weights[src] < 1 && !(tagged != null && src < tagged.length && tagged[src]);
            boolean strictOnly = literal && (!ctx.anchored.isEmpty() || guessed(q, list));
            for (int rank = 0; rank < list.size(); rank++) {
                StreamInfoItem item = list.get(rank);
                double s = score(q, ctx, item, rank, weights[src]);
                if (strictOnly && !mentions(q, item)) s = Double.NEGATIVE_INFINITY;
                boolean partial = literal && q.terms.size() > 1 && mentions(q, item) && coverage(q, item) < required(q);
                if (literal && s >= 0 && (!mentions(q, item) || partial)) s = LOOSE;
                com.orvid.app.DevInfo.rank(item, src, rank, s);
                if (s >= 0) {
                    Scored kept = new Scored(item, s * jitter(item), order++);
                    kept.covered = coverage(q, item);
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
                : a.score != b.score ? Double.compare(b.score, a.score) : a.order - b.order);
        List<StreamInfoItem> out = diversify(all);
        List<Scored> loose = new ArrayList<>();
        for (int k = 0; k < sources.size(); k++) {
            boolean primary = weights[k] >= 1;
            boolean byTags = tagged != null && k < tagged.length && tagged[k];
            if (strictBySource[k] < MIN_STRICT && (byTags || (primary && out.size() < MIN_STRICT))) {
                loose.addAll(looseBySource.get(k));
            }
        }
        Collections.sort(loose, (a, b) -> a.order - b.order);
        for (Scored s : loose) out.add(s.item);
        List<StreamInfoItem> head = collapse(out);
        java.util.Set<StreamInfoItem> taken = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        taken.addAll(out);
        List<StreamInfoItem> withTail = new ArrayList<>(head);
        for (int r = 0; ; r++) {
            boolean more = false;
            for (List<Scored> list : looseBySource) {
                if (r >= list.size()) continue;
                more = true;
                if (!list.get(r).partial && !taken.contains(list.get(r).item)) withTail.add(list.get(r).item);
            }
            if (!more) break;
        }
        return new Ranked(collapse(withTail), head.size());
    }

    private static final int GUESS_MIN = 4;
    private static final double GUESS_SHARE = 0.3;

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

    static boolean vouched(StreamInfoItem item) {
        return VOUCHED.contains(item);
    }

    public static boolean covers(Query q, StreamInfoItem item) {
        return !q.terms.isEmpty() && coverage(q, item) >= q.terms.size();
    }

    public static boolean covers(Query q, String title) {
        if (q.terms.isEmpty()) return false;
        List<String> words = Query.words(Query.fold(title == null ? "" : title));
        for (String t : q.terms) if (!contains(words, t)) return false;
        return true;
    }

    static int coverage(Query q, StreamInfoItem item) {
        if (vouched(item)) return q.terms.size();
        List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
        List<String> channel = Query.words(Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName()));
        int n = 0;
        for (String t : q.terms) {
            if (contains(words, t) || contains(channel, t) || Query.acronym(t, words) || Source.about(t, item)) n++;
        }
        return n;
    }

    static boolean mentions(Query q, StreamInfoItem item) {
        if (q.terms.isEmpty() || vouched(item)) return true;
        List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
        List<String> channel = Query.words(Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName()));
        for (String t : q.terms) {
            if (contains(words, t) || contains(channel, t) || Query.acronym(t, words) || Source.about(t, item)) return true;
        }
        return false;
    }

    static void popularity(Context ctx, List<List<StreamInfoItem>> sources) {
        List<Double> logs = new ArrayList<>();
        for (List<StreamInfoItem> list : sources) {
            for (StreamInfoItem item : list) {
                if (item.getViewCount() < 0) continue;
                double log = Math.log10(item.getViewCount() + 1);
                ctx.popularity.put(item, log);
                logs.add(log);
            }
        }
        if (logs.isEmpty()) return;
        Collections.sort(logs);
        ctx.unknownPopularity = logs.get(logs.size() / 2);
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
            String key = duplicateKey(item.getName());
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

    static List<StreamInfoItem> diversify(List<Scored> sorted) {
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
                        if (s.item.getServiceId() != a && s.covered >= pool.get(0).covered && s.score >= best * 0.7 - 1) {
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
        String channel = item.getUploaderName();
        return item.getServiceId() + "/" + (channel == null || channel.isEmpty() ? item.getUrl() : Query.fold(channel));
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
                    List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
                    List<String> channel = Query.words(Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName()));
                    if (contains(words, term) || contains(channel, term) || Query.acronym(term, words)) hit++;
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
    private static final double YOUTUBE_VOTE = 2.0;
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
                for (String word : new java.util.HashSet<>(keyTerms(Query.words(Query.fold(item.getName() == null ? "" : item.getName()))))) {
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
            List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
            List<String> channel = Query.words(Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName()));
            boolean any = false;
            for (int t = 0; t < q.terms.size(); t++) {
                String term = q.terms.get(t);
                if (contains(words, term) || contains(channel, term) || Query.acronym(term, words)) {
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
        String title = Query.fold(item.getName() == null ? "" : item.getName());
        String channel = Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName());
        List<String> titleWords = Query.words(title);
        List<String> channelWords = Query.words(channel);

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
            boolean inTitle = contains(titleWords, t) || Query.acronym(t, titleWords);
            boolean inChannel = contains(channelWords, t) || !inTitle && (vouched || Source.about(t, item));
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
        if (!q.terms.isEmpty()) {
            int need = q.anyOf ? 1 : Math.max(1, (ctx.anchored.size() + 1) / 2);
            boolean trusted = ctx.trustedChannels.contains(channel);
            if (!ctx.anchored.isEmpty() && anchoredMatched < need && !trusted && topicHits < 2) {
                return LOOSE - (matched * 0.1);
            }
            if (matched == q.terms.size()) s += 3;
            String joined = String.join(" ", q.terms);
            if (q.terms.size() > 1 && title.contains(joined)) s += 4;
        }
        boolean offTopic = q.terms.size() <= AMBIGUOUS_TERMS && !ctx.topicShare.isEmpty() && topicHits == 0 && strongest >= STRONG_TOPIC;
        if (!q.terms.isEmpty() && !offTopic && compact(channel).equals(String.join("", q.terms))) s += EXACT_CHANNEL;
        if (offTopic) s -= OFF_TOPIC;
        s += q.phrases.size() * 4;
        s += 6.0 * weight / (1 + rank * 0.35);
        Double popular = ctx.popularity.get(item);
        s += (popular != null ? popular : ctx.unknownPopularity) * POPULARITY;
        s *= SOURCE_FLOOR + (1 - SOURCE_FLOOR) * weight;
        if (item.getViewCount() >= 0 && item.getViewCount() < FEW_VIEWS) s -= FEW_VIEWS_PENALTY;
        String name = item.getName() == null ? "" : item.getName();
        if (!Query.script(name).equals(q.script())) {
            s = Math.max(0, s - FOREIGN_SCRIPT);
        } else {
            double share = Query.foreignShare(name, q.script());
            if (share >= MIXED_SCRIPT_SHARE) s = Math.max(0, s - FOREIGN_SCRIPT * Math.min(1, share * 2));
        }
        return s;
    }

    private static boolean contains(List<String> words, String term) {
        for (String w : words) if (Query.wordMatches(term, w)) return true;
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

    private static final java.util.Set<String> ENDINGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "s", "es", "ler", "lar", "in", "nin", "un", "nun", "i", "u", "e", "a", "de", "da"));

    static boolean sameWord(String term, String word) {
        if (word.equals(term)) return true;
        String longer = word.length() > term.length() ? word : term;
        String shorter = longer == word ? term : word;
        return shorter.length() >= 4 && longer.startsWith(shorter) && ENDINGS.contains(longer.substring(shorter.length()));
    }

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
        List<String> keys = keyTerms(Query.parse(title).terms);
        int needed = keys.size() >= 3 ? 2 : 1;
        String ownChannel = channelNamed(channel, title) ? Query.fold(channel) : "";
        List<Scored> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(selfUrl);
        int order = 0;
        List<StreamInfoItem> weak = new ArrayList<>();
        List<List<StreamInfoItem>> sources = java.util.Arrays.asList(next, search);
        double[] weights = {1.0, 0.8};
        for (int src = 0; src < sources.size(); src++) {
            List<StreamInfoItem> list = sources.get(src);
            boolean trusted = src == 0 && trustNext;
            for (int rank = 0; rank < list.size(); rank++) {
                StreamInfoItem item = list.get(rank);
                if (!seen.add(item.getUrl()) || NotInterested.hides(item)) continue;
                List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
                int overlap = 0;
                for (String t : keys) {
                    for (String w : words) {
                        if (sameWord(t, w)) {
                            overlap++;
                            break;
                        }
                    }
                }
                boolean sameChannel = !ownChannel.isEmpty()
                        && ownChannel.equals(Query.fold(item.getUploaderName() == null ? "" : item.getUploaderName()));
                if (!sameChannel && overlap < (trusted ? 1 : needed)) {
                    if (src == 0 || tagBased(item)) weak.add(item);
                    continue;
                }
                double s = overlap * 3 + (sameChannel ? 4 : 0) + 4.0 * weights[src] / (1 + rank * 0.3);
                long views = item.getViewCount();
                if (views > 0) s += Math.log10(views + 1) * 0.25;
                all.add(new Scored(item, s, order++));
            }
        }
        Collections.sort(all, (a, b) -> a.covered != b.covered ? b.covered - a.covered
                : a.score != b.score ? Double.compare(b.score, a.score) : a.order - b.order);
        List<StreamInfoItem> out = new ArrayList<>(all.size());
        for (Scored s : all) out.add(s.item);
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
        for (java.util.Map.Entry<String, Integer> e : seen.entrySet()) if (e.getValue() >= 2) topic.add(e.getKey());
        List<String> words = Query.words(Query.fold(item.getName() == null ? "" : item.getName()));
        for (String t : topic) {
            for (String w : words) if (sameWord(t, w)) return true;
        }
        return false;
    }

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
