import com.orvid.ext.ExtSource;
import com.orvid.ext.Registry;
import com.orvid.net.Fetch;
import com.orvid.search.Query;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SnapFetch {

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        File out = new File(a[1]);
        boolean adult = a[2].equals("adult");
        boolean tor = a[3].equals("tor");
        out.mkdirs();
        Fetch.setTorMode(tor);
        if (tor) {
            File dir = new File(System.getenv("PREFIX") + "/tmp/orvid-tor");
            dir.mkdirs();
            com.orvid.tor.Tor.get().start(dir, System.getenv("PREFIX") + "/tmp/torx/jni/arm64-v8a/libtor.so", (int) ProcessHandle.current().pid());
            System.out.println("tor " + com.orvid.tor.Tor.get().awaitReady(180000));
            Thread.sleep(3000);
        }
        Registry.reload(root);
        List<ExtSource> srcs = new ArrayList<>();
        for (ExtSource s : Registry.sources()) {
            if (s.adult == adult && !s.usesWebView() && !s.slow()) srcs.add(s);
        }
        String top = System.getenv("ONLY_TOP");
        if (top != null) {
            List<ExtSource> byWeight = new ArrayList<>(srcs);
            byWeight.sort((x, y) -> Double.compare(y.weight, x.weight));
            java.util.Set<ExtSource> keep = new java.util.LinkedHashSet<>(byWeight.subList(0, Math.min(Integer.parseInt(top), byWeight.size())));
            for (ExtSource s : srcs) {
                for (int qi = 4; qi < a.length; qi++) {
                    for (String t : Query.parse(a[qi]).terms) if (s.about(t)) keep.add(s);
                }
            }
            srcs = new ArrayList<>(keep);
        }
        System.out.println("sources " + srcs.size());
        ExecutorService ex = Executors.newFixedThreadPool(tor ? 8 : 16);
        for (int qi = 4; qi < a.length; qi++) {
            String text = a[qi];
            Query q = Query.parse(text);
            Map<String, Callable<List<StreamInfoItem>>> jobs = new LinkedHashMap<>();
            if (!adult) {
                jobs.put("youtube", () -> com.orvid.yt.Search.first(Fetch.newCircuit("yt"), text, null).items);
                jobs.put("dailymotion", () -> com.orvid.dm.Dailymotion.search(Fetch.newCircuit("dm"), text, 1,
                        "&sort=relevance&family_filter=true&longer_than=1", false).items);
                jobs.put("vk", () -> com.orvid.vk.Vk.search(Fetch.newCircuit("vk"), text, null).items);
            }
            for (ExtSource s : srcs) jobs.put(s.key, () -> s.search(Fetch.newCircuit("s" + s.key), q.plainQuery, null, null).items);
            Map<String, ArrayList<StreamInfoItem>> got = run(ex, jobs);
            Map<String, Callable<List<StreamInfoItem>>> again = new LinkedHashMap<>();
            if (q.terms.size() >= 2) {
                for (ExtSource s : srcs) {
                    ArrayList<StreamInfoItem> l = got.get(s.key);
                    if (l == null || !l.isEmpty()) continue;
                    if (!String.join(" ", q.terms).equals(q.plainQuery)) {
                        String joined = String.join(" ", q.terms);
                        again.put(s.key + "~", () -> s.search(Fetch.newCircuit("r" + s.key), joined, null, null).items);
                    }
                    for (String drop : q.terms) {
                        List<String> rest = new ArrayList<>(q.terms);
                        rest.remove(drop);
                        String relaxed = String.join(" ", rest);
                        again.put(s.key + "~-" + drop, () -> s.search(Fetch.newCircuit("r" + s.key + drop), relaxed, null, null).items);
                    }
                }
            }
            got.putAll(run(ex, again));
            int empty = 0, failed = 0, items = 0;
            for (Map.Entry<String, ArrayList<StreamInfoItem>> e : got.entrySet()) {
                if (e.getKey().contains("~")) continue;
                if (e.getValue() == null) failed++;
                else if (e.getValue().isEmpty()) empty++;
                else items += e.getValue().size();
            }
            got.values().removeIf(v -> v == null);
            try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(new File(out, text.replace(' ', '_') + ".ser")))) {
                o.writeObject(new LinkedHashMap<>(got));
            }
            System.out.println(text + "\titems=" + items + "\tempty=" + empty + "\tfailed=" + failed + "\trelaxedTries=" + again.size());
        }
        ex.shutdownNow();
        if (tor) com.orvid.tor.Tor.get().stop();
        Thread.sleep(300);
        System.exit(0);
    }

    static Map<String, ArrayList<StreamInfoItem>> run(ExecutorService ex, Map<String, Callable<List<StreamInfoItem>>> jobs) {
        Map<String, Future<List<StreamInfoItem>>> fs = new LinkedHashMap<>();
        for (Map.Entry<String, Callable<List<StreamInfoItem>>> e : jobs.entrySet()) fs.put(e.getKey(), ex.submit(e.getValue()));
        Map<String, ArrayList<StreamInfoItem>> got = new LinkedHashMap<>();
        for (Map.Entry<String, Future<List<StreamInfoItem>>> e : fs.entrySet()) {
            try {
                got.put(e.getKey(), new ArrayList<>(e.getValue().get(40, TimeUnit.SECONDS)));
            } catch (Exception x) {
                e.getValue().cancel(true);
                got.put(e.getKey(), null);
            }
        }
        return got;
    }
}
