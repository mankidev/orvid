package com.orvid.search;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Known {

    public static final Known MARKED = new Known("known.bin");

    private final Set<String> ids = ConcurrentHashMap.newKeySet();
    private final List<long[]> prints = new CopyOnWriteArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private static final String ALIAS = "orvid_known";
    private final String name;
    private File file;

    private Known(String name) {
        this.name = name;
    }

    public static void loadAll(File dir) {
        MARKED.load(dir);
    }

    public synchronized void load(File dir) {
        ids.clear();
        prints.clear();
        lines.clear();
        file = new File(dir, name);
        if (!file.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            String text = new String(open(com.orvid.net.Streams.readAll(in)), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isEmpty()) continue;
                lines.add(line);
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                if (!p[0].isEmpty()) ids.add(p[0]);
                if (!p[1].isEmpty()) prints.add(new long[] {Long.parseUnsignedLong(p[1], 16), Long.parseLong(p[2])});
            }
        } catch (Exception ignored) {
        }
    }

    public int count() {
        return ids.size();
    }

    public void add(StreamInfoItem item) {
        add(item.getUrl(), item.getDuration());
    }

    public void add(String url, long duration) {
        String id = id(url);
        if (id != null && ids.contains(id)) return;
        Long print = com.orvid.image.ThumbPrint.of(url);
        if (id != null) ids.add(id);
        if (print != null && duration > 0) prints.add(new long[] {print, duration});
        String line = (id == null ? "" : id) + "\t"
                + (print != null && duration > 0 ? Long.toHexString(print) : "") + "\t" + Math.max(0, duration);
        new Thread(() -> append(line), "orvid-known").start();
    }

    public synchronized void clear() {
        ids.clear();
        prints.clear();
        lines.clear();
        if (file != null) file.delete();
    }

    public boolean hides(StreamInfoItem item) {
        if (ids.isEmpty()) return false;
        String id = id(item.getUrl());
        if (id != null && ids.contains(id)) return true;
        String raw = hash(item.getUrl());
        if (raw != null && ids.contains(raw)) return true;
        long duration = item.getDuration();
        if (duration <= 0 || prints.isEmpty()) return false;
        Long print = com.orvid.image.ThumbPrint.of(item.getUrl());
        if (print == null) return false;
        for (long[] k : prints) {
            if (com.orvid.image.ThumbPrint.same(print, k[0]) && Math.abs(duration - k[1]) <= 2) return true;
        }
        return false;
    }

    private synchronized void append(String line) {
        if (file == null) return;
        lines.add(line);
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(seal(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
            out.getFD().sync();
        } catch (Exception e) {
            temp.delete();
            return;
        }
        temp.renameTo(file);
    }

    private static javax.crypto.SecretKey key() throws Exception {
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof javax.crypto.SecretKey) return (javax.crypto.SecretKey) existing;
        javax.crypto.KeyGenerator gen = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    static byte[] seal(byte[] plain) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] body = c.doFinal(plain);
        byte[] out = new byte[1 + iv.length + body.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(body, 0, out, 1 + iv.length, body.length);
        return out;
    }

    static byte[] open(byte[] sealed) throws Exception {
        int n = sealed[0];
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), new javax.crypto.spec.GCMParameterSpec(128, sealed, 1, n));
        return c.doFinal(sealed, 1 + n, sealed.length - 1 - n);
    }

    private static final java.util.regex.Pattern YOUTUBE = java.util.regex.Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    public static String canonical(String url) {
        java.util.regex.Matcher m = YOUTUBE.matcher(url);
        if (m.find()) return "https://www.youtube.com/watch?v=" + m.group(1);
        String s = url.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        s = s.replaceFirst("^(?i)https?://(www\\.|m\\.)?", "https://");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String id(String url) {
        if (url == null || url.isEmpty()) return null;
        return hash(canonical(url));
    }

    private static String hash(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
