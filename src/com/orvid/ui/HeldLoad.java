package com.orvid.ui;

import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

@SuppressWarnings("deprecation")
final class HeldLoad implements LoadControl {

    private final LoadControl inner;
    private volatile long holdUs;

    HeldLoad(long holdMs) {
        inner = PlayerActivity.defaultLoad();
        holdUs = holdMs * 1000;
    }

    void release() {
        holdUs = 0;
    }

    @Override
    public boolean shouldContinueLoading(LoadControl.Parameters p) {
        long hold = holdUs;
        if (hold > 0 && p.bufferedDurationUs >= hold) return false;
        return inner.shouldContinueLoading(p);
    }

    @Override
    public boolean shouldContinueLoading(long playbackPositionUs, long bufferedDurationUs, float speed) {
        long hold = holdUs;
        if (hold > 0 && bufferedDurationUs >= hold) return false;
        return inner.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, speed);
    }

    @Override
    public void onPrepared(PlayerId id) {
        inner.onPrepared(id);
    }

    @Override
    public void onPrepared() {
        inner.onPrepared();
    }

    @Override
    public void onTracksSelected(LoadControl.Parameters p, TrackGroupArray groups, ExoTrackSelection[] selections) {
        inner.onTracksSelected(p, groups, selections);
    }

    @Override
    public void onTracksSelected(PlayerId id, Timeline timeline, MediaSource.MediaPeriodId period, Renderer[] renderers,
                                 TrackGroupArray groups, ExoTrackSelection[] selections) {
        inner.onTracksSelected(id, timeline, period, renderers, groups, selections);
    }

    @Override
    public void onTracksSelected(Timeline timeline, MediaSource.MediaPeriodId period, Renderer[] renderers,
                                 TrackGroupArray groups, ExoTrackSelection[] selections) {
        inner.onTracksSelected(timeline, period, renderers, groups, selections);
    }

    @Override
    public void onTracksSelected(Renderer[] renderers, TrackGroupArray groups, ExoTrackSelection[] selections) {
        inner.onTracksSelected(renderers, groups, selections);
    }

    @Override
    public void onStopped(PlayerId id) {
        inner.onStopped(id);
    }

    @Override
    public void onStopped() {
        inner.onStopped();
    }

    @Override
    public void onReleased(PlayerId id) {
        inner.onReleased(id);
    }

    @Override
    public void onReleased() {
        inner.onReleased();
    }

    @Override
    public Allocator getAllocator(PlayerId id) {
        return inner.getAllocator(id);
    }

    @Override
    public long getBackBufferDurationUs(PlayerId id) {
        return inner.getBackBufferDurationUs(id);
    }

    @Override
    public long getBackBufferDurationUs() {
        return inner.getBackBufferDurationUs();
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId id) {
        return inner.retainBackBufferFromKeyframe(id);
    }

    @Override
    public boolean retainBackBufferFromKeyframe() {
        return inner.retainBackBufferFromKeyframe();
    }

    @Override
    public boolean shouldContinuePreloading(PlayerId id, Timeline timeline, MediaSource.MediaPeriodId period, long bufferedDurationUs) {
        return inner.shouldContinuePreloading(id, timeline, period, bufferedDurationUs);
    }

    @Override
    public boolean shouldStartPlayback(LoadControl.Parameters p) {
        return inner.shouldStartPlayback(p);
    }

    @Override
    public boolean shouldStartPlayback(Timeline timeline, MediaSource.MediaPeriodId period, long bufferedDurationUs,
                                       float speed, boolean rebuffering, long targetLiveOffsetUs) {
        return inner.shouldStartPlayback(timeline, period, bufferedDurationUs, speed, rebuffering, targetLiveOffsetUs);
    }

    @Override
    public boolean shouldStartPlayback(long bufferedDurationUs, float speed, boolean rebuffering, long targetLiveOffsetUs) {
        return inner.shouldStartPlayback(bufferedDurationUs, speed, rebuffering, targetLiveOffsetUs);
    }
}
