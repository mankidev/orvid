package com.orvid.more;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.orvid.play.Direct;
import com.orvid.search.Source;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

final class Imgur extends Site {

    private static final String CLIENT = "546c25a59c58ad7";
    private static final long DURATION_BUDGET_MS = 4000;

    Imgur() {
        super(149, "Imgur", "imgur\\.com/(?:gallery/|a/)?([A-Za-z0-9]{5,8})(?:[/?#.]|$)");
    }

    @Override
    public Source.Page search(String circuit, String query, String next) throws IOException {
        int page = next == null ? 0 : Integer.parseInt(next);
        JsonArray data = api(circuit, "gallery/search/top/all/" + page + "?q=" + enc(query)).getArray("data");
        Source.Page p = new Source.Page();
        java.util.Map<StreamInfoItem, String> mp4 = new java.util.IdentityHashMap<>();
        for (int i = 0; i < data.size(); i++) {
            JsonObject g = data.getObject(i);
            if (g.getBoolean("nsfw", false)) continue;
            JsonObject media = g.getBoolean("is_album", false) ? firstVideo(g.getArray("images")) : g;
            if (media == null || media.getString("mp4", null) == null) continue;
            String id = g.getString("id");
            StreamInfoItem item = item("https://imgur.com/gallery/" + id, g.getString("title", ""),
                    g.getString("account_url", ""), -1, g.getLong("views", -1),
                    "https://i.imgur.com/" + media.getString("id") + "m.jpg");
            com.orvid.search.Dates.set(item, g.getLong("datetime", 0));
            mp4.put(item, media.getString("mp4"));
            p.items.add(item);
        }
        if (!data.isEmpty()) p.next = Integer.toString(page + 1);
        durations(p.items, DURATION_BUDGET_MS, item -> mp4Seconds(circuit, mp4.get(item)));
        return p;
    }

    @Override
    public Direct resolve(String circuit, String url) throws IOException {
        String id = id(url);
        JsonObject g;
        try {
            g = api(circuit, "gallery/" + id).getObject("data");
        } catch (IOException e) {
            g = api(circuit, "image/" + id).getObject("data");
        }
        JsonObject media = g.getBoolean("is_album", false) ? firstVideo(g.getArray("images")) : g;
        if (media == null || media.getString("mp4", null) == null) throw new IOException("no video");
        return new Direct(g.getString("title", ""), g.getString("account_url", ""), media.getString("mp4"), false, null);
    }

    private static JsonObject firstVideo(JsonArray images) {
        for (int i = 0; i < images.size(); i++) {
            JsonObject im = images.getObject(i);
            if (im.getString("mp4", null) != null) return im;
        }
        return null;
    }

    private JsonObject api(String circuit, String path) throws IOException {
        try {
            String body = com.orvid.net.Fetch.on(circuit, () -> {
                HttpURLConnection c = com.orvid.net.Fetch.open("https://api.imgur.com/3/" + path);
                c.setRequestProperty("User-Agent", com.orvid.net.Fetch.USER_AGENT);
                c.setRequestProperty("Authorization", "Client-ID " + CLIENT);
                int code = c.getResponseCode();
                if (code == 403 || code == 429) throw new IOException("Captcha needed (HTTP " + code + ")");
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    return new String(Streams.readAll(in), StandardCharsets.UTF_8);
                }
            });
            return com.grack.nanojson.JsonParser.object().from(body);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
