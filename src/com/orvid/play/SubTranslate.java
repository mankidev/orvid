package com.orvid.play;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonParser;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class SubTranslate {

    public static final String SCHEME = "orvid-translate";
    private static final String ENDPOINT = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=";
    private static final int BATCH = 100;
    private static final int BATCH_CHARS = 8000;
    private static final int ATTEMPTS = 3;
    private static final int MAX_SOURCE = 4 * 1024 * 1024;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "orvid-translate");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, byte[]> DONE = new LinkedHashMap<String, byte[]>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 6;
        }
    };

    private SubTranslate() {
    }

    public static String uri(String source, String to) {
        try {
            return SCHEME + "://sub?to=" + URLEncoder.encode(to, "UTF-8") + "&src=" + URLEncoder.encode(source, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean handles(String uri) {
        return uri != null && uri.startsWith(SCHEME + "://");
    }

    public static byte[] vtt(String circuit, String uri, String userAgent, String referer, String cookie) throws IOException {
        synchronized (DONE) {
            byte[] known = DONE.get(uri);
            if (known != null) return known;
        }
        String source = param(uri, "src");
        String to = param(uri, "to");
        if (source == null || to == null) throw new IOException("bad translation link");
        long began = System.currentTimeMillis();
        String text = fetch(circuit, source, userAgent, referer, cookie);
        List<String[]> cues = cues(text);
        List<String> lines = new ArrayList<>(cues.size());
        for (String[] c : cues) lines.add(c[1]);
        List<String> done = translate(circuit, lines, to);
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < cues.size(); i++) out.append(cues.get(i)[0]).append('\n').append(done.get(i)).append("\n\n");
        byte[] raw = out.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (DONE) {
            DONE.put(uri, raw);
        }
        Diag.mark("subtitle translated to " + to + ": " + cues.size() + " cues in " + (System.currentTimeMillis() - began) + "ms");
        return raw;
    }

    private static String param(String uri, String name) {
        int q = uri.indexOf('?');
        if (q < 0) return null;
        for (String part : uri.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String fetch(String circuit, String url, String userAgent, String referer, String cookie) throws IOException {
        try {
            return Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", userAgent != null ? userAgent : Fetch.USER_AGENT);
                c.setRequestProperty("Accept-Encoding", "identity");
                if (referer != null) c.setRequestProperty("Referer", referer);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                if (c.getResponseCode() >= 400) throw new IOException("subtitle HTTP " + c.getResponseCode());
                return new String(read(c.getInputStream()), StandardCharsets.UTF_8).replace("﻿", "");
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    static List<String[]> cues(String text) {
        List<String[]> out = new ArrayList<>();
        String[] blocks = text.replace("\r\n", "\n").replace('\r', '\n').split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            int timing = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timing = i;
                    break;
                }
            }
            if (timing < 0) continue;
            StringBuilder body = new StringBuilder();
            for (int i = timing + 1; i < lines.length; i++) {
                String l = lines[i].replaceAll("<[^>]+>", "").replaceAll("\\{\\\\[^}]*}", "").trim();
                if (l.isEmpty()) continue;
                if (body.length() > 0) body.append(' ');
                body.append(l);
            }
            if (body.length() == 0) continue;
            String time = lines[timing].trim().replaceAll("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})", "$1.$2");
            out.add(new String[]{time, body.toString()});
        }
        return out;
    }

    private static List<String> translate(String circuit, List<String> lines, String to) throws IOException {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int chars = 0;
        for (String line : lines) {
            if (!current.isEmpty() && (current.size() >= BATCH || chars + line.length() > BATCH_CHARS)) {
                batches.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(line);
            chars += line.length();
        }
        if (!current.isEmpty()) batches.add(current);
        List<Future<List<String>>> running = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<String> b = batches.get(i);
            String own = circuit + "/tr" + (i % 3);
            running.add(POOL.submit(() -> batch(own, b, to)));
        }
        List<String> out = new ArrayList<>(lines.size());
        try {
            for (Future<List<String>> f : running) out.addAll(f.get());
        } catch (Exception e) {
            for (Future<List<String>> f : running) f.cancel(true);
            throw new IOException("translation failed: " + e.getMessage());
        }
        return out;
    }

    private static List<String> batch(String circuit, List<String> lines, String to) throws Exception {
        StringBuilder form = new StringBuilder();
        for (String line : lines) {
            if (form.length() > 0) form.append('&');
            form.append("q=").append(URLEncoder.encode(line, "UTF-8"));
        }
        byte[] body = form.toString().getBytes(StandardCharsets.UTF_8);
        Exception last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                String text = Fetch.on(circuit, () -> {
                    HttpURLConnection c = Fetch.open(ENDPOINT + URLEncoder.encode(to, "UTF-8"));
                    c.setRequestMethod("POST");
                    c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body);
                    }
                    if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                    return new String(read(c.getInputStream()), StandardCharsets.UTF_8);
                });
                return parse(text, lines.size());
            } catch (Exception e) {
                last = e;
                Fetch.rotate(circuit);
            }
        }
        throw last;
    }

    private static List<String> parse(String body, int count) throws Exception {
        Object root = JsonParser.any().from(body);
        List<String> out = new ArrayList<>(count);
        if (root instanceof String) {
            out.add((String) root);
        } else if (root instanceof JsonArray) {
            JsonArray a = (JsonArray) root;
            if (count == 1 && a.size() == 2 && a.get(0) instanceof String && a.get(1) instanceof String) {
                out.add(a.getString(0));
            } else {
                for (int i = 0; i < a.size(); i++) {
                    Object item = a.get(i);
                    out.add(item instanceof JsonArray ? ((JsonArray) item).getString(0, "") : String.valueOf(item));
                }
            }
        }
        if (out.size() != count) throw new IOException("translation returned " + out.size() + " of " + count);
        return out;
    }

    private static byte[] read(InputStream raw) throws IOException {
        try (InputStream in = raw) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_SOURCE) throw new IOException("subtitle too large");
            }
            return buf.toByteArray();
        }
    }
}
