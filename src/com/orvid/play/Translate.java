package com.orvid.play;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Translate {

    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 500;
        }
    };

    private Translate() {
    }

    public static String text(String circuit, String text, String from, String to) {
        if (text == null || text.trim().isEmpty() || to == null) return text;
        if (from != null && !from.isEmpty() && !from.equals("auto") && same(from, to)) return text;
        String key = to + "\u0000" + text;
        synchronized (CACHE) {
            String known = CACHE.get(key);
            if (known != null) return known;
        }
        String out = null;
        try {
            out = google(circuit, text, to);
        } catch (Exception e) {
            Diag.mark("translate google FAIL " + e.getMessage());
            Fetch.rotate(circuit);
        }
        if (out == null && from != null && !from.isEmpty() && !from.equals("auto")) {
            try {
                out = myMemory(circuit, text, from, to);
            } catch (Exception e) {
                Diag.mark("translate mymemory FAIL " + e.getMessage());
            }
        }
        if (out == null) return text;
        synchronized (CACHE) {
            CACHE.put(key, out);
        }
        return out;
    }

    private static boolean same(String a, String b) {
        return a.equalsIgnoreCase(b) || a.toLowerCase().startsWith(b.toLowerCase() + "-")
                || b.toLowerCase().startsWith(a.toLowerCase() + "-");
    }

    private static String google(String circuit, String text, String to) throws Exception {
        String body = get(circuit, "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl="
                + enc(to) + "&q=" + enc(text));
        JsonArray root = JsonParser.array().from(body);
        if (root.isEmpty()) return null;
        Object first = root.get(0);
        if (first instanceof JsonArray) {
            JsonArray pair = (JsonArray) first;
            String detected = pair.size() > 1 ? pair.getString(1, "") : "";
            if (!detected.isEmpty() && same(detected, to)) return text;
            return pair.getString(0, null);
        }
        return first instanceof String ? (String) first : null;
    }

    private static String myMemory(String circuit, String text, String from, String to) throws Exception {
        String body = get(circuit, "https://api.mymemory.translated.net/get?q=" + enc(text) + "&langpair="
                + enc(from) + "%7C" + enc(to));
        JsonObject data = JsonParser.object().from(body).getObject("responseData");
        String out = data.getString("translatedText", null);
        return out == null || out.trim().isEmpty() ? null : out;
    }

    private static String get(String circuit, String url) throws Exception {
        return Fetch.on(circuit, () -> {
            HttpURLConnection c = Fetch.open(url);
            c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return new String(Streams.readAll(in), StandardCharsets.UTF_8);
            }
        });
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
