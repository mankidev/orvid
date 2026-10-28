package com.orvid.more;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.orvid.play.Direct;
import com.orvid.search.Source;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

final class Europeana extends Site {

    private static final String KEY = "api2demo";
    private static final int ROWS = 24;
    private static final long DURATION_BUDGET_MS = 8000;

    Europeana() {
        super(180, "Europeana", "europeana\\.eu/(?:[a-z]{2}/)?item(/[0-9A-Za-z_]+/[0-9A-Za-z_.\\-]+)");
    }

    @Override
    public Source.Page search(String circuit, String query, String next) throws IOException {
        int start = next == null ? 1 : Integer.parseInt(next);
        JsonObject root = json(circuit, "https://api.europeana.eu/record/v2/search.json?wskey=" + KEY + "&query=" + enc(query)
                + "&qf=MIME_TYPE:video%2Fmp4&media=true&rows=" + ROWS + "&start=" + start + "&profile=rich");
        JsonArray items = root.getArray("items");
        Source.Page p = new Source.Page();
        Map<StreamInfoItem, String> files = new IdentityHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            JsonObject r = items.getObject(i);
            String file = first(r.getArray("edmIsShownBy"));
            String id = r.getString("id", null);
            if (id == null || file == null || !file.toLowerCase().contains(".mp4")) continue;
            StreamInfoItem item = item("https://www.europeana.eu/item" + id, first(r.getArray("title")),
                    first(r.getArray("dataProvider")), -1, -1, first(r.getArray("edmPreview")));
            files.put(item, https(file));
            p.items.add(item);
        }
        if (start + ROWS <= root.getInt("totalResults", 0)) p.next = Integer.toString(start + ROWS);
        dropUnreachable(p.items, files);
        durations(p.items, DURATION_BUDGET_MS, item -> mp4Seconds(circuit, files.get(item)));
        dropUnreachable(p.items, files);
        return p;
    }

    private static String first(JsonArray a) {
        return a == null || a.isEmpty() ? null : a.getString(0);
    }

    @Override
    public Direct resolve(String circuit, String url) throws IOException {
        JsonObject o = json(circuit, "https://api.europeana.eu/record/v2" + id(url) + ".json?wskey=" + KEY).getObject("object");
        String file = null;
        JsonArray aggregations = o.getArray("aggregations");
        for (int i = 0; i < aggregations.size() && file == null; i++) {
            file = aggregations.getObject(i).getString("edmIsShownBy", null);
        }
        if (file == null) throw new IOException("video unavailable");
        String title = "";
        JsonArray proxies = o.getArray("proxies");
        for (int i = 0; i < proxies.size() && title.isEmpty(); i++) {
            JsonObject t = proxies.getObject(i).getObject("dcTitle");
            for (Object v : t.values()) {
                if (v instanceof JsonArray && !((JsonArray) v).isEmpty()) title = ((JsonArray) v).getString(0);
            }
        }
        return new Direct(title, "Europeana", https(file), false, null);
    }
}
