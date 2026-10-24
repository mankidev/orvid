package com.orvid.yt;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonParser;
import com.orvid.net.Fetch;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Suggest {

    private Suggest() {
    }

    private static final String HOST = "https://suggestqueries-clients6.youtube.com/";

    public static void warm() throws Exception {
        Fetch.on("suggest", () -> {
            HttpURLConnection c = Fetch.open(HOST);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in != null) in.close();
            return code;
        });
    }

    public static List<String> fetch(String query) throws Exception {
        String url = HOST + "complete/search?client=youtube&ds=yt&hl=en&gl=us&q="
                + URLEncoder.encode(query, "UTF-8");
        String text = Fetch.on("suggest", () -> {
            HttpURLConnection c = Fetch.open(url);
            c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            try (java.io.InputStream in = c.getInputStream()) {
                return new String(Streams.readAll(in), StandardCharsets.UTF_8);
            }
        });
        int a = text.indexOf('(');
        int b = text.lastIndexOf(')');
        List<String> out = new ArrayList<>();
        if (a < 0 || b <= a) return out;
        JsonArray root = JsonParser.array().from(text.substring(a + 1, b));
        JsonArray items = root.getArray(1);
        for (int i = 0; i < items.size() && out.size() < 8; i++) {
            Object o = items.get(i);
            if (o instanceof JsonArray && !((JsonArray) o).isEmpty()) out.add(((JsonArray) o).getString(0));
        }
        return out;
    }
}
