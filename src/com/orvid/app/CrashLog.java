package com.orvid.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;

import com.orvid.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLog {

    private static boolean installed;

    private CrashLog() {
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    write(app, thread, error);
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    private static final long EXIT_LOOKBACK_MS = 24L * 60 * 60 * 1000;
    private static final int TRACE_MAX = 24 * 1024;

    private static final long SELF_EXIT_SLACK_MS = 10_000;

    static void selfExit(Context context) {
        context.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().putLong("self_exit", System.currentTimeMillis()).commit();
    }

    private static void collectExits(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE);
            long seen = prefs.getLong("exit_seen", System.currentTimeMillis() - EXIT_LOOKBACK_MS);
            android.app.ActivityManager am = context.getSystemService(android.app.ActivityManager.class);
            java.util.List<android.app.ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            long self = prefs.getLong("self_exit", 0);
            long newest = seen;
            StringBuilder report = new StringBuilder();
            for (android.app.ApplicationExitInfo e : exits) {
                if (e.getTimestamp() <= seen) continue;
                newest = Math.max(newest, e.getTimestamp());
                if (!abnormal(e)) continue;
                if (e.getReason() == android.app.ApplicationExitInfo.REASON_SIGNALED && Math.abs(e.getTimestamp() - self) < SELF_EXIT_SLACK_MS) continue;
                report.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(e.getTimestamp())))
                    .append("\nandroid ").append(Build.VERSION.SDK_INT).append(", ").append(Build.MODEL)
                    .append("\nexit ").append(reason(e.getReason())).append(", status ").append(e.getStatus())
                    .append(", importance ").append(e.getImportance())
                    .append(", pss ").append(e.getPss() / 1024).append(" MB, rss ").append(e.getRss() / 1024).append(" MB\n");
                if (e.getDescription() != null) report.append(e.getDescription()).append('\n');
                if (e.getReason() == android.app.ApplicationExitInfo.REASON_ANR) report.append(mainThread(e));
                report.append("\n----\n");
            }
            prefs.edit().putLong("exit_seen", newest).apply();
            if (report.length() == 0) return;
            FileOutputStream out = new FileOutputStream(file(context), true);
            try {
                out.write(report.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean abnormal(android.app.ApplicationExitInfo e) {
        switch (e.getReason()) {
            case android.app.ApplicationExitInfo.REASON_ANR:
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE:
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY:
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return true;
            case android.app.ApplicationExitInfo.REASON_SIGNALED:
                return e.getImportance() <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
            default:
                return false;
        }
    }

    private static String reason(int r) {
        switch (r) {
            case android.app.ApplicationExitInfo.REASON_ANR: return "ANR (not responding)";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive resource use";
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "init failure";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "killed by signal";
            default: return "reason " + r;
        }
    }

    private static String mainThread(android.app.ApplicationExitInfo e) {
        try (java.io.InputStream in = e.getTraceInputStream()) {
            if (in == null) return "";
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            StringBuilder out = new StringBuilder();
            boolean inMain = false;
            for (String line = r.readLine(); line != null && out.length() < TRACE_MAX; line = r.readLine()) {
                if (line.startsWith("\"main\"")) inMain = true;
                else if (inMain && line.isEmpty()) break;
                if (inMain) out.append(line).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            return "trace unreadable: " + t + "\n";
        }
    }

    public static void showPending(final Activity activity) {
        collectExits(activity);
        File f = file(activity);
        if (!f.exists() || f.length() == 0) return;
        final String report = read(f);
        f.delete();
        if (report == null) return;
        new AlertDialog.Builder(activity, R.style.AppDialog)
            .setTitle(R.string.crash_title)
            .setMessage(report)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.crash_share, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, report), null));
                }
            })
            .show();
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "crash.txt");
    }

    private static String read(File f) {
        RandomAccessFile in = null;
        try {
            in = new RandomAccessFile(f, "r");
            byte[] raw = new byte[(int) in.length()];
            in.readFully(raw);
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void write(Context app, Thread thread, Throwable error) throws Exception {
        StringWriter sw = new StringWriter();
        sw.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        sw.write("\nandroid " + Build.VERSION.SDK_INT + ", " + Build.MODEL + ", thread " + thread.getName() + "\n");
        error.printStackTrace(new PrintWriter(sw));
        sw.write("\n----\n");
        FileOutputStream out = new FileOutputStream(file(app), true);
        try {
            out.write(sw.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
