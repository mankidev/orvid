package com.orvid.ui;

import android.app.Activity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.orvid.R;
import com.orvid.cast.Cast;
import com.orvid.cast.CastSession;

final class CastPanel {

    interface Host {
        void subtitleMenu();

        void castMenu();

        void replayOnTv();
    }

    private final Activity activity;
    private final Host host;
    private final View panel;
    private final ImageView art;
    private final TextView state;
    private final ImageButton toggle;
    private final ImageButton cc;
    private final ImageButton volume;
    private final View busy;
    private final TextView time;
    private final SeekBar seek;
    private boolean dragging;
    private long lastPosition;
    private boolean subtitles;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            panel.postDelayed(this, 500);
        }
    };

    CastPanel(Activity activity, View stage, Host host) {
        this.activity = activity;
        this.host = host;
        panel = stage.findViewById(R.id.cast_panel);
        art = stage.findViewById(R.id.cast_art);
        state = stage.findViewById(R.id.cast_state);
        toggle = stage.findViewById(R.id.cast_toggle);
        cc = stage.findViewById(R.id.cast_cc);
        volume = stage.findViewById(R.id.cast_volume);
        busy = stage.findViewById(R.id.cast_busy);
        time = stage.findViewById(R.id.cast_time);
        seek = stage.findViewById(R.id.cast_seek);
        toggle.setOnClickListener(v -> {
            CastSession s = Cast.get(activity).session();
            if (s == null) return;
            if (s.finished() || s.error() != null) host.replayOnTv();
            else if (s.playing()) s.pause();
            else s.play();
        });
        cc.setOnClickListener(v -> host.subtitleMenu());
        stage.findViewById(R.id.cast_device).setOnClickListener(v -> host.castMenu());
        volume.setOnClickListener(v -> {
            CastSession s = Cast.get(activity).session();
            if (s != null) s.setMuted(!s.muted());
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                CastSession s = Cast.get(activity).session();
                if (fromUser && s != null) time.setText(time(s.durationMs() * progress / 1000, s.durationMs()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                CastSession s = Cast.get(activity).session();
                if (s != null && s.durationMs() > 0) s.seek(s.durationMs() * bar.getProgress() / 1000);
            }
        });
    }

    void show(String thumbnail, boolean hasSubtitles) {
        subtitles = hasSubtitles;
        if (thumbnail != null) com.orvid.app.OrvidApp.get().images().load(art, thumbnail, 480, null);
        if (panel.getVisibility() != View.VISIBLE) {
            panel.setVisibility(View.VISIBLE);
            panel.removeCallbacks(tick);
            panel.post(tick);
        }
        update();
    }

    private static String time(long positionMs, long durationMs) {
        String p = Ui.duration(positionMs / 1000);
        if (p.isEmpty()) p = "0:00";
        return durationMs > 0 ? p + " / " + Ui.duration(durationMs / 1000) : p;
    }

    void hide() {
        panel.removeCallbacks(tick);
        panel.setVisibility(View.GONE);
    }

    boolean showing() {
        return panel.getVisibility() == View.VISIBLE;
    }

    long lastPosition() {
        return lastPosition;
    }

    void update() {
        CastSession s = Cast.get(activity).session();
        if (s == null) return;
        String name = s.device.name;
        boolean loading = s.state() == CastSession.State.CONNECTING || s.loading() || "BUFFERING".equals(s.playerState());
        int text;
        if (s.state() == CastSession.State.CONNECTING) text = R.string.connecting_to;
        else if (s.finished()) text = R.string.ended_on;
        else if (s.loading()) text = R.string.loading_on;
        else if ("PAUSED".equals(s.playerState())) text = R.string.paused_on;
        else text = R.string.playing_on;
        boolean failed = s.error() != null && !s.loading();
        if (failed) loading = false;
        state.setText(failed ? s.error() : activity.getString(text, name));
        busy.setVisibility(loading ? View.VISIBLE : View.GONE);
        toggle.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        toggle.setImageResource(s.finished() || failed ? R.drawable.ic_replay_white
                : s.playing() ? R.drawable.ic_pause_white : R.drawable.ic_play_white);
        volume.setImageResource(s.muted() ? R.drawable.ic_volume_x_white : R.drawable.ic_volume_2_white);
        cc.setVisibility(subtitles ? View.VISIBLE : View.GONE);
        boolean ccOn = s.activeTracks().length > 0;
        cc.setImageResource(ccOn ? R.drawable.ic_cc_white : R.drawable.ic_cc_off_white);
        long d = s.durationMs();
        long p = s.positionMs();
        if (!s.loading() && p > 0) lastPosition = p;
        if (!dragging) {
            seek.setProgress(d > 0 ? (int) (p * 1000 / d) : 0);
            time.setText(time(p, d));
        }
        seek.setEnabled(d > 0);
    }
}
