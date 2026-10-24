package com.orvid.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.orvid.R;
import com.orvid.app.Job;
import com.orvid.app.OrvidApp;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;
import com.orvid.net.Hedge;
import com.orvid.play.CircuitDataSource;
import com.orvid.play.Dash;
import com.orvid.play.Session;
import com.orvid.play.Subtitles;
import com.orvid.tor.Tor;
import com.orvid.yt.Visionos;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlayerActivity extends Activity implements com.orvid.app.Job.Scoped, com.orvid.cast.Cast.Listener, CastPanel.Host {

    private final com.orvid.net.Fetch.Scope jobScope = new com.orvid.net.Fetch.Scope();
    private final com.orvid.net.Fetch.Scope relatedScope = new com.orvid.net.Fetch.Scope().background();

    @Override
    public com.orvid.net.Fetch.Scope scope() {
        return jobScope;
    }

    @Override
    public com.orvid.net.Fetch.Scope backgroundScope() {
        return relatedScope;
    }

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_SERVICE = "service";
    public static final String EXTRA_THUMB = "thumb";
    public static final String EXTRA_INSTANT = "instant";
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_AVATAR = "avatar";
    public static final String EXTRA_DATE = "date";

    static String dateOf(StreamInfoItem item) {
        if (item.getUploadDate() != null) return "@" + item.getUploadDate().getInstant().toEpochMilli();
        String text = item.getTextualUploadDate();
        return text == null || text.isEmpty() ? null : text;
    }

    private void showPublished(String date) {
        published = date;
        TextView view = header.findViewById(R.id.published);
        if (date == null || date.isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
            return;
        }
        String shown = date;
        if (date.startsWith("@")) {
            try {
                shown = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.getDefault())
                        .format(new java.util.Date(Long.parseLong(date.substring(1))));
            } catch (NumberFormatException e) {
                shown = "";
            }
        }
        view.setText(shown.isEmpty() ? "" : getString(R.string.published, shown));
        view.setVisibility(!shown.isEmpty() && description.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private String published;
    private String nextDate;
    private static final Pattern LINK = Pattern.compile("https?://\\S+");

    private TextureView surface;
    private AmbientView ambient;
    private final android.graphics.Bitmap sample =
            android.graphics.Bitmap.createBitmap(32, 18, android.graphics.Bitmap.Config.ARGB_8888);
    private final Runnable sampler = this::sampleColors;
    private ImageView poster;
    private FrameLayout stage;
    private ListView details;
    private View header;
    private View buffering;
    private TextView debug;
    private TextView subtitle;
    private TextView description;
    private ImageView expand;
    private PlayerControls controls;
    private DragSheet sheet;
    private Player.Listener playerListener;
    private AnalyticsListener analytics;
    private boolean minimizing;
    private SidePanel side;
    private ExoPlayer player;
    private VideoAdapter related;
    private int videoWidth;
    private int videoHeight;

    private final List<MediaSource> sources = new ArrayList<>();
    private int sourceIndex;
    private String pageUrl;
    private String videoId;
    private String circuit;
    private Uri liveUri;
    private boolean firstFrame;
    private final Runnable saveLive = this::saveDiag;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (panic.feed(this, e)) return true;
        return super.dispatchTouchEvent(e);
    }

    private final Panic panic = new Panic();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        setContentView(R.layout.activity_player);
        surface = findViewById(R.id.video);
        ambient = findViewById(R.id.ambient);
        poster = findViewById(R.id.poster);
        stage = findViewById(R.id.stage);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::fitSurface);
        });
        details = findViewById(R.id.details);
        buffering = findViewById(R.id.buffering);
        debug = findViewById(R.id.debug);
        subtitle = findViewById(R.id.subtitle);

        header = LayoutInflater.from(this).inflate(R.layout.header_player, details, false);
        hideWhenEmpty(header.findViewById(R.id.uploader));
        hideWhenEmpty(header.findViewById(R.id.sub));
        header.findViewById(R.id.uploader).setOnClickListener(v -> openChannel());
        header.findViewById(R.id.uploader_avatar).setOnClickListener(v -> openChannel());
        details.addHeaderView(header, null, false);
        ((TextView) header.findViewById(R.id.name)).addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence t, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable t) {
                if (t.length() > 0 && !relatedRequested && (videoId != null || streamStarted)) {
                    stage.post(PlayerActivity.this::loadRelated);
                }
            }
        });
        description = header.findViewById(R.id.description);
        showPublished(getIntent().getStringExtra(EXTRA_DATE));
        expand = header.findViewById(R.id.expand);
        header.findViewById(R.id.title_block).setOnClickListener(v -> toggleDescription());
        related = new VideoAdapter(this, VideoAdapter.COMPACT, this::openRelated);
        details.setAdapter(related);
        details.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int state) {
                related.setIdle(state == SCROLL_STATE_IDLE);
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
                if (related.getCount() > 0 && first + visible >= total - 12) {
                    if (!hiddenRelated.isEmpty()) {
                        relatedShown += RELATED_STEP;
                        reveal(java.util.Collections.<StreamInfoItem>emptyList());
                    } else {
                        loadMoreRelated();
                    }
                }
            }
        });

        Diag.reset();
        Diag.mark("orvid " + (Fetch.torMode() ? "tor=on " + Tor.get().state() : "tor=off")
                + " android " + Build.VERSION.SDK_INT + " activity " + Integer.toHexString(hashCode())
                + " state=" + (state != null));
        Diag.listen(() -> debug.post(() -> {
            if (!debugOn()) return;
            debug.setText(Diag.tail(14));
            debug.removeCallbacks(saveLive);
            debug.postDelayed(saveLive, 700);
        }));
        applyDebug();

        pageUrl = linkFrom(getIntent());
        resumeAt = Math.max(0, getIntent().getLongExtra(EXTRA_START_MS, 0));
        final boolean reuse = pageUrl != null && Session.has(pageUrl);
        if (state != null && !reuse) resumeAt = Math.max(resumeAt, state.getLong(STATE_POSITION, 0));
        String thumb = reuse ? Session.thumb : getIntent().getStringExtra(EXTRA_THUMB);
        coverUrl = thumb;
        if (thumb != null) {
            OrvidApp.get().images().load(poster, thumb,
                    Math.min(640, getResources().getDisplayMetrics().widthPixels), pageUrl);
            poster.post(this::accentFromCover);
        }

        if (reuse) {
            player = Session.player();
        } else {
            player = new ExoPlayer.Builder(getApplicationContext(),
                    new androidx.media3.exoplayer.DefaultRenderersFactory(getApplicationContext()) {
                        @Override
                        protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                                android.content.Context context, boolean floatOutput, boolean playbackParams) {
                            return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                    .setEnableAudioTrackPlaybackParams(true)
                                    .build();
                        }
                    })
                .setBandwidthMeter(new DefaultBandwidthMeter.Builder(getApplicationContext())
                        .setInitialBitrateEstimate(com.orvid.play.Warm.startBitrate())
                        .build())
                .setTrackSelector(new DefaultTrackSelector(getApplicationContext(),
                        new AdaptiveTrackSelection.Factory(3000, 25000, 25000, 0.75f)))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 60000, 700, 2000)
                        .build())
                .build();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            if (pageUrl != null) Session.begin(player, pageUrl, thumb);
        }
        player.setVideoTextureView(surface);
        player.addListener(playerListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                stage.removeCallbacks(stallWatch);
                if (state == Player.STATE_BUFFERING) {
                    buffering.setVisibility(View.VISIBLE);
                    Diag.mark("player buffering");
                    watchStall();
                } else if (state == Player.STATE_READY) {
                    buffering.setVisibility(View.GONE);
                    Diag.mark("player ready");
                    applySprite();
                    cancelUpNext();
                } else if (state == Player.STATE_ENDED) {
                    upNext();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                frameSinceRestore = true;
                if (!firstFrame) {
                    Diag.mark("player first frame");
                }
                firstFrame = true;
                poster.setVisibility(View.GONE);
                stage.removeCallbacks(sampler);
                stage.postDelayed(sampler, 200);
                stage.postDelayed(PlayerActivity.this::loadRelated, relatedEarly != null ? 0 : 400);
                stage.removeCallbacks(warmNext);
                stage.postDelayed(warmNext, NEXT_WARM_CHECK_MS);
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoWidth = size.width;
                videoHeight = size.height;
                fitSurface();
                if (coverSpriteTried && pendingSprite == null && !coverProbing) grabFrames();
            }

            @Override
            public void onCues(CueGroup cues) {
                StringBuilder sb = new StringBuilder();
                for (Cue c : cues.cues) {
                    if (c.text == null) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.text);
                }
                subtitle.setText(sb);
                subtitle.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Diag.mark("player ERROR " + error.getErrorCodeName() + " "
                        + (error.getCause() == null ? "" : error.getCause().toString()));
                if (playlistInFile(error)) return;
                if (textOn() && textDrops < 2) {
                    textDrops++;
                    Diag.mark("subtitle failed, playing without it");
                    long at = Math.max(0, player.getCurrentPosition());
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                    player.seekTo(at);
                    player.prepare();
                    android.widget.Toast.makeText(PlayerActivity.this, R.string.subtitle_failed, android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveRecovers < LIVE_RECOVERS) {
                    liveRecovers++;
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED && reclaims < 3) {
                    reclaims++;
                    player.prepare();
                    return;
                }
                if (rateLimited(error) && videoId != null && reextracts < 2) {
                    reextracts++;
                    Diag.mark("rate limited, new circuit");
                    long at = Math.max(0, player.getCurrentPosition());
                    Visionos.forget(videoId);
                    resumeAt = at;
                    loadFast();
                    return;
                }
                if (videoId == null && sourceIndex + 1 >= sources.size() && reroutes < 2 && !fatal(error)) {
                    reroutes++;
                    resumeAt = Math.max(0, player.getCurrentPosition());
                    Diag.mark("playback failed, resolving again on a new circuit (" + reroutes + ")");
                    if (circuit != null) Fetch.rotate(circuit);
                    route();
                    return;
                }
                nextSource();
            }
        });
        player.addAnalyticsListener(analytics = new AnalyticsListener() {
            @Override
            public void onVideoInputFormatChanged(EventTime time, Format format, DecoderReuseEvaluation reuse) {
                Diag.mark("video quality " + format.height + "p " + format.bitrate / 1000 + "kbps");
            }
        });

        side = new SidePanel(findViewById(R.id.side_panel));
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) stage.post(this::fitSurface);
        });
        controls = new PlayerControls(this, player, stage, this::minimize, side);
        castPanel = new CastPanel(this, stage, this);
        cast = com.orvid.cast.Cast.get(this);
        cast.addListener(this);
        sourceAccent = Ui.accent(Ui.iconForUrl(this, pageUrl));
        if (sourceAccent != 0) controls.setAccent(sourceAccent);
        controls.setSkip(this::openPrevious, this::openNext);
        related.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override
            public void onChanged() {
                refreshSkip();
            }
        });
        refreshSkip();

        sheet = findViewById(R.id.sheet);
        sheet.setScroller(details);
        sheet.setListener(this::minimize);
        applyLayout(getResources().getConfiguration().orientation);
        if (!getIntent().getBooleanExtra(EXTRA_INSTANT, false)) {
            sheet.setTranslationY(getResources().getDisplayMetrics().heightPixels);
            sheet.animate().translationY(0).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        if (reuse) {
            restoreFromSession();
            return;
        }
        if (pageUrl == null) {
            showError(getString(R.string.error_unsupported));
            return;
        }
        related.setSkeleton(4);
        header.findViewById(R.id.related_label).setVisibility(View.VISIBLE);
        Session.avatar = getIntent().getStringExtra(EXTRA_AVATAR);
        showAvatar(Session.avatar);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            ((TextView) header.findViewById(R.id.name)).setText(title);
            controls.setTitle(title);
            seedRelated(title);
        }
        route();
    }

    private static final int RELATED_SEED = 6;

    private void seedRelated(String title) {
        List<StreamInfoItem> pool = new ArrayList<>(Session.lastSearch);
        if (pool.isEmpty()) return;
        List<StreamInfoItem> similar = com.orvid.search.Ranker.similar(title, null, pageUrl,
                java.util.Collections.<StreamInfoItem>emptyList(), pool);
        if (similar.isEmpty()) return;
        java.util.Map<String, Integer> at = new java.util.HashMap<>();
        for (int i = 0; i < pool.size(); i++) at.putIfAbsent(pool.get(i).getUrl(), i);
        similar.removeIf(i -> recentlyShown(i.getUrl()));
        if (similar.isEmpty()) return;
        List<StreamInfoItem> seeds = new ArrayList<>();
        Integer self = at.get(pageUrl);
        if (self != null) {
            int n = pool.size();
            StreamInfoItem next = java.util.Collections.min(similar, (x, y) -> Integer.compare(
                    Math.floorMod(at.get(x.getUrl()) - self, n), Math.floorMod(at.get(y.getUrl()) - self, n)));
            similar.remove(next);
            seeds.add(next);
        }
        List<StreamInfoItem> top = new ArrayList<>(similar.subList(0, Math.min(similar.size(), RELATED_SEED * 3)));
        java.util.Collections.shuffle(top, new java.util.Random(pageUrl.hashCode()));
        for (StreamInfoItem i : top) {
            if (seeds.size() >= RELATED_SEED) break;
            seeds.add(i);
        }
        Diag.mark("related seeded from search " + seeds.size());
        appendRelated(seeds);
    }

    private static final int RECENT_VIDEOS = 3;
    private static final java.util.ArrayDeque<java.util.Set<String>> RECENT = new java.util.ArrayDeque<>();
    private final java.util.Set<String> shownHere = new java.util.HashSet<>();

    private static boolean recentlyShown(String url) {
        synchronized (RECENT) {
            for (java.util.Set<String> s : RECENT) if (s.contains(url)) return true;
        }
        return false;
    }

    private boolean remembered;

    private void rememberShown() {
        if (remembered) return;
        remembered = true;
        if (pageUrl != null) shownHere.add(pageUrl);
        if (shownHere.isEmpty()) return;
        synchronized (RECENT) {
            RECENT.addFirst(new java.util.HashSet<>(shownHere));
            while (RECENT.size() > RECENT_VIDEOS) RECENT.removeLast();
        }
    }

    private void route() {
        java.util.regex.Matcher phEmbed = java.util.regex.Pattern.compile("pornhub\\.com/embed/([A-Za-z0-9]+)").matcher(pageUrl);
        if (phEmbed.find()) pageUrl = "https://www.pornhub.com/view_video.php?viewkey=" + phEmbed.group(1);
        videoId = Visionos.videoId(pageUrl);
        if (videoId != null) {
            final String id = videoId;
            relatedEarly = RELATED_POOL.submit(() -> com.orvid.yt.Related.page(Visionos.takeCircuit(), id));
        }
        if (videoId == null) {
            java.util.concurrent.Future<com.orvid.play.Resolve.Stream> ready = com.orvid.play.Resolve.takePrepared(pageUrl);
            if (ready != null) {
                loadPrepared(ready);
                return;
            }
        }
        String dmId = com.orvid.dm.Dailymotion.videoId(pageUrl);
        String vkId = com.orvid.vk.Vk.videoId(pageUrl);
        String twVod = com.orvid.tw.Twitch.vodId(pageUrl);
        if (player.getTrackSelectionParameters().maxVideoBitrate != Integer.MAX_VALUE) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setMaxVideoBitrate(Integer.MAX_VALUE).build());
        }
        String twClip = com.orvid.tw.Twitch.clipSlug(pageUrl);
        final com.orvid.ext.ExtSource ext = com.orvid.ext.Registry.forUrl(pageUrl);
        slowSource = ext != null && ext.hedged();
        if (videoId != null) loadFast();
        else if (ext != null) loadDirect(c -> ext.resolve(c, pageUrl), false);
        else if (dmId != null) loadDailymotion(dmId);
        else if (vkId != null) loadVk(vkId);
        else if (twVod != null || twClip != null) loadTwitch(twVod, twClip);
        else if (com.orvid.pt.PeerTube.handles(pageUrl)) loadPeerTube(pageUrl);
        else if (com.orvid.ia.Archive.itemId(pageUrl) != null) loadArchive(com.orvid.ia.Archive.itemId(pageUrl));
        else if (com.orvid.more.Sites.forUrl(pageUrl) != null) loadDirect(c -> com.orvid.more.Sites.forUrl(pageUrl).resolve(c, pageUrl), false);
        else if (newPipeHandles(pageUrl)) loadNewPipe(true);
        else loadGeneric();
    }

    private static boolean newPipeHandles(String url) {
        try {
            NewPipe.getServiceByUrl(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadGeneric() {
        new Job<Hedge.Result<com.orvid.play.Direct>>(this) {
            @Override
            protected Hedge.Result<com.orvid.play.Direct> load() throws Exception {
                return Hedge.call(Visionos.takePlayCircuit(), Visionos::takeCircuit, Fetch.torMode() ? 2500 : 0,
                        c -> com.orvid.play.Generic.resolve(c, pageUrl));
            }

            @Override
            protected void show(Hedge.Result<com.orvid.play.Direct> r) {
                playDirect(r.value, r.value.circuit != null ? r.value.circuit : r.circuit);
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("generic embed FAIL " + error);
                startActivity(new Intent(PlayerActivity.this, WebPlayerActivity.class)
                        .putExtra(WebPlayerActivity.EXTRA_URL, pageUrl));
                finish();
            }
        }.start();
    }

    private boolean frameSinceRestore;
    private static final long RESTORE_FRAME_WAIT_MS = 700;

    private void restoreFromSession() {
        showAvatar(Session.avatar);
        ((TextView) header.findViewById(R.id.name)).setText(Session.title);
        ((TextView) header.findViewById(R.id.uploader)).setText(Session.author);
        channelUrl = Session.channel;
        ((TextView) header.findViewById(R.id.sub)).setText(Session.sub);
        description.setText(Session.description);
        showPublished(Session.published);
        controls.setTitle(Session.title);
        circuit = Session.circuit;
        ytVideo = Session.video;
        directMedia = Session.direct;
        sources.clear();
        sources.addAll(Session.sources);
        sourceIndex = Session.sourceIndex;
        streamStarted = true;
        if (Session.storyboard != null) {
            coverSpriteTried = true;
            showFrames(Session.storyboard);
        }
        videoId = Visionos.videoId(pageUrl);
        relatedRequested = true;
        relatedToken = Session.relatedToken;
        List<StreamInfoItem> saved = new ArrayList<>(Session.related);
        for (StreamInfoItem i : saved) relatedSeen.add(i.getUrl());
        related.set(saved);
        header.findViewById(R.id.related_label).setVisibility(saved.isEmpty() ? View.GONE : View.VISIBLE);
        if (saved.isEmpty()) {
            relatedRequested = false;
            stage.post(this::loadRelated);
        }
        firstFrame = true;
        poster.setVisibility(View.GONE);
        frameSinceRestore = false;
        stage.postDelayed(() -> {
            if (frameSinceRestore || isFinishing() || player.getVideoSize().width == 0) return;
            Diag.mark("no frame after restore, redrawing");
            player.seekTo(Math.max(0, player.getCurrentPosition()));
        }, RESTORE_FRAME_WAIT_MS);
        VideoSize size = player.getVideoSize();
        videoWidth = size.width;
        videoHeight = size.height;
        stage.post(this::fitSurface);
        buffering.setVisibility(player.getPlaybackState() == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        controls.onPlaybackStateChanged(player.getPlaybackState());
        controls.onIsPlayingChanged(player.isPlaying());
        controls.onTracksChanged(player.getCurrentTracks());
        stage.postDelayed(sampler, 300);
    }

    private void saveToSession() {
        if (!Session.has(pageUrl)) return;
        Session.title = ((TextView) header.findViewById(R.id.name)).getText().toString();
        Session.author = ((TextView) header.findViewById(R.id.uploader)).getText().toString();
        Session.channel = channelUrl;
        Session.sub = ((TextView) header.findViewById(R.id.sub)).getText().toString();
        Session.description = description.getText();
        Session.published = published;
        Session.circuit = circuit;
        Session.relatedToken = relatedToken;
        Session.video = ytVideo;
        Session.direct = directMedia;
        Session.storyboard = board;
        Session.sourceIndex = sourceIndex;
        Session.sources.clear();
        Session.sources.addAll(sources);
    }

    void minimize() {
        if (minimizing) return;
        minimizing = true;
        saveToSession();
        Session.frame = null;
        if (surface.isAvailable() && surface.getWidth() > 0 && surface.getHeight() > 0) {
            int w = Math.min(surface.getWidth(), Ui.dp(this, 200));
            int h = Math.max(1, Math.round(w * surface.getHeight() / (float) surface.getWidth()));
            Session.frame = surface.getBitmap(w, h);
        }
        sheet.animate().translationY(sheet.getHeight()).setDuration(200)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    private static String linkFrom(Intent intent) {
        String url = intent.getStringExtra(EXTRA_URL);
        if (url != null) return url;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null) return null;
        Matcher m = LINK.matcher(shared);
        return m.find() ? m.group() : null;
    }

    private static final int FRAME_COUNT = 8;

    private com.orvid.play.Frames board;

    private void showFrames(com.orvid.play.Frames sb) {
        if (board != null && board != sb) board.stop();
        board = sb;
        controls.setStoryboard(sb);
        LinearLayout row = header.findViewById(R.id.frames_row);
        row.removeAllViews();
        pendingFrames.clear();
        header.findViewById(R.id.frames).setVisibility(View.GONE);
        if (!(sb instanceof com.orvid.play.Storyboard)) return;
        int w = Ui.dp(this, 128);
        int h = Math.round(w / sb.aspect());
        for (int i = 0; i < FRAME_COUNT; i++) {
            final long ms = (long) ((i + 0.5) * sb.duration() / FRAME_COUNT);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundResource(R.drawable.bg_thumb);
            img.setClipToOutline(true);
            cell.addView(img, new LinearLayout.LayoutParams(w, h));
            TextView t = new TextView(this);
            t.setText(Ui.duration(ms / 1000));
            t.setTextColor(getColor(R.color.text_secondary));
            t.setTextSize(12);
            t.setPadding(0, Ui.dp(this, 4), 0, 0);
            cell.addView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            row.addView(cell, lp);
            cell.setOnClickListener(v -> {
                player.seekTo(ms);
                player.setPlayWhenReady(true);
            });
            pendingFrames.add(() -> sb.frameAt(ms, img::setImageBitmap));
        }
        if (description.getVisibility() == View.VISIBLE) {
            header.findViewById(R.id.frames).setVisibility(View.VISIBLE);
            loadFrames();
        }
    }

    private final List<Runnable> pendingFrames = new ArrayList<>();

    private void loadFrames() {
        for (Runnable r : pendingFrames) r.run();
        pendingFrames.clear();
    }

    private void toggleDescription() {
        boolean open = description.getVisibility() != View.VISIBLE;
        description.setVisibility(open ? View.VISIBLE : View.GONE);
        TextView date = header.findViewById(R.id.published);
        date.setVisibility(open && date.length() > 0 ? View.VISIBLE : View.GONE);
        View frames = header.findViewById(R.id.frames);
        LinearLayout row = header.findViewById(R.id.frames_row);
        frames.setVisibility(open && row.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (open) loadFrames();
        ((TextView) header.findViewById(R.id.name)).setMaxLines(open ? Integer.MAX_VALUE : 2);
        expand.setRotation(open ? 180 : 0);
    }

    private static final int BACK_LIMIT = 20;
    private String nextAvatar;
    private static final java.util.ArrayDeque<String[]> BACK = new java.util.ArrayDeque<>();

    static void clearBack() {
        BACK.clear();
    }

    private void openRelated(StreamInfoItem item) {
        if (pageUrl != null) {
            String shown = ((TextView) header.findViewById(R.id.name)).getText().toString();
            BACK.push(new String[] {pageUrl, Integer.toString(getIntent().getIntExtra(EXTRA_SERVICE, -1)), coverUrl, shown});
            while (BACK.size() > BACK_LIMIT) BACK.removeLast();
        }
        nextAvatar = Ui.thumbnail(item.getUploaderAvatars(), Ui.dp(this, 32));
        nextDate = dateOf(item);
        switchTo(item.getUrl(), item.getServiceId(), Ui.thumbnail(item.getThumbnails(), 640), item.getName());
    }

    private void openNext() {
        cancelUpNext();
        StreamInfoItem queued = com.orvid.play.Queue.poll();
        if (queued != null) {
            openRelated(queued);
            return;
        }
        List<StreamInfoItem> list = related.items();
        if (!list.isEmpty()) openRelated(list.get(0));
    }

    private StreamInfoItem nextItem() {
        StreamInfoItem queued = com.orvid.play.Queue.peek();
        if (queued != null) return queued;
        List<StreamInfoItem> list = related.items();
        return list.isEmpty() ? null : list.get(0);
    }

    void queueChanged() {
        refreshSkip();
    }

    private static final int UP_NEXT_SECONDS = 5;
    private TextView upNext;
    private int upNextLeft;
    private final Runnable upNextTick = new Runnable() {
        @Override
        public void run() {
            if (upNext == null) return;
            if (upNextLeft <= 0) {
                openNext();
                return;
            }
            StreamInfoItem item = nextItem();
            if (item == null) {
                cancelUpNext();
                return;
            }
            upNext.setText(getString(R.string.up_next, upNextLeft, item.getName()));
            upNextLeft--;
            stage.postDelayed(this, 1000);
        }
    };

    private void upNext() {
        if (player == null || player.getRepeatMode() != Player.REPEAT_MODE_OFF || !OrvidApp.get().autoplay()
                || nextItem() == null || upNext != null) return;
        upNext = new TextView(this);
        upNext.setTextColor(getColor(R.color.badge_text));
        upNext.setTextSize(13);
        upNext.setMaxLines(3);
        upNext.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int pad = Ui.dp(this, 12);
        upNext.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.badge_bg));
        bg.setCornerRadius(Ui.dp(this, 16));
        upNext.setBackground(bg);
        upNext.setOnClickListener(v -> cancelUpNext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(this, 28);
        lp.leftMargin = lp.rightMargin = Ui.dp(this, 24);
        stage.addView(upNext, lp);
        upNextLeft = UP_NEXT_SECONDS;
        upNextTick.run();
    }

    private void cancelUpNext() {
        if (upNext == null) return;
        stage.removeCallbacks(upNextTick);
        stage.removeView(upNext);
        upNext = null;
    }

    private void openPrevious() {
        String[] last = BACK.poll();
        if (last != null) switchTo(last[0], Integer.parseInt(last[1]), last[2], last[3]);
    }

    private void refreshSkip() {
        if (controls != null) controls.showSkip(!BACK.isEmpty(), !com.orvid.play.Queue.isEmpty() || !related.items().isEmpty());
    }

    private void showAvatar(String use) {
        android.widget.ImageView avatar = header.findViewById(R.id.uploader_avatar);
        if (use == null || use.isEmpty()) {
            avatar.setVisibility(View.GONE);
            return;
        }
        avatar.setVisibility(View.VISIBLE);
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        OrvidApp.get().images().loadAvatar(avatar, use, Ui.dp(this, 32));
    }

    private void switchTo(String url, int service, String thumb, String title) {
        if (Ui.doubleTap()) return;
        rememberShown();
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_AVATAR, nextAvatar)
                .putExtra(EXTRA_DATE, nextDate)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_SERVICE, service)
                .putExtra(EXTRA_THUMB, thumb)
                .putExtra(EXTRA_INSTANT, true));
        overridePendingTransition(0, 0);
        finish();
        overridePendingTransition(0, 0);
    }

    private static final int PREPARE_WAIT_S = 45;

    private void loadFast() {
        new Job<Visionos.Prepared>(this) {
            @Override
            protected Visionos.Prepared load() throws Exception {
                Diag.mark("fast extract start");
                Visionos.Prepared p = Visionos.prepareNow(videoId).get(PREPARE_WAIT_S, java.util.concurrent.TimeUnit.SECONDS);
                Diag.mark("fast extract done");
                return p;
            }

            @Override
            protected void show(Visionos.Prepared p) {
                circuit = p.circuit;
                bindFast(p.video);
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("fast extract FAIL " + error + ", trying NewPipe");
                Visionos.forget(videoId);
                for (Throwable t = error; t != null; t = t.getCause()) {
                    if (t instanceof Visionos.AgeRestricted) {
                        buffering.setVisibility(View.GONE);
                        showError(getString(R.string.error_age));
                        return;
                    }
                }
                loadNewPipe(true);
            }
        }.start();
    }

    private void bindFast(Visionos.Video v) {
        ytVideo = v;
        ((TextView) header.findViewById(R.id.name)).setText(v.title);
        controls.setTitle(v.title);
        ((TextView) header.findViewById(R.id.sub)).setText(
                v.views >= 0 ? getString(R.string.views, Ui.count(v.views)) : "");
        ((TextView) header.findViewById(R.id.uploader)).setText(v.author);
        if (v.channelId != null) channelUrl = "https://www.youtube.com/channel/" + v.channelId;
        description.setText(v.description);
        showFrames(com.orvid.play.Storyboard.parse(v.storyboard, v.lengthSeconds * 1000, circuit));

        CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Visionos.USER_AGENT);
        sources.clear();
        List<Visionos.Format> videos = Dash.videoTracks(v.formats, 2160, com.orvid.play.Codecs.vp9MaxHeight());
        List<Visionos.Format> audios = Dash.audioTracks(v.formats);
        Visionos.Format audio = Dash.audioTrack(v.formats);
        if (!v.live && !videos.isEmpty() && audio != null && !audios.isEmpty()) {
            try {
                List<Visionos.Caption> captions = Subtitles.withTranslation(this, v.captions);
                String mpd = Dash.manifest(videos, audios, captions);
                DashManifest manifest = new DashManifestParser().parse(Uri.parse("https://www.youtube.com/"),
                        new ByteArrayInputStream(mpd.getBytes(StandardCharsets.UTF_8)));
                sources.add(new DashMediaSource.Factory(data)
                        .createMediaSource(manifest, MediaItem.fromUri(Uri.parse("https://www.youtube.com/"))));
                Diag.mark("source dash " + videos.get(0).codec() + " " + videos.size() + " qualities, " + audios.size() + " audio, "
                        + captions.size() + " subtitles");
            } catch (Exception e) {
                Diag.mark("dash build FAIL " + e);
            }
            int cap = Fetch.torMode() ? 720 : 1080;
            Visionos.Format top = null;
            for (Visionos.Format f : videos) {
                if (Dash.shortSide(f) <= cap && (top == null || Dash.shortSide(f) > Dash.shortSide(top))) top = f;
            }
            if (top == null) top = videos.get(videos.size() - 1);
            ProgressiveMediaSource.Factory progressive = new ProgressiveMediaSource.Factory(data);
            sources.add(new MergingMediaSource(
                    progressive.createMediaSource(MediaItem.fromUri(top.url)),
                    progressive.createMediaSource(MediaItem.fromUri(audio.url))));
        }
        if (v.hls != null) {
            sources.add(new HlsMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(v.hls)));
        }
        if (sources.isEmpty()) {
            loadNewPipe(true);
            return;
        }
        startPlayback();
        com.orvid.play.Warm.caption(circuit, likelyCaption(v.captions));
    }

    private static String likelyCaption(List<Visionos.Caption> captions) {
        String mine = java.util.Locale.getDefault().getLanguage();
        for (Visionos.Caption c : captions) {
            if (c.language.equals(mine) || c.language.startsWith(mine + "-")) return c.url;
        }
        return captions.isEmpty() ? null : captions.get(0).url;
    }

    private static final int TOR_TWITCH_MAX_BITRATE = 2_000_000;
    private static final int LIVE_RECOVERS = 5;
    private int textDrops;

    private boolean textOn() {
        if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return false;
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_TEXT && g.isSelected()) return true;
        }
        return false;
    }
    private int liveRecovers;
    private int reclaims;
    private com.orvid.play.Storyboard.Spec pendingSprite;

    private String coverUrl;
    private String channelUrl;

    private void openChannel() {
        if (!ChannelActivity.canOpen(channelUrl)) return;
        String name = ((TextView) header.findViewById(R.id.uploader)).getText().toString();
        String avatar = nextAvatarOrShown();
        minimize();
        ChannelActivity.open(this, channelUrl, name, avatar);
    }

    private String nextAvatarOrShown() {
        String a = getIntent().getStringExtra(EXTRA_AVATAR);
        return a != null ? a : Session.avatar;
    }
    private boolean coverSpriteTried;
    private boolean coverProbing;

    private void applySprite() {
        if (pendingSprite == null && !coverSpriteTried) {
            coverSpriteTried = true;
            com.orvid.play.Storyboard.Spec shots = com.orvid.play.Storyboard.screens(coverUrl);
            if (shots != null && pageUrl != null) {
                android.net.Uri page = android.net.Uri.parse(pageUrl);
                shots.referer = page.getScheme() + "://" + page.getHost() + "/";
            }
            if (shots != null) {
                coverProbing = true;
                final String c = circuit;
                RELATED_POOL.execute(() -> {
                    int n = com.orvid.play.Storyboard.probeCount(shots, c);
                    Diag.mark("cover screens " + n);
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        coverProbing = false;
                        if (n >= 3) pendingSprite = shots.withCount(n);
                        applySprite();
                    });
                });
            }
        }
        if (pendingSprite == null) {
            if (!coverProbing) grabFrames();
            return;
        }
        long duration = player.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) return;
        com.orvid.play.Storyboard sb = com.orvid.play.Storyboard.grid(pendingSprite, duration, circuit);
        pendingSprite = null;
        if (sb != null) showFrames(sb);
    }

    private void grabFrames() {
        if (board != null || directMedia == null || directMedia.dash) return;
        long duration = player.getDuration();
        VideoSize size = player.getVideoSize();
        if (duration <= 0 || duration == C.TIME_UNSET || size.width == 0 || size.height == 0) return;
        float aspect = size.width * size.pixelWidthHeightRatio / size.height;
        Diag.mark("frame grab on " + (directMedia.hls ? "hls" : "file"));
        showFrames(new com.orvid.play.FrameGrab(directMedia, circuit, duration, aspect));
    }

    private static final long STALL_START_MS = 8000;
    private static final long STALL_PLAYING_MS = 10000;
    private static final long STALL_PROGRESS_MS = 3000;
    private static final long STALL_PROGRESS_BYTES = 128 * 1024;
    private int stallSwitches;
    private boolean slowSource;
    private static final int SLOW_SOURCE_FACTOR = 4;
    private static final int SLOW_MEDIA_TIMEOUT_MS = 45000;
    private long stallBuffered;
    private final Runnable stallWatch = this::onStall;

    private static final long NEXT_WARM_BUFFER_MS = 20000;
    private static final long NEXT_WARM_CHECK_MS = 3000;
    private static final int NEXT_FIRST_FRAME = 2;
    private boolean nextWarmed;
    private static final long FOCUS_LOW_MS = 15000;
    private static final long FOCUS_HIGH_MS = 30000;
    private static final long FOCUS_START_MS = 20000;
    private static final long FOCUS_TICK_MS = 1000;
    private final long openedAt = android.os.SystemClock.uptimeMillis();
    private boolean focused = true;

    private final Runnable focusTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            int state = player.getPlaybackState();
            long ahead = player.getTotalBufferedDuration();
            long duration = player.getDuration();
            boolean whole = duration != C.TIME_UNSET && duration > 0 && player.getBufferedPosition() >= duration - 1000;
            if (state == Player.STATE_ENDED || player.getPlayerError() != null) {
                focused = false;
            } else if (state == Player.STATE_IDLE) {
                focused = android.os.SystemClock.uptimeMillis() - openedAt < FOCUS_START_MS;
            } else if (player.isCurrentMediaItemLive()) {
                focused = state == Player.STATE_BUFFERING;
            } else if (whole || ahead >= FOCUS_HIGH_MS) {
                focused = false;
            } else if (ahead < FOCUS_LOW_MS || state == Player.STATE_BUFFERING) {
                focused = true;
            }
            com.orvid.net.Focus.hold(focused);
            stage.postDelayed(this, FOCUS_TICK_MS);
        }
    };
    private static final int LINK_AHEAD = 4;
    private static final int LINK_AHEAD_DIRECT = 1;
    private int linked;

    private void linkAhead(List<StreamInfoItem> items) {
        int ahead = Fetch.torMode() ? LINK_AHEAD : LINK_AHEAD_DIRECT;
        for (int i = linked; i < Math.min(items.size(), ahead); i++) com.orvid.play.Warm.link(items.get(i).getUrl());
        linked = Math.max(linked, Math.min(items.size(), ahead));
    }
    private final Runnable warmNext = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || nextWarmed) return;
            List<StreamInfoItem> next = related.items();
            if (!next.isEmpty() && !focused && player.getTotalBufferedDuration() >= NEXT_WARM_BUFFER_MS) {
                nextWarmed = true;
                com.orvid.play.Warm.prepare(next.get(0).getUrl());
                for (int i = 1; i < Math.min(next.size(), 1 + NEXT_FIRST_FRAME); i++) {
                    com.orvid.play.Warm.prepare(next.get(i).getUrl(), true);
                }
                linkAhead(next);
                return;
            }
            stage.postDelayed(this, NEXT_WARM_CHECK_MS);
        }
    };

    private long stallBytes;

    private void watchStall() {
        stallBuffered = player.getBufferedPosition();
        stallBytes = com.orvid.play.CircuitDataSource.received();
        stage.postDelayed(stallWatch, (firstFrame ? STALL_PLAYING_MS : STALL_START_MS) * (slowSource ? SLOW_SOURCE_FACTOR : 1));
    }

    private void onStall() {
        if (isFinishing() || !Fetch.torMode() || player.getPlaybackState() != Player.STATE_BUFFERING
                || !player.getPlayWhenReady() || stallSwitches >= 2 || pageUrl == null) return;
        long buffered = player.getBufferedPosition();
        long bytes = com.orvid.play.CircuitDataSource.received();
        if (buffered - stallBuffered >= STALL_PROGRESS_MS || !firstFrame && bytes - stallBytes >= STALL_PROGRESS_BYTES) {
            watchStall();
            return;
        }
        stallSwitches++;
        resumeAt = Math.max(0, player.getCurrentPosition());
        Diag.mark("stalled, switching circuit (" + stallSwitches + ")");
        if (circuit != null && !slowSource) Fetch.rotate(circuit);
        if (videoId != null) {
            Visionos.forget(videoId);
            loadFast();
        } else {
            route();
        }
    }
    private int reextracts;
    private int reroutes;
    private long resumeAt;
    private static final String STATE_POSITION = "position";

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (player != null && !player.isCurrentMediaItemLive() && player.getCurrentPosition() > 0) {
            out.putLong(STATE_POSITION, player.getCurrentPosition());
        }
    }

    private static boolean rateLimited(PlaybackException error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int code = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) t).responseCode;
                return code == 429 || code == 403;
            }
        }
        return false;
    }

    private boolean relatedRequested;

    private static void hideWhenEmpty(TextView view) {
        view.setVisibility(view.getText().length() == 0 ? View.GONE : View.VISIBLE);
        view.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                view.setVisibility(s.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
    }

    private java.util.concurrent.Future<com.orvid.yt.Search.Page> relatedEarly;

    private void loadRelated() {
        if (relatedRequested || isFinishing()) return;
        if (((TextView) header.findViewById(R.id.name)).length() == 0) return;
        relatedRequested = true;
        if (videoId == null) {
            loadRelatedElsewhere();
            return;
        }
        if (circuit == null && relatedEarly == null) {
            loadRelatedElsewhere();
            return;
        }
        final String c0 = circuit != null ? circuit : Visionos.takeCircuit();
        final java.util.concurrent.Future<com.orvid.yt.Search.Page> early = relatedEarly;
        final String title = ((TextView) header.findViewById(R.id.name)).getText().toString();
        final String channel = ((TextView) header.findViewById(R.id.uploader)).getText().toString();
        relatedPending++;
        new Job<List<StreamInfoItem>>(this) {
            @Override
            protected List<StreamInfoItem> load() throws Exception {
                com.orvid.yt.Search.Page p;
                try {
                    p = early != null ? early.get() : com.orvid.yt.Related.page(Visionos.takeCircuit(), videoId);
                } catch (java.util.concurrent.ExecutionException e) {
                    p = com.orvid.yt.Related.page(Visionos.takeCircuit(), videoId);
                }
                relatedToken = p.continuation;
                return com.orvid.search.Ranker.similar(title, channel, pageUrl, p.items,
                        java.util.Collections.<StreamInfoItem>emptyList(), true);
            }

            @Override
            protected void show(List<StreamInfoItem> first) {
                appendRelated(first);
                if (first.size() < RELATED_STEP) {
                    loadRelatedByText(c0, title, channel);
                    loadRelatedAcross(title, channel, false, null, true);
                }
                relatedDone();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("related next FAIL " + error);
                loadRelatedByText(c0, title, channel);
                relatedDone();
            }
        }.startBackground();
    }

    private static final int RELATED_MIN = 16;

    private void loadRelatedAcross(String title, String channel, boolean adult, com.orvid.search.Source own,
                                   boolean skipYouTube) {
        String keys = relatedKeys(com.orvid.search.Query.parse(title), title);
        if (keys.isEmpty()) return;
        final com.orvid.search.Filters filters = relatedFilters();
        relatedPending++;
        new Job<List<StreamInfoItem>>(this) {
            @Override
            protected List<StreamInfoItem> load() {
                List<StreamInfoItem> found = relatedFromOthers(keys, own, adult, filters);
                if (skipYouTube) found.removeIf(i -> i.getServiceId() == org.schabi.newpipe.extractor.ServiceList.YouTube.getServiceId());
                return com.orvid.search.Ranker.similar(title, channel, pageUrl,
                        java.util.Collections.<StreamInfoItem>emptyList(), found, false, adult ? RELATED_MIN : 0);
            }

            @Override
            protected void show(List<StreamInfoItem> items) {
                appendRelated(items);
                relatedDone();
            }

            @Override
            protected void fail(Exception error) {
                relatedDone();
            }
        }.startBackground();
    }
    private int relatedPending;
    private int relatedFallbacks;

    private void relatedDone() {
        if (relatedPending > 0) relatedPending--;
        if (relatedPending > 0 || related.getCount() > 0 || isFinishing()) return;
        if (relatedFallbacks == 0) {
            List<StreamInfoItem> fromSearch = new ArrayList<>();
            for (StreamInfoItem i : Session.lastSearch) {
                if (!i.getUrl().equals(pageUrl) && !com.orvid.search.NotInterested.hides(i)) fromSearch.add(i);
            }
            if (!fromSearch.isEmpty()) {
                Diag.mark("related from last search " + fromSearch.size());
                appendRelated(fromSearch);
                if (related.getCount() > 0) return;
            }
        }
        if (relatedFallbacks >= 3) return;
        relatedFallbacks++;
        loadRelatedFallback(relatedFallbacks);
    }

    private void loadRelatedFallback(int round) {
        final String title = ((TextView) header.findViewById(R.id.name)).getText().toString();
        final String channel = ((TextView) header.findViewById(R.id.uploader)).getText().toString();
        com.orvid.search.Query q = com.orvid.search.Query.parse(title);
        List<String> keys = com.orvid.search.Ranker.keyTerms(q.terms);
        if (keys.isEmpty()) keys = q.terms;
        if (keys.isEmpty()) return;
        int take = round == 1 ? Math.min(5, keys.size()) : round == 2 ? Math.min(2, keys.size()) : 1;
        final String query = String.join(" ", keys.subList(0, take));
        final com.orvid.search.Source own = pageUrl == null ? null : Sources.forUrl(pageUrl);
        final boolean adult = own != null && own.adult;
        final com.orvid.search.Filters filters = relatedFilters();
        Diag.mark("related fallback " + round + " [" + query + "]");
        relatedPending++;
        new Job<List<StreamInfoItem>>(this) {
            @Override
            protected List<StreamInfoItem> load() {
                return com.orvid.search.Ranker.similar(title, channel, pageUrl,
                        java.util.Collections.<StreamInfoItem>emptyList(),
                        relatedFromOthers(query, null, adult, filters), false, RELATED_MIN);
            }

            @Override
            protected void show(List<StreamInfoItem> items) {
                appendRelated(items);
                relatedDone();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("related fallback FAIL " + error);
                relatedDone();
            }
        }.startBackground();
    }

    private static String relatedKeys(com.orvid.search.Query q, String title) {
        List<String> keys = com.orvid.search.Ranker.keyTerms(q.terms);
        if (keys.isEmpty()) keys = q.terms;
        if (keys.isEmpty()) return title.trim();
        return String.join(" ", keys.subList(0, Math.min(5, keys.size())));
    }

    private void loadRelatedElsewhere() {
        final String title = ((TextView) header.findViewById(R.id.name)).getText().toString();
        final String channel = ((TextView) header.findViewById(R.id.uploader)).getText().toString();
        com.orvid.search.Query q = com.orvid.search.Query.parse(title);
        final String keys = relatedKeys(q, title);
        if (keys.isEmpty() || pageUrl == null) {
            relatedPending++;
            relatedDone();
            return;
        }
        final com.orvid.search.Source own = Sources.forUrl(pageUrl);
        final boolean adult = own != null && own.adult;
        final int service = own != null ? own.serviceId
                : com.orvid.dm.Dailymotion.videoId(pageUrl) != null ? com.orvid.dm.Dailymotion.SERVICE_ID
                : com.orvid.vk.Vk.videoId(pageUrl) != null ? com.orvid.vk.Vk.SERVICE_ID : -1;
        final com.orvid.search.Filters filters = relatedFilters();
        relatedPending++;
        new Job<List<StreamInfoItem>>(this) {
            @Override
            protected List<StreamInfoItem> load() throws Exception {
                List<StreamInfoItem> same = new ArrayList<>();
                List<StreamInfoItem> other = new ArrayList<>();
                String c = Visionos.takeCircuit();
                String sameKeys = keys;
                if (own instanceof com.orvid.ext.ExtSource) {
                    try {
                        List<String> tags = Fetch.retrying(c, cc -> ((com.orvid.ext.ExtSource) own).tags(cc, pageUrl));
                        if (!tags.isEmpty()) sameKeys = String.join(" ", tags.subList(0, Math.min(3, tags.size())));
                    } catch (Exception e) {
                        Diag.mark("related tags FAIL " + e);
                    }
                }
                final String query = sameKeys;
                try {
                    if (own != null && !own.slow()) {
                        same.addAll(Fetch.retrying(c, cc -> own.search(cc, query, filters, null)).items);
                    } else if (service == com.orvid.dm.Dailymotion.SERVICE_ID) {
                        same.addAll(Fetch.retrying(c, cc -> com.orvid.dm.Dailymotion.search(cc, query, 1,
                                filters.dailymotionParams(), false)).items);
                    } else if (service == com.orvid.vk.Vk.SERVICE_ID) {
                        same.addAll(Fetch.retrying(c, cc -> com.orvid.vk.Vk.search(cc, query, null)).items);
                    }
                } catch (Exception e) {
                    Diag.mark("related same-source FAIL " + e);
                }

                return com.orvid.search.Ranker.similar(title, channel, pageUrl, same, other, false, RELATED_MIN);
            }

            @Override
            protected void show(List<StreamInfoItem> items) {
                appendRelated(items);
                if (items.size() < RELATED_STEP) loadRelatedAcross(title, channel, adult, own, false);
                relatedDone();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("related elsewhere FAIL " + error);
                loadRelatedAcross(title, channel, adult, own, false);
                relatedDone();
            }
        }.startBackground();
    }

    private static List<StreamInfoItem> relatedFromOthers(String keys, com.orvid.search.Source own, boolean adult,
                                                          com.orvid.search.Filters filters) {
        com.orvid.search.Query q = com.orvid.search.Query.parse(keys);
        List<java.util.concurrent.Callable<List<StreamInfoItem>>> jobs = new ArrayList<>();
        if (!adult && filters.youtube && com.orvid.ext.Registry.engineOn("youtube")) {
            jobs.add(() -> Fetch.retrying(Visionos.takeCircuit(), cc -> com.orvid.yt.Search.first(cc, keys, null)).items);
        }
        List<com.orvid.search.Source> pool = new ArrayList<>();
        for (com.orvid.search.Source s : Sources.current()) {
            if (s == own || s.adult != adult || s.usesWebView() || s.slow() || !s.wanted(filters, q)) continue;
            pool.add(s);
        }
        pool.sort((x, y) -> Double.compare(y.weight, x.weight));
        for (com.orvid.search.Source s : pool.subList(0, Math.min(3, pool.size()))) {
            jobs.add(() -> Fetch.retrying(Fetch.newCircuit("rel"), cc -> s.search(cc, keys, filters, null)).items);
        }
        List<StreamInfoItem> out = new ArrayList<>();
        if (jobs.isEmpty()) return out;
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(jobs.size());
        try {
            List<java.util.concurrent.Future<List<StreamInfoItem>>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<List<StreamInfoItem>> j : jobs) futures.add(ex.submit(j));
            long deadline = System.currentTimeMillis() + 12000;
            for (java.util.concurrent.Future<List<StreamInfoItem>> f : futures) {
                try {
                    out.addAll(f.get(Math.max(1, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    f.cancel(true);
                }
            }
        } finally {
            ex.shutdownNow();
        }
        return out;
    }

    private void loadRelatedByText(String c0, String title, String channel) {
        com.orvid.search.Query q = com.orvid.search.Query.parse(title);
        if (q.terms.isEmpty() || !relatedFilters().youtube || !com.orvid.ext.Registry.engineOn("youtube")) return;
        final String keys = relatedKeys(q, title);
        final int minimum = related.getCount() < RELATED_MIN ? RELATED_MIN : 0;
        relatedPending++;
        new Job<List<StreamInfoItem>>(this) {
            @Override
            protected List<StreamInfoItem> load() throws Exception {
                List<StreamInfoItem> found = com.orvid.yt.Search.first(c0, keys, null).items;
                return com.orvid.search.Ranker.similar(title, channel, pageUrl,
                        java.util.Collections.<StreamInfoItem>emptyList(), found, false, minimum);
            }

            @Override
            protected void show(List<StreamInfoItem> more) {
                appendRelated(more);
                relatedDone();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("related search FAIL " + error);
                relatedDone();
            }
        }.startBackground();
    }

    private static final int RELATED_STEP = 6;
    private int relatedShown = RELATED_STEP;
    private final List<StreamInfoItem> hiddenRelated = new ArrayList<>();

    private void reveal(List<StreamInfoItem> fresh) {
        hiddenRelated.addAll(fresh);
        int room = relatedShown - related.items().size();
        if (room <= 0 || hiddenRelated.isEmpty()) return;
        List<StreamInfoItem> now = new ArrayList<>(hiddenRelated.subList(0, Math.min(room, hiddenRelated.size())));
        hiddenRelated.subList(0, now.size()).clear();
        for (StreamInfoItem i : now) shownHere.add(i.getUrl());
        related.add(now);
        if (nextWarmed) linkAhead(related.items());
    }

    private void appendRelated(List<StreamInfoItem> items) {
        if (items.isEmpty() && relatedSeen.isEmpty()) {
            related.set(new ArrayList<>());
            header.findViewById(R.id.related_label).setVisibility(View.GONE);
            return;
        }
        com.orvid.search.Filters allowed = relatedFilters();
        String own = ((TextView) header.findViewById(R.id.name)).getText().toString();
        List<StreamInfoItem> fresh = new ArrayList<>();
        List<StreamInfoItem> repeats = new ArrayList<>();
        for (StreamInfoItem i : items) {
            if (!com.orvid.search.Ranker.sameScript(own, i.getName())) continue;
            if (sourceOn(allowed, i) && allowed.orientationAccepts(i) && relatedSeen.add(i.getUrl())) {
                (recentlyShown(i.getUrl()) ? repeats : fresh).add(i);
            }
        }
        fresh.addAll(repeats);
        reveal(fresh);
        Session.related.addAll(fresh);
        header.findViewById(R.id.related_label).setVisibility(related.getCount() == 0 ? View.GONE : View.VISIBLE);
    }


    private com.orvid.search.Filters relatedFilters() {
        if (com.orvid.search.Filters.current != null) return com.orvid.search.Filters.current;
        com.orvid.search.Filters.current = com.orvid.search.Filters.load(getSharedPreferences("settings", MODE_PRIVATE));
        return com.orvid.search.Filters.current;
    }

    private static boolean sourceOn(com.orvid.search.Filters f, StreamInfoItem item) {
        int sid = item.getServiceId();
        if (sid == com.orvid.dm.Dailymotion.SERVICE_ID) return f.dailymotion;
        if (sid == com.orvid.vk.Vk.SERVICE_ID) return f.vk;
        com.orvid.search.Source s = Sources.byService(sid);
        if (s == null) return f.youtube;
        if (s.adult && (f.hideAdult || !com.orvid.search.Source.adultUnlocked)) return false;
        return f.on(s);
    }

    private volatile String relatedToken;
    private boolean relatedLoading;
    private final java.util.Set<String> relatedSeen = new java.util.HashSet<>();

    private final java.util.Set<String> relatedSeeds = new java.util.HashSet<>();
    private int relatedEmptyPages;
    private static final int RELATED_EMPTY_LIMIT = 3;

    private String nextSeed() {
        for (StreamInfoItem i : related.items()) {
            String id = Visionos.videoId(i.getUrl());
            if (id != null && relatedSeeds.add(id)) return id;
        }
        return null;
    }

    private void loadMoreRelated() {
        if (relatedLoading) return;
        final String token = relatedToken;
        final String seed = token == null ? nextSeed() : null;
        if (token == null && seed == null) return;
        relatedLoading = true;
        final String c0 = circuit != null ? circuit : Visionos.takeCircuit();
        final String own = ((TextView) header.findViewById(R.id.name)).getText().toString();
        final String ownChannel = ((TextView) header.findViewById(R.id.uploader)).getText().toString();
        final List<StreamInfoItem> shownNow = new ArrayList<>(related.items());
        final com.orvid.search.Filters allowed = relatedFilters();
        final String self = pageUrl;
        final int[] foreign = new int[1];
        final List<StreamInfoItem> kept = new ArrayList<>();
        new Job<com.orvid.yt.Search.Page>(this) {
            @Override
            protected com.orvid.yt.Search.Page load() throws Exception {
                com.orvid.yt.Search.Page page = Fetch.retrying(c0, c -> token != null
                        ? com.orvid.yt.Related.more(c, token) : com.orvid.yt.Related.page(c, seed));
                for (StreamInfoItem i : page.items) {
                    if (i.getUrl().equals(self) || !sourceOn(allowed, i)) continue;
                    if (!com.orvid.search.Ranker.sameScript(own, i.getName())
                            || !com.orvid.search.Ranker.nearTopic(own, ownChannel, i, shownNow)) {
                        foreign[0]++;
                        continue;
                    }
                    kept.add(i);
                }
                return page;
            }

            @Override
            protected void show(com.orvid.yt.Search.Page p) {
                relatedToken = p.continuation;
                List<StreamInfoItem> fresh = new ArrayList<>();
                for (StreamInfoItem i : kept) if (relatedSeen.add(i.getUrl())) fresh.add(i);
                if (foreign[0] > 0) Diag.mark("related more: " + foreign[0] + " off topic dropped");
                reveal(fresh);
                Session.related.addAll(fresh);
                Session.relatedToken = relatedToken;
                relatedLoading = false;
                relatedEmptyPages = fresh.isEmpty() ? relatedEmptyPages + 1 : 0;
                if (fresh.isEmpty() && relatedEmptyPages < RELATED_EMPTY_LIMIT) loadMoreRelated();
            }

            @Override
            protected void fail(Exception error) {
                relatedLoading = false;
            }
        }.startBackground();
    }

    private static final java.util.concurrent.ExecutorService RELATED_POOL =
            java.util.concurrent.Executors.newCachedThreadPool();

    private void loadTwitch(final String vod, final String clip) {
        circuit = Visionos.takePlayCircuit();
        final String c0 = circuit;
        new Job<com.orvid.tw.Twitch.Media>(this) {
            @Override
            protected com.orvid.tw.Twitch.Media load() throws Exception {
                Diag.mark("twitch resolve start");
                Hedge.Result<com.orvid.tw.Twitch.Media> r = Hedge.call(c0, Visionos::takeCircuit,
                        Fetch.torMode() ? 2500 : 0, c -> Fetch.retrying(c, cc -> vod != null
                                ? com.orvid.tw.Twitch.vod(cc, vod) : com.orvid.tw.Twitch.clip(cc, clip)));
                circuit = r.circuit;
                return r.value;
            }

            @Override
            protected void show(com.orvid.tw.Twitch.Media m) {
                Diag.mark("twitch show hls=" + (m.hls != null) + " mp4=" + (m.mp4 != null));
                if (Fetch.torMode()) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setMaxVideoBitrate(TOR_TWITCH_MAX_BITRATE).build());
                }
                ((TextView) header.findViewById(R.id.name)).setText(m.title);
                controls.setTitle(m.title);
                ((TextView) header.findViewById(R.id.uploader)).setText(m.author);
                ((TextView) header.findViewById(R.id.sub)).setText(
                        m.views >= 0 ? getString(R.string.views, Ui.count(m.views)) : "");
                CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Fetch.USER_AGENT);
                sources.clear();
                if (m.hls != null) {
                    String uri = m.hls;
                    if (m.master != null) {
                        try {
                            java.io.File f = new java.io.File(getCacheDir(), "twitch-master.m3u8");
                            java.nio.file.Files.write(f.toPath(), m.master.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            uri = android.net.Uri.fromFile(f).toString();
                        } catch (java.io.IOException ignored) {
                        }
                    }
                    androidx.media3.datasource.DataSource.Factory any =
                            new androidx.media3.datasource.DefaultDataSource.Factory(PlayerActivity.this, data);
                    sources.add(new HlsMediaSource.Factory(any).createMediaSource(MediaItem.fromUri(uri)));
                }
                if (m.mp4 != null) sources.add(new ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(m.mp4)));
                startPlayback();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("twitch FAIL " + error);
                buffering.setVisibility(View.GONE);
                showError(Ui.error(PlayerActivity.this, error));
            }
        }.start();
    }

    static final class Download {
        final String label;
        final com.orvid.app.DownloadService.Request request;

        Download(String label, com.orvid.app.DownloadService.Request request) {
            this.label = label;
            this.request = request;
        }
    }

    private Visionos.Video ytVideo;
    private com.orvid.play.Direct directMedia;
    private com.orvid.app.DownloadService.Request waitingStorage;
    private static final int STORAGE_REQUEST = 7;

    boolean downloadable() {
        return ytVideo != null && !ytVideo.live && !downloads().isEmpty() || directMedia != null && !directMedia.hls && !directMedia.dash;
    }

    List<Download> downloads() {
        List<Download> out = new ArrayList<>();
        String title = ((TextView) header.findViewById(R.id.name)).getText().toString();
        String base = fileName(title);
        if (ytVideo != null) {
            Visionos.Format audio = null;
            for (Visionos.Format f : ytVideo.formats) {
                if (f.isVideo() || !f.mime.startsWith("audio/mp4") || !f.isOriginalAudio()) continue;
                if (audio == null || f.bitrate > audio.bitrate) audio = f;
            }
            if (audio == null) return out;
            java.util.Map<Integer, Visionos.Format> best = new java.util.TreeMap<>(java.util.Collections.reverseOrder());
            for (Visionos.Format f : ytVideo.formats) {
                if (!f.isVideo() || !f.codec().startsWith("avc1") || f.height <= 0) continue;
                Visionos.Format had = best.get(Dash.shortSide(f));
                if (had == null || f.bitrate > had.bitrate) best.put(Dash.shortSide(f), f);
            }
            for (Visionos.Format f : best.values()) {
                String quality = Dash.shortSide(f) + "p" + (f.fps > 30 ? f.fps : "");
                com.orvid.app.DownloadService.Request r = new com.orvid.app.DownloadService.Request(title,
                        base + " (" + quality + ").mp4", "video/mp4").add(f.url, f.contentLength).add(audio.url, audio.contentLength);
                r.userAgent = Visionos.USER_AGENT;
                out.add(new Download(quality + size(f.contentLength, audio.contentLength), r));
            }
            com.orvid.app.DownloadService.Request a = new com.orvid.app.DownloadService.Request(title, base + ".m4a", "audio/mp4")
                    .add(audio.url, audio.contentLength);
            a.userAgent = Visionos.USER_AGENT;
            a.remux = true;
            out.add(new Download(getString(R.string.download_audio) + size(audio.contentLength, 0), a));
        } else if (directMedia != null && !directMedia.hls && !directMedia.dash) {
            boolean webm = directMedia.url.contains(".webm");
            com.orvid.app.DownloadService.Request r = new com.orvid.app.DownloadService.Request(title,
                    base + (webm ? ".webm" : ".mp4"), webm ? "video/webm" : "video/mp4").add(directMedia.url, -1);
            r.referer = directMedia.referer;
            if (directMedia.cookie != null) {
                r.cookie = directMedia.cookie;
                r.circuit = circuit;
            }
            out.add(new Download(getString(R.string.download_video), r));
        }
        return out;
    }

    private static String size(long a, long b) {
        if (a <= 0) return "";
        return String.format(java.util.Locale.getDefault(), " (%.0f MB)", (a + Math.max(0, b)) / 1048576.0);
    }

    private static String fileName(String title) {
        String s = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.isEmpty() ? "video" : s;
    }

    void download(Download d) {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        if (android.os.Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            waitingStorage = d.request;
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            return;
        }
        startDownload(d.request);
    }

    private void startDownload(com.orvid.app.DownloadService.Request r) {
        com.orvid.app.DownloadService.enqueue(this, r);
        android.widget.Toast.makeText(this, R.string.download_started, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != STORAGE_REQUEST || waitingStorage == null) return;
        com.orvid.app.DownloadService.Request r = waitingStorage;
        waitingStorage = null;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startDownload(r);
        else android.widget.Toast.makeText(this, R.string.download_failed, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void playDirect(com.orvid.play.Direct d, String on) {
        circuit = on;
        directMedia = d;
        Subtitles.addTranslation(this, d);
        TextView name = header.findViewById(R.id.name);
        if (!d.title.isEmpty() || name.getText().length() == 0) {
            name.setText(d.title);
            controls.setTitle(d.title);
        }
        ((TextView) header.findViewById(R.id.uploader)).setText(d.author);
        ((TextView) header.findViewById(R.id.sub)).setText("");
        CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Fetch.USER_AGENT, d.referer).cookie(d.cookie)
                .timeout(slowSource ? SLOW_MEDIA_TIMEOUT_MS : 0);
        sources.clear();
        sources.add(directSource(data, d.url, d.hls, d.dash));
        if (d.fallback != null) {
            MediaItem backup = MediaItem.fromUri(d.fallback);
            sources.add(d.fallbackHls ? new HlsMediaSource.Factory(data).createMediaSource(backup)
                    : new ProgressiveMediaSource.Factory(data).createMediaSource(backup));
        }
        fileUrl = d.url;
        pendingSprite = d.sprite;
        if (pendingSprite != null && pendingSprite.referer == null) pendingSprite.referer = d.referer;
        startPlayback();
    }

    private String fileUrl;

    private MediaSource directSource(CircuitDataSource.Factory data, String url, boolean hls, boolean dash) {
        if (directMedia == null || directMedia.subtitles.isEmpty()) {
            MediaItem item = MediaItem.fromUri(url);
            return hls ? new HlsMediaSource.Factory(data).createMediaSource(item)
                    : dash ? new DashMediaSource.Factory(data).createMediaSource(item)
                    : new ProgressiveMediaSource.Factory(data).createMediaSource(item);
        }
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
        for (com.orvid.play.Direct.Subtitle t : directMedia.subtitles) {
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
                    .setMimeType(!com.orvid.play.SubTranslate.handles(t.url) && t.url.toLowerCase(java.util.Locale.ROOT).contains(".srt")
                            ? androidx.media3.common.MimeTypes.APPLICATION_SUBRIP : androidx.media3.common.MimeTypes.TEXT_VTT)
                    .setLanguage(t.language).setLabel(t.label).build());
        }
        MediaItem item = new MediaItem.Builder().setUri(url)
                .setMimeType(hls ? androidx.media3.common.MimeTypes.APPLICATION_M3U8
                        : dash ? androidx.media3.common.MimeTypes.APPLICATION_MPD : null)
                .setSubtitleConfigurations(subs).build();
        Diag.mark("direct " + subs.size() + " subtitles");
        return new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(data).createMediaSource(item);
    }

    List<com.orvid.play.Direct.Variant> fileQualities() {
        return directMedia == null || directMedia.variants.size() < 2
                ? java.util.Collections.<com.orvid.play.Direct.Variant>emptyList() : directMedia.variants;
    }

    String fileQuality() {
        return fileUrl != null ? fileUrl : directMedia == null ? null : directMedia.url;
    }

    private boolean fileAsHlsTried;

    private boolean playlistInFile(PlaybackException error) {
        if (directMedia == null || fileAsHlsTried || sourceIndex >= sources.size()
                || !(sources.get(sourceIndex) instanceof ProgressiveMediaSource)) return false;
        boolean unknown = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof androidx.media3.exoplayer.source.UnrecognizedInputFormatException) unknown = true;
        }
        if (!unknown) return false;
        fileAsHlsTried = true;
        String url = fileQuality();
        Diag.mark("file is not a video, trying it as HLS");
        CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Fetch.USER_AGENT, directMedia.referer)
                .cookie(directMedia.cookie).timeout(slowSource ? SLOW_MEDIA_TIMEOUT_MS : 0);
        MediaSource source = new HlsMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(url));
        sources.set(sourceIndex, source);
        player.setMediaSource(source, Math.max(0, player.getCurrentPosition()));
        player.prepare();
        return true;
    }

    void pickFile(com.orvid.play.Direct.Variant v) {
        if (directMedia == null || v.url.equals(fileUrl)) return;
        Diag.mark("quality file " + v.height + "p");
        fileUrl = v.url;
        CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Fetch.USER_AGENT, directMedia.referer)
                .cookie(directMedia.cookie).timeout(slowSource ? SLOW_MEDIA_TIMEOUT_MS : 0);
        MediaSource source = directSource(data, v.url, v.hls, false);
        long at = Math.max(0, player.getCurrentPosition());
        sources.clear();
        sources.add(source);
        sourceIndex = 0;
        player.setMediaSource(source, at);
        player.prepare();
    }

    private static final int PREPARED_WAIT_S = 8;

    private void loadPrepared(java.util.concurrent.Future<com.orvid.play.Resolve.Stream> ready) {
        Diag.mark("using prepared stream");
        new Job<com.orvid.play.Resolve.Stream>(this) {
            @Override
            protected com.orvid.play.Resolve.Stream load() throws Exception {
                com.orvid.play.Resolve.Stream s = ready.get(PREPARED_WAIT_S, java.util.concurrent.TimeUnit.SECONDS);
                if (s == null || s.direct == null || s.direct.url == null) throw new java.io.IOException("not prepared");
                return s;
            }

            @Override
            protected void show(com.orvid.play.Resolve.Stream s) {
                playDirect(s.direct, s.circuit);
            }

            @Override
            protected void fail(Exception error) {
                Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof com.orvid.net.Hedge.Final) {
                    Diag.mark("prepared stream FAIL " + cause + ", final");
                    buffering.setVisibility(View.GONE);
                    showError(Ui.error(PlayerActivity.this, cause));
                    return;
                }
                Diag.mark("prepared stream FAIL " + error + ", resolving normally");
                route();
            }
        }.start();
    }

    private void loadDirect(final Hedge.Task<com.orvid.play.Direct> resolver) {
        loadDirect(resolver, true);
    }

    private void loadDirect(final Hedge.Task<com.orvid.play.Direct> resolver, boolean spare) {
        new Job<Hedge.Result<com.orvid.play.Direct>>(this) {
            @Override
            protected Hedge.Result<com.orvid.play.Direct> load() throws Exception {
                return Hedge.call(Visionos.takePlayCircuit(), Visionos::takeCircuit, spare && Fetch.torMode() ? 2500 : 0, resolver);
            }

            @Override
            protected void show(Hedge.Result<com.orvid.play.Direct> r) {
                playDirect(r.value, r.value.circuit != null ? r.value.circuit : r.circuit);
            }

            @Override
            protected void fail(Exception error) {
                buffering.setVisibility(View.GONE);
                showError(Ui.error(PlayerActivity.this, error));
            }
        }.start();
    }

    private void loadArchive(final String id) {
        new Job<Hedge.Result<com.orvid.ia.Archive.Media>>(this) {
            @Override
            protected Hedge.Result<com.orvid.ia.Archive.Media> load() throws Exception {
                Diag.mark("archive resolve start");
                return Hedge.call(Visionos.takePlayCircuit(), Visionos::takeCircuit, Fetch.torMode() ? 2500 : 0,
                        c -> com.orvid.ia.Archive.resolve(c, id));
            }

            @Override
            protected void show(Hedge.Result<com.orvid.ia.Archive.Media> r) {
                com.orvid.ia.Archive.Media m = r.value;
                circuit = r.circuit;
                ((TextView) header.findViewById(R.id.name)).setText(m.title);
                controls.setTitle(m.title);
                ((TextView) header.findViewById(R.id.uploader)).setText(m.author);
                ((TextView) header.findViewById(R.id.sub)).setText("Internet Archive");
                description.setText(m.description);
                CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Fetch.USER_AGENT);
                sources.clear();
                sources.add(new ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(m.mp4)));
                startPlayback();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("archive FAIL " + error);
                buffering.setVisibility(View.GONE);
                showError(Ui.error(PlayerActivity.this, error));
            }
        }.start();
    }

    private void loadPeerTube(final String url) {
        new Job<Hedge.Result<com.orvid.pt.PeerTube.Media>>(this) {
            @Override
            protected Hedge.Result<com.orvid.pt.PeerTube.Media> load() throws Exception {
                Diag.mark("peertube resolve start");
                return Hedge.call(Visionos.takePlayCircuit(), Visionos::takeCircuit, Fetch.torMode() ? 2500 : 0,
                        c -> Fetch.retrying(c, cc -> com.orvid.pt.PeerTube.resolve(cc, url)));
            }

            @Override
            protected void show(Hedge.Result<com.orvid.pt.PeerTube.Media> r) {
                com.orvid.pt.PeerTube.Media m = r.value;
                circuit = r.circuit;
                ((TextView) header.findViewById(R.id.name)).setText(m.title);
                controls.setTitle(m.title);
                ((TextView) header.findViewById(R.id.uploader)).setText(m.author);
                ((TextView) header.findViewById(R.id.sub)).setText(
                        m.views >= 0 ? getString(R.string.views, Ui.count(m.views)) : "");
                description.setText(m.description);
                CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Fetch.USER_AGENT);
                sources.clear();
                if (m.hls != null) sources.add(new HlsMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(m.hls)));
                if (m.mp4 != null) sources.add(new ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(m.mp4)));
                startPlayback();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("peertube FAIL " + error);
                buffering.setVisibility(View.GONE);
                showError(Ui.error(PlayerActivity.this, error));
            }
        }.start();
    }

    private void loadVk(final String id) {
        new Job<com.orvid.vk.Vk.Video>(this) {
            @Override
            protected com.orvid.vk.Vk.Video load() throws Exception {
                Diag.mark("vk resolve start");
                com.orvid.vk.Vk.Video known = com.orvid.vk.Vk.known(id);
                if (known != null && !known.files.isEmpty()) return known;
                String c = Visionos.takeCircuit();
                return Fetch.retrying(c, cc -> com.orvid.vk.Vk.fetch(cc, id));
            }

            @Override
            protected void show(com.orvid.vk.Vk.Video v) {
                circuit = v.circuit;
                ((TextView) header.findViewById(R.id.name)).setText(v.title);
                controls.setTitle(v.title);
                ((TextView) header.findViewById(R.id.uploader)).setText(v.author);
                ((TextView) header.findViewById(R.id.sub)).setText(
                        v.views >= 0 ? getString(R.string.views, Ui.count(v.views)) : "");
                description.setText(v.description);
                CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, com.orvid.vk.Vk.USER_AGENT);
                sources.clear();
                String hls = v.files.containsKey("hls") ? v.files.get("hls") : v.files.get("hls_ondemand");
                if (hls != null) sources.add(new HlsMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(hls)));
                String dash = v.files.containsKey("dash_sep") ? v.files.get("dash_sep") : v.files.get("dash_ondemand");
                if (dash != null) sources.add(new DashMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(dash)));
                int cap = Fetch.torMode() ? 720 : 1080;
                String mp4 = null;
                for (int h : new int[] {1080, 720, 480, 360, 240, 144}) {
                    if (h <= cap && v.files.containsKey("mp4_" + h)) {
                        mp4 = v.files.get("mp4_" + h);
                        break;
                    }
                }
                if (mp4 != null) sources.add(new ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(mp4)));
                if (sources.isEmpty()) {
                    buffering.setVisibility(View.GONE);
                    showError(getString(R.string.error_playback));
                    return;
                }
                startPlayback();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("vk FAIL " + error);
                buffering.setVisibility(View.GONE);
                showError(Ui.error(PlayerActivity.this, error));
            }
        }.start();
    }

    private void loadDailymotion(final String id) {
        circuit = Visionos.takePlayCircuit();
        final String c0 = circuit;
        new Job<com.orvid.dm.Dailymotion.Video>(this) {
            @Override
            protected com.orvid.dm.Dailymotion.Video load() throws Exception {
                Diag.mark("dailymotion resolve start");
                return Fetch.retrying(c0, c -> com.orvid.dm.Dailymotion.resolve(c, id));
            }

            @Override
            protected void show(com.orvid.dm.Dailymotion.Video v) {
                ((TextView) header.findViewById(R.id.name)).setText(v.title);
                controls.setTitle(v.title);
                ((TextView) header.findViewById(R.id.uploader)).setText(v.author);
                ((TextView) header.findViewById(R.id.sub)).setText(R.string.source_dailymotion);
                description.setText(v.description);
                sources.clear();
                sources.add(new HlsMediaSource.Factory(new CircuitDataSource.Factory(c0, Fetch.USER_AGENT))
                        .createMediaSource(MediaItem.fromUri(v.hls)));
                startPlayback();
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("dailymotion FAIL " + error);
                buffering.setVisibility(View.GONE);
                showError(Ui.error(PlayerActivity.this, error));
            }
        }.start();
    }

    private void loadNewPipe(final boolean forPlayback) {
        if (circuit == null) circuit = Fetch.newCircuit("v");
        final String c0 = circuit;
        new Job<StreamInfo>(this) {
            @Override
            protected StreamInfo load() throws Exception {
                Diag.mark("newpipe start" + (forPlayback ? "" : " (related)"));
                StreamingService service = NewPipe.getServiceByUrl(pageUrl);
                StreamInfo info = Fetch.retrying(c0, c -> StreamInfo.getInfo(service, pageUrl));
                Diag.mark("newpipe done");
                return info;
            }

            @Override
            protected void show(StreamInfo info) {
                bindNewPipe(info, forPlayback);
            }

            @Override
            protected void fail(Exception error) {
                Diag.mark("newpipe FAIL " + error);
                if (!forPlayback) return;
                buffering.setVisibility(View.GONE);
                showError(error instanceof ExtractionException && error.getMessage() != null
                        && error.getMessage().startsWith("No service")
                        ? getString(R.string.error_unsupported)
                        : Ui.error(PlayerActivity.this, error));
            }
        }.start();
    }

    private void bindNewPipe(StreamInfo info, boolean forPlayback) {
        String views = info.getViewCount() >= 0
                ? getString(R.string.views, Ui.count(info.getViewCount())) : "";
        ((TextView) header.findViewById(R.id.sub)).setText(Ui.joinParts(views, info.getTextualUploadDate()));
        String text = info.getDescription() == null ? "" : info.getDescription().getContent();
        if (!text.isEmpty()) description.setText(Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT));
        List<StreamInfoItem> more = new ArrayList<>();
        for (InfoItem i : info.getRelatedItems()) if (i instanceof StreamInfoItem) more.add((StreamInfoItem) i);
        more = com.orvid.search.Ranker.similar(info.getName(), info.getUploaderName(), pageUrl, more,
                java.util.Collections.<StreamInfoItem>emptyList(), true);
        appendRelated(more);
        if (!forPlayback) return;

        ((TextView) header.findViewById(R.id.name)).setText(info.getName());
        controls.setTitle(info.getName());
        ((TextView) header.findViewById(R.id.uploader)).setText(info.getUploaderName());
        if (info.getUploaderUrl() != null) channelUrl = info.getUploaderUrl();
        CircuitDataSource.Factory data = new CircuitDataSource.Factory(circuit, Fetch.USER_AGENT);
        sources.clear();
        if (info.getHlsUrl() != null && !info.getHlsUrl().isEmpty()) {
            sources.add(new HlsMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(info.getHlsUrl())));
        }
        VideoStream best = null;
        for (VideoStream v : info.getVideoStreams()) {
            if (v.isVideoOnly() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            if (!v.getContent().startsWith("https://")) continue;
            if (best == null || v.getHeight() > best.getHeight()) best = v;
        }
        if (best != null) {
            sources.add(new ProgressiveMediaSource.Factory(data)
                    .createMediaSource(MediaItem.fromUri(best.getContent())));
        }
        if (sources.isEmpty()) {
            buffering.setVisibility(View.GONE);
            showError(getString(R.string.error_playback));
            return;
        }
        startPlayback();
    }

    private static boolean gone(PlaybackException error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int status = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) t).responseCode;
                return status == 404 || status == 410;
            }
        }
        return false;
    }

    private static boolean fatal(PlaybackException error) {
        if (gone(error)) return true;
        int code = error.errorCode;
        return code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
    }

    private void startPlayback() {
        sourceIndex = 0;
        play(resumeAt);
    }

    private boolean streamStarted;

    private void play(long positionMs) {
        Diag.mark("player open source " + sourceIndex);
        if (!streamStarted) {
            streamStarted = true;
            if (((TextView) header.findViewById(R.id.name)).length() > 0) stage.post(this::loadRelated);
        }
        buffering.setVisibility(View.VISIBLE);
        player.setMediaSource(sources.get(sourceIndex), positionMs);
        player.prepare();
        player.setPlayWhenReady(!onTv);
        if (cast.active() && !onTv) stage.post(() -> {
            if (!onTv) castHere(positionMs);
        });
    }

    private com.orvid.cast.Cast cast;
    private CastPanel castPanel;
    private boolean onTv;
    private com.orvid.cast.CastItem castItem;
    private boolean castRefused;

    private com.orvid.cast.CastItem castable() {
        com.orvid.cast.CastItem item = null;
        if (directMedia != null && !directMedia.dash) {
            String url = fileQuality() != null ? fileQuality() : directMedia.url;
            item = new com.orvid.cast.CastItem(url, url.equals(directMedia.url) ? directMedia.hls : url.contains(".m3u8"), circuit);
            if (directMedia.referer != null) item.headers.put("Referer", directMedia.referer);
            if (directMedia.cookie != null) item.headers.put("Cookie", directMedia.cookie);
            for (com.orvid.play.Direct.Subtitle t : directMedia.subtitles) {
                item.subtitles.add(new com.orvid.cast.CastItem.Track(t.url, t.language, t.label));
            }
        } else if (ytVideo != null && ytVideo.hls != null) {
            item = new com.orvid.cast.CastItem(ytVideo.hls, true, circuit);
            item.headers.put("User-Agent", Visionos.USER_AGENT);
            item.live = ytVideo.live;
            item.durationMs = ytVideo.lengthSeconds * 1000;
            for (Visionos.Caption c : Subtitles.withTranslation(this, ytVideo.captions)) {
                item.subtitles.add(new com.orvid.cast.CastItem.Track(c.url, c.language, c.name));
            }
        }
        if (item == null) return null;
        item.page = pageUrl;
        item.title = ((TextView) header.findViewById(R.id.name)).getText().toString();
        item.author = ((TextView) header.findViewById(R.id.uploader)).getText().toString();
        item.thumbnail = coverUrl;
        if (item.durationMs <= 0 && player.getDuration() > 0) item.durationMs = player.getDuration();
        return item;
    }

    private void castHere(long positionMs) {
        com.orvid.cast.CastItem item = castable();
        if (item == null) {
            if (streamStarted && !castRefused) {
                castRefused = true;
                android.widget.Toast.makeText(this, R.string.cast_unsupported, android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        castItem = item;
        onTv = true;
        player.pause();
        castPanel.show(item.thumbnail, !item.subtitles.isEmpty());
        cast.cast(item, preferredTrack(item), positionMs);
    }

    private static int preferredTrack(com.orvid.cast.CastItem item) {
        String mine = java.util.Locale.getDefault().getLanguage();
        for (int i = 0; i < item.subtitles.size(); i++) {
            String l = item.subtitles.get(i).language;
            if (l.equals(mine) || l.startsWith(mine + "-")) return i;
        }
        return -1;
    }

    @Override
    public void onCastUpdate() {
        String error = cast.takeError();
        if (error != null) android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show();
        if (cast.active() && cast.media() != castItem) {
            if (cast.media() != null && pageUrl.equals(cast.media().page)) {
                castItem = cast.media();
            } else {
                if (streamStarted && !castRefused) castHere(Math.max(0, player.getCurrentPosition()));
                return;
            }
        }
        if (!cast.active() && onTv) {
            onTv = false;
            long at = castPanel.lastPosition();
            castPanel.hide();
            castItem = null;
            if (at > 0) player.seekTo(at);
            player.play();
            return;
        }
        if (cast.active() && !onTv && castItem != null && pageUrl.equals(castItem.page)) {
            onTv = true;
            player.pause();
            castPanel.show(castItem.thumbnail, !castItem.subtitles.isEmpty());
        }
        if (castPanel.showing()) castPanel.update();
    }

    @Override
    public void subtitleMenu() {
        com.orvid.cast.CastSession s = cast.session();
        if (s == null || castItem == null) return;
        List<String> names = new ArrayList<>();
        for (com.orvid.cast.CastItem.Track t : castItem.subtitles) names.add(t.name);
        long[] active = s.activeTracks();
        int selected = active.length > 0 ? (int) active[0] - 1 : -1;
        controls.castSubtitles(names, selected, i -> s.setTracks(i < 0 ? new long[0] : new long[]{i + 1}));
    }

    @Override
    public void castMenu() {
        DeviceDialog.show(this);
    }

    @Override
    public void replayOnTv() {
        castHere(0);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        com.orvid.cast.CastSession s = cast == null ? null : cast.session();
        int code = event.getKeyCode();
        if (onTv && s != null && cast.connected()
                && (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                s.setVolume(s.volume() + (code == android.view.KeyEvent.KEYCODE_VOLUME_UP ? 0.05 : -0.05));
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void nextSource() {
        long at = Math.max(0, player.getCurrentPosition());
        if (++sourceIndex < sources.size()) {
            play(at);
        } else {
            buffering.setVisibility(View.GONE);
            showError(getString(R.string.error_playback));
        }
    }

    private void fitSurface() {
        int w = stage.getWidth();
        int h = stage.getHeight();
        if (w == 0 || h == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = Math.min((float) w / videoWidth, (float) h / videoHeight);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = Math.round(videoWidth * scale);
        lp.height = Math.round(videoHeight * scale);
        surface.setLayoutParams(lp);
        int left = (w - lp.width) / 2;
        int top = (h - lp.height) / 2;
        ambient.setVideoRect(left, top, left + lp.width, top + lp.height);
    }

    private int coverTries;
    private int sourceAccent;

    private void accentFromCover() {
        android.graphics.drawable.Drawable d = poster.getDrawable();
        if (!(d instanceof android.graphics.drawable.BitmapDrawable)) {
            if (++coverTries < 20) poster.postDelayed(this::accentFromCover, 150);
            return;
        }
        android.graphics.Bitmap cover = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        if (cover.getHeight() > cover.getWidth()) poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (sourceAccent != 0) return;
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(cover, 32, 18, true);
        int[] px = new int[32 * 18];
        small.getPixels(px, 0, 32, 0, 0, 32, 18);
        controls.setAccent(vivid(average(px, 32, 0, 2, 32, 16)));
    }

    private void sampleColors() {
        if (isFinishing()) return;
        if (surface.isAvailable() && firstFrame && ambient.barsVisible()) {
            surface.getBitmap(sample);
            int sw = sample.getWidth();
            int sh = sample.getHeight();
            int[] px = new int[sw * sh];
            sample.getPixels(px, 0, sw, 0, 0, sw, sh);
            ambient.setGlow(average(px, sw, 0, sh * 2 / 3, sw, sh));
        }
        stage.postDelayed(sampler, 5000);
    }

    private static int average(int[] px, int stride, int x0, int y0, int x1, int y1) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = px[y * stride + x];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0xFF000000;
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static int vivid(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.15f) return 0xFFFFFFFF;
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 0.95f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    private void showError(String text) {
        if (relatedSeen.isEmpty()) {
            related.set(new ArrayList<>());
            header.findViewById(R.id.related_label).setVisibility(View.GONE);
        }
        ((TextView) header.findViewById(R.id.sub)).setText(text);
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        applyLayout(config.orientation);
        controls.refreshFullscreenIcon();
    }

    private void applyLayout(int orientation) {
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        View row = findViewById(R.id.stage_row);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        int width = getResources().getDisplayMetrics().widthPixels;
        lp.height = landscape ? LinearLayout.LayoutParams.MATCH_PARENT : width * 9 / 16;
        row.setLayoutParams(lp);
        if (!landscape) side.close(false);
        details.setVisibility(landscape ? View.GONE : View.VISIBLE);
        if (sheet != null) sheet.setDragEnabled(!landscape);
        stage.post(this::fitSurface);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController insets = getWindow().getInsetsController();
            if (insets == null) return;
            if (landscape) {
                insets.hide(WindowInsets.Type.systemBars());
                insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                insets.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(landscape
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : 0);
        }
    }

    private boolean debugOn() {
        return OrvidApp.get().debugOverlay() || getIntent().getBooleanExtra("debug", false);
    }

    void applyDebug() {
        boolean on = debugOn();
        debug.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            debug.setText(Diag.tail(14));
            saveDiag();
        }
    }

    private void saveDiag() {
        if (Build.VERSION.SDK_INT < 29 || !debugOn()) return;
        byte[] body = Diag.dump().getBytes(StandardCharsets.UTF_8);
        try {
            if (liveUri == null) {
                liveUri = com.orvid.app.TempLogs.create(this, "orvid-debug");
                if (liveUri == null) return;
            }
            try (OutputStream out = getContentResolver().openOutputStream(liveUri, "wt")) {
                out.write(body);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (side.isOpen()) {
            side.close(true);
            return;
        }
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            return;
        }
        minimize();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stage.removeCallbacks(focusTick);
        stage.post(focusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stage.removeCallbacks(focusTick);
        com.orvid.net.Focus.hold(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing() && !minimizing) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        jobScope.cancel();
        relatedScope.cancel();
        rememberShown();
        stage.removeCallbacks(focusTick);
        com.orvid.net.Focus.hold(false);
        cancelUpNext();
        Diag.listen(null);
        debug.removeCallbacks(saveLive);
        saveDiag();
        stage.removeCallbacks(sampler);
        stage.removeCallbacks(stallWatch);
        controls.release();
        cast.removeListener(this);
        castPanel.hide();
        saveToSession();
        if (board != null && !Session.has(pageUrl)) board.stop();
        player.clearVideoTextureView(surface);
        player.removeListener(playerListener);
        player.removeAnalyticsListener(analytics);
    }
}
