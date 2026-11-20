import com.orvid.adult.HtmlTube;
import com.orvid.ext.ExtSource;
import com.orvid.ext.Registry;
import com.orvid.net.Fetch;
import com.orvid.search.Query;
import com.orvid.search.Ranker;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TagProbe {

    static Set<String> urls(List<StreamInfoItem> items) {
        Set<String> out = new HashSet<>();
        for (StreamInfoItem it : items) out.add(it.getUrl());
        return out;
    }

    static int mentioning(String term, List<StreamInfoItem> items) {
        int n = 0;
        for (StreamInfoItem it : items) if (Ranker.covers(Query.parse(term), it.getName())) n++;
        return n;
    }

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        int count = Integer.parseInt(a[1]);
        Fetch.setTorMode(true);
        File dir = new File(System.getenv("PREFIX") + "/tmp/orvid-tor");
        dir.mkdirs();
        com.orvid.tor.Tor.get().start(dir, System.getenv("PREFIX") + "/tmp/torx/jni/arm64-v8a/libtor.so", (int) ProcessHandle.current().pid());
        System.out.println("tor " + com.orvid.tor.Tor.get().awaitReady(180000));
        Thread.sleep(3000);
        Registry.reload(root);
        Field htmlField = ExtSource.class.getDeclaredField("html");
        htmlField.setAccessible(true);
        Field first = HtmlTube.class.getDeclaredField("firstPage");
        first.setAccessible(true);
        Field siteField = HtmlTube.class.getDeclaredField("site");
        siteField.setAccessible(true);
        List<ExtSource> picked = new ArrayList<>();
        List<ExtSource> all = new ArrayList<>(Registry.sources());
        all.sort((x, y) -> Double.compare(y.weight, x.weight));
        for (ExtSource s : all) {
            if (!s.adult || s.usesWebView() || s.slow()) continue;
            HtmlTube h = (HtmlTube) htmlField.get(s);
            if (h == null) continue;
            String f = (String) first.get(h);
            if (!f.matches("https?://[^/]+/search/\\{q[p]?\\}/")) continue;
            picked.add(s);
            if (picked.size() >= count) break;
        }
        System.out.println("sources " + picked.size());
        ExecutorService ex = Executors.newFixedThreadPool(8);
        Map<String, Future<List<StreamInfoItem>>> jobs = new LinkedHashMap<>();
        for (ExtSource s : picked) {
            HtmlTube h = (HtmlTube) htmlField.get(s);
            String site = ((String) siteField.get(h)).replaceAll("/+$", "");
            jobs.put(s.key + "|home", ex.submit(call(() -> h.listing(Fetch.newCircuit("h" + s.key), site + "/").items)));
            for (int qi = 2; qi < a.length; qi++) {
                String term = a[qi];
                jobs.put(s.key + "|" + term + "|search", ex.submit(call(() -> h.search(Fetch.newCircuit("s" + s.key), term, null).items)));
                jobs.put(s.key + "|" + term + "|tags", ex.submit(call(() -> h.listing(Fetch.newCircuit("t" + s.key), site + "/tags/" + term + "/").items)));
                jobs.put(s.key + "|" + term + "|categories", ex.submit(call(() -> h.listing(Fetch.newCircuit("c" + s.key), site + "/categories/" + term + "/").items)));
            }
        }
        Map<String, List<StreamInfoItem>> got = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, Future<List<StreamInfoItem>>> e : jobs.entrySet()) {
            try {
                got.put(e.getKey(), e.getValue().get(90, TimeUnit.SECONDS));
            } catch (Exception x) {
                e.getValue().cancel(true);
                Throwable c = x.getCause() == null ? x : x.getCause();
                errors.put(e.getKey(), c.getClass().getSimpleName() + ": " + String.valueOf(c.getMessage()).replaceAll("\\s+", " "));
            }
        }
        ex.shutdownNow();
        if (System.getenv("TAG_SAVE") != null) {
            Map<String, ArrayList<StreamInfoItem>> save = new LinkedHashMap<>();
            for (Map.Entry<String, List<StreamInfoItem>> e : got.entrySet()) save.put(e.getKey(), new ArrayList<>(e.getValue()));
            try (java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(new java.io.FileOutputStream(System.getenv("TAG_SAVE")))) {
                o.writeObject(save);
            }
        }
        System.out.println(String.format(Locale.ROOT, "%-10s %-14s %10s %10s %10s %6s %6s", "query", "source", "search", "tags", "categories", "new", "home%"));
        for (int qi = 2; qi < a.length; qi++) {
            String term = a[qi];
            int[] sum = new int[9];
            for (ExtSource s : picked) {
                List<StreamInfoItem> home = got.get(s.key + "|home");
                Set<String> homeUrls = home == null ? new HashSet<>() : urls(home);
                List<StreamInfoItem> search = got.get(s.key + "|" + term + "|search");
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-10s %-14s", term, s.key.length() > 14 ? s.key.substring(0, 14) : s.key));
                row.append(String.format(Locale.ROOT, " %10s", search == null ? "err" : search.size() + "/" + mentioning(term, search)));
                if (search != null) { sum[0] += search.size(); sum[1] += mentioning(term, search); }
                Set<String> searchUrls = search == null ? new HashSet<>() : urls(search);
                int fresh = 0;
                double homeShare = 0;
                int k = 0;
                for (String kind : new String[] {"tags", "categories"}) {
                    List<StreamInfoItem> l = got.get(s.key + "|" + term + "|" + kind);
                    if (l == null) {
                        row.append(String.format(Locale.ROOT, " %10s", "err"));
                        continue;
                    }
                    int inHome = 0;
                    for (StreamInfoItem it : l) if (homeUrls.contains(it.getUrl())) inHome++;
                    double share = l.isEmpty() ? 0 : inHome / (double) l.size();
                    boolean fake = !l.isEmpty() && share > 0.5;
                    row.append(String.format(Locale.ROOT, " %10s", l.size() + "/" + mentioning(term, l) + (fake ? "!" : "")));
                    if (!fake && !l.isEmpty()) {
                        sum[2 + 2 * k] += l.size();
                        sum[3 + 2 * k] += mentioning(term, l);
                        for (StreamInfoItem it : l) if (!searchUrls.contains(it.getUrl())) fresh++;
                        homeShare = Math.max(homeShare, share);
                    }
                    if (fake) sum[6]++;
                    k++;
                }
                sum[7] += fresh;
                row.append(String.format(Locale.ROOT, " %6d %5.0f%%", fresh, homeShare * 100));
                System.out.println(row);
            }
            System.out.println(String.format(Locale.ROOT, "== %s search=%d (%d mention) tags=%d (%d) categories=%d (%d) fakePages=%d newVsSearch=%d",
                    term, sum[0], sum[1], sum[2], sum[3], sum[4], sum[5], sum[6], sum[7]));
        }
        Map<String, Integer> errorKinds = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : errors.entrySet()) {
            String kind = e.getKey().substring(e.getKey().lastIndexOf('|') + 1) + " " + e.getValue().replaceAll("\\(.*", "");
            errorKinds.merge(kind.length() > 90 ? kind.substring(0, 90) : kind, 1, Integer::sum);
        }
        System.out.println("errors:");
        for (Map.Entry<String, Integer> e : errorKinds.entrySet()) System.out.println("  " + e.getValue() + " " + e.getKey());
        com.orvid.tor.Tor.get().stop();
        Thread.sleep(300);
        System.exit(0);
    }

    static Callable<List<StreamInfoItem>> call(Callable<List<StreamInfoItem>> c) {
        return c;
    }
}
