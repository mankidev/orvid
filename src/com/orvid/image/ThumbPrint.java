package com.orvid.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ThumbPrint {

    public interface Listener {
        void printed(String page);
    }

    private static final int CLOSE = 6;
    private static final Map<String, Long> PRINTS = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 3000;
                }
            });
    private static final List<WeakReference<Listener>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThumbPrint() {
    }

    public static void listen(Listener listener) {
        LISTENERS.add(new WeakReference<>(listener));
    }

    public static Long of(String page) {
        return page == null ? null : PRINTS.get(page);
    }

    public static boolean same(long a, long b) {
        return Long.bitCount(a ^ b) <= CLOSE;
    }

    static void record(String page, byte[] bytes) {
        if (page == null || bytes == null || PRINTS.containsKey(page)) return;
        Long print = hash(bytes);
        if (print == null) return;
        PRINTS.put(page, print);
        MAIN.post(() -> {
            for (WeakReference<Listener> ref : LISTENERS) {
                Listener listener = ref.get();
                if (listener == null) LISTENERS.remove(ref);
                else listener.printed(page);
            }
        });
    }

    private static Long hash(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 9 || bounds.outHeight < 8) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 36 && bounds.outHeight / (sample * 2) >= 32) sample *= 2;
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(decoded, 9, 8, true);
        int[] px = new int[72];
        small.getPixels(px, 0, 9, 0, 0, 9, 8);
        if (small != decoded) small.recycle();
        decoded.recycle();
        long bits = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                bits <<= 1;
                if (luma(px[row * 9 + col]) > luma(px[row * 9 + col + 1])) bits |= 1;
            }
        }
        int set = Long.bitCount(bits);
        return set < 8 || set > 56 ? null : bits;
    }

    private static int luma(int c) {
        return ((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114;
    }
}
