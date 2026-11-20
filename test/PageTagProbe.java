import com.orvid.adult.HtmlTube;
import com.orvid.adult.Web;
import com.orvid.ext.ExtSource;
import com.orvid.ext.Registry;
import com.orvid.net.Fetch;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PageTagProbe {

    static final Pattern META = Pattern.compile("<meta\\s+name=[\"']keywords[\"']\\s+content=[\"']([^\"']*)", Pattern.CASE_INSENSITIVE);
    static final Pattern FLASH = Pattern.compile("\\bvideo_(tags|categories)\\s*:\\s*'([^']*)'");
    static final Pattern LINK = Pattern.compile("href=[\"'](?:https?://[^/\"']+)?/(tags|categories)/([^/\"'?#]+)/?[\"']");
    static final Pattern MARKER = Pattern.compile("video_url|<source\\b");

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        int count = Integer.parseInt(a[1]);
        String term = a[2];
        Fetch.setTorMode(true);
        File dir = new File(System.getenv("PREFIX") + "/tmp/orvid-tor");
        dir.mkdirs();
        com.orvid.tor.Tor.get().start(dir, System.getenv("PREFIX") + "/tmp/torx/jni/arm64-v8a/libtor.so", (int) ProcessHandle.current().pid());
        System.out.println("tor " + com.orvid.tor.Tor.get().awaitReady(180000));
        Thread.sleep(3000);
        Registry.reload(root);
        Field htmlField = ExtSource.class.getDeclaredField("html");
        htmlField.setAccessible(true);
        List<ExtSource> picked = new ArrayList<>();
        List<ExtSource> all = new ArrayList<>(Registry.sources());
        all.sort((x, y) -> Double.compare(y.weight, x.weight));
        for (ExtSource s : all) {
            if (!s.adult || s.usesWebView() || s.slow() || htmlField.get(s) == null) continue;
            picked.add(s);
            if (picked.size() >= count) break;
        }
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<String>> jobs = new ArrayList<>();
        for (ExtSource s : picked) {
            HtmlTube h = (HtmlTube) htmlField.get(s);
            jobs.add(ex.submit(() -> {
                try {
                    String c = Fetch.newCircuit("p" + s.key);
                    List<StreamInfoItem> items;
                    try {
                        items = h.search(c, term, null).items;
                    } catch (Exception e) {
                        return s.key + " SEARCH FAIL " + e;
                    }
                    if (items.isEmpty()) return s.key + " no items";
                    String url = items.get(0).getUrl();
                    long t0 = System.currentTimeMillis();
                    List<String> tags = null;
                    for (int t = 0; t < 3 && tags == null; t++) {
                        try {
                            tags = s.tags(Fetch.newCircuit("q" + t + s.key), url);
                        } catch (Exception e) {
                            System.out.println(s.key + " TAGS FAIL " + e);
                        }
                    }
                    if (tags == null) return s.key + " tags gave up";
                    List<String> again = s.tags(c, url);
                    StringBuilder out = new StringBuilder(s.key + " " + (System.currentTimeMillis() - t0) + "ms tags=" + tags.size() + " " + tags);
                    String title = items.get(0).getName();
                    out.append("\n   hints ").append(com.orvid.search.Ranker.hints(String.join(" ", tags.subList(0, Math.min(3, tags.size()))), tags, title));
                    return out.toString();
                } catch (Exception e) {
                    return s.key + " FAIL " + e;
                }
            }));
        }
        for (Future<String> j : jobs) {
            try {
                System.out.println(j.get(120, TimeUnit.SECONDS));
            } catch (Exception e) {
                System.out.println("timeout");
            }
        }
        com.orvid.tor.Tor.get().stop();
        System.exit(0);
    }

    static String cut(String s) {
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
