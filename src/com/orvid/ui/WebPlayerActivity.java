package com.orvid.ui;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.orvid.web.TorBrowser;

import java.util.concurrent.Executors;

public final class WebPlayerActivity extends Activity {

    public static final String EXTRA_URL = "url";

    private FrameLayout root;
    private WebView web;
    private View custom;
    private WebChromeClient.CustomViewCallback customCallback;
    private int retries;
    private boolean focusOn = true;
    private int focusTries;
    private android.widget.LinearLayout bar;
    private android.widget.TextView pageButton;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideBar = () -> {
        if (bar != null) bar.animate().alpha(0f).setDuration(200).start();
    };
    private final java.util.concurrent.Executor background = Executors.newSingleThreadExecutor();
    private static final int MAX_RETRIES = 3;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        ProgressBar spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        setContentView(root);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return;
        }
        androidx.media3.exoplayer.ExoPlayer behind = com.orvid.play.Session.player();
        if (behind != null) behind.pause();
        TorBrowser.openSession(this, "webplayer", background, new TorBrowser.Ready() {
            @Override
            public void ready(WebView v) {
                if (isFinishing() || isDestroyed()) {
                    TorBrowser.destroy(v);
                    return;
                }
                web = v;
                v.setTag(new Listener());
                v.getSettings().setMediaPlaybackRequiresUserGesture(false);
                v.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onPermissionRequest(PermissionRequest request) {
                        request.deny();
                    }

                    @Override
                    public void onShowCustomView(View view, CustomViewCallback callback) {
                        custom = view;
                        customCallback = callback;
                        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        web.setVisibility(View.GONE);
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    }

                    @Override
                    public void onHideCustomView() {
                        leaveFullscreen();
                    }
                });
                root.removeAllViews();
                root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                addBar();
                v.loadUrl(url);
            }

            @Override
            public void unavailable(String reason) {
                finish();
            }
        });
    }

    private final class Listener implements TorBrowser.ErrorListener, TorBrowser.PageListener {
        @Override
        public void failed(WebView view, String url, String reason) {
            retry(url);
        }

        @Override
        public void finished(WebView view, String url) {
            focusTries = 0;
            focus();
        }
    }

    private void focus() {
        if (web == null || !focusOn || isFinishing()) return;
        web.evaluateJavascript(com.orvid.web.Focus.ON, result -> {
            String r = result == null ? "" : result.replace("\"", "");
            if (r.equals("none") && ++focusTries < 12) main.postDelayed(this::focus, 1000);
        });
    }

    private void addBar() {
        bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int pad = Ui.dp(this, 8);
        bar.setPadding(pad, pad, pad, pad);
        android.widget.TextView close = chip("\u2715");
        close.setOnClickListener(v -> finish());
        pageButton = chip(getString(com.orvid.R.string.web_show_page));
        pageButton.setOnClickListener(v -> toggleFocus());
        bar.addView(close);
        android.view.View spacer = new android.view.View(this);
        bar.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(pageButton);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP));
        showBar();
    }

    private android.widget.TextView chip(String text) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        int p = Ui.dp(this, 10);
        t.setPadding(p + p / 2, p, p + p / 2, p);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(Ui.dp(this, 20));
        t.setBackground(bg);
        return t;
    }

    private void showBar() {
        if (bar == null) return;
        bar.animate().cancel();
        bar.setAlpha(1f);
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, 3000);
    }

    private void toggleFocus() {
        if (web == null) return;
        focusOn = !focusOn;
        pageButton.setText(focusOn ? com.orvid.R.string.web_show_page : com.orvid.R.string.web_focus_video);
        if (focusOn) {
            web.evaluateJavascript(com.orvid.web.Focus.RESUME, r -> {
                focusTries = 0;
                focus();
            });
        } else {
            web.evaluateJavascript(com.orvid.web.Focus.OFF, null);
        }
        showBar();
    }

    private final Panic panic = new Panic();

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (panic.feed(this, e)) return true;
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) showBar();
        return super.dispatchTouchEvent(e);
    }

    private void retry(String url) {
        if (retries >= MAX_RETRIES || web == null || isFinishing()) return;
        retries++;
        web.stopLoading();
        TorBrowser.reroute(this, "webplayer", background, () -> {
            if (web != null && !isFinishing()) web.loadUrl(url);
        });
    }

    private void leaveFullscreen() {
        if (custom == null) return;
        root.removeView(custom);
        custom = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        if (web != null) web.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    @Override
    public void onBackPressed() {
        if (custom != null) {
            leaveFullscreen();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TorBrowser.destroy(web);
        web = null;
    }
}
