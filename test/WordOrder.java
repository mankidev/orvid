import com.orvid.search.Query;
import com.orvid.search.Ranker;
import java.io.*;
import java.text.Normalizer;
import java.util.*;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

public class WordOrder {
    static List<String> words(String s) {
        String f = Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        List<String> out = new ArrayList<>();
        for (String w : f.split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    static final Set<String> FILLER = new HashSet<>(Arrays.asList("how", "to", "the", "a", "an", "of", "in", "on", "for", "and", "with", "ve", "ile", "bir", "nasil"));

    static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        for (String w : words(query)) if (!FILLER.contains(w)) out.add(w);
        return out;
    }

    static boolean has(List<String> words, String t) {
        for (String w : words) if (w.equals(t) || w.startsWith(t) && w.length() - t.length() <= 3) return true;
        return false;
    }

    static int covered(List<String> terms, StreamInfoItem it) {
        List<String> title = words(it.getName()), channel = words(it.getUploaderName());
        int n = 0;
        for (String t : terms) if (has(title, t) || has(channel, t)) n++;
        return n;
    }

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
            List<String> terms = terms(query);
            if (terms.size() < 2) continue;
            double[] w = new double[lists.size()]; Arrays.fill(w, 0.6); w[0] = 1;
            List<StreamInfoItem> r = Ranker.rank(Query.parse(query), lists, w);
            int top = Math.min(30, r.size()), inv = 0;
            for (int i = 0; i < top; i++) for (int j = i + 1; j < top; j++) if (covered(terms, r.get(j)) > covered(terms, r.get(i))) inv++;
            total += inv; queries++;
        }
        System.out.printf("%d\t%d%n", total, queries);
    }
}
