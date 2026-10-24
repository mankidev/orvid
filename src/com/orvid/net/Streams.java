package com.orvid.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class Streams {

    private Streams() {
    }

    public static byte[] readAll(InputStream in) throws IOException {
        return readN(in, Integer.MAX_VALUE);
    }

    public static byte[] readN(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        while (buf.size() < max) {
            int n = in.read(chunk, 0, Math.min(chunk.length, max - buf.size()));
            if (n < 0) break;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
