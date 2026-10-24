package com.orvid.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;

import com.orvid.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class PlayerControls implements Player.Listener {

    private static final long HIDE_MS = 3000;
    private static final long SEEK_STEP_MS = 10000;
    private static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    private final Activity activity;
    private final ExoPlayer player;
    private final View overlay;
    private final ImageButton play;
    private final ImageButton fullscreen;
    private final TextView time;
    private final SeekBar seek;
    private final android.widget.ImageView preview;
    private com.orvid.play.Frames storyboard;
    private long previewFor = -1;
    private float downX;
    private float downY;
    private boolean scrubbing;
    private long scrubFrom;
    private long scrubTarget;

    void setStoryboard(com.orvid.play.Frames sb) {
        storyboard = sb;
        preview.setVisibility(View.GONE);
    }

    private void sizePreview() {
        View parent = (View) preview.getParent();
        boolean landscape = parent.getWidth() > parent.getHeight();
        int max = Ui.dp(activity, landscape ? 360 : 260);
        int width = Math.min(max, (int) (parent.getWidth() * (landscape ? 0.34f : 0.5f)));
        float aspect = storyboard.aspect() > 0.5f && storyboard.aspect() < 3f ? storyboard.aspect() : 16f / 9f;
        int height = Math.round(width / aspect);
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            preview.setLayoutParams(lp);
        }
    }

    private void showPreview(int progress) {
        if (storyboard == null) return;
        sizePreview();
        long ms = position(progress);
        int width = preview.getLayoutParams().width;
        int track = seek.getWidth() - seek.getPaddingStart() - seek.getPaddingEnd();
        float x = seek.getLeft() + seek.getPaddingStart() + track * (progress / (float) seek.getMax()) - width / 2f;
        View parent = (View) preview.getParent();
        x = Math.max(8, Math.min(parent.getWidth() - width - 8, x));
        preview.setTranslationX(x);
        int height = preview.getLayoutParams().height;
        float above = seek.getTop() + seek.getTranslationY() + seek.getPaddingTop() - height - Ui.dp(activity, 8);
        preview.setTranslationY(above - (parent.getHeight() - height - ((ViewGroup.MarginLayoutParams) preview.getLayoutParams()).bottomMargin));
        preview.setVisibility(View.VISIBLE);
        long bucket = ms / 1000;
        if (bucket == previewFor) return;
        previewFor = bucket;
        storyboard.frameAt(ms, bmp -> {
            if (previewFor == bucket && preview.getVisibility() == View.VISIBLE) preview.setImageBitmap(bmp);
        });
    }
    private final ProgressBar mini;
    private final TextView hintLeft;
    private final TextView hintRight;
    private final TextView title;
    private final ImageButton cc;
    private final ImageButton download;
    private boolean visible;
    private boolean dragging;

    private final Runnable hide = () -> {
        if (!dragging) hide();
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (boosting && !touching) endBoost();
            overlay.postDelayed(this, 500);
        }
    };

    private final SidePanel side;

    private final View stageView;

    PlayerControls(Activity activity, ExoPlayer player, View stage, Runnable close, SidePanel side) {
        this.stageView = stage;
        this.activity = activity;
        this.side = side;
        this.player = player;
        overlay = stage.findViewById(R.id.overlay);
        play = stage.findViewById(R.id.ctl_play);
        fullscreen = stage.findViewById(R.id.ctl_fullscreen);
        time = stage.findViewById(R.id.ctl_time);
        seek = stage.findViewById(R.id.ctl_seek);
        preview = stage.findViewById(R.id.seek_preview);
        mini = stage.findViewById(R.id.ctl_mini);
        hintLeft = stage.findViewById(R.id.seek_hint_left);
        hintRight = stage.findViewById(R.id.seek_hint_right);
        title = stage.findViewById(R.id.ctl_title);
        cc = stage.findViewById(R.id.ctl_cc);
        cc.setOnClickListener(v -> {
            toggleSubtitles();
            scheduleHide();
        });
        stage.findViewById(R.id.ctl_cast).setOnClickListener(v -> {
            overlay.removeCallbacks(hide);
            DeviceDialog.show(activity);
        });
        download = stage.findViewById(R.id.ctl_download);
        download.setOnClickListener(v -> showDownloads());

        play.setOnClickListener(v -> {
            if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
            if (player.isPlaying()) player.pause();
            else player.play();
            scheduleHide();
        });
        stage.findViewById(R.id.ctl_close).setOnClickListener(v -> close.run());
        stage.findViewById(R.id.ctl_settings).setOnClickListener(v -> {
            if (sheetOpen()) return;
            overlay.removeCallbacks(hide);
            showSettings();
        });
        fullscreen.setOnClickListener(v -> toggleFullscreen());
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    time.setText(timeText(position(progress), player.getDuration()));
                    showPreview(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                overlay.removeCallbacks(hide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                preview.setVisibility(View.GONE);
                previewFor = -1;
                player.seekTo(position(bar.getProgress()));
                scheduleHide();
            }
        });

        GestureDetector gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (visible) hide();
                else show();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (scrubbing || !player.isPlaying()) return;
                boostFrom = player.getPlaybackParameters().speed;
                boosting = true;
                player.setPlaybackSpeed(2f);
                boostLabel().setVisibility(View.VISIBLE);
                if (visible) hide();
                if (stage.getParent() != null) stage.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                boolean forward = e.getX() > stage.getWidth() / 2f;
                long target = player.getCurrentPosition() + (forward ? SEEK_STEP_MS : -SEEK_STEP_MS);
                long duration = player.getDuration();
                if (duration != C.TIME_UNSET) target = Math.min(target, duration);
                player.seekTo(Math.max(0, target));
                flash(forward ? hintRight : hintLeft);
                return true;
            }
        });
        final float slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        stage.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    touching = true;
                    scrubbing = false;
                    swiping = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (boosting) return true;
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (!swiping && !scrubbing && e.getPointerCount() == 1 && Math.abs(dy) > slop * 2
                            && Math.abs(dy) > Math.abs(dx) * 1.5f && (isLandscape() ? dy > 0 : dy < 0)) {
                        swiping = true;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swiping) {
                        float pull = Math.max(0f, isLandscape() ? dy : -dy);
                        float f = Math.min(1f, pull / (SWIPE_DP * 2 * activity.getResources().getDisplayMetrics().density));
                        float scale = isLandscape() ? 1f - SWIPE_SCALE * f : 1f + SWIPE_SCALE * f;
                        stageView.setScaleX(scale);
                        stageView.setScaleY(scale);
                        return true;
                    }
                    long d = player.getDuration();
                    if (!scrubbing && !isLandscape() && d != C.TIME_UNSET && d > 0 && Math.abs(dx) > slop * 2
                            && Math.abs(dx) > Math.abs(dy) * 2 && e.getPointerCount() == 1) {
                        scrubbing = true;
                        dragging = true;
                        scrubFrom = player.getCurrentPosition();
                        if (!visible) show();
                        overlay.removeCallbacks(hide);
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (scrubbing) {
                        int track = Math.max(1, seek.getWidth() - seek.getPaddingStart() - seek.getPaddingEnd());
                        long target = scrubFrom + (long) (dx / track * d);
                        target = Math.max(0, Math.min(d, target));
                        scrubTarget = target;
                        int progress = (int) (target * 1000 / d);
                        seek.setProgress(progress);
                        time.setText(timeText(target, d));
                        showPreview(progress);
                        return true;
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    if (swiping) {
                        swiping = false;
                        float dy = e.getY() - downY;
                        float pull = isLandscape() ? dy : -dy;
                        boolean commit = e.getActionMasked() == MotionEvent.ACTION_UP
                                && pull > SWIPE_DP * activity.getResources().getDisplayMetrics().density;
                        if (commit) {
                            stageView.setScaleX(1f);
                            stageView.setScaleY(1f);
                            toggleFullscreen();
                        } else {
                            stageView.animate().scaleX(1f).scaleY(1f).setDuration(160)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                        }
                        return true;
                    }
                    if (boosting) {
                        endBoost();
                        gestures.onTouchEvent(e);
                        return true;
                    }
                    if (scrubbing) {
                        scrubbing = false;
                        dragging = false;
                        player.seekTo(scrubTarget);
                        preview.setVisibility(View.GONE);
                        previewFor = -1;
                        scheduleHide();
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return scrubbing || gestures.onTouchEvent(e);
        });
        player.addListener(this);
        overlay.post(tick);
        refreshFullscreenIcon();
        play.setVisibility(View.INVISIBLE);
    }

    private long position(int progress) {
        long d = player.getDuration();
        return d == C.TIME_UNSET ? 0 : d * progress / 1000;
    }

    private void updateProgress() {
        long d = player.getDuration();
        long p = player.getCurrentPosition();
        int buffered = player.getBufferedPercentage() * 10;
        int pos = d == C.TIME_UNSET || d <= 0 ? 0 : (int) (p * 1000 / d);
        if (!dragging) {
            seek.setProgress(pos);
            time.setText(timeText(p, d));
        }
        seek.setSecondaryProgress(buffered);
        mini.setProgress(pos);
        mini.setSecondaryProgress(buffered);
    }

    private static String timeText(long position, long duration) {
        String d = duration == C.TIME_UNSET ? "--:--" : Ui.duration(duration / 1000);
        String p = Ui.duration(position / 1000);
        if (p.isEmpty()) p = "0:00";
        return p + " / " + d;
    }

    private static final long SKIP_GUARD_MS = 600;
    private static final int SWIPE_DP = 48;
    private static final float SWIPE_SCALE = 0.12f;
    private boolean swiping;
    private long shownAt;
    private View previous;
    private View next;

    void setSkip(Runnable onPrevious, Runnable onNext) {
        previous = stageView.findViewById(R.id.ctl_previous);
        next = stageView.findViewById(R.id.ctl_next);
        previous.setOnClickListener(v -> {
            if (android.os.SystemClock.uptimeMillis() - shownAt >= SKIP_GUARD_MS) onPrevious.run();
        });
        next.setOnClickListener(v -> {
            if (android.os.SystemClock.uptimeMillis() - shownAt >= SKIP_GUARD_MS) onNext.run();
        });
    }

    void showSkip(boolean canPrevious, boolean canNext) {
        if (previous == null) return;
        previous.setVisibility(canPrevious ? View.VISIBLE : View.INVISIBLE);
        next.setVisibility(canNext ? View.VISIBLE : View.INVISIBLE);
    }

    void show() {
        if (!visible) shownAt = android.os.SystemClock.uptimeMillis();
        visible = true;
        overlay.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(160).start();
        mini.animate().alpha(0f).setDuration(160).start();
        updateProgress();
        scheduleHide();
    }

    void hide() {
        visible = false;
        overlay.removeCallbacks(hide);
        overlay.animate().cancel();
        overlay.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
        mini.animate().alpha(isLandscape() ? 0f : 1f).setDuration(220).start();
    }

    private void scheduleHide() {
        overlay.removeCallbacks(hide);
        if (dragging) return;
        if (player.isPlaying()) overlay.postDelayed(hide, HIDE_MS);
    }

    private void flash(View hint) {
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(500).setDuration(300).start();
    }

    @Override
    public void onIsPlayingChanged(boolean playing) {
        play.setImageResource(playing ? R.drawable.ic_pause_white : R.drawable.ic_play_white);
        if (playing) scheduleHide();
        else if (player.getPlaybackState() != Player.STATE_BUFFERING) show();
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        refreshCc();
    }

    private void textOff() {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        TextView shown = stageView.findViewById(R.id.subtitle);
        if (shown != null) {
            shown.setText("");
            shown.setVisibility(View.GONE);
        }
    }

    private boolean textWanted() {
        androidx.media3.common.TrackSelectionParameters p = player.getTrackSelectionParameters();
        if (p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return false;
        for (androidx.media3.common.TrackSelectionOverride o : p.overrides.values()) {
            if (o.getType() == C.TRACK_TYPE_TEXT) return true;
        }
        return !selectedLabel(choices(C.TRACK_TYPE_TEXT)).isEmpty();
    }

    private void toggleSubtitles() {
        List<Choice> text = choices(C.TRACK_TYPE_TEXT);
        if (textWanted()) {
            if (!text.isEmpty()) textOff();
            refreshCc();
            return;
        }
        if (text.isEmpty()) return;
        String mine = Locale.getDefault().getLanguage();
        Choice pick = null;
        for (Choice c : text) {
            if (c.language != null && (c.language.equals(mine) || c.language.startsWith(mine + "-"))) {
                pick = c;
                break;
            }
        }
        if (pick == null) pick = text.get(0);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(new TrackSelectionOverride(pick.group, 0)).build());
        refreshCc();
    }

    private boolean boosting;
    private float boostFrom = 1f;
    private TextView boost;

    private TextView boostLabel() {
        if (boost == null) {
            boost = new TextView(activity);
            boost.setText("2x \u25B6\u25B6");
            boost.setTextColor(0xFFFFFFFF);
            boost.setTextSize(13);
            boost.setBackgroundResource(R.drawable.bg_pill);
            int h = Ui.dp(activity, 16);
            int v = Ui.dp(activity, 6);
            boost.setPadding(h, v, h, v);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = Ui.dp(activity, 16);
            ((ViewGroup) stageView).addView(boost, lp);
            boost.setVisibility(View.GONE);
        }
        return boost;
    }

    void setTitle(CharSequence text) {
        title.setText(text);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        play.setVisibility(state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                ? View.INVISIBLE : View.VISIBLE);
        if (state == Player.STATE_ENDED) {
            play.setImageResource(R.drawable.ic_replay_white);
            show();
        }
    }

    private void toggleFullscreen() {
        boolean landscape = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        try {
            activity.setRequestedOrientation(landscape
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (IllegalStateException ignored) {
        }
    }

    private static final int LANDSCAPE_LIFT_DP = 16;

    private boolean isLandscape() {
        return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    void refreshFullscreenIcon() {
        boolean landscape = isLandscape();
        mini.setVisibility(landscape ? View.GONE : View.VISIBLE);
        int inset = landscape ? Math.round(12 * activity.getResources().getDisplayMetrics().density) : 0;
        seek.setPadding(inset, seek.getPaddingTop(), inset, seek.getPaddingBottom());
        float drop = landscape ? -Ui.dp(activity, LANDSCAPE_LIFT_DP) : (seek.getLayoutParams().height - mini.getLayoutParams().height) / 2f;
        seek.setTranslationY(drop);
        ((View) time.getParent()).setTranslationY(drop);
        View row = (View) stageView.getParent();
        row.setTranslationZ(landscape ? 0 : 1);
        if (!landscape) {
            for (android.view.ViewParent v = seek.getParent(); v instanceof android.view.ViewGroup; v = v.getParent()) {
                ((android.view.ViewGroup) v).setClipChildren(false);
                if (v == row.getParent()) break;
            }
        }
        title.setVisibility(landscape ? View.VISIBLE : View.GONE);
        fullscreen.setImageResource(landscape ? R.drawable.ic_fullscreen_exit_white : R.drawable.ic_fullscreen_white);
    }

    private int accent = 0xFFFFFFFF;

    void setAccent(int color) {
        if (color == accent) return;
        accent = color;
        android.content.res.ColorStateList c = android.content.res.ColorStateList.valueOf(color);
        seek.setProgressTintList(c);
        seek.setThumbTintList(c);
        mini.setProgressTintList(c);
    }

    private boolean touching;

    private void endBoost() {
        if (!boosting) return;
        boosting = false;
        player.setPlaybackSpeed(boostFrom);
        boostLabel().setVisibility(View.GONE);
    }

    void release() {
        endBoost();
        overlay.removeCallbacks(tick);
        overlay.removeCallbacks(hide);
        player.removeListener(this);
    }

    private void showSettings() {
        Sheet sheet = new Sheet();
        sheet.row(R.drawable.ic_tune, activity.getString(R.string.quality), qualityLabel(), () -> {
            sheet.replaceWith(PlayerControls.this::showQuality);
        });
        sheet.row(R.drawable.ic_speed, activity.getString(R.string.speed), speedLabel(player.getPlaybackParameters().speed), () -> {
            sheet.replaceWith(PlayerControls.this::showSpeed);
        });
        List<Choice> audio = choices(C.TRACK_TYPE_AUDIO);
        if (audio.size() > 1) {
            sheet.row(R.drawable.ic_voice, activity.getString(R.string.audio_track), selectedLabel(audio), () -> {
                sheet.replaceWith(() -> showChoices(C.TRACK_TYPE_AUDIO, audio));
            });
        }
        List<Choice> text = choices(C.TRACK_TYPE_TEXT);
        if (!text.isEmpty()) {
            String now = selectedLabel(text);
            sheet.row(R.drawable.ic_subtitles, activity.getString(R.string.subtitles),
                    now.isEmpty() ? activity.getString(R.string.off) : now, () -> {
                sheet.replaceWith(this::showSubtitles);
            });
        }
        boolean autoOn = com.orvid.app.OrvidApp.get().autoplay();
        sheet.row(R.drawable.ic_autoplay, activity.getString(R.string.autoplay),
                activity.getString(autoOn ? R.string.on : R.string.off), () -> {
            com.orvid.app.OrvidApp.get().setAutoplay(!autoOn);
            sheet.dismiss();
        });
        boolean loopOn = player.getRepeatMode() == Player.REPEAT_MODE_ONE;
        sheet.row(R.drawable.ic_loop, activity.getString(R.string.loop),
                activity.getString(loopOn ? R.string.on : R.string.off), () -> {
            player.setRepeatMode(loopOn ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
            sheet.dismiss();
        });
        boolean debugOn = com.orvid.app.OrvidApp.get().debugOverlay();
        sheet.row(R.drawable.ic_bug, activity.getString(R.string.debug_overlay),
                activity.getString(debugOn ? R.string.on : R.string.off), () -> {
            com.orvid.app.OrvidApp.get().setDebugOverlay(!debugOn);
            if (activity instanceof PlayerActivity) ((PlayerActivity) activity).applyDebug();
            sheet.dismiss();
        });
        sheet.show();
    }

    private static final class Quality {
        final TrackGroup group;
        final int index;
        final int height;
        final int fps;

        Quality(TrackGroup group, int index, Format f) {
            this.group = group;
            this.index = index;
            this.height = shortSide(f);
            this.fps = Math.round(f.frameRate);
        }

        String label() {
            return height + "p" + (fps > 30 ? fps : "");
        }
    }

    private static int shortSide(Format f) {
        return f.width > 0 ? Math.min(f.width, f.height) : f.height;
    }

    private List<Quality> qualities() {
        List<Quality> out = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                if (f.height > 0) out.add(new Quality(g.getMediaTrackGroup(), i, f));
            }
        }
        Collections.sort(out, (a, b) -> b.height - a.height);
        return out;
    }

    private boolean auto() {
        for (TrackSelectionOverride o : player.getTrackSelectionParameters().overrides.values()) {
            if (o.getType() == C.TRACK_TYPE_VIDEO) return false;
        }
        return true;
    }

    private List<com.orvid.play.Direct.Variant> files() {
        return activity instanceof PlayerActivity ? ((PlayerActivity) activity).fileQualities()
                : Collections.<com.orvid.play.Direct.Variant>emptyList();
    }

    private com.orvid.play.Direct.Variant currentFile() {
        String url = ((PlayerActivity) activity).fileQuality();
        for (com.orvid.play.Direct.Variant v : files()) if (v.url.equals(url)) return v;
        return null;
    }

    private String qualityLabel() {
        if (!files().isEmpty()) {
            com.orvid.play.Direct.Variant v = currentFile();
            return v == null ? activity.getString(R.string.quality_auto) : v.height + "p";
        }
        Format now = player.getVideoFormat();
        String current = now == null || now.height <= 0 ? "" : shortSide(now) + "p";
        if (auto()) {
            return current.isEmpty() ? activity.getString(R.string.quality_auto)
                    : activity.getString(R.string.quality_auto_now, current);
        }
        return current;
    }

    private void showQuality() {
        Sheet sheet = new Sheet();
        List<com.orvid.play.Direct.Variant> files = files();
        if (!files.isEmpty()) {
            com.orvid.play.Direct.Variant now = currentFile();
            for (com.orvid.play.Direct.Variant v : files) {
                sheet.choice(v.height + "p", v == now, () -> {
                    ((PlayerActivity) activity).pickFile(v);
                    sheet.dismiss();
                });
            }
            sheet.show();
            return;
        }
        boolean auto = auto();
        Format now = player.getVideoFormat();
        sheet.choice(activity.getString(R.string.quality_auto), auto, () -> {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
            sheet.dismiss();
        });
        for (Quality q : qualities()) {
            boolean selected = !auto && now != null && shortSide(now) == q.height;
            sheet.choice(q.label(), selected, () -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(q.group, q.index)).build());
                sheet.dismiss();
            });
        }
        sheet.show();
    }

    private static final class Choice {
        final TrackGroup group;
        final String label;
        final boolean selected;
        final String language;

        Choice(TrackGroup group, String label, boolean selected, String language) {
            this.group = group;
            this.label = label;
            this.selected = selected;
            this.language = language;
        }
    }

    private List<Choice> choices(int type) {
        List<Choice> out = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type || !g.isTrackSupported(0)) continue;
            Format f = g.getTrackFormat(0);
            String label = f.label;
            if (label == null || label.isEmpty()) {
                label = f.language == null ? "?" : Locale.forLanguageTag(f.language).getDisplayName(Locale.getDefault());
            }
            out.add(new Choice(g.getMediaTrackGroup(), label, g.isSelected(), f.language));
        }
        return out;
    }

    private static String selectedLabel(List<Choice> list) {
        for (Choice c : list) if (c.selected) return c.label;
        return "";
    }

    private void showSubtitles() {
        Sheet sheet = new Sheet();
        List<Choice> text = choices(C.TRACK_TYPE_TEXT);
        sheet.choice(activity.getString(R.string.off), selectedLabel(text).isEmpty(), () -> {
            if (!text.isEmpty()) textOff();
            sheet.dismiss();
            refreshCc();
        });
        for (Choice c : text) {
            sheet.choice(c.label, c.selected, () -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(c.group, 0)).build());
                sheet.dismiss();
                refreshCc();
            });
        }
        sheet.show();
    }

    private void showDownloads() {
        if (!(activity instanceof PlayerActivity)) return;
        PlayerActivity owner = (PlayerActivity) activity;
        Sheet sheet = new Sheet();
        for (PlayerActivity.Download d : owner.downloads()) {
            sheet.choice(d.label, false, () -> {
                sheet.dismiss();
                owner.download(d);
            });
        }
        sheet.show();
    }

    private void refreshCc() {
        download.setVisibility(activity instanceof PlayerActivity && ((PlayerActivity) activity).downloadable()
                ? View.VISIBLE : View.GONE);
        List<Choice> text = choices(C.TRACK_TYPE_TEXT);
        cc.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        boolean on = textWanted();
        cc.setImageResource(on ? R.drawable.ic_cc_white : R.drawable.ic_cc_off_white);
        cc.setAlpha(on ? 1f : 0.85f);
    }

    private void showChoices(int type, List<Choice> list) {
        Sheet sheet = new Sheet();
        for (Choice c : list) {
            sheet.choice(c.label, c.selected, () -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(c.group, 0)).build());
                sheet.dismiss();
            });
        }
        sheet.show();
    }

    private String speedLabel(float speed) {
        if (speed == 1f) return activity.getString(R.string.speed_normal);
        return (speed == Math.floor(speed) ? String.format(Locale.ROOT, "%.0f", speed)
                : String.format(Locale.ROOT, "%s", speed)) + "×";
    }

    private void showSpeed() {
        Sheet sheet = new Sheet();
        float current = player.getPlaybackParameters().speed;
        for (float s : SPEEDS) {
            sheet.choice(speedLabel(s), Math.abs(s - current) < 0.01f, () -> {
                player.setPlaybackParameters(new PlaybackParameters(s));
                sheet.dismiss();
            });
        }
        sheet.show();
    }

    private Sheet openSheet;

    private boolean sheetOpen() {
        if (isLandscape()) return side.isOpen();
        return openSheet != null && openSheet.dialog.isShowing();
    }

    void castSubtitles(List<String> names, int selected, java.util.function.IntConsumer pick) {
        Sheet sheet = new Sheet();
        sheet.choice(activity.getString(R.string.off), selected < 0, () -> {
            pick.accept(-1);
            sheet.dismiss();
        });
        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            sheet.choice(names.get(i), i == selected, () -> {
                pick.accept(index);
                sheet.dismiss();
            });
        }
        sheet.show();
    }

    private final class Sheet {
        private final Dialog dialog = Panic.guard(activity, new Dialog(activity));
        private final LinearLayout list = new LinearLayout(activity);

        Sheet() {
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackgroundResource(R.drawable.bg_sheet);
            int pad = Ui.dp(activity, 8);
            list.setPadding(0, pad, 0, pad + Ui.dp(activity, 8));
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int max = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.7f);
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
                }
            };
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(list);
            dialog.setContentView(scroll);
            dialog.setOnDismissListener(d -> scheduleHide());
        }

        void row(int icon, String title, String value, Runnable action) {
            View row = activity.getLayoutInflater().inflate(R.layout.row_sheet, list, false);
            ((ImageView) row.findViewById(R.id.sheet_icon)).setImageResource(icon);
            ((TextView) row.findViewById(R.id.sheet_title)).setText(title);
            ((TextView) row.findViewById(R.id.sheet_value)).setText(value);
            row.setOnClickListener(v -> action.run());
            list.addView(row);
        }

        void choice(String title, boolean selected, Runnable action) {
            View row = activity.getLayoutInflater().inflate(R.layout.row_sheet, list, false);
            ImageView icon = row.findViewById(R.id.sheet_icon);
            if (selected) icon.setImageResource(R.drawable.ic_check);
            else icon.setImageDrawable(null);
            ((TextView) row.findViewById(R.id.sheet_title)).setText(title);
            row.findViewById(R.id.sheet_value).setVisibility(View.GONE);
            row.setOnClickListener(v -> action.run());
            list.addView(row);
        }

        void show() {
            if (isLandscape()) {
                list.setBackground(null);
                if (list.getParent() instanceof ViewGroup) ((ViewGroup) list.getParent()).removeView(list);
                addBack();
                side.open(list, PlayerControls.this::scheduleHide);
                return;
            }
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setGravity(Gravity.BOTTOM);
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0.4f);
                w.setWindowAnimations(android.R.style.Animation_InputMethod);
            }
            openSheet = this;
            dialog.show();
        }

        private boolean hasBack;

        private void addBack() {
            if (hasBack) return;
            hasBack = true;
            View row = activity.getLayoutInflater().inflate(R.layout.row_sheet, list, false);
            ((ImageView) row.findViewById(R.id.sheet_icon)).setImageResource(R.drawable.ic_back);
            ((TextView) row.findViewById(R.id.sheet_title)).setText(activity.getString(R.string.back));
            row.findViewById(R.id.sheet_value).setVisibility(View.GONE);
            row.setOnClickListener(v -> dismiss());
            list.addView(row, 0);
        }

        void dismiss() {
            if (isLandscape() && side.isOpen()) {
                side.close(true);
                return;
            }
            dialog.dismiss();
        }

        void replaceWith(Runnable next) {
            if (isLandscape() && side.isOpen()) {
                next.run();
                return;
            }
            dialog.dismiss();
            next.run();
        }
    }
}
