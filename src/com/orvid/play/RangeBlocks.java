package com.orvid.play;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

final class RangeBlocks {

    interface Source {
        byte[] fetch(long from, long length) throws IOException;
    }

    final class Reader {
        private long next = -1;
        private int run = 1;

        int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
            long index = position / block;
            byte[] b = block(index, this);
            int inBlock = (int) (position - index * block);
            if (inBlock >= b.length) return -1;
            int n = Math.min(length, b.length - inBlock);
            System.arraycopy(b, inBlock, buffer, offset, n);
            return n;
        }
    }

    private static final byte[] EMPTY = new byte[0];

    private final Source source;
    private final int block;
    private final int runMax;
    private final Map<Long, byte[]> blocks;
    private final Map<Long, CompletableFuture<byte[]>> pending = new HashMap<>();

    RangeBlocks(Source source, int block, int runMax, final int keep) {
        this.source = source;
        this.block = block;
        this.runMax = runMax;
        this.blocks = new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > keep;
            }
        };
    }

    Reader reader() {
        return new Reader();
    }

    int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        return reader().readAt(position, buffer, offset, length);
    }

    private byte[] block(long index, Reader r) throws IOException {
        CompletableFuture<byte[]> wait;
        int want = 0;
        synchronized (this) {
            byte[] b = blocks.get(index);
            if (b != null) return b;
            wait = pending.get(index);
            if (wait == null) {
                r.run = index == r.next ? Math.min(r.run * 2, runMax) : 1;
                want = r.run;
                for (int i = 1; i < want; i++) {
                    if (blocks.containsKey(index + i) || pending.containsKey(index + i)) {
                        want = i;
                        break;
                    }
                }
                for (int i = 0; i < want; i++) pending.put(index + i, new CompletableFuture<>());
            }
        }
        if (wait != null) {
            r.next = index + 1;
            return join(wait);
        }
        byte[] data;
        try {
            data = source.fetch(index * block, (long) want * block);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                for (int i = 0; i < want; i++) {
                    CompletableFuture<byte[]> f = pending.remove(index + i);
                    if (f != null) f.completeExceptionally(e);
                }
            }
            throw e;
        }
        byte[] first = EMPTY;
        synchronized (this) {
            for (int i = 0; i < want; i++) {
                int from = i * block;
                byte[] part = from >= data.length ? EMPTY
                        : Arrays.copyOfRange(data, from, Math.min(data.length, from + block));
                if (i == 0) first = part;
                if (part.length > 0) blocks.put(index + i, part);
                CompletableFuture<byte[]> f = pending.remove(index + i);
                if (f != null) f.complete(part);
            }
        }
        r.next = index + want;
        return first;
    }

    private static byte[] join(CompletableFuture<byte[]> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c);
        }
    }
}
