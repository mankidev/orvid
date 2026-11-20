package com.orvid.more;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.orvid.play.Direct;
import com.orvid.search.Source;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

final class Commons extends Site {

    private static final int PAGE = 20;
    private static final String API = "https://commons.wikimedia.org/w/api.php?format=json&formatversion=2&action=query"
            + "&prop=videoinfo&viprop=url|mime|size|derivatives|timestamp|user&viurlwidth=320";

    Commons() {
        super(141, "Wikimedia Commons", "commons\\.wikimedia\\.org/wiki/(File:[^?#]+)");
    }

    @Override
    public Source.Page search(String circuit, String query, String next) throws IOException {
        int offset = next == null ? 0 : Integer.parseInt(next);
        JsonObject root = json(circuit, API + "&generator=search&gsrnamespace=6&gsrlimit=" + PAGE + "&gsroffset=" + offset
                + "&gsrsearch=" + enc(query + " filetype:video"));
        Source.Page p = new Source.Page();
        List<JsonObject> pages = new ArrayList<>();
        JsonArray arr = root.getObject("query").getArray("pages");
        for (int i = 0; i < arr.size(); i++) pages.add(arr.getObject(i));
        pages.sort((a, b) -> Integer.compare(a.getInt("index", 0), b.getInt("index", 0)));
        for (JsonObject pg : pages) {
            JsonArray vi = pg.getArray("videoinfo");
            if (vi.isEmpty()) continue;
            JsonObject v = vi.getObject(0);
            String title = pg.getString("title", "").replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z0-9]+$", "");
            if (adult(title)) continue;
            StreamInfoItem item = item(v.getString("descriptionurl", ""), title, v.getString("user", ""),
                    (long) v.getDouble("duration", -1), -1, v.getString("thumburl", null));
            com.orvid.search.Dates.set(item, v.getString("timestamp", null));
            p.items.add(item);
        }
        JsonObject cont = root.getObject("continue");
        if (cont.has("gsroffset")) p.next = Long.toString(cont.getLong("gsroffset"));
        return p;
    }

    @Override
    public Direct resolve(String circuit, String url) throws IOException {
        String file = URLDecoder.decode(id(url), "UTF-8");
        JsonArray pages = json(circuit, API + "&titles=" + enc(file)).getObject("query").getArray("pages");
        if (pages.isEmpty() || pages.getObject(0).getArray("videoinfo").isEmpty()) throw new IOException("video unavailable");
        JsonObject v = pages.getObject(0).getArray("videoinfo").getObject(0);
        String best = v.getString("url", null);
        int bestHeight = v.getInt("height", 0) <= 720 ? v.getInt("height", 0) : -1;
        JsonArray derivatives = v.getArray("derivatives");
        for (int i = 0; i < derivatives.size(); i++) {
            JsonObject d = derivatives.getObject(i);
            String type = d.getString("type", "");
            int h = d.getInt("height", 0);
            if (!type.startsWith("video/") || type.contains("ogg") || h > 720 || h <= bestHeight) continue;
            bestHeight = h;
            best = d.getString("src");
        }
        if (best == null) throw new IOException("no stream");
        String title = file.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z0-9]+$", "").replace('_', ' ');
        return new Direct(title, v.getString("user", ""), best, false, null);
    }
}
