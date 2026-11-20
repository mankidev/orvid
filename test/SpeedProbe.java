import com.orvid.ext.ExtSource;
import com.orvid.ext.Registry;
import com.orvid.net.Fetch;
import com.orvid.search.Query;
import com.orvid.search.Ranker;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SpeedProbe {

    static final class Job {
        final String key;
        final double weight;
        final Callable<List<StreamInfoItem>> call;
        volatile long done = -1;
        volatile int items = -1;
        volatile String error;

        Job(String key, double weight, Callable<List<StreamInfoItem>> call) {
            this.key = key;
            this.weight = weight;
            this.call = call;
        }
    }

    static final Map<String, List<Long>> LATENCY = new TreeMap<>();
    static final Map<String, int[]> OUTCOME = new TreeMap<>();
    static final Map<String, java.util.Set<String>> ERRORS = new TreeMap<>();

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        boolean tor = a[1].equals("tor");
        Fetch.setTorMode(tor);
        if (tor) {
            File dir = new File("/tmp/orvid-speed-tor");
            dir.mkdirs();
            long t = System.currentTimeMillis();
            com.orvid.tor.Tor.get().start(dir, "/usr/sbin/tor", (int) ProcessHandle.current().pid());
            System.out.println("tor ready=" + com.orvid.tor.Tor.get().awaitReady(180000) + " in " + (System.currentTimeMillis() - t) + " ms");
            Thread.sleep(3000);
        }
        Registry.reload(root);
        List<ExtSource> extras = new ArrayList<>();
        for (ExtSource s : Registry.sources()) if (!s.adult && !s.usesWebView()) extras.add(s);
        System.out.println("sources " + extras.size() + " + youtube, dailymotion, vk");
        int slots = tor ? 16 : 32;
        int firstWave = tor ? 8 : 1_000_000;
        List<long[]> rows = new ArrayList<>();
        System.out.println(String.format(Locale.ROOT, "%-34s %6s %6s %6s %6s %6s %5s %5s %7s %5s",
                "query", "first", "yt", "p50", "p90", "last", "ok", "fail", "rankMs", "ranks"));
        for (int qi = 2; qi < a.length; qi++) {
            String text = a[qi];
            Query q = Query.parse(text);
            List<Job> jobs = new ArrayList<>();
            jobs.add(new Job("youtube", 1.0, () -> com.orvid.yt.Search.first(Fetch.newCircuit("yt"), text, null).items));
            jobs.add(new Job("dailymotion", 0.7, () -> com.orvid.dm.Dailymotion.search(Fetch.newCircuit("dm"), q.plainQuery, 1,
                    new com.orvid.search.Filters().dailymotionParams(), false).items));
            jobs.add(new Job("vk", 0.8, () -> com.orvid.vk.Vk.search(Fetch.newCircuit("vk"), q.plainQuery, null).items));
            List<ExtSource> ordered = new ArrayList<>(extras);
            ordered.sort((x, y) -> {
                int px = x.priority(q), py = y.priority(q);
                return px != py ? Integer.compare(px, py) : Double.compare(y.weight, x.weight);
            });
            for (ExtSource s : ordered) {
                if (!s.wanted(new com.orvid.search.Filters(), q)) continue;
                jobs.add(new Job(s.key, s.weight, () -> s.search(Fetch.newCircuit(s.key), q.plainQuery, null, null).items));
            }
            ExecutorService pool = Executors.newFixedThreadPool(slots);
            CountDownLatch latch = new CountDownLatch(jobs.size());
            long start = System.currentTimeMillis();
            List<List<StreamInfoItem>> arrived = Collections.synchronizedList(new ArrayList<>());
            List<Double> weights = Collections.synchronizedList(new ArrayList<>());
            long[] rank = new long[2];
            Object rankLock = new Object();
            for (int i = 0; i < jobs.size(); i++) {
                Job j = jobs.get(i);
                Runnable r = () -> {
                    try {
                        List<StreamInfoItem> items = j.call.call();
                        j.items = items.size();
                        synchronized (rankLock) {
                            arrived.add(new ArrayList<>(items));
                            weights.add(j.weight);
                            double[] w = new double[weights.size()];
                            for (int k = 0; k < w.length; k++) w[k] = weights.get(k);
                            long t = System.nanoTime();
                            Ranker.rank(q, new ArrayList<>(arrived), w);
                            rank[0] += System.nanoTime() - t;
                            rank[1]++;
                        }
                    } catch (Throwable e) {
                        String m = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
                        j.error = e.getClass().getSimpleName() + ": " + (m.length() > 70 ? m.substring(0, 70) : m);
                    } finally {
                        j.done = System.currentTimeMillis() - start;
                        latch.countDown();
                    }
                };
                if (i < 3 + firstWave) pool.submit(r);
                else laterRuns.add(r);
            }
            if (!laterRuns.isEmpty()) {
                Thread.sleep(3000);
                for (Runnable r : laterRuns) pool.submit(r);
                laterRuns.clear();
            }
            latch.await(120, TimeUnit.SECONDS);
            pool.shutdownNow();
            long first = Long.MAX_VALUE, yt = -1;
            List<Long> doneTimes = new ArrayList<>();
            int ok = 0, fail = 0;
            for (Job j : jobs) {
                if (j.done < 0) {
                    fail++;
                    OUTCOME.computeIfAbsent(j.key, k -> new int[3])[2]++;
                    continue;
                }
                doneTimes.add(j.done);
                if (j.error != null) {
                    fail++;
                    OUTCOME.computeIfAbsent(j.key, k -> new int[3])[1]++;
                    ERRORS.computeIfAbsent(j.key, k -> new java.util.TreeSet<>()).add(j.error);
                } else {
                    ok++;
                    OUTCOME.computeIfAbsent(j.key, k -> new int[3])[0]++;
                    LATENCY.computeIfAbsent(j.key, k -> new ArrayList<>()).add(j.done);
                    if (j.items > 0) first = Math.min(first, j.done);
                }
                if (j.key.equals("youtube")) yt = j.done;
            }
            Collections.sort(doneTimes);
            long p50 = doneTimes.isEmpty() ? -1 : doneTimes.get(doneTimes.size() / 2);
            long p90 = doneTimes.isEmpty() ? -1 : doneTimes.get(Math.min(doneTimes.size() - 1, doneTimes.size() * 9 / 10));
            long last = doneTimes.isEmpty() ? -1 : doneTimes.get(doneTimes.size() - 1);
            System.out.println(String.format(Locale.ROOT, "%-34s %6d %6d %6d %6d %6d %5d %5d %7.0f %5d",
                    text.length() > 34 ? text.substring(0, 34) : text, first == Long.MAX_VALUE ? -1 : first, yt, p50, p90, last,
                    ok, fail, rank[0] / 1e6, rank[1]));
            rows.add(new long[] {first, yt, p50, p90, last});
        }
        System.out.println("\nslowest sources (median ms over ok runs, ok/error/timeout):");
        List<Map.Entry<String, List<Long>>> slow = new ArrayList<>(LATENCY.entrySet());
        slow.sort((x, y) -> Long.compare(median(y.getValue()), median(x.getValue())));
        for (int i = 0; i < Math.min(15, slow.size()); i++) {
            int[] o = OUTCOME.get(slow.get(i).getKey());
            System.out.println(String.format(Locale.ROOT, "  %-14s %6d  %d/%d/%d", slow.get(i).getKey(), median(slow.get(i).getValue()), o[0], o[1], o[2]));
        }
        System.out.println("failing sources:");
        for (Map.Entry<String, int[]> e : OUTCOME.entrySet()) {
            int[] o = e.getValue();
            if (o[1] + o[2] > 0) System.out.println(String.format(Locale.ROOT, "  %-14s ok=%d error=%d timeout=%d %s", e.getKey(), o[0], o[1], o[2], ERRORS.getOrDefault(e.getKey(), java.util.Collections.emptySet())));
        }
        if (tor) com.orvid.tor.Tor.get().stop();
        Thread.sleep(300);
        System.exit(0);
    }

    static final List<Runnable> laterRuns = new ArrayList<>();

    static long median(List<Long> l) {
        List<Long> s = new ArrayList<>(l);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
