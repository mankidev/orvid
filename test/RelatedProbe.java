import com.orvid.net.Fetch;
import com.orvid.search.Ranker;
import com.orvid.yt.Related;
import com.orvid.yt.Search;
import com.orvid.yt.Visionos;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RelatedProbe {

    public static void main(String[] a) throws Exception {
        boolean tor = System.getenv("TOR") != null;
        Fetch.setTorMode(tor);
        if (tor) {
            java.io.File dir = new java.io.File("/tmp/orvid-related-tor");
            dir.mkdirs();
            com.orvid.tor.Tor.get().start(dir, "/usr/sbin/tor", (int) ProcessHandle.current().pid());
            System.out.println("tor ready=" + com.orvid.tor.Tor.get().awaitReady(180000));
            Thread.sleep(3000);
        }
        int pick = Integer.parseInt(System.getenv().getOrDefault("PICK", "0"));
        for (String query : a) {
            String c = Visionos.takeCircuit();
            List<StreamInfoItem> found = Search.first(c, query, null).items;
            if (found.size() <= pick) continue;
            String id = Visionos.videoId(found.get(pick).getUrl());
            Visionos.Video v = null;
            for (int t = 0; t < 4 && v == null; t++) {
                try {
                    v = Visionos.fetch(t == 0 ? c : (c = Visionos.takeCircuit()), id);
                } catch (Exception e) {
                    System.out.println("fetch FAIL " + e);
                }
            }
            if (v == null) continue;
            Ranker.Hints hints = Ranker.hints(v.title, v.keywords, v.description);
            String oldQ = Ranker.relatedQuery(v.title, null);
            String newQ = Ranker.relatedQuery(v.title, null, hints);
            System.out.println("\n### " + v.title + "  [" + v.author + "]");
            System.out.println("keywords " + v.keywords.size() + ": " + v.keywords.subList(0, Math.min(10, v.keywords.size())));
            System.out.println("hints " + hints);
            System.out.println("query old=[" + oldQ + "] new=[" + newQ + "]");
            List<StreamInfoItem> next = new ArrayList<>();
            for (int t = 0; t < 4 && next.isEmpty(); t++) {
                try {
                    next = Related.page(t == 0 ? c : Visionos.takeCircuit(), id).items;
                } catch (Exception e) {
                    System.out.println("related FAIL " + e);
                }
            }
            List<StreamInfoItem> text = new ArrayList<>(Search.first(c, oldQ, null).items);
            if (!newQ.equals(oldQ)) text.addAll(Search.first(c, newQ, null).items);
            String url = "https://www.youtube.com/watch?v=" + id;
            for (boolean trust : new boolean[] {true, false}) {
                List<StreamInfoItem> nx = trust ? next : new ArrayList<>();
                List<StreamInfoItem> before = Ranker.similar(v.title, v.author, url, nx, text, trust, 0, new ArrayList<>());
                List<StreamInfoItem> after = Ranker.similar(v.title, v.author, url, nx, text, trust, 0, new ArrayList<>(), hints);
                java.util.Map<String, Integer> had = new java.util.HashMap<>();
                for (int i = 0; i < before.size(); i++) had.put(before.get(i).getUrl(), i + 1);
                Set<String> has = new HashSet<>();
                for (StreamInfoItem i : after) has.add(i.getUrl());
                System.out.println((trust ? "next+search" : "search only") + ": before=" + before.size() + " after=" + after.size());
                for (int i = 0; i < Math.min(15, after.size()); i++) {
                    StreamInfoItem it = after.get(i);
                    Integer was = had.get(it.getUrl());
                    System.out.println(String.format("  %2d %4s %s", i + 1, was == null ? "new" : "<" + was, cut(it.getName())));
                }
                for (StreamInfoItem it : before) if (!has.contains(it.getUrl())) System.out.println("  - " + cut(it.getName()));
            }
        }
        System.exit(0);
    }

    static String cut(String s) {
        return s.length() > 90 ? s.substring(0, 90) : s;
    }
}
