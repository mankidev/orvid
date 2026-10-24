package com.orvid.ext;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import com.orvid.net.Fetch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

public final class Repos {

    public static final class Repo {
        public String id;
        public String url;
        public String name;
        public String key;
        public long updated;
        public int count;
        public boolean adult;
    }

    public static final class Link {
        public String name;
        public String url;
        public boolean adult;
    }

    public static final String OFFICIAL = "https://raw.githubusercontent.com/mankidev/orvid-sources/main/";
    public static final String SECRET = "https://raw.githubusercontent.com/mankidev/orvid-sources-secret/main/";
    public static final String FILMS = "https://raw.githubusercontent.com/mankidev/orvid-films/main/";

    public static boolean installed(File root, String url) {
        String wanted = canonical(normalize(url));
        for (Repo r : list(root)) if (canonical(r.url).equals(wanted)) return true;
        return false;
    }

    private static String canonical(String url) {
        return url.replace("://raw.githubusercontent.com/DavutKaraoglan/", "://raw.githubusercontent.com/mankidev/");
    }

    public static List<Link> suggested(File root) {
        List<Link> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Repo r : list(root)) seen.add(r.url);
        for (Repo r : list(root)) {
            for (Link l : others(root, r)) if (seen.add(l.url)) out.add(l);
        }
        return out;
    }

    public static List<Link> others(File root, Repo r) {
        List<Link> out = new ArrayList<>();
        try {
            JsonObject index = JsonParser.object().from(Registry.read(new File(new File(root, r.id), "index.json")));
            JsonArray repos = index.getArray("repos");
            if (repos == null) return out;
            for (int i = 0; i < repos.size(); i++) {
                JsonObject o = repos.getObject(i);
                String url = o.getString("url", null);
                if (url == null || !url.startsWith("https://")) continue;
                Link l = new Link();
                l.name = o.getString("name", url);
                l.url = normalize(url);
                l.adult = o.getBoolean("adult", false);
                out.add(l);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_\\-]+\\.(json|js|png|webp)");
    private static final int MAX_FILE = 2 * 1024 * 1024;
    private static final int PARALLEL = 6;

    private Repos() {
    }

    static String safeName(String name) throws IOException {
        if (name == null || !SAFE.matcher(name).matches()) throw new IOException("bad file name " + name);
        return name;
    }

    public static String normalize(String input) {
        String url = input.trim();
        if (url.endsWith("/index.json")) url = url.substring(0, url.length() - "index.json".length());
        if (!url.endsWith("/")) url = url + "/";
        return url;
    }

    public static List<Repo> list(File root) {
        List<Repo> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            try {
                JsonObject m = JsonParser.object().from(Registry.read(new File(d, "meta.json")));
                Repo r = new Repo();
                r.id = d.getName();
                r.url = m.getString("url");
                r.name = m.getString("name", r.url);
                r.key = m.getString("key");
                r.updated = m.getLong("updated", 0);
                r.count = m.getInt("count", 0);
                out.add(r);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static Repo add(File root, String input, String circuit) throws Exception {
        return install(root, normalize(input), null, circuit);
    }

    public static Repo update(File root, Repo repo, String circuit) throws Exception {
        return install(root, repo.url, repo.key, circuit);
    }

    public static void remove(File root, String id) {
        File dir = new File(root, id);
        if (!dir.getParentFile().equals(root)) return;
        delete(dir);
        Registry.reload(root);
    }

    private static Repo install(File root, String base, String pinnedKey, String circuit) throws Exception {
        byte[] indexBytes = download(circuit, base + "index.json");
        Repo same = unchanged(root, base, pinnedKey, indexBytes);
        if (same != null) return same;
        byte[] sig = Base64.getDecoder().decode(new String(download(circuit, base + "index.sig"), StandardCharsets.US_ASCII).trim());
        JsonObject index = JsonParser.object().from(new String(indexBytes, StandardCharsets.UTF_8));
        String key = index.getString("key", "");
        if (pinnedKey != null && !pinnedKey.equals(key)) throw new IOException("repository key changed");
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
        Signature verify = Signature.getInstance("SHA256withECDSA");
        verify.initVerify(publicKey);
        verify.update(indexBytes);
        if (!verify.verify(sig)) throw new IOException("bad repository signature");
        String id = hex(sha256(key.getBytes(StandardCharsets.US_ASCII))).substring(0, 16);

        File target = new File(root, id);
        File staging = new File(root, "." + id + ".new");
        delete(staging);
        if (!staging.mkdirs()) throw new IOException("cannot write");
        JsonObject files = index.getObject("files");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL);
        try {
            List<java.util.concurrent.Future<Void>> jobs = new ArrayList<>();
            for (String name : files.keySet()) {
                String safe = safeName(name);
                String expected = files.getString(name);
                File old = new File(target, safe);
                if (old.isFile() && old.length() <= MAX_FILE) {
                    byte[] body = java.nio.file.Files.readAllBytes(old.toPath());
                    if (hex(sha256(body)).equalsIgnoreCase(expected)) {
                        write(new File(staging, safe), body);
                        continue;
                    }
                }
                jobs.add(pool.submit(() -> {
                    byte[] body = download(circuit, base + safe);
                    if (!hex(sha256(body)).equalsIgnoreCase(expected)) throw new IOException("checksum mismatch " + safe);
                    write(new File(staging, safe), body);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<Void> job : jobs) {
                try {
                    job.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        write(new File(staging, "index.json"), indexBytes);
        Repo r = new Repo();
        r.id = id;
        r.url = base;
        r.name = index.getString("name", base);
        r.key = key;
        r.updated = System.currentTimeMillis();
        JsonArray sources = index.getArray("sources");
        r.count = sources.size();
        for (int i = 0; i < sources.size() && !r.adult; i++) {
            try {
                r.adult = JsonParser.object().from(Registry.read(new File(staging, safeName(sources.getString(i)))))
                        .getBoolean("adult", false);
            } catch (Exception ignored) {
            }
        }
        JsonObject meta = new JsonObject();
        meta.put("url", r.url);
        meta.put("name", r.name);
        meta.put("key", r.key);
        meta.put("updated", r.updated);
        meta.put("count", r.count);
        write(new File(staging, "meta.json"), JsonWriter.string(meta).getBytes(StandardCharsets.UTF_8));
        Registry.load(staging);
        delete(target);
        if (!staging.renameTo(target)) throw new IOException("cannot install");
        Registry.reload(root);
        return r;
    }

    private static Repo unchanged(File root, String base, String pinnedKey, byte[] indexBytes) {
        if (pinnedKey == null) return null;
        for (Repo r : list(root)) {
            if (!r.url.equals(base) || !r.key.equals(pinnedKey)) continue;
            File dir = new File(root, r.id);
            try {
                byte[] local = java.nio.file.Files.readAllBytes(new File(dir, "index.json").toPath());
                if (!java.util.Arrays.equals(local, indexBytes)) return null;
                JsonObject meta = JsonParser.object().from(Registry.read(new File(dir, "meta.json")));
                r.updated = System.currentTimeMillis();
                meta.put("updated", r.updated);
                write(new File(dir, "meta.json"), JsonWriter.string(meta).getBytes(StandardCharsets.UTF_8));
                return r;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static byte[] download(String circuit, String url) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("repository must use https");
        return Fetch.on(circuit, () -> {
            HttpURLConnection c = Fetch.open(url);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " for " + url.substring(url.lastIndexOf('/') + 1));
            try (InputStream in = c.getInputStream()) {
                byte[] body = Streams.readN(in, MAX_FILE + 1);
                if (body.length > MAX_FILE) throw new IOException("file too large");
                return body;
            }
        });
    }

    private static void write(File f, byte[] body) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(body);
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
