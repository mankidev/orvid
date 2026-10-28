package com.orvid.search;

import android.content.SharedPreferences;

import com.orvid.app.OrvidApp;

public final class Health {

    public static final int OK = 0;
    public static final int FLAKY = 1;
    public static final int DOWN = 2;
    public static final int EMPTY = 3;
    public static final int LIMITED = 4;

    private static final int FLAKY_AT = 3;
    private static final int DOWN_AT = 5;
    private static final int EMPTY_AT = 3;
    private static final long RETRY_MS = 6 * 60 * 60 * 1000L;
    private static final long LIMIT_MS = 15 * 60 * 1000L;
    private static final java.util.Map<String, Long> LIMITED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    private Health() {
    }

    private static SharedPreferences prefs() {
        return OrvidApp.get().getSharedPreferences("health", 0);
    }

    public static synchronized int state(String key) {
        SharedPreferences p = prefs();
        int fails = p.getInt(key + ".fails", 0);
        if (fails >= DOWN_AT) return DOWN;
        if (limitedNow(key)) return LIMITED;
        if (fails >= FLAKY_AT) return FLAKY;
        return !p.getBoolean(key + ".worked", false) && p.getInt(key + ".empty", 0) >= EMPTY_AT ? EMPTY : OK;
    }

    public static synchronized boolean skip(String key) {
        SharedPreferences p = prefs();
        if (limitedNow(key)) return true;
        if (p.getInt(key + ".fails", 0) < DOWN_AT) return false;
        long now = System.currentTimeMillis();
        if (now - p.getLong(key + ".tried", 0) < RETRY_MS) return true;
        p.edit().putLong(key + ".tried", now).apply();
        return false;
    }

    public static synchronized void ok(String key) {
        SharedPreferences p = prefs();
        LIMITED_AT.remove(key);
        if (p.getBoolean(key + ".worked", false) && !p.contains(key + ".fails") && !p.contains(key + ".empty")) return;
        p.edit().remove(key + ".fails").remove(key + ".tried").remove(key + ".empty").putBoolean(key + ".worked", true).apply();
    }

    public static boolean limited(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains("HTTP 429")) return true;
        }
        return false;
    }

    public static void limit(String key) {
        LIMITED_AT.put(key, System.currentTimeMillis());
    }

    private static boolean limitedNow(String key) {
        Long at = LIMITED_AT.get(key);
        return at != null && System.currentTimeMillis() - at < LIMIT_MS;
    }

    public static synchronized void fail(String key) {
        SharedPreferences p = prefs();
        int fails = p.getInt(key + ".fails", 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt(key + ".fails", fails);
        if (fails == DOWN_AT) e.putLong(key + ".tried", System.currentTimeMillis());
        e.apply();
    }

    public static synchronized void empty(String key) {
        SharedPreferences p = prefs();
        p.edit().remove(key + ".fails").remove(key + ".tried").putInt(key + ".empty", p.getInt(key + ".empty", 0) + 1).apply();
    }

    public static synchronized void reset(String key) {
        LIMITED_AT.remove(key);
        prefs().edit().remove(key + ".fails").remove(key + ".tried").remove(key + ".empty").apply();
    }
}
