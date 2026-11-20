import com.orvid.ext.ExtSource;
import com.orvid.ext.Registry;
import com.orvid.search.Query;
import com.orvid.search.Ranker;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RankEval {

    static Method coverage;
    static Method mentions;
    static Method dupKey;
    static int echoTop20;
    static int ytTop20;
    static int distinctTop20;
    static int fewTop20;
    static int echoCount;
    static long echoPosSum;

    static int coverage(Query q, StreamInfoItem item) throws Exception {
        return (Integer) coverage.invoke(null, q, item);
    }

    static boolean mentions(Query q, StreamInfoItem item) throws Exception {
        return (Boolean) mentions.invoke(null, q, item);
    }

    static String queryOf(File f) {
        String name = f.getName().replace(".ser", "");
        if (name.startsWith("bar___man")) return "barış manço";
        return name.replace('_', ' ');
    }

    static final int[] DEPTHS = {10, 20, 60};
    static final String RELAX = System.getenv().getOrDefault("RELAX", "none");
    static final String SETS = System.getenv().getOrDefault("SETS", "mix,amix");
    static final int VERBOSE_ROWS = Integer.parseInt(System.getenv().getOrDefault("ROWS", "20"));

    static List<StreamInfoItem> relax(Query q, String key, Map<String, List<StreamInfoItem>> got) throws Exception {
        List<StreamInfoItem> joined = got.get(key + "~");
        if (joined != null && RELAX.equals("strict")) {
            List<StreamInfoItem> kept = new ArrayList<>();
            for (StreamInfoItem it : joined) if (coverage(q, it) >= Math.max(1, q.terms.size() - 1)) kept.add(it);
            joined = kept;
        }
        if (joined != null && !joined.isEmpty()) return joined;
        if (RELAX.equals("stop")) return null;
        if (RELAX.equals("strict")) {
            if (q.terms.size() < 3) return null;
            List<StreamInfoItem> best = null;
            for (String t : q.terms) {
                List<StreamInfoItem> l = got.get(key + "~-" + t);
                if (l == null) continue;
                List<StreamInfoItem> kept = new ArrayList<>();
                for (StreamInfoItem it : l) if (coverage(q, it) >= q.terms.size() - 1) kept.add(it);
                if (best == null || kept.size() > best.size()) best = kept;
            }
            return best;
        }
        Map<String, Integer> hits = new HashMap<>();
        for (String t : q.terms) hits.put(t, 0);
        for (Map.Entry<String, List<StreamInfoItem>> e : got.entrySet()) {
            if (e.getKey().contains("~")) continue;
            for (StreamInfoItem it : e.getValue()) {
                for (String t : q.terms) {
                    if (covers(Query.parse(t), it)) hits.merge(t, 1, Integer::sum);
                }
            }
        }
        String drop = null;
        for (String t : q.terms) {
            if (drop == null) drop = t;
            else if (RELAX.equals("rare") ? hits.get(t) < hits.get(drop) : hits.get(t) > hits.get(drop)) drop = t;
        }
        return drop == null ? null : got.get(key + "~-" + drop);
    }

    static java.util.Set<String> wave(Query q, List<ExtSource> registry, Map<String, List<StreamInfoItem>> got) {
        String size = System.getenv("WAVE");
        if (size == null) return null;
        boolean topic = "topic".equals(System.getenv("WAVEORDER"));
        List<ExtSource> order = new ArrayList<>();
        for (ExtSource s : registry) if (got.containsKey(s.key)) order.add(s);
        order.sort((a, b) -> {
            int pa = topic ? a.priority(q) : (((com.orvid.search.Source) a).script != null ? 2 : a.adult ? 5 : 4);
            int pb = topic ? b.priority(q) : (((com.orvid.search.Source) b).script != null ? 2 : b.adult ? 5 : 4);
            if (pa != pb) return Integer.compare(pa, pb);
            return Double.compare(b.weight, a.weight);
        });
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < Math.min(Integer.parseInt(size), order.size()); i++) out.add(order.get(i).key);
        if (System.getenv("WAVELIST") != null) System.out.println("wave " + q.raw + ": " + out);
        return out;
    }

    static boolean covers(Query single, StreamInfoItem it) throws Exception {
        return mentions(single, it);
    }

    public static void main(String[] args) throws Exception {
        coverage = Ranker.class.getDeclaredMethod("coverage", Query.class, StreamInfoItem.class);
        coverage.setAccessible(true);
        mentions = Ranker.class.getDeclaredMethod("mentions", Query.class, StreamInfoItem.class);
        mentions.setAccessible(true);
        dupKey = Ranker.class.getDeclaredMethod("duplicateKey", String.class);
        dupKey.setAccessible(true);
        String base = System.getenv("PREFIX") + "/tmp/orvid-probe/";
        boolean verbose = false;
        Map<String, String> typos = new java.util.LinkedHashMap<>();
        for (String arg : args) {
            if (arg.equals("-v")) verbose = true;
            else if (arg.contains("@")) typos.put(arg.substring(0, arg.indexOf('@')), arg.substring(arg.indexOf('@') + 1));
        }
        int[] offSum = new int[DEPTHS.length];
        int[] fullSum = new int[DEPTHS.length];
        int queries = 0;
        long totalSum = 0;
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%-34s %5s %6s %s%n", "query", "total", "strict", "off@10/20/60  full@10/20/60  firstFull"));
        for (String set : SETS.split(",")) {
            File[] files = new File(base + set).listFiles((d, n) -> n.endsWith(".ser"));
            if (!typos.isEmpty()) {
                if (!set.equals("mix")) continue;
                List<File> picked = new ArrayList<>();
                for (String snap : typos.values()) picked.add(new File(base + set, snap.replace(' ', '_') + ".ser"));
                files = picked.toArray(new File[0]);
            }
            if (files == null) continue;
            if (typos.isEmpty()) Arrays.sort(files);
            Map<String, Double> weightOf = new HashMap<>();
            Map<String, Boolean> adultKey = new HashMap<>();
            File root = set.equals("amix") ? new File(base + "adultroot") : set.equals("snap") ? new File(base + "genroot")
                    : set.equals("asnap") ? new File(base + "adroot") : null;
            List<ExtSource> registry = new ArrayList<>();
            if (root != null) {
                Registry.reload(root);
                registry.addAll(Registry.sources());
                for (ExtSource s : Registry.sources()) {
                    weightOf.put(s.key, s.weight);
                    adultKey.put(s.key, s.adult);
                }
            }
            List<String> typoQueries = new ArrayList<>(typos.keySet());
            for (int fi = 0; fi < files.length; fi++) {
                File f = files[fi];
                Object data;
                try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                    data = in.readObject();
                }
                List<List<StreamInfoItem>> lists = new ArrayList<>();
                List<Double> w = new ArrayList<>();
                List<Boolean> tg = new ArrayList<>();
                java.util.Set<StreamInfoItem> relaxedItems = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                int relaxedLists = 0;
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, List<StreamInfoItem>> got = (Map<String, List<StreamInfoItem>>) data;
                    String[] core = {"youtube", "dailymotion", "vk"};
                    double[] coreWeight = {1.0, 0.7, 0.8};
                    for (int i = 0; i < 3; i++) {
                        lists.add(got.containsKey(core[i]) ? got.get(core[i]) : new ArrayList<>());
                        w.add(coreWeight[i]);
                        tg.add(false);
                    }
                    Query pq = Query.parse(typos.isEmpty() ? queryOf(f) : typoQueries.get(fi));
                    java.util.Set<String> wave = wave(pq, registry, got);
                    for (Map.Entry<String, List<StreamInfoItem>> e : got.entrySet()) {
                        String key = e.getKey();
                        if (key.contains("~") || Arrays.asList(core).contains(key)) continue;
                        if (wave != null && !wave.contains(key)) continue;
                        List<StreamInfoItem> list = e.getValue();
                        if (list.isEmpty() && !RELAX.equals("none")) {
                            List<StreamInfoItem> relaxed = relax(pq, key, got);
                            if (relaxed != null && !relaxed.isEmpty()) {
                                list = relaxed;
                                relaxedItems.addAll(relaxed);
                                relaxedLists++;
                            }
                        }
                        lists.add(list);
                        w.add(weightOf.getOrDefault(key, 0.6));
                        tg.add(adultKey.getOrDefault(key, set.equals("amix")));
                    }
                } else {
                    @SuppressWarnings("unchecked")
                    List<List<StreamInfoItem>> got = (List<List<StreamInfoItem>>) data;
                    for (int i = 0; i < got.size(); i++) {
                        lists.add(got.get(i));
                        w.add(i == 0 ? 1.0 : 0.6);
                        tg.add(false);
                    }
                }
                double[] wa = new double[w.size()];
                boolean[] ta = new boolean[w.size()];
                for (int i = 0; i < wa.length; i++) {
                    wa[i] = w.get(i);
                    ta[i] = tg.get(i);
                }
                String text = typos.isEmpty() ? queryOf(f) : typoQueries.get(fi);
                Query q = Query.parse(text);
                java.lang.reflect.Field seed = Ranker.class.getDeclaredField("seed");
                seed.setAccessible(true);
                seed.setLong(null, 42L);
                List<StreamInfoItem> r = Ranker.rank(q, lists, wa, ta);
                Map<StreamInfoItem, Integer> listOf = new java.util.IdentityHashMap<>();
                for (int li = 0; li < lists.size(); li++) for (StreamInfoItem it : lists.get(li)) listOf.put(it, li);
                java.util.Set<Integer> distinct = new java.util.HashSet<>();
                for (int i = 0; i < Math.min(20, r.size()); i++) {
                    Integer li = listOf.get(r.get(i));
                    if (li != null && li == 0) ytTop20++;
                    if (li != null) distinct.add(li);
                    long v = r.get(i).getViewCount();
                    if (v >= 0 && v < 1000) fewTop20++;
                }
                distinctTop20 += distinct.size();
                Map<String, java.util.Set<Integer>> keySources = new HashMap<>();
                for (int li = 0; li < lists.size(); li++) {
                    for (StreamInfoItem it : lists.get(li)) {
                        String k = (String) dupKey.invoke(null, it.getName());
                        if (k.length() >= 12) keySources.computeIfAbsent(k, x -> new java.util.HashSet<>()).add(li);
                    }
                }
                for (int i = 0; i < r.size(); i++) {
                    String k = (String) dupKey.invoke(null, r.get(i).getName());
                    java.util.Set<Integer> from = keySources.get(k);
                    if (from == null || from.size() < 2) continue;
                    echoCount++;
                    echoPosSum += i + 1;
                    if (i < 20) echoTop20++;
                    if (System.getenv("ECHOLIST") != null) out.append(String.format(Locale.ROOT, "      echo %4d %s src=%d strict=%b %s%n", i + 1, mentions(q, r.get(i)) ? (coverage(q, r.get(i)) >= q.terms.size() ? "=" : "~") : "x", from.size(), i < Ranker.Ranked.strict(r), r.get(i).getName()));
                }
                int need = q.terms.size();
                int[] off = new int[DEPTHS.length];
                int[] full = new int[DEPTHS.length];
                int firstFull = -1;
                for (int i = 0; i < r.size(); i++) {
                    StreamInfoItem it = r.get(i);
                    boolean isFull = need > 0 && coverage(q, it) >= need;
                    boolean isOff = !mentions(q, it);
                    if (isFull && firstFull < 0) firstFull = i + 1;
                    for (int d = 0; d < DEPTHS.length; d++) {
                        if (i >= DEPTHS[d]) continue;
                        if (isOff) off[d]++;
                        if (isFull) full[d]++;
                    }
                }
                for (int d = 0; d < DEPTHS.length; d++) {
                    offSum[d] += off[d];
                    fullSum[d] += full[d];
                }
                totalSum += r.size();
                queries++;
                out.append(String.format(Locale.ROOT, "%-34s %5d %6d %3d %3d %3d     %3d %3d %3d     %3d%n",
                        set.charAt(0) + ":" + (text.length() > 30 ? text.substring(0, 30) : text), r.size(),
                        Ranker.Ranked.strict(r), off[0], off[1], off[2], full[0], full[1], full[2], firstFull));
                if (!q.alias.isEmpty()) out.append("      alias ").append(q.alias).append('\n');
                if (relaxedLists > 0) {
                    int kept = 0, first = -1, mention = 0;
                    for (int i = 0; i < r.size(); i++) {
                        if (!relaxedItems.contains(r.get(i))) continue;
                        kept++;
                        if (mentions(q, r.get(i))) mention++;
                        if (first < 0) first = i + 1;
                    }
                    out.append(String.format(Locale.ROOT, "      relaxed lists=%d items=%d kept=%d mentioning=%d firstAt=%d%n",
                            relaxedLists, relaxedItems.size(), kept, mention, first));
                }
                Map<StreamInfoItem, Integer> listOfVerbose = new java.util.IdentityHashMap<>();
                for (int li = 0; li < lists.size(); li++) for (StreamInfoItem it : lists.get(li)) listOfVerbose.put(it, li);
                if (verbose) {
                    for (int i = 0; i < Math.min(VERBOSE_ROWS, r.size()); i++) {
                        StreamInfoItem it = r.get(i);
                        out.append(String.format(Locale.ROOT, "      %2d %s v=%d s=%s %s%n", i + 1,
                                (mentions(q, it) ? (coverage(q, it) >= need ? "=" : "~") : "x") + (relaxedItems.contains(it) ? "r" : " "), it.getViewCount(), listOfVerbose.get(it), it.getName()));
                    }
                }
            }
        }
        out.append(String.format(Locale.ROOT, "%-34s %5d %6s %3d %3d %3d     %3d %3d %3d%n", "TOTAL " + queries, totalSum, "",
                offSum[0], offSum[1], offSum[2], fullSum[0], fullSum[1], fullSum[2]));
        out.append(String.format(Locale.ROOT, "top20 youtube=%d distinctSources=%d under1000views=%d%n", ytTop20, distinctTop20, fewTop20));
        out.append(String.format(Locale.ROOT, "echo items=%d inTop20=%d meanPos=%.1f%n", echoCount, echoTop20, echoPosSum / (double) Math.max(1, echoCount)));
        System.out.print(out);
        System.exit(0);
    }
}
