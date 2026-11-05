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

    private static final Set<String> IDS = ConcurrentHashMap.newKeySet();
    private static final List<long[]> PRINTS = new CopyOnWriteArrayList<>();
    private static final List<String> LINES = new ArrayList<>();
    private static final String ALIAS = "orvid_known";
    private static File file;

    private Known() {
    }

    public static synchronized void load(File dir) {
        IDS.clear();
        PRINTS.clear();
        LINES.clear();
        file = new File(dir, "known.bin");
        if (!file.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            String text = new String(open(com.orvid.net.Streams.readAll(in)), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isEmpty()) continue;
                LINES.add(line);
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                if (!p[0].isEmpty()) IDS.add(p[0]);
                if (!p[1].isEmpty()) PRINTS.add(new long[] {Long.parseUnsignedLong(p[1], 16), Long.parseLong(p[2])});
            }
        } catch (Exception ignored) {
        }
    }

    public static int count() {
        return IDS.size();
    }

    public static void add(StreamInfoItem item) {
        String id = id(item.getUrl());
        Long print = com.orvid.image.ThumbPrint.of(item.getUrl());
        long duration = item.getDuration();
        if (id != null) IDS.add(id);
        if (print != null && duration > 0) PRINTS.add(new long[] {print, duration});
        String line = (id == null ? "" : id) + "\t"
                + (print != null && duration > 0 ? Long.toHexString(print) : "") + "\t" + Math.max(0, duration);
        new Thread(() -> append(line), "orvid-known").start();
    }

    public static synchronized void clear() {
        IDS.clear();
        PRINTS.clear();
        LINES.clear();
        if (file != null) file.delete();
    }

    public static boolean hides(StreamInfoItem item) {
        if (IDS.isEmpty()) return false;
        String id = id(item.getUrl());
        if (id != null && IDS.contains(id)) return true;
        long duration = item.getDuration();
        if (duration <= 0 || PRINTS.isEmpty()) return false;
        Long print = com.orvid.image.ThumbPrint.of(item.getUrl());
        if (print == null) return false;
        for (long[] k : PRINTS) {
            if (com.orvid.image.ThumbPrint.same(print, k[0]) && Math.abs(duration - k[1]) <= 2) return true;
        }
        return false;
    }

    private static synchronized void append(String line) {
        if (file == null) return;
        LINES.add(line);
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(seal(String.join("\n", LINES).getBytes(StandardCharsets.UTF_8)));
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

    private static byte[] seal(byte[] plain) throws Exception {
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

    private static byte[] open(byte[] sealed) throws Exception {
        int n = sealed[0];
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), new javax.crypto.spec.GCMParameterSpec(128, sealed, 1, n));
        return c.doFinal(sealed, 1 + n, sealed.length - 1 - n);
    }

    private static String id(String url) {
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
