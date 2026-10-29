package com.orvid.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

public final class TempLogs {

    public static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/Orvid/tmp/";
    private static final long KEEP_SECONDS = 24 * 60 * 60;

    private TempLogs() {
    }

    public static void sweep(Context context) {
        if (Build.VERSION.SDK_INT < 29) return;
        long cutoff = System.currentTimeMillis() / 1000 - KEEP_SECONDS;
        ContentResolver resolver = context.getContentResolver();
        Uri table = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String where = "(" + MediaStore.Downloads.RELATIVE_PATH + " = ? OR (" + MediaStore.Downloads.RELATIVE_PATH
                + " = ? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE 'orvid-%')) AND "
                + MediaStore.Downloads.DATE_ADDED + " < ?";
        String[] args = {FOLDER, Environment.DIRECTORY_DOWNLOADS + "/", Long.toString(cutoff)};
        try (Cursor c = resolver.query(table, new String[]{MediaStore.Downloads._ID}, where, args, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                try {
                    resolver.delete(Uri.withAppendedPath(table, Long.toString(c.getLong(0))), null, null);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }
}
