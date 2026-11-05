package com.orvid.ui;

import android.app.Activity;
import android.view.MotionEvent;

import com.orvid.app.OrvidApp;

public final class Panic {

    private static final float DROP_DP = 60;
    private static final float SPAN_DRIFT = 0.35f;

    private float startX;
    private float startY;
    private float startSpan;
    private boolean tracking;

    public boolean feed(Activity activity, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                tracking = e.getPointerCount() == 2 && OrvidApp.get().panicExit();
                if (tracking) {
                    startX = midX(e);
                    startY = midY(e);
                    startSpan = span(e);
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                if (e.getPointerCount() != 2) {
                    tracking = false;
                    return false;
                }
                float drop = midY(e) - startY;
                float sideways = Math.abs(midX(e) - startX);
                float drift = startSpan > 0 ? Math.abs(span(e) - startSpan) / startSpan : 1;
                if (drift > SPAN_DRIFT || sideways > Math.max(drop, 0) * 0.5f + Ui.dp(activity, 24)) {
                    tracking = false;
                    return false;
                }
                if (drop < Ui.dp(activity, DROP_DP)) return false;
                tracking = false;
                exit(activity);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return false;
            default:
                return false;
        }
    }

    public static <D extends android.app.Dialog> D guard(Activity activity, D dialog) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return dialog;
        android.view.Window.Callback inner = window.getCallback();
        Panic panic = new Panic();
        window.setCallback((android.view.Window.Callback) java.lang.reflect.Proxy.newProxyInstance(
                android.view.Window.Callback.class.getClassLoader(), new Class<?>[]{android.view.Window.Callback.class},
                (proxy, method, args) -> {
                    if ("dispatchTouchEvent".equals(method.getName()) && panic.feed(activity, (MotionEvent) args[0])) {
                        window.getDecorView().setVisibility(android.view.View.INVISIBLE);
                        return true;
                    }
                    try {
                        return method.invoke(inner, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
        return dialog;
    }

    private static void exit(Activity activity) {
        activity.getWindow().getDecorView().setVisibility(android.view.View.INVISIBLE);
        try {
            androidx.media3.exoplayer.ExoPlayer p = com.orvid.play.Session.player();
            if (p != null) {
                p.setVolume(0);
                p.pause();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION));
            activity.overridePendingTransition(0, 0);
        } catch (RuntimeException e) {
            activity.moveTaskToBack(true);
        }
        try {
            activity.finishAndRemoveTask();
            activity.overridePendingTransition(0, 0);
        } catch (RuntimeException ignored) {
        }
        new Thread(() -> OrvidApp.get().wipeAndExit(), "orvid-panic").start();
    }

    private static float midX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2;
    }

    private static float midY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2;
    }

    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }
}
