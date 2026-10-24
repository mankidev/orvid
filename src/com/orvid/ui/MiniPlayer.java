package com.orvid.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.orvid.R;
import com.orvid.app.OrvidApp;
import com.orvid.play.Session;

final class MiniPlayer {

    private static final long COVER_DELAY_MS = 500;
    private static final long TICK_MS = 500;

    private final Activity activity;
    private final View root;
    private final ImageButton play;
    private final ProgressBar progress;
    private final ImageView cover;
    private TextureView video;
    private Player attached;

    private final Runnable showCover = new Runnable() {
        @Override
        public void run() {
            cover.setVisibility(View.VISIBLE);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (attached == null) return;
            long duration = attached.getDuration();
            progress.setProgress(duration > 0 ? (int) (attached.getCurrentPosition() * 1000 / duration) : 0);
            progress.postDelayed(this, TICK_MS);
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean playing) {
            play.setImageResource(playing ? R.drawable.ic_pause_white : R.drawable.ic_play_white);
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            fit(size);
        }

        @Override
        public void onRenderedFirstFrame() {
            cover.removeCallbacks(showCover);
            cover.setVisibility(View.GONE);
            Session.frame = null;
        }
    };

    MiniPlayer(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
        play = root.findViewById(R.id.mini_play);
        progress = root.findViewById(R.id.mini_progress);
        cover = root.findViewById(R.id.mini_cover);
        video = root.findViewById(R.id.mini_video);
        root.setOnClickListener(v -> {
            if (!Session.active()) return;
            activity.startActivity(new Intent(activity, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_URL, Session.url()));
            activity.overridePendingTransition(0, 0);
        });
        play.setOnClickListener(v -> {
            ExoPlayer p = Session.player();
            if (p == null) return;
            if (p.isPlaying()) p.pause();
            else p.play();
        });
        root.findViewById(R.id.mini_close).setOnClickListener(v -> {
            detach();
            Session.release();
            root.setVisibility(View.GONE);
        });
    }

    void attach() {
        ExoPlayer p = Session.player();
        if (p == null) {
            root.setVisibility(View.GONE);
            return;
        }
        root.setVisibility(View.VISIBLE);
        ((TextView) root.findViewById(R.id.mini_title)).setText(Session.title);
        ((TextView) root.findViewById(R.id.mini_author)).setText(Session.author);
        freshSurface();
        fit(p.getVideoSize());
        cover.removeCallbacks(showCover);
        if (Session.frame != null) {
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setImageBitmap(Session.frame);
            cover.setVisibility(View.VISIBLE);
        } else {
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setVisibility(View.GONE);
            cover.postDelayed(showCover, COVER_DELAY_MS);
            int coverWidth = cover.getLayoutParams().width > 0 ? cover.getLayoutParams().width : Ui.dp(activity, 150);
            OrvidApp.get().images().load(cover, Session.thumb, coverWidth, Session.url());
        }
        p.setVideoTextureView(video);
        p.addListener(listener);
        attached = p;
        listener.onIsPlayingChanged(p.isPlaying());
        int tint = Ui.accent(Ui.iconForUrl(activity, Session.url()));
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(tint != 0 ? tint : activity.getColor(R.color.text_primary)));
        progress.removeCallbacks(tick);
        tick.run();
    }

    void detach() {
        progress.removeCallbacks(tick);
        if (attached == null) return;
        attached.clearVideoTextureView(video);
        attached.removeListener(listener);
        attached = null;
    }

    private void fit(VideoSize size) {
        View box = (View) video.getParent();
        int w = box.getLayoutParams().width;
        int h = box.getLayoutParams().height;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        if (size.width > 0 && size.height > 0) {
            float ratio = size.width * size.pixelWidthHeightRatio / size.height;
            if (ratio > (float) w / h) lp.height = Math.round(w / ratio);
            else lp.width = Math.round(h * ratio);
        }
        video.setLayoutParams(lp);
    }

    private void freshSurface() {
        ViewGroup box = (ViewGroup) video.getParent();
        int index = box.indexOfChild(video);
        box.removeView(video);
        video = new TextureView(activity);
        video.setId(R.id.mini_video);
        box.addView(video, index);
    }
}
