package com.orvid.ui;

import android.content.Context;

import com.orvid.R;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class Ui {

    private Ui() {
    }

    private static final long DOUBLE_TAP_MS = 800;
    private static long lastOpen;

    static boolean doubleTap() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastOpen < DOUBLE_TAP_MS) return true;
        lastOpen = now;
        return false;
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static String duration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    public static String count(long n) {
        if (n < 0) return "";
        if (n < 1000) return Long.toString(n);
        String[] units = {"K", "M", "B"};
        double v = n;
        int u = -1;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        return (v < 10 ? String.format(Locale.getDefault(), "%.1f", v)
                : String.format(Locale.getDefault(), "%.0f", v)) + units[u];
    }

    public static String joinParts(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern YT_THUMB =
            java.util.regex.Pattern.compile("^https://i\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/");

    private static final java.util.Map<String, android.graphics.Bitmap> ICONS = new java.util.concurrent.ConcurrentHashMap<>();

    public static android.graphics.drawable.Drawable sourceIcon(Context context, com.orvid.search.Source s) {
        if (s.iconFile != null) {
            android.graphics.Bitmap b = ICONS.get(s.iconFile);
            if (b == null) {
                b = android.graphics.BitmapFactory.decodeFile(s.iconFile);
                if (b != null) ICONS.put(s.iconFile, b);
            }
            if (b != null) return new android.graphics.drawable.BitmapDrawable(context.getResources(), b);
        }
        return s.icon != 0 ? context.getDrawable(s.icon) : null;
    }

    public static android.graphics.drawable.Drawable iconForUrl(Context context, String url) {
        if (url == null) return null;
        com.orvid.search.Source s = Sources.forUrl(url);
        if (s != null) return sourceIcon(context, s);
        if (com.orvid.yt.Visionos.videoId(url) != null) return context.getDrawable(R.drawable.ic_src_youtube);
        if (com.orvid.dm.Dailymotion.videoId(url) != null) return context.getDrawable(R.drawable.ic_src_dailymotion);
        if (com.orvid.vk.Vk.videoId(url) != null) return context.getDrawable(R.drawable.ic_src_vk);
        return null;
    }

    private static final int HUES = 24;
    private static final int ACCENT_SIDE = 32;
    private static final int ACCENT_MIN_PIXELS = 20;

    public static int accent(android.graphics.drawable.Drawable icon) {
        if (icon == null) return 0;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(ACCENT_SIDE, ACCENT_SIDE, android.graphics.Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, ACCENT_SIDE, ACCENT_SIDE);
        icon.draw(new android.graphics.Canvas(b));
        int[] pixels = new int[ACCENT_SIDE * ACCENT_SIDE];
        b.getPixels(pixels, 0, ACCENT_SIDE, 0, 0, ACCENT_SIDE, ACCENT_SIDE);
        b.recycle();
        int[] count = new int[HUES];
        long[][] sum = new long[HUES][3];
        float[] hsv = new float[3];
        for (int px : pixels) {
            if (android.graphics.Color.alpha(px) < 128) continue;
            android.graphics.Color.colorToHSV(px, hsv);
            if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue;
            int k = (int) (hsv[0] / (360f / HUES)) % HUES;
            count[k]++;
            sum[k][0] += android.graphics.Color.red(px);
            sum[k][1] += android.graphics.Color.green(px);
            sum[k][2] += android.graphics.Color.blue(px);
        }
        int best = 0;
        for (int k = 1; k < HUES; k++) if (count[k] > count[best]) best = k;
        int n = count[best];
        if (n < ACCENT_MIN_PIXELS) return 0;
        return android.graphics.Color.rgb((int) (sum[best][0] / n), (int) (sum[best][1] / n), (int) (sum[best][2] / n));
    }

    public static String thumbnail(List<Image> images, int targetWidth) {
        String picked = pickThumbnail(images, targetWidth);
        if (picked == null) return null;
        java.util.regex.Matcher m = YT_THUMB.matcher(picked);
        if (!m.find()) return picked;
        return targetWidth > 480 ? "https://i.ytimg.com/vi_webp/" + m.group(1) + "/hq720.webp"
                : "https://i.ytimg.com/vi/" + m.group(1) + "/hqdefault.jpg";
    }

    private static String pickThumbnail(List<Image> images, int targetWidth) {
        int enough = targetWidth <= 400 ? targetWidth * 3 / 4 : targetWidth;
        Image best = null;
        for (Image i : images) {
            if (best == null) {
                best = i;
                continue;
            }
            int w = i.getWidth() > 0 ? i.getWidth() : i.getHeight() * 16 / 9;
            int bw = best.getWidth() > 0 ? best.getWidth() : best.getHeight() * 16 / 9;
            boolean fits = w >= enough;
            boolean bestFits = bw >= enough;
            if (fits && (!bestFits || w < bw) || !fits && !bestFits && w > bw) best = i;
        }
        return best == null ? null : best.getUrl();
    }

    public static String error(Context context, Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.orvid.net.Fetch.TorUnavailable) return context.getString(R.string.error_tor);
        }
        if (e instanceof ReCaptchaException || e instanceof SignInConfirmNotBotException) {
            if (!com.orvid.net.Fetch.torMode()) return context.getString(R.string.error_ip_blocked);
            return context.getString(R.string.error_captcha);
        }
        if (e instanceof AgeRestrictedContentException) return context.getString(R.string.error_age);
        if (e instanceof ContentNotAvailableException || e instanceof com.orvid.ext.ExtSource.Gone) {
            return context.getString(R.string.error_unavailable);
        }
        if (e instanceof IOException) return context.getString(R.string.error_network);
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
