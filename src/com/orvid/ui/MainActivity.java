package com.orvid.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.orvid.R;
import com.orvid.app.CrashLog;
import com.orvid.app.Job;
import com.orvid.app.OrvidApp;
import com.orvid.net.Diag;
import com.orvid.net.Fetch;
import com.orvid.tor.Tor;
import com.orvid.dm.Dailymotion;
import com.orvid.net.Hedge;
import com.orvid.search.Filters;
import com.orvid.search.Query;
import com.orvid.search.Source;
import com.orvid.yt.Search;
import com.orvid.yt.Visionos;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity implements Tor.Listener {

    private TextView title;
    private EditText field;
    private ImageButton searchButton;
    private ImageButton layoutButton;
    private TextView torBanner;
    private ListView list;
    private View progress;
    private TextView message;
    private VideoAdapter adapter;
    private boolean searching;

    private boolean searched;
    private int searchGeneration;
    private String circuit;
    private String continuation;
    private Query query;
    private boolean dmMore;
    private int dmPage;
    private String vkNext;
    private String vkCircuit;
    private TextView correction;
    private Filters filters;
    private String lastInput;
    private String correctedQuery;
    private static final java.util.concurrent.ExecutorService PARALLEL =
            java.util.concurrent.Executors.newCachedThreadPool();
    private Job<?> running;
    private boolean loadingMore;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        try {
            startService(new android.content.Intent(this, com.orvid.app.Reaper.class));
        } catch (Exception ignored) {
        }
        pinch = new android.view.ScaleGestureDetector(this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float start;

            @Override
            public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                start = d.getCurrentSpan();
                return true;
            }

            @Override
            public void onScaleEnd(android.view.ScaleGestureDetector d) {
                if (start <= 0) return;
                float ratio = d.getCurrentSpan() / start;
                if (ratio > 1.6f && !com.orvid.search.Source.adultUnlocked) setAdult(true);
                else if (ratio < 0.6f && com.orvid.search.Source.adultUnlocked) setAdult(false);
            }
        });
        title = findViewById(R.id.title);
        title.setOnClickListener(v -> logoTapped());
        field = findViewById(R.id.search_field);
        searchBox = findViewById(R.id.search_box);
        clearButton = findViewById(R.id.search_clear);
        clearButton.setOnClickListener(v -> {
            if (field.length() > 0) field.setText("");
            field.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
        searchButton = findViewById(R.id.btn_search);
        torBanner = findViewById(R.id.tor_banner);
        torBanner.setOnClickListener(v -> {
            if (Tor.get().state() == Tor.State.FAILED) OrvidApp.get().restartTor();
        });
        list = findViewById(R.id.list);
        progress = findViewById(R.id.progress);
        message = findViewById(R.id.message);
        addRepo = findViewById(R.id.add_repo);
        addRepo.setOnClickListener(v -> RepoDialog.add(this, this::repoChanged));
        correction = findViewById(R.id.correction);
        trace = findViewById(R.id.trace);
        wait = findViewById(R.id.search_wait);
        waitLogo = findViewById(R.id.search_wait_logo);
        waitBar = findViewById(R.id.search_wait_bar);
        traceLine = findViewById(R.id.trace_line);
        traceCount = findViewById(R.id.trace_count);
        traceBar = findViewById(R.id.trace_bar);
        traceDetail = findViewById(R.id.trace_detail);
        trace.setOnClickListener(v -> {
            traceOpen = !traceOpen;
            renderTrace();
        });
        traceDetail.setOnClickListener(v -> copyTrace());
        correction.setOnClickListener(v -> {
            if (correctedQuery == null) return;
            String fixed = correctedQuery;
            field.setText(fixed);
            runSearch(fixed);
        });

        filters = Filters.current != null ? Filters.current : Filters.load(getSharedPreferences("settings", MODE_PRIVATE));
        Filters.current = filters;
        com.orvid.yt.Related.safeSearch = filters.hideAdult;
        adapter = new VideoAdapter(this, OrvidApp.get().layoutMode(), item -> {
            if (Ui.doubleTap()) return;
            com.orvid.ext.ExtSource ext = com.orvid.ext.Registry.forUrl(item.getUrl());
            if (ext != null ? ext.webPlayback : com.orvid.web.WebSites.forUrl(item.getUrl()) != null) {
                startActivity(new Intent(this, WebPlayerActivity.class).putExtra(WebPlayerActivity.EXTRA_URL, item.getUrl()));
                return;
            }
            PlayerActivity.clearBack();
            long at = adapter.previewPosition(item.getUrl());
            startActivity(new Intent(this, PlayerActivity.class)
                        .putExtra(PlayerActivity.EXTRA_START_MS, Math.max(0, at))
                        .putExtra(PlayerActivity.EXTRA_TITLE, item.getName())
                        .putExtra(PlayerActivity.EXTRA_AVATAR, Ui.thumbnail(item.getUploaderAvatars(), Ui.dp(this, 32)))
                        .putExtra(PlayerActivity.EXTRA_DATE, PlayerActivity.dateOf(item))
                        .putExtra(PlayerActivity.EXTRA_URL, item.getUrl())
                        .putExtra(PlayerActivity.EXTRA_SERVICE, item.getServiceId())
                        .putExtra(PlayerActivity.EXTRA_THUMB, Ui.thumbnail(item.getThumbnails(), 640)));
            overridePendingTransition(0, 0);
        });
        com.orvid.web.WebSearch.prompter = new com.orvid.web.WebSearch.Prompter() {
            private android.app.Dialog dialog;

            @Override
            public void show(android.webkit.WebView view, String site) {
                if (isFinishing() || dialog != null) return;
                dialog = Panic.guard(MainActivity.this, new android.app.Dialog(MainActivity.this));
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                android.widget.LinearLayout box = new android.widget.LinearLayout(MainActivity.this);
                box.setOrientation(android.widget.LinearLayout.VERTICAL);
                box.setBackgroundColor(getColor(R.color.elevated));
                TextView label = new TextView(MainActivity.this);
                label.setText(getString(R.string.web_verify, site));
                label.setTextColor(getColor(R.color.text_primary));
                int p = Ui.dp(MainActivity.this, 14);
                label.setPadding(p, p, p, p);
                box.addView(label);
                if (view.getParent() != null) ((android.view.ViewGroup) view.getParent()).removeView(view);
                box.addView(view, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(MainActivity.this, 320)));
                dialog.setContentView(box);
                dialog.setCancelable(true);
                dialog.show();
            }

            @Override
            public void hide(android.webkit.WebView view) {
                if (view != null && view.getParent() != null) ((android.view.ViewGroup) view.getParent()).removeView(view);
                if (dialog != null) dialog.dismiss();
                dialog = null;
            }
        };
        list.setAdapter(adapter);
        adapter.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override
            public void onChanged() {
                if (adapter.getCount() > 0) stopWait();
            }
        });
        layoutButton = findViewById(R.id.btn_layout);
        layoutButton.setImageResource(R.drawable.ic_filter);
        layoutButton.setOnClickListener(v -> {
            Filters before = filters.copy();
            FilterSheet.show(this, OrvidApp.get().layoutMode(), filters,
                    new FilterSheet.Listener() {
                        @Override
                        public void onLayout(int mode) {
                            int current = OrvidApp.get().layoutMode();
                            OrvidApp.get().setLayoutMode(mode);
                            int first = list.getFirstVisiblePosition();
                            int firstItem = first * VideoAdapter.columns(current);
                            adapter.setMode(mode);
                            list.setSelection(firstItem / VideoAdapter.columns(mode));
                        }

                        @Override
                        public void onFilters(Filters f) {
                            f.save(getSharedPreferences("settings", MODE_PRIVATE));
                            com.orvid.yt.Related.safeSearch = f.hideAdult;
                            list.removeCallbacks(devRefresh);
                            if (com.orvid.app.DevInfo.on) list.postDelayed(devRefresh, DEV_REFRESH_MS);
                            if (searched && lastInput != null && onlyTurnedOff(before, f)) dropOffSources();
                            else if (searched && lastInput != null) runSearch(lastInput);
                            else if (pendingQuery != null) repoChanged();
                            else if (!searched) showHome();
                        }
                    });
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                adapter.setIdle(scrollState == SCROLL_STATE_IDLE);
                list.removeCallbacks(prepareVisible);
                list.removeCallbacks(prepareLater);
                prepareWaitStarted = 0;
                if (scrollState == SCROLL_STATE_IDLE) list.postDelayed(prepareVisible, PREPARE_IDLE_MS);
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                adapter.checkPreview();
                if (shown && first > list.getHeaderViewsCount()) scrolledAway = true;
                adapter.prefetchAfter(Math.max(0, first + visible - list.getHeaderViewsCount()) * adapter.columns());
                if (total > 0 && first + visible >= total - Math.max(12, visible * 3)) loadMore();
            }
        });

        searchButton.setOnClickListener(v -> {
            if (!searching) {
                openSearch();
                return;
            }
            dismissSuggest();
            String q = field.getText().toString().trim();
            hideKeyboard();
            runSearch(q);
        });
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean empty = s.length() == 0;
                clearButton.setImageResource(empty ? R.drawable.ic_search : R.drawable.ic_close);
                clearButton.setContentDescription(getString(empty ? R.string.search : R.string.clear));
                if (s.length() > 0) {
                    OrvidApp.get().images().warm();
                    prewarmSources();
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                String text = s.toString().trim();
                for (String known : lastSuggestions) {
                    if (Query.fold(known).equals(Query.fold(text))) {
                        guessAhead(text, lastSuggestions);
                        break;
                    }
                }
                scheduleSuggest(text);
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            dismissSuggest();
            String q = field.getText().toString().trim();
            hideKeyboard();
            runSearch(q);
            return true;
        });

        message.setText(R.string.tagline);
        miniPlayer = new MiniPlayer(this, findViewById(R.id.mini));
        CrashLog.showPending(this);
        showHome();
    }

    private MiniPlayer miniPlayer;

    private int logoTaps;
    private long logoTapAt;

    private void logoTapped() {
        long now = System.currentTimeMillis();
        logoTaps = now - logoTapAt < 600 ? logoTaps + 1 : 1;
        logoTapAt = now;
        if (logoTaps < 5) return;
        logoTaps = 0;
        boolean on = !com.orvid.app.DevInfo.on;
        OrvidApp.get().setDevMode(on);
        android.widget.Toast.makeText(this, on ? R.string.dev_mode_on : R.string.dev_mode_off, android.widget.Toast.LENGTH_SHORT).show();
        list.removeCallbacks(devRefresh);
        if (on) list.postDelayed(devRefresh, DEV_REFRESH_MS);
        else findViewById(R.id.dev_status).setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private final Runnable devRefresh = new Runnable() {
        @Override
        public void run() {
            if (!com.orvid.app.DevInfo.on) return;
            if (searched && adapter.getCount() > 0) adapter.notifyDataSetChanged();
            list.postDelayed(this, DEV_REFRESH_MS);
        }
    };
    private static final long DEV_REFRESH_MS = 2000;

    private final java.util.Map<Integer, String> devSources = new java.util.TreeMap<>();
    private long searchStarted;

    private void devNote(String text) {
        if (!com.orvid.app.DevInfo.on) return;
        devSources.put(-1, text);
        TextView view = findViewById(R.id.dev_status);
        view.setText(android.text.TextUtils.join(" | ", devSources.values()));
        view.setVisibility(View.VISIBLE);
    }

    private void devStatus(int source, String state) {
        if (!com.orvid.app.DevInfo.on) return;
        String name = source == 0 ? "youtube" : source == 1 ? "dailymotion" : source == 2 ? "vk"
                : source - CORE < extras.size() ? extras.get(source - CORE).key : "src" + source;
        devSources.put(source, name + " " + state);
        TextView view = findViewById(R.id.dev_status);
        view.setText(android.text.TextUtils.join(" | ", devSources.values()));
        view.setVisibility(View.VISIBLE);
    }

    private void showHome() {
        adapter.set(Collections.<StreamInfoItem>emptyList());
        if (com.orvid.ext.Registry.empty()) showEmpty();
        else showMessage(getString(R.string.tagline));
    }

    private String pendingQuery;

    private void showEmpty() {
        message.setText(R.string.nothing_here);
        stopWait();
        message.setVisibility(View.VISIBLE);
        addRepo.setVisibility(View.VISIBLE);
    }

    private void repoChanged() {
        addRepo.setVisibility(View.GONE);
        String again = pendingQuery;
        pendingQuery = null;
        if (again != null && !com.orvid.ext.Registry.empty()) runSearch(again);
        else if (query != null) runSearch(query.raw);
        else showHome();
    }

    @Override
    protected void onPause() {
        super.onPause();
        miniPlayer.detach();
        adapter.releasePreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        list.removeCallbacks(devRefresh);
        if (com.orvid.app.DevInfo.on) list.postDelayed(devRefresh, DEV_REFRESH_MS);
        miniPlayer.attach();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (adapter != null && filters != null) adapter.pruneOff(filters);
        Tor.get().addListener(this);
        refreshTor();
        com.orvid.app.UpdateCheck.run(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Tor.get().removeListener(this);
    }

    @Override
    public void onTorChanged() {
        runOnUiThread(() -> {
            refreshTor();
            boolean ready = Tor.get().state() == Tor.State.READY;
            if (ready && !torWasReady && searching && field.hasFocus()) scheduleSuggest(field.getText().toString().trim());
            torWasReady = ready;
        });
    }

    private boolean torWasReady = Tor.get().state() == Tor.State.READY;

    private void refreshTor() {
        Tor.State st = Tor.get().state();
        if (!Fetch.torMode()) {
            torBanner.setText(R.string.tor_banner_off);
            torBanner.setVisibility(View.VISIBLE);
        } else if (st == Tor.State.STARTING) {
            com.orvid.tor.Bridges.Mode mode = Tor.get().mode();
            torBanner.setText(mode == com.orvid.tor.Bridges.Mode.DIRECT
                    ? getString(R.string.tor_banner_starting, Tor.get().progress())
                    : getString(R.string.tor_banner_bridge, mode.name().toLowerCase(java.util.Locale.ROOT), Tor.get().progress()));
            torBanner.setVisibility(View.VISIBLE);
        } else if (st == Tor.State.FAILED) {
            torBanner.setText(OrvidApp.get().bridges() ? getString(R.string.tor_banner_failed)
                    : getString(R.string.tor_banner_failed) + " " + getString(R.string.tor_bridge_hint));
            torBanner.setVisibility(View.VISIBLE);
        } else {
            torBanner.setVisibility(View.GONE);
        }
    }

    private View searchBox;
    private View addRepo;
    private ImageButton clearButton;

    private void openSearch() {
        searching = true;
        title.setVisibility(View.GONE);
        searchBox.setVisibility(View.VISIBLE);
        searchButton.setVisibility(View.GONE);
        field.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        searching = false;
        hideKeyboard();
        field.setText("");
        searchBox.setVisibility(View.GONE);
        searchButton.setVisibility(View.VISIBLE);
        title.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    private List<Source> extras = new ArrayList<>();
    private double[] weights = weights(extras);
    private boolean[] tagged = tagged(extras);
    private final Object extraLock = new Object();

    private static boolean[] tagged(List<Source> extras) {
        boolean[] t = new boolean[CORE + extras.size()];
        for (int i = 0; i < extras.size(); i++) t[CORE + i] = extras.get(i).adult;
        return t;
    }
    private static final long HEDGE_MS = 1500;

    private static final class Batch {
        final List<StreamInfoItem> youtube = new ArrayList<>();
        final List<StreamInfoItem> dailymotion = new ArrayList<>();
        final List<StreamInfoItem> vk = new ArrayList<>();
        final List<List<StreamInfoItem>> extra = new ArrayList<>();
        final String[] extraNext;
        String vkNext;

        Batch(int extras) {
            extraNext = new String[extras];
        }
        String ytContinuation;
        boolean dmMore;
        List<StreamInfoItem> fresh;
        boolean[] barren;
        boolean[] repeating;
    }

    private int pendingSources;
    private Exception lastSourceError;

    private void runSearch(final String input) {
        String link = com.orvid.play.Generic.linkIn(input);
        if (link != null) {
            dismissSuggest();
            startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_URL, link));
            return;
        }
        dismissSuggest();
        if (com.orvid.ext.Registry.empty()) {
            if (running != null) running.cancel();
            running = null;
            adapter.set(Collections.<StreamInfoItem>emptyList());
            pendingQuery = input;
            showEmpty();
            return;
        }
        OrvidApp.get().images().warm();
        if (running != null) running.cancel();
        running = null;
        adapter.set(Collections.<StreamInfoItem>emptyList());
        message.setVisibility(View.GONE);
        addRepo.setVisibility(View.GONE);
        correction.setVisibility(View.GONE);
        continuation = null;
        circuit = null;
        dmMore = false;
        dmPage = 1;
        vkNext = null;
        vkCircuit = null;
        extras = Sources.current();
        weights = weights(extras);
        tagged = tagged(extras);
        extraNext = new String[extras.size()];
        extraCircuit = new String[extras.size()];
        noMore = new boolean[weights.length];
        loadingMore = false;
        correctedQuery = null;
        lastSourceError = null;
        if (flush != null) list.removeCallbacks(flush);
        flush = null;
        graceOver = false;
        if (rankTask != null) list.removeCallbacks(rankTask);
        rankTask = null;
        rankDirty = false;
        shown = false;
        scrolledAway = false;
        com.orvid.search.Ranker.reseed();
        arrived = null;
        final Query q = Query.parse(input);
        final Filters f = filters;
        query = q;
        lastInput = input;
        rememberKey = Query.fold(input.trim()) + "\n" + f.key() + "\n" + extras.size();
        Diag.mark("search start '" + input + "' tor=" + Tor.get().state());
        searchLogged = false;
        failedSources = 0;
        Remembered past = REMEMBERED.get(rememberKey);
        fromMemory = past != null && android.os.SystemClock.uptimeMillis() - past.at < REMEMBER_MS;
        if (fromMemory) {
            Diag.mark("search from memory: " + past.items.size());
            adapter.set(past.items);
            com.orvid.play.Session.lastSearch = new ArrayList<>(past.items);
            list.setSelection(0);
            shown = true;
        }
        adapter.setQuery(q);
        searched = true;
        final int generation = ++searchGeneration;
        synchronized (stragglers) {
            stragglers.clear();
        }
        emptyBatches = 0;
        prepared.clear();
        devSources.clear();
        searchStarted = android.os.SystemClock.uptimeMillis();
        traceReset();
        startWait();
        findViewById(R.id.dev_status).setVisibility(View.GONE);
        pendingSources = 0;

        if (searchScope != null) searchScope.cancel();
        searchScope = new Fetch.Scope().background();
        sizeSearchPool();
        focusHolds = com.orvid.net.Focus.holds();
        extraQuery = q;
        synchronized (extraLock) {
            extraStarted = new Query[extras.size()];
        }
        List<Queued> jobs = new ArrayList<>();

        final java.util.concurrent.Future<Hedge.Result<Search.Page>> guessed = takeGuess(q, f);
        youtubePending = false;
        final boolean blank = input.trim().isEmpty();
        if (!blank && q.siteIs("youtube", "YouTube") && f.youtube && com.orvid.ext.Registry.engineOn("youtube")) {
            youtubePending = true;
            pendingSources++;
            jobs.add(queued(0, 0, 0, () -> {
                try {
                    Hedge.Result<Search.Page> early = null;
                    if (guessed != null) {
                        try {
                            early = guessed.get();
                        } catch (Exception e) {
                            Diag.mark("guess ahead failed: " + e.getClass().getSimpleName());
                        }
                    }
                    Hedge.Result<Search.Page> r = early != null ? early : Hedge.call(Visionos.takeCircuit(), Visionos::takeCircuit,
                            Fetch.torMode() ? HEDGE_MS : 0, c -> Search.first(c, q.platformQuery, f.youtubeParams()));
                    runOnUiThread(() -> {
                        if (generation != searchGeneration) return;
                        circuit = r.circuit;
                        continuation = r.value.continuation;
                        correctedQuery = r.value.corrected;
                        if (correctedQuery != null) {
                            correction.setText(getString(R.string.showing_results_for, correctedQuery));
                            correction.setVisibility(View.VISIBLE);
                        }
                        deliver(generation, q, f, 0, r.value.items);
                        if (correctedQuery != null && !Query.fold(correctedQuery).equals(Query.fold(q.platformQuery))) {
                            secondWave(generation, Query.parse(correctedQuery), f);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> sourceFailed(generation, 0, e));
                }
            }));
        }
        if (!blank && q.siteIs("dailymotion", "Dailymotion") && f.dailymotion && com.orvid.ext.Registry.engineOn("dailymotion")) {
            pendingSources++;
            jobs.add(queued(1, 1, 0, () -> {
                try {
                    Dailymotion.Page page = Dailymotion.search(Visionos.takeCircuit(), q.plainQuery, 1,
                            f.dailymotionParams(), f.type == Filters.TYPE_SHORTS);
                    runOnUiThread(() -> {
                        if (generation != searchGeneration) return;
                        dmMore = page.hasMore;
                        deliver(generation, q, f, 1, page.items);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> sourceFailed(generation, 1, e));
                }
            }));
        }
        if (!blank && q.siteIs("vk", "VK") && f.vk && f.type != Filters.TYPE_SHORTS && com.orvid.ext.Registry.engineOn("vk")) {
            pendingSources++;
            jobs.add(queued(2, Query.CYRILLIC.equals(q.script()) ? 2 : 4, -0.8, () -> {
                try {
                    Hedge.Result<com.orvid.vk.Vk.Page> r = Hedge.race(com.orvid.vk.Vk.circuits(Visionos::takeCircuit),
                            1200, c -> com.orvid.vk.Vk.search(c, q.plainQuery, null));
                    runOnUiThread(() -> {
                        if (generation != searchGeneration) return;
                        vkNext = r.value.next;
                        vkCircuit = r.circuit;
                        deliver(generation, q, f, 2, r.value.items);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> sourceFailed(generation, 2, e));
                }
            }));
        }
        deferred.clear();
        int[] waveCount = new int[2];
        for (int i = 0; i < extras.size(); i++) {
            final Source src = extras.get(i);
            final int slot = i;
            if (blank && !src.browses() || !src.wanted(f, q) || healthSkip(generation, CORE + slot)) continue;
            pendingSources++;
            Queued job = queued(CORE + slot, src.priority(q) + healthPenalty(src), -src.weight, () -> runExtra(generation, f, src, slot, null));
            if (!Fetch.torMode() || waveCount[src.adult ? 1 : 0]++ < FIRST_WAVE) jobs.add(job);
            else deferred.add(job);
        }
        if (pendingSources == 0) {
            adapter.set(Collections.<StreamInfoItem>emptyList());
            showMessage(getString(blank ? R.string.browse_none : R.string.no_results));
        }
        startedSources = pendingSources;
        firstWave = jobs.size();
        java.util.Collections.sort(jobs);
        for (Queued job : jobs) SEARCH.execute(job);
        if (fromMemory) scheduleSecondWave(generation);
    }

    private static final long REMEMBER_MS = 5 * 60 * 1000;
    private static final int REMEMBER_MAX = 10;
    private static final java.util.Map<String, Remembered> REMEMBERED =
            new java.util.LinkedHashMap<String, Remembered>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Remembered> eldest) {
                    return size() > REMEMBER_MAX;
                }
            };
    private String rememberKey;
    private boolean fromMemory;
    private int startedSources;
    private int firstWave;
    private int failedSources;

    private static final class Remembered {
        final List<StreamInfoItem> items;
        final long at = android.os.SystemClock.uptimeMillis();

        Remembered(List<StreamInfoItem> items) {
            this.items = items;
        }
    }

    private void remember() {
        if (rememberKey == null || pendingSources > 0 || failedSources * 2 > startedSources) return;
        List<StreamInfoItem> items = adapter.items();
        if (items.isEmpty()) return;
        REMEMBERED.put(rememberKey, new Remembered(new ArrayList<>(items)));
    }

    private static final int FIRST_WAVE = 8;
    private static final long SECOND_WAVE_CAP_MS = 3000;
    private static final long SECOND_WAVE_POLL_MS = 200;
    private final List<Queued> deferred = new ArrayList<>();

    private void releaseDeferred() {
        if (deferred.isEmpty()) return;
        List<Queued> go = new ArrayList<>(deferred);
        deferred.clear();
        Diag.mark("second wave: " + go.size() + " sources");
        for (Queued job : go) SEARCH.execute(job);
    }

    private void scheduleSecondWave(int generation) {
        if (deferred.isEmpty()) return;
        long until = android.os.SystemClock.uptimeMillis() + SECOND_WAVE_CAP_MS;
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation != searchGeneration) return;
                if (OrvidApp.get().images().loadingVisible() && android.os.SystemClock.uptimeMillis() < until) {
                    list.postDelayed(this, SECOND_WAVE_POLL_MS);
                    return;
                }
                releaseDeferred();
            }
        }, SECOND_WAVE_POLL_MS);
    }

    private void runExtra(int generation, Filters f, Source src, int slot, Query forced) {
        Query use;
        synchronized (extraLock) {
            use = forced != null ? forced : extraQuery;
            extraStarted[slot] = use;
        }
        if (!src.wanted(f, use)) {
            runOnUiThread(() -> sourceFailed(generation, CORE + slot, null));
            return;
        }
        try {
            Hedge.Result<Source.Page> first = Hedge.call(com.orvid.net.Prewarm.take(src.key), () -> Fetch.newCircuit(src.key),
                    EXTRA_HEDGE_MS, c -> src.search(c, use.plainQuery, f, null));
            com.orvid.net.Prewarm.learn(src.key, Fetch.firstHost(first.circuit));
            if (first.value.items.isEmpty()) {
                Diag.mark(src.key + " empty, asking again from another exit");
                com.orvid.net.Trace.note(com.orvid.net.Trace.Step.AGAIN, 0);
                Hedge.Result<Source.Page> again = Hedge.call(Fetch.newCircuit("x"), () -> Fetch.newCircuit("x"),
                        EXTRA_HEDGE_MS, c -> src.search(c, use.plainQuery, f, null));
                if (!again.value.items.isEmpty()) first = again;
            }
            final Hedge.Result<Source.Page> r = first;
            runOnUiThread(() -> {
                if (generation != searchGeneration) return;
                extraNext[slot] = r.value.next;
                extraCircuit[slot] = r.circuit;
                deliver(generation, use, f, CORE + slot, r.value.items);
            });
        } catch (Exception e) {
            runOnUiThread(() -> sourceFailed(generation, CORE + slot, e));
        }
    }

    private void secondWave(int generation, Query q2, Filters f) {
        query = q2;
        adapter.setQuery(q2);
        List<Queued> jobs = new ArrayList<>();
        synchronized (extraLock) {
            extraQuery = q2;
            for (int i = 0; i < extras.size(); i++) {
                final Source src = extras.get(i);
                final int slot = i;
                if (extraStarted[i] == null || extraStarted[i] == q2 || !src.wanted(f, q2)
                        || com.orvid.search.Health.state(src.key) == com.orvid.search.Health.DOWN) continue;
                pendingSources++;
                jobs.add(queued(CORE + slot, src.priority(q2), -src.weight, () -> runExtra(generation, f, src, slot, q2)));
            }
        }
        java.util.Collections.sort(jobs);
        for (Queued job : jobs) SEARCH.execute(job);
    }

    private Fetch.Scope searchScope;
    private volatile Query extraQuery;
    private Query[] extraStarted = new Query[0];
    private static final int SEARCH_SLOTS = 16;
    private static final java.util.concurrent.ThreadPoolExecutor SEARCH = new java.util.concurrent.ThreadPoolExecutor(
            SEARCH_SLOTS, SEARCH_SLOTS, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.PriorityBlockingQueue<Runnable>());
    private static final java.util.concurrent.atomic.AtomicLong ORDER = new java.util.concurrent.atomic.AtomicLong();

    private static final int SEARCH_SLOTS_DIRECT = 32;

    static {
        SEARCH.allowCoreThreadTimeOut(true);
    }

    private static void sizeSearchPool() {
        int slots = Fetch.torMode() ? SEARCH_SLOTS : SEARCH_SLOTS_DIRECT;
        if (SEARCH.getCorePoolSize() == slots) return;
        if (slots > SEARCH.getMaximumPoolSize()) {
            SEARCH.setMaximumPoolSize(slots);
            SEARCH.setCorePoolSize(slots);
        } else {
            SEARCH.setCorePoolSize(slots);
            SEARCH.setMaximumPoolSize(slots);
        }
    }

    private static final class Queued implements Runnable, Comparable<Queued> {
        final int priority;
        final double tie;
        final long order = ORDER.incrementAndGet();
        final Fetch.Scope scope;
        final Runnable body;

        Queued(int priority, double tie, Fetch.Scope scope, Runnable body) {
            this.priority = priority;
            this.tie = tie;
            this.scope = scope;
            this.body = body;
        }

        @Override
        public void run() {
            if (scope.cancelled()) return;
            try {
                Fetch.within(scope, () -> {
                    body.run();
                    return null;
                });
            } catch (Exception ignored) {
            }
        }

        @Override
        public int compareTo(Queued o) {
            if (priority != o.priority) return Integer.compare(priority, o.priority);
            if (tie != o.tie) return Double.compare(tie, o.tie);
            return Long.compare(order, o.order);
        }
    }

    private Queued queued(int source, int priority, double tie, Runnable body) {
        final int generation = searchGeneration;
        traceSet(generation, source, getString(R.string.trace_queued), false);
        com.orvid.net.Trace.Sink sink = new com.orvid.net.Trace.Sink() {
            @Override
            public void note(com.orvid.net.Trace.Step step, int n) {
                runOnUiThread(() -> traceSet(generation, source, traceStep(step, n), false));
            }

            @Override
            public void page(String detail) {
                runOnUiThread(() -> {
                    if (generation != searchGeneration || source >= traceInfo.length) return;
                    traceInfo[source] = detail;
                    if (traceOpen) renderTrace();
                });
            }
        };
        return new Queued(priority, tie, searchScope, () -> {
            runOnUiThread(() -> traceSet(generation, source, getString(R.string.trace_searching), false));
            try {
                com.orvid.net.Trace.with(sink, () -> {
                    body.run();
                    return null;
                });
            } catch (Exception ignored) {
            }
        });
    }

    private View trace;
    private TextView traceLine;
    private TextView traceCount;
    private android.widget.ProgressBar traceBar;
    private TextView traceDetail;
    private boolean traceOpen;
    private String[] traceState = new String[0];
    private boolean[] traceEnded = new boolean[0];
    private String[] traceInfo = new String[0];
    private int[] healthCount = new int[0];
    private boolean healthJudged;

    private boolean tracked(int source) {
        return source >= CORE && source - CORE < extras.size();
    }

    private int focusHolds;

    private static boolean onlyTurnedOff(Filters before, Filters now) {
        if (before.date != now.date || before.type != now.type || before.duration != now.duration
                || before.sort != now.sort
                || before.hideAdult != now.hideAdult || before.hideJunk != now.hideJunk) return false;
        if (now.youtube && !before.youtube || now.dailymotion && !before.dailymotion || now.vk && !before.vk) return false;
        for (Source s : Sources.current()) if (now.on(s) && !before.on(s)) return false;
        return true;
    }

    private void dropOffSources() {
        adapter.pruneOff(filters);
        com.orvid.play.Session.lastSearch = new ArrayList<>(adapter.items());
        Diag.mark("sources turned off, list pruned to " + adapter.items().size());
        if (adapter.items().isEmpty() && pendingSources <= 0) showMessage(getString(R.string.no_results));
    }

    private void judgeHealth() {
        if (healthJudged) return;
        healthJudged = true;
        boolean focused = com.orvid.net.Focus.holds() != focusHolds;
        int others = 0;
        for (int c : healthCount) if (c > 0) others++;
        for (int i = CORE; i < healthCount.length; i++) {
            if (!tracked(i)) continue;
            String key = extras.get(i - CORE).key;
            if (healthCount[i] > 0) com.orvid.search.Health.ok(key);
            else if (others < HEALTH_QUORUM) continue;
            else if (healthCount[i] == -2) {
                if (!focused) com.orvid.search.Health.fail(key);
            }
            else if (healthCount[i] == 0) com.orvid.search.Health.empty(key);
        }
    }

    private static final int HEALTH_QUORUM = 3;

    private boolean healthSkip(int generation, int source) {
        if (!tracked(source) || !com.orvid.search.Health.skip(extras.get(source - CORE).key)) return false;
        traceSet(generation, source, getString(R.string.health_skipped), true);
        return true;
    }

    private int healthPenalty(Source src) {
        return src instanceof com.orvid.ext.ExtSource && com.orvid.search.Health.state(src.key) == com.orvid.search.Health.FLAKY ? 3 : 0;
    }
    private String[] traceError = new String[0];
    private long traceFinishedAt;
    private int traceLast = -1;
    private int traceTotal;
    private int traceDone;
    private Runnable traceHide;

    private void traceReset() {
        int n = CORE + extras.size();
        traceState = new String[n];
        traceEnded = new boolean[n];
        traceInfo = new String[n];
        healthCount = new int[n];
        java.util.Arrays.fill(healthCount, -1);
        healthJudged = false;
        traceError = new String[n];
        traceFinishedAt = 0;
        traceLast = -1;
        traceTotal = 0;
        traceDone = 0;
        if (traceHide != null) trace.removeCallbacks(traceHide);
        traceHide = null;
        trace.setVisibility(View.GONE);
    }

    private String traceName(int source) {
        if (source == 0) return "YouTube";
        if (source == 1) return "Dailymotion";
        if (source == 2) return "VK";
        return source - CORE < extras.size() ? extras.get(source - CORE).name : "?";
    }

    private String traceStep(com.orvid.net.Trace.Step step, int n) {
        switch (step) {
            case TOR: return getString(R.string.trace_tor);
            case LANE: return getString(R.string.trace_lane, n);
            case RETRY: return getString(R.string.trace_retry, n);
            default: return getString(R.string.trace_again);
        }
    }

    private void traceSet(int generation, int source, String state, boolean end) {
        if (generation != searchGeneration || source < 0 || source >= traceState.length) return;
        if (traceEnded[source] && !end) {
            if (state.equals(getString(R.string.trace_queued))) {
                traceEnded[source] = false;
                traceTotal++;
            } else {
                return;
            }
        } else if (traceState[source] == null) {
            traceTotal++;
        }
        if (end && !traceEnded[source]) {
            traceEnded[source] = true;
            traceDone++;
        }
        traceFinishedAt = traceDone >= traceTotal ? android.os.SystemClock.uptimeMillis() : 0;
        traceState[source] = state;
        traceLast = source;
        updateWait();
        renderTrace();
    }

    private void renderTrace() {
        if (!com.orvid.app.DevInfo.on) {
            trace.setVisibility(View.GONE);
            return;
        }
        if (traceTotal == 0) return;
        boolean finished = traceDone >= traceTotal;
        if (finished) {
            long ms = (traceFinishedAt > 0 ? traceFinishedAt : android.os.SystemClock.uptimeMillis()) - searchStarted;
            traceLine.setText(getString(R.string.trace_summary, traceTotal, String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)));
        } else if (traceOpen) {
            traceLine.setText(getString(R.string.trace_running, traceTotal));
        } else if (traceLast >= 0) {
            traceLine.setText(traceName(traceLast) + ": " + traceState[traceLast]);
        }
        traceCount.setText(traceDone + "/" + traceTotal);
        traceBar.setMax(traceTotal);
        traceBar.setProgress(traceDone);
        if (traceOpen) {
            StringBuilder b = new StringBuilder();
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < traceState.length; i++) {
                    if (traceState[i] == null || traceEnded[i] != (pass == 1)) continue;
                    if (b.length() > 0) b.append('\n');
                    b.append(traceName(i)).append(": ").append(traceState[i]);
                    if (traceInfo[i] != null) b.append("\n    ").append(traceInfo[i]);
                }
            }
            traceDetail.setText(b);
            traceDetail.setVisibility(View.VISIBLE);
        } else {
            traceDetail.setVisibility(View.GONE);
        }
        trace.setVisibility(View.VISIBLE);
        if (traceHide != null) trace.removeCallbacks(traceHide);
        traceHide = null;
        if (finished && !traceOpen) {
            traceHide = () -> trace.setVisibility(View.GONE);
            trace.postDelayed(traceHide, TRACE_LINGER_MS);
        }
    }

    private static final long TRACE_LINGER_MS = 4000;

    private static final long WAIT_DELAY_MS = 400;
    private View wait;
    private android.widget.ImageView waitLogo;
    private android.widget.ProgressBar waitBar;
    private boolean waiting;
    private final Runnable waitShow = () -> {
        if (!waiting || adapter.getCount() > 0) return;
        wait.setVisibility(View.VISIBLE);
        updateWait();
    };

    private void startWait() {
        waiting = true;
        wait.removeCallbacks(waitShow);
        wait.setVisibility(View.GONE);
        waitBar.setProgress(0);
        if (!fromMemory) wait.postDelayed(waitShow, WAIT_DELAY_MS);
    }

    private void stopWait() {
        waiting = false;
        wait.removeCallbacks(waitShow);
        wait.setVisibility(View.GONE);
    }

    private void updateWait() {
        if (!waiting || wait.getVisibility() != View.VISIBLE) return;
        int p = traceTotal == 0 ? 30 : Math.max(40, 1000 * traceDone / traceTotal);
        waitBar.setProgress(p, true);
        android.graphics.drawable.Drawable icon = null;
        if (traceLast == 0) icon = getDrawable(R.drawable.ic_src_youtube);
        else if (traceLast == 1) icon = getDrawable(R.drawable.ic_src_dailymotion);
        else if (traceLast == 2) icon = getDrawable(R.drawable.ic_src_vk);
        else if (traceLast >= CORE && traceLast - CORE < extras.size()) icon = Ui.sourceIcon(this, extras.get(traceLast - CORE));
        waitLogo.setImageDrawable(icon);
        waitLogo.setVisibility(icon != null ? View.VISIBLE : View.INVISIBLE);
    }

    private void prewarmSources() {
        Query blank = Query.parse("");
        int[] waveCount = new int[2];
        for (Source s : Sources.current()) {
            if (!s.wanted(filters, blank) || waveCount[s.adult ? 1 : 0]++ >= FIRST_WAVE) continue;
            if (!(s instanceof com.orvid.ext.ExtSource)) continue;
            com.orvid.ext.ExtSource ext = (com.orvid.ext.ExtSource) s;
            if (ext.webPlayback) continue;
            com.orvid.net.Prewarm.warm(ext.key, ext.host());
        }
    }

    private void copyTrace() {
        StringBuilder b = new StringBuilder("Orvid ");
        try {
            b.append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
        }
        b.append('\n').append(traceLine.getText()).append(' ').append(traceCount.getText());
        for (int i = 0; i < traceState.length; i++) {
            if (traceState[i] == null) continue;
            b.append('\n').append(traceName(i)).append(": ").append(traceState[i]);
            if (traceInfo[i] != null) b.append("\n    ").append(traceInfo[i]);
            if (traceError[i] != null) b.append("\n    ").append(traceError[i]);
        }
        android.content.ClipData clip = android.content.ClipData.newPlainText("Orvid", b);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(clip);
        android.widget.Toast.makeText(this, R.string.trace_copied, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static final int CORE = 3;
    private String[] extraNext = new String[0];
    private String[] extraCircuit = new String[0];
    private boolean[] noMore = new boolean[0];
    private static final int BARREN_MIN = 5;

    private static boolean[] barren(List<List<StreamInfoItem>> lists, List<StreamInfoItem> ranked) {
        java.util.Set<StreamInfoItem> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        kept.addAll(ranked.subList(0, com.orvid.search.Ranker.Ranked.strict(ranked)));
        boolean[] out = new boolean[lists.size()];
        for (int i = 0; i < lists.size(); i++) {
            List<StreamInfoItem> list = lists.get(i);
            if (list.size() < BARREN_MIN) continue;
            int n = 0;
            for (StreamInfoItem item : list) if (kept.contains(item)) n++;
            out[i] = n * 10 < list.size();
        }
        return out;
    }

    private void stopPaging(boolean[] dropped) {
        stopPaging(dropped, "mostly filtered out");
    }

    private void stopPaging(boolean[] dropped, String why) {
        for (int i = 0; i < dropped.length && i < noMore.length; i++) {
            if (dropped[i] && !noMore[i] && !portal(i)) {
                noMore[i] = true;
                Diag.mark("source " + i + " " + why + ", no more pages");
            }
        }
    }

    private boolean portal(int slot) {
        if (slot < CORE || slot - CORE >= extras.size()) return false;
        Source s = extras.get(slot - CORE);
        return s instanceof com.orvid.ext.ExtSource && ((com.orvid.ext.ExtSource) s).portal;
    }

    private static boolean[] repeating(List<List<StreamInfoItem>> lists, java.util.Set<String> shown) {
        boolean[] out = new boolean[lists.size()];
        for (int i = 0; i < lists.size(); i++) {
            List<StreamInfoItem> list = lists.get(i);
            if (list.isEmpty()) continue;
            boolean all = true;
            for (StreamInfoItem item : list) all &= shown.contains(VideoAdapter.key(item));
            out[i] = all;
        }
        return out;
    }

    private static double[] weights(List<Source> extras) {
        double[] w = new double[CORE + extras.size()];
        w[0] = 1.0;
        w[1] = 0.7;
        w[2] = 0.8;
        for (int i = 0; i < extras.size(); i++) w[CORE + i] = extras.get(i).weight;
        return w;
    }

    private static final long VK_HEDGE_MS = 2500;
    private static final long EXTRA_HEDGE_MS = 0;

    private static final long GRACE_MS = 2000;
    private static final long YOUTUBE_WAIT_MS = 6000;
    private boolean youtubePending;
    private List<List<StreamInfoItem>> arrived;
    private boolean shown;
    private boolean scrolledAway;
    private Runnable flush;
    private boolean graceOver;

    private void deliver(int generation, Query q, Filters f, int source, List<StreamInfoItem> items) {
        if (generation != searchGeneration) return;
        if (source == 0) youtubePending = false;
        Diag.mark("src " + traceName(source) + " " + items.size() + " items");
        devStatus(source, items.size() + " " + (android.os.SystemClock.uptimeMillis() - searchStarted) / 100 / 10.0 + "s");
        traceSet(generation, source, getString(R.string.trace_results, items.size()), true);
        if (source < healthCount.length) healthCount[source] = Math.max(0, healthCount[source]) + items.size();
        pendingSources--;
        if (arrived == null) {
            arrived = new ArrayList<>();
            for (int i = 0; i < weights.length; i++) arrived.add(new ArrayList<StreamInfoItem>());
        }
        List<StreamInfoItem> merged = new ArrayList<>(arrived.get(source));
        java.util.Set<String> have = new java.util.HashSet<>();
        for (StreamInfoItem i : merged) have.add(i.getUrl());
        for (StreamInfoItem i : items) if (have.add(i.getUrl())) merged.add(i);
        arrived.set(source, merged);
        if (!shown) {
            if (pendingSources <= 0 || graceOver || (startedSources - pendingSources) * 2 >= firstWave) {
                if (flush != null) list.removeCallbacks(flush);
                flush = null;
                requestRank(generation, true);
            } else if (flush == null) {
                flush = () -> {
                    flush = null;
                    graceOver = true;
                    requestRank(generation, true);
                };
                long waitYouTube = youtubePending
                        ? searchStarted + YOUTUBE_WAIT_MS - android.os.SystemClock.uptimeMillis() : 0;
                list.postDelayed(flush, Math.max(GRACE_MS, waitYouTube));
            }
        } else {
            requestRank(generation, false);
        }
        finishIfDone();
    }

    private static final long RANK_THROTTLE_MS = 300;
    private static final java.util.concurrent.ExecutorService RANK = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orvid-rank");
        t.setDaemon(true);
        return t;
    });
    private boolean rankBusy;
    private boolean rankDirty;
    private Runnable rankTask;

    private void requestRank(int generation, boolean immediate) {
        if (rankBusy) {
            rankDirty = true;
            return;
        }
        if (rankTask != null) {
            if (!immediate) return;
            list.removeCallbacks(rankTask);
        }
        rankTask = () -> {
            rankTask = null;
            startRank(generation);
        };
        list.postDelayed(rankTask, immediate ? 0 : RANK_THROTTLE_MS);
    }

    private void startRank(int generation) {
        if (generation != searchGeneration || arrived == null || query == null) return;
        rankBusy = true;
        rankDirty = false;
        List<List<StreamInfoItem>> snapshot = new ArrayList<>(arrived);
        Query use = query;
        Filters f = filters;
        RANK.execute(() -> {
            List<StreamInfoItem> ranked;
            try {
                ranked = f.arrange(use, snapshot, weights, tagged);
            } catch (RuntimeException e) {
                ranked = null;
            }
            List<StreamInfoItem> result = ranked;
            boolean[] dropped = ranked == null ? null : barren(snapshot, ranked);
            runOnUiThread(() -> {
                rankBusy = false;
                if (generation == searchGeneration && result != null) {
                    stopPaging(dropped);
                    applyRanked(result);
                    remember();
                }
                if (rankDirty) {
                    rankDirty = false;
                    requestRank(searchGeneration, !shown);
                }
                finishIfDone();
            });
        });
    }


    private void applyRanked(List<StreamInfoItem> ranked) {
        if (shown) {
            mergeTail(ranked);
            return;
        }
        if (ranked.isEmpty() && pendingSources > 0) return;
        shown = true;
        Diag.mark("first paint " + ranked.size() + " items");
        saveSearchLog();
        scheduleSecondWave(searchGeneration);
        adapter.set(ranked);
        com.orvid.play.Session.lastSearch = new ArrayList<>(ranked);
        list.setSelection(0);
        list.removeCallbacks(prepareVisible);
        list.removeCallbacks(prepareLater);
        prepareWaitStarted = 0;
        list.postDelayed(prepareVisible, PREPARE_IDLE_MS);
    }


    private static final int PREPARE_AHEAD = 2;
    private static final int PREPARE_FIRST = 2;
    private static final int PREPARE_FIRST_DIRECT = 1;
    private static final int LINK_BUDGET = 4;
    private static final long PREPARE_IDLE_MS = 1500;
    private static final long PREPARE_RETRY_MS = 400;
    private static final long PREPARE_WAIT_MS = 8000;
    private static final long PREPARE_REST_MS = 2000;
    private long prepareWaitStarted;
    private final java.util.Set<String> prepared = new java.util.HashSet<>();
    private final Runnable prepareVisible = this::prepareVisible;
    private List<StreamInfoItem> prepareRest = new ArrayList<>();
    private final Runnable prepareLater = () -> {
        if (!Fetch.torMode()) prepareRest.removeIf(item -> com.orvid.yt.Visionos.videoId(item.getUrl()) != null);
        for (StreamInfoItem item : prepareRest) {
            if (prepared.add(item.getUrl())) com.orvid.play.Warm.link(item.getUrl());
        }
        prepareRest = new ArrayList<>();
    };

    private void prepareVisible() {
        if (!searched || list.getChildCount() == 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (prepareWaitStarted == 0) prepareWaitStarted = now;
        if (Fetch.torMode() && OrvidApp.get().images().loadingVisible() && now - prepareWaitStarted < PREPARE_WAIT_MS) {
            list.postDelayed(prepareVisible, PREPARE_RETRY_MS);
            return;
        }
        prepareWaitStarted = 0;
        int cols = VideoAdapter.columns(adapter.mode());
        int prepareFirst = !Fetch.torMode() ? PREPARE_FIRST_DIRECT : adapter.mode() == VideoAdapter.GRID3 ? 1 : PREPARE_FIRST;
        int first = list.getFirstVisiblePosition();
        int center = list.getHeight() / 2;
        List<StreamInfoItem> rows = adapter.itemsInRows(first, list.getLastVisiblePosition() + 1);
        List<StreamInfoItem> rest = new ArrayList<>();
        List<Object[]> byDistance = new ArrayList<>();
        int lastFull = -1;
        for (int c = 0; c < list.getChildCount(); c++) {
            View row = list.getChildAt(c);
            if (row.getTop() < 0 || row.getBottom() > list.getHeight()) continue;
            lastFull = c;
            int distance = Math.abs((row.getTop() + row.getBottom()) / 2 - center);
            for (int k = 0; k < cols; k++) {
                int index = c * cols + k;
                if (index >= rows.size()) break;
                byDistance.add(new Object[] {rows.get(index), distance});
            }
        }
        byDistance.sort((x, y) -> Integer.compare((int) x[1], (int) y[1]));
        for (int i = 0; i < byDistance.size(); i++) {
            StreamInfoItem item = (StreamInfoItem) byDistance.get(i)[0];
            if (i >= prepareFirst) {
                rest.add(item);
                continue;
            }
            if (prepared.add(item.getUrl())) com.orvid.play.Warm.link(item.getUrl());
            com.orvid.play.Warm.prepare(item.getUrl(), true);
        }
        for (int index = (lastFull + 1) * cols, n = 0; index < rows.size() && n < PREPARE_AHEAD; index++, n++) {
            rest.add(rows.get(index));
        }
        prepareRest = rest.size() > LINK_BUDGET ? new ArrayList<>(rest.subList(0, LINK_BUDGET)) : rest;
        list.removeCallbacks(prepareLater);
        list.postDelayed(prepareLater, PREPARE_REST_MS);
    }

    private void mergeTail(List<StreamInfoItem> ranked) {
        List<StreamInfoItem> current = adapter.items();
        int visible = Math.max(0, list.getLastVisiblePosition() - list.getHeaderViewsCount() + 1);
        int keep = Math.min(current.size(), visible * adapter.columns() + 4);
        if (scrolledAway) while (keep < current.size() && !adapter.inTail(current.get(keep))) keep++;
        int strict = com.orvid.search.Ranker.Ranked.strict(ranked);
        List<StreamInfoItem> head = new ArrayList<>(current.subList(0, keep));
        List<StreamInfoItem> tail = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (StreamInfoItem i : head) seen.add(VideoAdapter.key(i));
        for (StreamInfoItem i : ranked.subList(0, strict)) if (seen.add(VideoAdapter.key(i))) head.add(i);
        for (StreamInfoItem i : current.subList(keep, current.size())) {
            if (!adapter.inTail(i) && seen.add(VideoAdapter.key(i))) head.add(i);
        }
        for (StreamInfoItem i : ranked.subList(strict, ranked.size())) if (seen.add(VideoAdapter.key(i))) tail.add(i);
        for (StreamInfoItem i : current.subList(keep, current.size())) if (seen.add(VideoAdapter.key(i))) tail.add(i);
        adapter.set(com.orvid.search.Ranker.Ranked.of(head, tail));
        com.orvid.play.Session.lastSearch = adapter.items();
    }

    private void sourceFailed(int generation, int source, Exception e) {
        if (generation != searchGeneration) return;
        String why = e == null ? null : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Diag.mark("src " + traceName(source) + (why == null ? " skipped" : " FAIL " + why));
        devStatus(source, why == null ? "skipped" : "FAIL " + (why.length() > 40 ? why.substring(0, 40) : why));
        if (e != null && generation == searchGeneration && source < traceError.length) traceError[source] = String.valueOf(e);
        traceSet(generation, source, e == null ? getString(R.string.trace_skipped) : getString(R.string.trace_failed, Ui.error(this, e)), true);
        if (e != null && !(e instanceof Fetch.Cancelled) && tracked(source) && source < healthCount.length) {
            healthCount[source] = -2;
        }
        pendingSources--;
        lastSourceError = e;
        if (e != null && !(e instanceof Fetch.Cancelled)) failedSources++;
        if (source == 0) {
            youtubePending = false;
            if (!shown && flush != null) {
                list.removeCallbacks(flush);
                flush.run();
                return;
            }
        }
        finishIfDone();
    }

    private android.net.Uri searchLog;
    private boolean searchLogged;

    private void saveSearchLog() {
        if (!com.orvid.app.DevInfo.on || android.os.Build.VERSION.SDK_INT < 29) return;
        byte[] body = Diag.dump().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PARALLEL.execute(() -> {
            try {
                if (searchLog == null) searchLog = com.orvid.app.TempLogs.create(this, "orvid-search");
                if (searchLog == null) return;
                try (java.io.OutputStream out = getContentResolver().openOutputStream(searchLog, "wt")) {
                    out.write(body);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void finishIfDone() {
        if (pendingSources <= 0 && !searchLogged && searched) {
            searchLogged = true;
            Diag.mark("search done");
            saveSearchLog();
        }
        if (!deferred.isEmpty() && pendingSources <= deferred.size()) releaseDeferred();
        if (pendingSources <= 0) judgeHealth();
        if (pendingSources <= 0 && !shown && arrived != null && query != null && !rankBusy) {
            if (flush != null) list.removeCallbacks(flush);
            flush = null;
            requestRank(searchGeneration, true);
            return;
        }
        if (pendingSources > 0 || adapter.itemCount() > 0 || rankBusy || rankTask != null) return;
        adapter.set(Collections.<StreamInfoItem>emptyList());
        showMessage(lastSourceError != null ? Ui.error(this, lastSourceError) : emptyMessage());
    }

    private String emptyMessage() {
        List<String> active = new ArrayList<>();
        if (filters.date != Filters.DATE_ANY) active.add(getString(R.string.filter_date));
        if (filters.duration != Filters.DURATION_ANY) active.add(getString(R.string.filter_duration));
        if (filters.type != Filters.TYPE_ALL) active.add(getString(R.string.filter_type));
        return active.isEmpty() ? getString(R.string.no_results)
                : getString(R.string.no_results_filters, android.text.TextUtils.join(", ", active));
    }

    private static final int REFILL_BELOW = 6;
    private static final int MAX_EMPTY_BATCHES = 3;
    private int emptyBatches;

    private void loadMore() {
        if (loadingMore || !searched || query == null) return;
        final String token = noMore[0] ? null : continuation;
        final boolean wantDm = dmMore && !noMore[1];
        final String wantVk = noMore[2] ? null : vkNext;
        final String[] wantExtra = extraNext.clone();
        boolean any = token != null || wantDm || wantVk != null;
        for (int i = 0; i < wantExtra.length; i++) {
            if (noMore[CORE + i]) wantExtra[i] = null;
            any |= wantExtra[i] != null;
        }
        if (!any) return;
        loadingMore = true;
        final int generation = searchGeneration;
        final String ownerCircuit = circuit;
        final int nextDm = dmPage + 1;
        final String ownerVk = vkCircuit;
        final List<Source> sourcesNow = extras;
        final String[] ownerExtra = extraCircuit.clone();
        final Query q = query;
        final Filters f = filters;
        final Fetch.Scope scope = searchScope;
        final double[] rankWeights = weights;
        final boolean[] rankTagged = tagged;
        final java.util.Set<String> shownKeys = adapter.keys();
        new Job<Batch>(this) {
            @Override
            protected Batch load() throws Exception {
                return Fetch.within(scope, this::collect);
            }

            private Batch collect() throws Exception {
                long until = android.os.SystemClock.uptimeMillis() + moreWait();
                Batch batch = new Batch(wantExtra.length);
                java.util.concurrent.Future<Dailymotion.Page> dm = !wantDm ? null
                        : PARALLEL.submit(inScope(scope, () -> Dailymotion.search(Visionos.takeCircuit(), q.plainQuery, nextDm, f.dailymotionParams(), f.type == Filters.TYPE_SHORTS)));
                java.util.concurrent.Future<com.orvid.vk.Vk.Page> vkf = wantVk == null || ownerVk == null ? null
                        : PARALLEL.submit(inScope(scope, () -> Fetch.retrying(ownerVk, c -> com.orvid.vk.Vk.search(c, q.plainQuery, wantVk))));
                java.util.concurrent.Future<Search.Page> yt = token == null ? null
                        : PARALLEL.submit(inScope(scope, () -> Fetch.retrying(ownerCircuit, c -> Search.more(c, token))));
                List<java.util.concurrent.Future<Source.Page>> extraJobs = new ArrayList<>();
                for (int i = 0; i < sourcesNow.size(); i++) {
                    final Source src = sourcesNow.get(i);
                    final String want = wantExtra[i];
                    final String owner = ownerExtra[i];
                    java.util.concurrent.Future<Source.Page> job = null;
                    if (want != null && owner != null) {
                        job = straggler(generation, i, want);
                        if (job == null) job = PARALLEL.submit(inScope(scope, () -> Fetch.retrying(owner, c -> src.search(c, q.plainQuery, f, want))));
                    }
                    extraJobs.add(job);
                }
                batch.ytContinuation = token;
                if (yt != null) {
                    try {
                        Search.Page p = upTo(yt, until);
                        if (p != null) {
                            batch.youtube.addAll(p.items);
                            batch.ytContinuation = p.continuation;
                        }
                    } catch (Exception ignored) {
                    }
                }
                batch.dmMore = wantDm;
                if (dm != null) {
                    try {
                        Dailymotion.Page page = upTo(dm, until);
                        if (page != null) {
                            batch.dailymotion.addAll(page.items);
                            batch.dmMore = page.hasMore;
                        }
                    } catch (Exception ignored) {
                    }
                }
                batch.vkNext = wantVk;
                if (vkf != null) {
                    try {
                        com.orvid.vk.Vk.Page page = upTo(vkf, until);
                        if (page != null) {
                            batch.vk.addAll(page.items);
                            batch.vkNext = page.next;
                        }
                    } catch (Exception ignored) {
                    }
                }
                for (int i = 0; i < extraJobs.size(); i++) {
                    List<StreamInfoItem> got = new ArrayList<>();
                    batch.extraNext[i] = wantExtra[i];
                    if (extraJobs.get(i) != null) {
                        try {
                            Source.Page page = waitOrKeep(extraJobs.get(i), until, generation, i, wantExtra[i]);
                            if (page != null) {
                                got.addAll(page.items);
                                batch.extraNext[i] = page.next;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    batch.extra.add(got);
                }
                List<List<StreamInfoItem>> lists = new ArrayList<>(java.util.Arrays.asList(batch.youtube, batch.dailymotion, batch.vk));
                lists.addAll(batch.extra);
                batch.fresh = f.arrange(q, lists, rankWeights, rankTagged);
                batch.barren = barren(lists, batch.fresh);
                batch.repeating = repeating(lists, shownKeys);
                return batch;
            }

            @Override
            protected void show(Batch result) {
                if (generation != searchGeneration) return;
                continuation = result.ytContinuation;
                dmMore = result.dmMore;
                if (wantDm) dmPage = nextDm;
                if (wantVk != null) vkNext = result.vkNext;
                for (int i = 0; i < wantExtra.length; i++) if (wantExtra[i] != null) extraNext[i] = result.extraNext[i];
                List<StreamInfoItem> fresh = result.fresh;
                stopPaging(result.barren);
                stopPaging(result.repeating, "repeats earlier results");
                int frozen = (Math.max(0, list.getLastVisiblePosition() - list.getHeaderViewsCount()) + 2) * adapter.columns();
                int added = adapter.add(fresh, frozen);
                com.orvid.play.Session.lastSearch = adapter.items();
                loadingMore = false;
                if (added < REFILL_BELOW) {
                    if (++emptyBatches <= MAX_EMPTY_BATCHES) list.post(MainActivity.this::loadMore);
                } else {
                    emptyBatches = 0;
                }
            }

            @Override
            protected void fail(Exception error) {
                loadingMore = false;
            }
        }.start();
    }

    private static final long MORE_WAIT_MS = 6000;
    private static final long MORE_WAIT_DIRECT_MS = 2500;

    private static long moreWait() {
        return Fetch.torMode() ? MORE_WAIT_MS : MORE_WAIT_DIRECT_MS;
    }

    private final java.util.Map<Integer, Object[]> stragglers = new java.util.HashMap<>();

    @SuppressWarnings("unchecked")
    private java.util.concurrent.Future<Source.Page> straggler(int generation, int index, String token) {
        synchronized (stragglers) {
            Object[] s = stragglers.remove(index);
            if (s == null || (int) s[0] != generation || !token.equals(s[1])) return null;
            return (java.util.concurrent.Future<Source.Page>) s[2];
        }
    }

    private Source.Page waitOrKeep(java.util.concurrent.Future<Source.Page> f, long until, int generation, int index, String token) throws Exception {
        long left = until - android.os.SystemClock.uptimeMillis();
        try {
            return f.get(Math.max(0, left), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            synchronized (stragglers) {
                stragglers.put(index, new Object[]{generation, token, f});
            }
            return null;
        }
    }

    private static <T> T upTo(java.util.concurrent.Future<T> f, long until) throws Exception {
        long left = until - android.os.SystemClock.uptimeMillis();
        try {
            return f.get(Math.max(0, left), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            return null;
        }
    }

    private static <T> java.util.concurrent.Callable<T> inScope(Fetch.Scope scope, java.util.concurrent.Callable<T> work) {
        return () -> Fetch.within(scope, work);
    }

    private void showMessage(String text) {
        message.setText(text);
        stopWait();
        message.setVisibility(View.VISIBLE);
        addRepo.setVisibility(View.GONE);
    }

    private boolean onHome() {
        return !searched && running == null;
    }

    private void goHome() {
        if (searchScope != null) searchScope.cancel();
        if (running != null) running.cancel();
        running = null;
        searched = false;
        searchGeneration++;
        continuation = null;
        query = null;
        dmMore = false;
        adapter.setQuery(null);
        correction.setVisibility(View.GONE);
        loadingMore = false;
        adapter.set(Collections.<StreamInfoItem>emptyList());
        progress.setVisibility(View.GONE);
        traceReset();
        closeSearch();
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (searching || !onHome()) {
            goHome();
            return;
        }
        finishAndRemoveTask();
        com.orvid.app.OrvidApp.get().wipeAndExit();
    }

    private android.view.ScaleGestureDetector pinch;
    private final Panic panic = new Panic();
    private int suggestVersion;

    private void scheduleSuggest(String text) {
        final int version = ++suggestVersion;
        if (text.length() < 2 || !field.hasFocus() || com.orvid.search.Source.adultUnlocked
                || !com.orvid.ext.Registry.engineOn("youtube")) {
            suggestNext = null;
            dismissSuggest();
            return;
        }
        if (suggestBusy) {
            suggestNext = text;
            return;
        }
        sendSuggest(text, version);
    }

    private boolean suggestBusy;
    private String suggestNext;

    private void sendSuggest(String text, int version) {
        suggestBusy = true;
        PARALLEL.execute(() -> {
            List<String> found = null;
            long began = android.os.SystemClock.uptimeMillis();
            try {
                found = com.orvid.yt.Suggest.fetch(text);
                Diag.mark("suggest '" + text + "' " + (android.os.SystemClock.uptimeMillis() - began) + "ms");
            } catch (Exception e) {
                Diag.mark("suggest '" + text + "' FAIL " + e.getClass().getSimpleName());
            }
            List<String> got = found;
            runOnUiThread(() -> {
                suggestBusy = false;
                if (got != null && field.hasFocus()) {
                    String now = field.getText().toString().trim();
                    if (version == suggestVersion) {
                        lastSuggestions = got;
                        guessAhead(text, got);
                    } else {
                        List<String> ahead = new ArrayList<>();
                        for (String g : got) if (Query.fold(g).startsWith(Query.fold(now))) ahead.add(g);
                        if (!ahead.isEmpty()) guessAhead(now, ahead);
                    }
                }
                String next = suggestNext;
                suggestNext = null;
                if (next != null && field.hasFocus()) sendSuggest(next, suggestVersion);
            });
        });
    }
    private static final int GUESS_THUMBS = 8;
    private String guessCircuit;
    private List<String> lastSuggestions = new ArrayList<>();
    private static final long GUESS_LIFE_MS = 120000;
    private final java.util.Map<String, Guess> guesses = new java.util.HashMap<>();

    private static final class Guess {
        final java.util.concurrent.Future<Hedge.Result<Search.Page>> page;
        final long at = android.os.SystemClock.uptimeMillis();

        Guess(java.util.concurrent.Future<Hedge.Result<Search.Page>> page) {
            this.page = page;
        }
    }

    private void guessAhead(String typed, List<String> found) {
        if (found.isEmpty() || !filters.youtube) return;
        String guess = found.get(0);
        for (String s : found) if (Query.fold(s).equals(Query.fold(typed))) guess = s;
        Query q = Query.parse(guess);
        if (!q.siteIs("youtube", "YouTube")) return;
        String params = filters.youtubeParams();
        String key = q.platformQuery + "\n" + params;
        if (guesses.containsKey(key)) return;
        for (Guess old : guesses.values()) old.page.cancel(true);
        guesses.clear();
        Diag.mark("guess ahead: " + guess);
        if (guessCircuit == null) guessCircuit = Visionos.takeCircuit();
        final String lane = guessCircuit;
        guesses.put(key, new Guess(PARALLEL.submit(() -> {
            Hedge.Result<Search.Page> r = Hedge.call(lane, Visionos::takeCircuit,
                    Fetch.torMode() ? HEDGE_MS : 0, c -> Search.first(c, q.platformQuery, params));
            runOnUiThread(() -> {
                if (!lane.equals(r.circuit) && lane.equals(guessCircuit)) guessCircuit = r.circuit;
                adapter.prefetch(r.value.items, GUESS_THUMBS, true);
            });
            return r;
        })));
    }

    private java.util.concurrent.Future<Hedge.Result<Search.Page>> takeGuess(Query q, Filters f) {
        Guess g = guesses.remove(q.platformQuery + "\n" + f.youtubeParams());
        java.util.List<String> other = new ArrayList<>();
        for (String k : guesses.keySet()) other.add(k.substring(0, k.indexOf('\n')));
        for (Guess old : guesses.values()) old.page.cancel(true);
        guesses.clear();
        if (g == null || android.os.SystemClock.uptimeMillis() - g.at > GUESS_LIFE_MS) {
            devNote(other.isEmpty() ? "guess: none" : "guess missed: " + android.text.TextUtils.join(", ", other));
            return null;
        }
        long age = android.os.SystemClock.uptimeMillis() - g.at;
        devNote("guess used: " + (g.page.isDone() ? "ready" : "running") + ", started " + age / 100 / 10.0 + "s before");
        Diag.mark("guess ahead used");
        return g.page;
    }

    private void dismissSuggest() {
        suggestVersion++;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (panic.feed(this, e)) return true;
        if (pinch != null) pinch.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    private void setAdult(boolean on) {
        com.orvid.search.Source.adultUnlocked = on;
        filters.hideAdult = !on;
        com.orvid.yt.Related.safeSearch = !on;
        getWindow().getDecorView().performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.LONG_PRESS);
        if (searched && lastInput != null) runSearch(lastInput);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        miniPlayer.detach();
        if (isFinishing()) com.orvid.play.Session.release();
    }
}
