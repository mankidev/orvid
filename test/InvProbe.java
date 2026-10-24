package com.orvid.search;

import java.io.*;
import java.util.*;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

public class InvProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        String dir = System.getenv("PREFIX") + "/tmp/orvid-probe/mix/";
        int total = 0, queries = 0;
        for (File f : Objects.requireNonNull(new File(dir).listFiles())) {
            if (!f.getName().endsWith(".ser")) continue;
            List<List<StreamInfoItem>> lists;
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) { lists = (List<List<StreamInfoItem>>) in.readObject(); }
            String query = f.getName().replace(".ser", "").replace('_', ' ');
            if (query.startsWith("bar ")) query = "barış manço";
            Query q = Query.parse(query);
            if (q.terms.size() < 2) continue;
            double[] w = new double[lists.size()]; Arrays.fill(w, 0.6); w[0] = 1;
            List<StreamInfoItem> r = Ranker.rank(q, lists, w);
            int top = Math.min(30, r.size()), inv = 0;
            for (int i = 0; i < top; i++) for (int j = i + 1; j < top; j++) if (Ranker.coverage(q, r.get(j)) > Ranker.coverage(q, r.get(i))) inv++;
            System.out.printf("%s\t%d%n", query, inv);
            total += inv; queries++;
        }
        System.out.printf("TOTAL\tinversions=%d\tqueries=%d%n", total, queries);
    }
}
