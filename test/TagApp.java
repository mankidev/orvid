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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TagApp {
    public static void main(String[] a) throws Exception {
        Fetch.setTorMode(true);
        File dir = new File(System.getenv("PREFIX") + "/tmp/orvid-tor");
        dir.mkdirs();
        com.orvid.tor.Tor.get().start(dir, System.getenv("PREFIX") + "/tmp/torx/jni/arm64-v8a/libtor.so", (int) ProcessHandle.current().pid());
        System.out.println("tor " + com.orvid.tor.Tor.get().awaitReady(180000));
        Thread.sleep(3000);
        Registry.reload(new File(a[0]));
        Field htmlField = ExtSource.class.getDeclaredField("html");
        htmlField.setAccessible(true);
        List<ExtSource> picked = new ArrayList<>();
        List<ExtSource> all = new ArrayList<>(Registry.sources());
        all.sort((x, y) -> Double.compare(y.weight, x.weight));
        for (ExtSource s : all) {
            if (!s.adult || s.usesWebView() || s.slow()) continue;
            HtmlTube h = (HtmlTube) htmlField.get(s);
            if (h == null || h.tagFor("massage") == null) continue;
            picked.add(s);
            if (picked.size() >= Integer.parseInt(a[1])) break;
        }
        ExecutorService ex = Executors.newFixedThreadPool(8);
        for (int qi = 2; qi < a.length; qi++) {
            String term = a[qi];
            Query q = Query.parse(term);
            List<Future<long[]>> fs = new ArrayList<>();
            for (ExtSource s : picked) {
                HtmlTube h = (HtmlTube) htmlField.get(s);
                fs.add(ex.submit(() -> {
                    long t0 = System.currentTimeMillis();
                    List<StreamInfoItem> plain = h.search(Fetch.newCircuit("p" + s.key), term, null).items;
                    long t1 = System.currentTimeMillis();
                    List<StreamInfoItem> app = s.search(Fetch.newCircuit("a" + s.key), term, null, null).items;
                    long t2 = System.currentTimeMillis();
                    int matching = 0;
                    for (StreamInfoItem it : app) if (Ranker.covers(q, it.getName())) matching++;
                    int plainMatching = 0;
                    for (StreamInfoItem it : plain) if (Ranker.covers(q, it.getName())) plainMatching++;
                    return new long[] {plain.size(), app.size(), plainMatching, matching, t1 - t0, t2 - t1};
                }));
            }
            long[] sum = new long[6];
            int ok = 0;
            List<Long> plainMs = new ArrayList<>(), appMs = new ArrayList<>();
            for (Future<long[]> f : fs) {
                try {
                    long[] r = f.get(120, TimeUnit.SECONDS);
                    for (int i = 0; i < 4; i++) sum[i] += r[i];
                    plainMs.add(r[4]);
                    appMs.add(r[5]);
                    ok++;
                } catch (Exception e) {
                    f.cancel(true);
                }
            }
            java.util.Collections.sort(plainMs);
            java.util.Collections.sort(appMs);
            System.out.println(String.format(Locale.ROOT, "%-14s ok=%d/%d  items %d -> %d  matching %d -> %d  median ms search=%d app=%d",
                    term, ok, picked.size(), sum[0], sum[1], sum[2], sum[3],
                    plainMs.isEmpty() ? -1 : plainMs.get(plainMs.size() / 2), appMs.isEmpty() ? -1 : appMs.get(appMs.size() / 2)));
        }
        ex.shutdownNow();
        com.orvid.tor.Tor.get().stop();
        Thread.sleep(300);
        System.exit(0);
    }
}
