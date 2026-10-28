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

    public static void showPending(final Activity activity) {
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
