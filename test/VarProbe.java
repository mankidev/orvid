package com.orvid.search;

import java.io.*;
import java.util.*;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

public class VarProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        String dir = System.getenv("PREFIX") + "/tmp/orvid-probe/mix/";
        int min = 10, max = 0;
        for (File f : Objects.requireNonNull(new File(dir).listFiles())) {
            if (!f.getName().endsWith(".ser")) continue;
            List<List<StreamInfoItem>> lists;
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) { lists = (List<List<StreamInfoItem>>) in.readObject(); }
            String query = f.getName().replace(".ser", "").replace('_', ' ');
            if (query.startsWith("bar ")) query = "barış manço";
            Query q = Query.parse(query);
            double[] w = new double[lists.size()]; Arrays.fill(w, 0.6); w[0] = 1;
            Ranker.reseed();
            List<StreamInfoItem> r1 = Ranker.rank(q, lists, w);
            Ranker.reseed();
            List<StreamInfoItem> r2 = Ranker.rank(q, lists, w);
            int same = 0;
            for (int i = 0; i < Math.min(10, Math.min(r1.size(), r2.size())); i++) if (r1.get(i) == r2.get(i)) same++;
            System.out.printf("%s\t%d%n", query, same);
            min = Math.min(min, same); max = Math.max(max, same);
        }
        System.out.printf("TOTAL\tsamePlaceInTop10=%d-%d%n", min, max);
    }
}
