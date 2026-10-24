package com.orvid.adult;

import com.orvid.net.Streams;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class Web {

    private Web() {
    }

    static String getUntil(String circuit, String url, String label, Map<String, String> headers,
                           java.util.regex.Pattern marker, int tail, int timeoutMs) throws IOException {
        long began = System.currentTimeMillis();
        try {
            String text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                c.setConnectTimeout(timeoutMs);
                c.setReadTimeout(timeoutMs);
                int code = c.getResponseCode();
                if (code == 403 || code == 429) throw new IOException("Captcha needed (HTTP " + code + ")");
                if (code >= 400) throw new IOException("HTTP " + code);
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(1 << 16);
                try (InputStream in = c.getInputStream()) {
                    byte[] chunk = new byte[16384];
                    long stopAt = -1;
                    int checked = 0;
                    for (int n = in.read(chunk); n > 0; n = in.read(chunk)) {
                        buf.write(chunk, 0, n);
                        if (stopAt < 0 && buf.size() - checked >= 16384) {
                            String sofar = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
                            if (marker.matcher(sofar).region(Math.max(0, checked - 256), sofar.length()).find()) {
                                stopAt = buf.size() + tail;
                            }
                            checked = buf.size();
                        }
                        if (stopAt >= 0 && buf.size() >= stopAt) break;
                    }
                }
                c.disconnect();
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            });
            Diag.mark(label + " " + text.length() / 1024 + "KB " + (System.currentTimeMillis() - began) + "ms");
            return text;
        } catch (IOException e) {
            Diag.mark(label + " FAIL " + e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static String get(String circuit, String url, String label, Map<String, String> headers) throws IOException {
        return get(circuit, url, label, headers, 15000);
    }

    public static String get(String circuit, String url, String label, Map<String, String> headers, int timeoutMs) throws IOException {
        long began = System.currentTimeMillis();
        try {
            String text = Fetch.on(circuit, () -> {
                HttpURLConnection c = Fetch.open(url);
                c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                c.setConnectTimeout(timeoutMs);
                c.setReadTimeout(timeoutMs);
                int code = c.getResponseCode();
                if (code == 403 || code == 429) throw new IOException("Captcha needed (HTTP " + code + ")");
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    return new String(Streams.readAll(in), StandardCharsets.UTF_8);
                }
            });
            Diag.mark(label + " " + text.length() / 1024 + "KB " + (System.currentTimeMillis() - began) + "ms");
            return text;
        } catch (IOException e) {
            Diag.mark(label + " FAIL " + e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public static long duration(String text) {
        if (text == null) return -1;
        if (text.contains(":")) return clock(text);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*(h|m|s)").matcher(text.toLowerCase());
        long total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            total += m.group(2).equals("h") ? n * 3600 : m.group(2).equals("m") ? n * 60 : n;
        }
        return any ? total : -1;
    }

    static long clock(String text) {
        if (text == null) return -1;
        long total = 0;
        try {
            for (String part : text.trim().split(":")) total = total * 60 + Long.parseLong(part.trim());
            return total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static long number(String text) {
        try {
            String t = text.trim().toUpperCase().replace(",", ".").replace(" ", "");
            double mult = t.endsWith("K") ? 1e3 : t.endsWith("M") ? 1e6 : 1;
            if (mult > 1) return (long) (Double.parseDouble(t.substring(0, t.length() - 1)) * mult);
            return Long.parseLong(t.replace(".", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    public static String unescape(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s.trim();
        return org.jsoup.parser.Parser.unescapeEntities(s, false).replace('\u00a0', ' ').trim();
    }
}
