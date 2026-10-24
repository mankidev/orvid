package com.orvid.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.orvid.R;
import com.orvid.app.Job;
import com.orvid.app.OrvidApp;
import com.orvid.net.Fetch;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

public final class ChannelActivity extends Activity implements Job.Scoped {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_AVATAR = "avatar";

    private final Fetch.Scope scope = new Fetch.Scope();
    private final String circuit = Fetch.newCircuit("channel");
    private final Panic panic = new Panic();
    private VideoAdapter adapter;
    private ListView list;
    private ProgressBar spinner;
    private TextView message;
    private TextView name;
    private TextView subs;
    private ImageView avatar;
    private StreamingService service;
    private ListLinkHandler tab;
    private Page next;
    private boolean loading;
    private String avatarUrl;
    private MiniPlayer miniPlayer;

    @Override
    protected void onResume() {
        super.onResume();
        if (miniPlayer != null) miniPlayer.attach();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (miniPlayer != null) miniPlayer.detach();
    }

    public static boolean canOpen(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            StreamingService s = NewPipe.getServiceByUrl(url);
            return s.getChannelLHFactory() != null && s.getChannelLHFactory().acceptUrl(url);
        } catch (Exception e) {
            return false;
        }
    }

    public static void open(Context context, String url, String name, String avatar) {
        if (!canOpen(url)) return;
        context.startActivity(new Intent(context, ChannelActivity.class)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_AVATAR, avatar));
    }

    @Override
    public Fetch.Scope scope() {
        return scope;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (panic.feed(this, e)) return true;
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary)));
        back.setBackground(null);
        back.setContentDescription(getString(R.string.back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.topbar_height)));

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.addHeaderView(header(), null, false);
        adapter = new VideoAdapter(this, OrvidApp.get().layoutMode(), this::play);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                adapter.setIdle(scrollState == SCROLL_STATE_IDLE);
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                adapter.prefetchAfter(Math.max(0, first + visible - list.getHeaderViewsCount()) * adapter.columns());
                if (total > list.getHeaderViewsCount() && first + visible >= total - 6) more();
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        spinner = new ProgressBar(this);
        body.addView(spinner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        message = new TextView(this);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        message.setVisibility(View.GONE);
        body.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        View miniView = getLayoutInflater().inflate(R.layout.view_mini, root, false);
        root.addView(miniView);
        miniPlayer = new MiniPlayer(this, miniView);
        setContentView(root);

        name.setText(getIntent().getStringExtra(EXTRA_NAME));
        showAvatar(getIntent().getStringExtra(EXTRA_AVATAR));
        load(url);
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 16);
        row.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 16));
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackgroundColor(getColor(R.color.art_placeholder));
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        int size = Ui.dp(this, 64);
        row.addView(avatar, new LinearLayout.LayoutParams(size, size));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(this, 16), 0, 0, 0);
        name = new TextView(this);
        name.setTextColor(getColor(R.color.text_primary));
        name.setTextSize(20);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        subs = new TextView(this);
        subs.setTextColor(getColor(R.color.text_secondary));
        subs.setTextSize(14);
        subs.setVisibility(View.GONE);
        text.addView(subs);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private void showAvatar(String url) {
        if (url == null || url.isEmpty()) return;
        avatarUrl = url;
        OrvidApp.get().images().loadAvatar(avatar, url, Ui.dp(this, 64));
    }

    private static final class First {
        ChannelInfo info;
        ListLinkHandler tab;
        List<StreamInfoItem> items = new ArrayList<>();
        Page next;
    }

    private String ytId;
    private String ytNext;
    private String ytName;

    private void load(String url) {
        loading = true;
        ytId = com.orvid.yt.Channel.id(url);
        if (ytId != null) {
            loadYouTube();
            return;
        }
        new Job<First>(this) {
            @Override
            protected First load() throws Exception {
                First f = new First();
                f.info = Fetch.retrying(circuit, c -> ChannelInfo.getInfo(url));
                for (ListLinkHandler h : f.info.getTabs()) {
                    if (h.getContentFilters().contains(ChannelTabs.VIDEOS)) {
                        f.tab = h;
                        break;
                    }
                }
                if (f.tab == null && !f.info.getTabs().isEmpty()) f.tab = f.info.getTabs().get(0);
                if (f.tab == null) return f;
                ListLinkHandler videos = f.tab;
                ChannelTabInfo first = Fetch.retrying(circuit, c -> ChannelTabInfo.getInfo(f.info.getService(), videos));
                for (InfoItem i : first.getRelatedItems()) if (i instanceof StreamInfoItem) f.items.add((StreamInfoItem) i);
                f.next = first.hasNextPage() ? first.getNextPage() : null;
                return f;
            }

            @Override
            protected void show(First f) {
                loading = false;
                spinner.setVisibility(View.GONE);
                service = f.info.getService();
                tab = f.tab;
                next = tab != null ? f.next : null;
                name.setText(f.info.getName());
                if (f.info.getSubscriberCount() >= 0) {
                    subs.setText(getString(R.string.channel_subscribers, Ui.count(f.info.getSubscriberCount())));
                    subs.setVisibility(View.VISIBLE);
                }
                showAvatar(Ui.thumbnail(f.info.getAvatars(), Ui.dp(ChannelActivity.this, 64)));
                adapter.set(f.items);
                if (f.items.isEmpty()) showMessage(getString(R.string.channel_empty));
            }

            @Override
            protected void fail(Exception error) {
                loading = false;
                spinner.setVisibility(View.GONE);
                showMessage(Ui.error(ChannelActivity.this, error));
            }
        }.start();
    }

    private void loadYouTube() {
        final String id = ytId;
        new Job<com.orvid.yt.Channel.Page>(this) {
            @Override
            protected com.orvid.yt.Channel.Page load() throws Exception {
                return Fetch.retrying(circuit, c -> com.orvid.yt.Channel.first(c, id));
            }

            @Override
            protected void show(com.orvid.yt.Channel.Page p) {
                loading = false;
                spinner.setVisibility(View.GONE);
                ytNext = p.continuation;
                ytName = p.name;
                if (p.name != null) name.setText(p.name);
                if (p.subscribers >= 0) {
                    subs.setText(getString(R.string.channel_subscribers, Ui.count(p.subscribers)));
                    subs.setVisibility(View.VISIBLE);
                }
                showAvatar(p.avatar);
                adapter.set(p.items);
                if (p.items.isEmpty()) showMessage(getString(R.string.channel_empty));
            }

            @Override
            protected void fail(Exception error) {
                loading = false;
                spinner.setVisibility(View.GONE);
                showMessage(Ui.error(ChannelActivity.this, error));
            }
        }.start();
    }

    private void moreYouTube() {
        if (loading || ytNext == null) return;
        loading = true;
        final String token = ytNext;
        final String id = ytId;
        final String owner = ytName;
        new Job<com.orvid.yt.Channel.Page>(this) {
            @Override
            protected com.orvid.yt.Channel.Page load() throws Exception {
                return Fetch.retrying(circuit, c -> com.orvid.yt.Channel.more(c, token, owner, id));
            }

            @Override
            protected void show(com.orvid.yt.Channel.Page p) {
                loading = false;
                adapter.add(p.items);
                ytNext = p.continuation;
            }

            @Override
            protected void fail(Exception error) {
                loading = false;
            }
        }.start();
    }

    private void more() {
        if (ytId != null) {
            moreYouTube();
            return;
        }
        if (loading || next == null || tab == null) return;
        loading = true;
        final Page page = next;
        final ListLinkHandler videos = tab;
        final StreamingService s = service;
        new Job<ListExtractor.InfoItemsPage<InfoItem>>(this) {
            @Override
            protected ListExtractor.InfoItemsPage<InfoItem> load() throws Exception {
                return Fetch.retrying(circuit, c -> ChannelTabInfo.getMoreItems(s, videos, page));
            }

            @Override
            protected void show(ListExtractor.InfoItemsPage<InfoItem> p) {
                loading = false;
                List<StreamInfoItem> fresh = new ArrayList<>();
                for (InfoItem i : p.getItems()) if (i instanceof StreamInfoItem) fresh.add((StreamInfoItem) i);
                adapter.add(fresh);
                next = p.hasNextPage() ? p.getNextPage() : null;
            }

            @Override
            protected void fail(Exception error) {
                loading = false;
            }
        }.start();
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void play(StreamInfoItem item) {
        if (Ui.doubleTap()) return;
        PlayerActivity.clearBack();
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.getName())
                .putExtra(PlayerActivity.EXTRA_AVATAR, avatarUrl)
                .putExtra(PlayerActivity.EXTRA_DATE, PlayerActivity.dateOf(item))
                .putExtra(PlayerActivity.EXTRA_URL, item.getUrl())
                .putExtra(PlayerActivity.EXTRA_SERVICE, item.getServiceId())
                .putExtra(PlayerActivity.EXTRA_THUMB, Ui.thumbnail(item.getThumbnails(), 640)));
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scope.cancel();
    }
}
