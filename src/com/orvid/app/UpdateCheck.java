package com.orvid.app;

import com.orvid.net.Streams;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.orvid.R;
import com.orvid.net.Fetch;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

public final class UpdateCheck {

    private static final String LATEST = "https://api.github.com/repositories/1422380931/releases/latest";
    private static final long INTERVAL_MS = 12 * 60 * 60 * 1000L;
    private static volatile boolean started;

    private UpdateCheck() {
    }

    public static void run(Activity activity) {
        if (started) return;
        SharedPreferences prefs = activity.getSharedPreferences("settings", Activity.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("update_checked", 0) < INTERVAL_MS) return;
        started = true;
        String current = current(activity);
        Thread t = new Thread(() -> {
            try {
                JsonObject release = Fetch.on(Fetch.newCircuit("update"), () -> {
                    HttpURLConnection c = Fetch.open(LATEST);
                    c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    try (java.io.InputStream in = c.getInputStream()) {
                        return JsonParser.object().from(new String(Streams.readAll(in), StandardCharsets.UTF_8));
                    }
                });
                prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply();
                String tag = release.getString("tag_name", "");
                String page = release.getString("html_url", "");
                if (release.getBoolean("draft", false) || release.getBoolean("prerelease", false)) return;
                if (!page.startsWith("https://github.com/") || !page.contains("/orvid/releases/")) return;
                if (newer(tag, current) <= 0 || tag.equals(prefs.getString("update_skipped", ""))) return;
                activity.runOnUiThread(() -> show(activity, prefs, tag, page));
            } catch (Exception ignored) {
                started = false;
            }
        }, "orvid-update");
        t.setDaemon(true);
        t.start();
    }

    private static void show(Activity activity, SharedPreferences prefs, String tag, String page) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(activity.getString(R.string.update_message, tag))
                .setPositiveButton(R.string.update_open, (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                    } catch (android.content.ActivityNotFoundException ignored) {
                    }
                })
                .setNegativeButton(R.string.update_later, (d, w) -> prefs.edit().putString("update_skipped", tag).apply())
                .show();
    }

    private static String current(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    static int newer(String tag, String current) {
        String[] a = tag.replaceFirst("^[vV]", "").split("[.-]");
        String[] b = current.replaceFirst("^[vV]", "").split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int number(String part) {
        String digits = part.replaceAll("\\D.*", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }
}
