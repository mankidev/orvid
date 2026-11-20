package com.orvid.ui;

import android.net.Uri;

import androidx.media3.datasource.DataSink;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class FeedCache {
    private FeedCache() {
    }

    private static final int KEEP_VIDEOS = 5;
    private static final int KEEP_PENDING = 3;
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final String DIR = "watch";

    private static SimpleCache cache;
    private static SecretKeySpec key;
    private static final LinkedHashSet<String> RECENT = new LinkedHashSet<>();
    private static final LinkedHashSet<String> PENDING = new LinkedHashSet<>();
    private static final ExecutorService SWEEPER = Executors.newSingleThreadExecutor(r -> new Thread(r, "orvid-cache"));

    private static File dir() {
        return new File(com.orvid.app.OrvidApp.get().getCacheDir(), DIR);
    }

    private static synchronized SimpleCache cache() {
        if (cache != null) return cache;
        File dir = dir();
        wipe(dir);
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        key = new SecretKeySpec(raw, "AES");
        cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES), null, null, false, true);
        return cache;
    }

    public static void prepare() {
        SWEEPER.execute(FeedCache::cache);
    }

    static DataSource.Factory wrap(DataSource.Factory upstream, String page) {
        if (page == null) return upstream;
        SimpleCache c;
        try {
            c = cache();
        } catch (RuntimeException e) {
            return upstream;
        }
        String tag = tag(page);
        synchronized (FeedCache.class) {
            if (!RECENT.contains(tag)) {
                PENDING.remove(tag);
                PENDING.add(tag);
                while (PENDING.size() > KEEP_PENDING) PENDING.remove(PENDING.iterator().next());
            }
        }
        CacheKeyFactory keys = spec -> tag + "/" + hash(CacheKeyFactory.DEFAULT.buildCacheKey(spec));
        CacheDataSource.Factory cached = new CacheDataSource.Factory()
                .setCache(c)
                .setCacheKeyFactory(keys)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheReadDataSourceFactory(() -> new Decrypting(new FileDataSource()))
                .setCacheWriteDataSinkFactory(() -> new Encrypting(new CacheDataSink(c, CacheDataSink.DEFAULT_FRAGMENT_SIZE)))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        return () -> new Routed(cached.createDataSource(), upstream.createDataSource());
    }

    static void visit(String page) {
        if (page == null) return;
        String tag = tag(page);
        synchronized (FeedCache.class) {
            PENDING.remove(tag);
            RECENT.remove(tag);
            RECENT.add(tag);
            while (RECENT.size() > KEEP_VIDEOS) RECENT.remove(RECENT.iterator().next());
        }
        SWEEPER.execute(FeedCache::sweep);
    }

    private static void sweep() {
        SimpleCache c;
        Set<String> keep;
        synchronized (FeedCache.class) {
            c = cache;
            keep = new HashSet<>(RECENT);
            keep.addAll(PENDING);
        }
        if (c == null) return;
        try {
            for (String k : new ArrayList<>(c.getKeys())) {
                int slash = k.indexOf('/');
                if (slash < 0 || !keep.contains(k.substring(0, slash))) c.removeResource(k);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static synchronized void clear() {
        RECENT.clear();
        PENDING.clear();
        if (cache != null) {
            try {
                cache.release();
            } catch (RuntimeException ignored) {
            }
            cache = null;
        }
        key = null;
        wipe(dir());
    }

    private static void wipe(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) wipe(k);
        f.delete();
    }

    private static String tag(String page) {
        return hash(com.orvid.search.Known.canonical(page));
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static Cipher cipher(int mode, String resource, long position) throws IOException {
        try {
            byte[] iv = new byte[16];
            byte[] nonce = MessageDigest.getInstance("SHA-256").digest(resource.getBytes(StandardCharsets.UTF_8));
            System.arraycopy(nonce, 0, iv, 0, 8);
            long block = position / 16;
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (block >>> (8 * i));
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(mode, key, new IvParameterSpec(iv));
            int skip = (int) (position % 16);
            if (skip > 0) c.update(new byte[skip]);
            return c;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static boolean manifest(DataSpec spec) {
        String path = spec.uri.getPath();
        if (path == null) return true;
        String p = path.toLowerCase(java.util.Locale.ROOT);
        String scheme = spec.uri.getScheme();
        return p.endsWith(".m3u8") || p.endsWith(".m3u") || p.endsWith(".mpd") || !"https".equals(scheme) && !"http".equals(scheme);
    }

    private static final class Routed implements DataSource {
        private final DataSource cached;
        private final DataSource direct;
        private DataSource open;

        Routed(DataSource cached, DataSource direct) {
            this.cached = cached;
            this.direct = direct;
        }

        @Override
        public void addTransferListener(TransferListener listener) {
            cached.addTransferListener(listener);
            direct.addTransferListener(listener);
        }

        @Override
        public long open(DataSpec spec) throws IOException {
            open = manifest(spec) || key == null ? direct : cached;
            return open.open(spec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return open.read(buffer, offset, length);
        }

        @Override
        public Uri getUri() {
            return open == null ? null : open.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return open == null ? java.util.Collections.emptyMap() : open.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            DataSource o = open;
            open = null;
            if (o != null) o.close();
        }
    }

    private static final class Encrypting implements DataSink {
        private final CacheDataSink inner;
        private Cipher cipher;
        private byte[] scratch = new byte[0];

        Encrypting(CacheDataSink inner) {
            this.inner = inner;
        }

        @Override
        public void open(DataSpec spec) throws IOException {
            cipher = cipher(Cipher.ENCRYPT_MODE, spec.key == null ? spec.uri.toString() : spec.key, spec.position);
            inner.open(spec);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (scratch.length < length) scratch = new byte[length];
            int n;
            try {
                n = cipher.update(buffer, offset, length, scratch, 0);
            } catch (Exception e) {
                throw new IOException(e);
            }
            inner.write(scratch, 0, n);
        }

        @Override
        public void close() throws IOException {
            cipher = null;
            inner.close();
        }
    }

    private static final class Decrypting implements DataSource {
        private final FileDataSource inner;
        private Cipher cipher;
        private byte[] scratch = new byte[0];

        Decrypting(FileDataSource inner) {
            this.inner = inner;
        }

        @Override
        public void addTransferListener(TransferListener listener) {
            inner.addTransferListener(listener);
        }

        @Override
        public long open(DataSpec spec) throws IOException {
            long length = inner.open(spec);
            cipher = cipher(Cipher.DECRYPT_MODE, spec.key == null ? spec.uri.toString() : spec.key,
                    spec.uriPositionOffset + spec.position);
            return length;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = inner.read(buffer, offset, length);
            if (n <= 0) return n;
            if (scratch.length < n) scratch = new byte[n];
            int out;
            try {
                out = cipher.update(buffer, offset, n, scratch, 0);
            } catch (Exception e) {
                throw new IOException(e);
            }
            if (out != n) throw new IOException("cipher");
            System.arraycopy(scratch, 0, buffer, offset, n);
            return n;
        }

        @Override
        public Uri getUri() {
            return inner.getUri();
        }

        @Override
        public void close() throws IOException {
            cipher = null;
            inner.close();
        }
    }
}
