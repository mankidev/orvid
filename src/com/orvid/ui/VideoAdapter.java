package com.orvid.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.orvid.R;
import com.orvid.app.OrvidApp;
import com.orvid.yt.Visionos;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;

final class VideoAdapter extends BaseAdapter {

    static final int CARD = 0;
    static final int COMPACT = 1;
    static final int GRID = 2;
    static final int TEXT = 3;
    static final int GRID3 = 4;

    private static final long PREPARE_DELAY_MS = 120;
    private static final android.view.ViewOutlineProvider OVAL = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            outline.setOval(0, 0, view.getWidth(), view.getHeight());
        }
    };

    private static float slop(View v) {
        return android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
    }

    int mode() {
        return mode;
    }

    static int columns(int mode) {
        return mode == GRID ? 2 : mode == GRID3 ? 3 : 1;
    }

    private static final int[] CELLS_2 = {R.id.cell_left, R.id.cell_right};
    private static final int[] CELLS_3 = {R.id.cell_left, R.id.cell_mid, R.id.cell_right};

    interface OnPick {
        void pick(StreamInfoItem item);
    }

    private final Context context;
    private final OnPick onPick;
    private final List<StreamInfoItem> items = new ArrayList<>();

    private void turnOffSource(StreamInfoItem item) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (com.orvid.search.Filters.current == null) com.orvid.search.Filters.current = com.orvid.search.Filters.load(prefs);
        com.orvid.search.Filters f = com.orvid.search.Filters.current;
        int sid = item.getServiceId();
        String name;
        if (sid == org.schabi.newpipe.extractor.ServiceList.YouTube.getServiceId()) {
            f.youtube = false;
            name = "YouTube";
        } else if (sid == com.orvid.dm.Dailymotion.SERVICE_ID) {
            f.dailymotion = false;
            name = context.getString(R.string.source_dailymotion);
        } else if (sid == com.orvid.vk.Vk.SERVICE_ID) {
            f.vk = false;
            name = "VK Video";
        } else {
            com.orvid.search.Source s = Sources.byService(sid);
            if (s == null) return;
            f.set(s, false);
            name = s.name;
        }
        boolean any = f.youtube || f.dailymotion || f.vk;
        for (com.orvid.search.Source s : Sources.current()) any |= f.on(s);
        if (!any) f.youtube = true;
        f.save(prefs);
        if (items.removeIf(i -> i.getServiceId() == sid)) notifyDataSetChanged();
        android.widget.Toast.makeText(context, context.getString(R.string.source_off_done, name), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showMenu(StreamInfoItem item, View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(context, anchor);
        menu.getMenu().add(0, 3, 0, R.string.play_next);
        menu.getMenu().add(0, 4, 1, R.string.add_queue);
        menu.getMenu().add(0, 1, 2, R.string.not_interested);
        menu.getMenu().add(0, 2, 3, R.string.source_off);
        if (ChannelActivity.canOpen(item.getUploaderUrl())) menu.getMenu().add(0, 5, 4, R.string.go_to_channel);
        menu.setOnMenuItemClickListener(m -> {
            if (m.getItemId() == 5) {
                openChannel(item);
                return true;
            }
            if (m.getItemId() == 3 || m.getItemId() == 4) {
                int n = m.getItemId() == 3 ? com.orvid.play.Queue.playNext(item) : com.orvid.play.Queue.add(item);
                android.widget.Toast.makeText(context, context.getString(R.string.queued, n), android.widget.Toast.LENGTH_SHORT).show();
                if (context instanceof PlayerActivity) ((PlayerActivity) context).queueChanged();
                return true;
            }
            if (m.getItemId() == 2) {
                turnOffSource(item);
                return true;
            }
            com.orvid.search.NotInterested.add(item);
            prune();
            android.widget.Toast.makeText(context, R.string.not_interested_done, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        menu.show();
    }

    private void channelTap(View v, StreamInfoItem item) {
        if (ChannelActivity.canOpen(item.getUploaderUrl())) {
            v.setOnClickListener(x -> openChannel(item));
        } else {
            v.setOnClickListener(null);
            v.setClickable(false);
        }
    }

    private void openChannel(StreamInfoItem item) {
        ChannelActivity.open(context, item.getUploaderUrl(), item.getUploaderName(),
                Ui.thumbnail(item.getUploaderAvatars(), Ui.dp(context, 64)));
    }

    void pruneOff(com.orvid.search.Filters f) {
        if (items.removeIf(i -> !f.serviceOn(i.getServiceId()))) notifyDataSetChanged();
    }

    void prune() {
        if (items.removeIf(com.orvid.search.NotInterested::hides)) notifyDataSetChanged();
    }
    private final int screenWidth;
    private int mode;
    private boolean idle = true;
    private com.orvid.search.Query query;



    void setQuery(com.orvid.search.Query query) {
        this.query = query;
    }

    void setIdle(boolean idle) {
        this.idle = idle;
    }

    VideoAdapter(Context context, int mode, OnPick onPick) {
        this.context = context;
        this.mode = mode;
        this.onPick = onPick;
        screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        com.orvid.image.ThumbPrint.listen(onPrint);
    }

    private final com.orvid.image.ThumbPrint.Listener onPrint = this::printed;

    private void printed(String page) {
        if (skeletons > 0) return;
        for (int i = 0; i < items.size(); i++) {
            StreamInfoItem item = items.get(i);
            if (!page.equals(item.getUrl())) continue;
            boolean dropped = false;
            while (i < items.size() && items.get(i) == item && dropCopies(i)) dropped = true;
            if (dropped) notifyDataSetChanged();
            return;
        }
    }

    private void sweepCopies(java.util.Set<StreamInfoItem> fresh) {
        int n = items.size();
        long[] prints = new long[n];
        boolean[] has = new boolean[n];
        boolean[] gone = new boolean[n];
        for (int i = 0; i < n; i++) {
            Long print = com.orvid.image.ThumbPrint.of(items.get(i).getUrl());
            if (print == null) continue;
            prints[i] = print;
            has[i] = true;
        }
        boolean dropped = false;
        for (int i = 0; i < n; i++) {
            if (!has[i] || gone[i] || !fresh.contains(items.get(i))) continue;
            for (int j = 0; j < n && !gone[i]; j++) {
                if (j == i || !has[j] || gone[j]) continue;
                if (!copies(items.get(i), prints[i], items.get(j), prints[j])) continue;
                gone[Math.max(i, j)] = true;
                dropped = true;
            }
        }
        if (!dropped) return;
        for (int i = n - 1; i >= 0; i--) {
            if (gone[i]) tail.remove(items.remove(i));
        }
    }

    private static java.util.Set<StreamInfoItem> identities() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    private boolean dropCopies(int index) {
        StreamInfoItem item = items.get(index);
        Long print = com.orvid.image.ThumbPrint.of(item.getUrl());
        if (print == null) return false;
        for (int j = 0; j < items.size(); j++) {
            if (j == index) continue;
            StreamInfoItem other = items.get(j);
            Long theirs = com.orvid.image.ThumbPrint.of(other.getUrl());
            if (theirs == null || !copies(item, print, other, theirs)) continue;
            StreamInfoItem gone = items.remove(Math.max(index, j));
            tail.remove(gone);
            return true;
        }
        return false;
    }

    private static boolean copies(StreamInfoItem a, long pa, StreamInfoItem b, long pb) {
        if (!com.orvid.image.ThumbPrint.same(pa, pb)) return false;
        long x = a.getDuration();
        long y = b.getDuration();
        return x > 0 && y > 0 && Math.abs(x - y) <= 2;
    }

    void setMode(int mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        notifyDataSetChanged();
    }

    private int skeletons;

    void setSkeleton(int count) {
        items.clear();
        skeletons = count;
        notifyDataSetChanged();
    }

    private final java.util.Set<StreamInfoItem> tail = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    static String key(StreamInfoItem item) {
        String id = Visionos.videoId(item.getUrl());
        return id != null ? "yt:" + id : item.getUrl();
    }

    java.util.Set<String> keys() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (StreamInfoItem i : items) out.add(key(i));
        return out;
    }

    boolean inTail(StreamInfoItem item) {
        return tail.contains(item);
    }

    void set(List<StreamInfoItem> next) {
        java.util.Set<StreamInfoItem> fresh = identities();
        fresh.addAll(next);
        if (skeletons == 0) fresh.removeAll(items);
        skeletons = 0;
        items.clear();
        items.addAll(next);
        tail.clear();
        tail.addAll(next.subList(com.orvid.search.Ranker.Ranked.strict(next), next.size()));
        sweepCopies(fresh);
        notifyDataSetChanged();
        if (next.isEmpty()) prefetchFrom = 0;
        prefetch();
    }

    private static final int PREFETCH = 30;
    private static final int PREFETCH_STEP = 5;
    private int prefetchFrom;

    private void prefetch() {
        boolean direct = !com.orvid.net.Fetch.torMode();
        prefetch(items, prefetchFrom, direct ? prefetchCount() * 2 : prefetchCount(), direct);
    }

    private int prefetchCount() {
        return mode == CARD ? 6 : mode == COMPACT ? 12 : mode == GRID ? 16 : PREFETCH;
    }

    void prefetchAfter(int item) {
        int from = Math.max(0, item);
        if (Math.abs(from - prefetchFrom) < PREFETCH_STEP) return;
        prefetchFrom = from;
        prefetch();
    }

    void prefetch(List<StreamInfoItem> list, int count, boolean full) {
        prefetch(list, 0, count, full);
    }

    private void prefetch(List<StreamInfoItem> list, int from, int count, boolean full) {
        if (mode == TEXT) return;
        int cols = columns(mode);
        int width = cols > 1 ? screenWidth / cols : mode == COMPACT ? Math.min(360, screenWidth / 2) : Math.min(640, screenWidth);
        com.orvid.image.Images images = OrvidApp.get().images();
        for (int i = from; i < Math.min(list.size(), from + count); i++) {
            StreamInfoItem item = list.get(i);
            images.prefetch(Ui.thumbnail(item.getThumbnails(), width), width, item.getUrl(), full);
        }
    }

    private Previewer previewer;

    private View previewThumb;
    private String previewUrl;

    void releasePreview() {
        if (previewer != null) previewer.release();
        previewer = null;
        previewThumb = null;
        previewUrl = null;
    }

    long previewPosition(String url) {
        return previewer == null ? -1 : previewer.position(url);
    }

    private void devOverlay(View thumb, StreamInfoItem item) {
        if (!(thumb.getParent() instanceof android.widget.FrameLayout)) return;
        android.widget.FrameLayout box = (android.widget.FrameLayout) thumb.getParent();
        TextView label = box.findViewWithTag("dev");
        if (!com.orvid.app.DevInfo.on) {
            if (label != null) label.setVisibility(View.GONE);
            return;
        }
        if (label == null) {
            label = new TextView(context);
            label.setTag("dev");
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(9);
            label.setTypeface(android.graphics.Typeface.MONOSPACE);
            label.setBackgroundColor(0xB3000000);
            int pad = Ui.dp(context, 3);
            label.setPadding(pad, pad, pad, pad);
            box.addView(label, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.START));
        }
        label.setVisibility(View.VISIBLE);
        label.bringToFront();
        String rank = com.orvid.app.DevInfo.rank(item);
        String warm = com.orvid.app.DevInfo.warm(item.getUrl());
        String thumbInfo = com.orvid.app.DevInfo.thumb(item.getUrl());
        int position = items.indexOf(item);
        label.setText("#" + (position + 1) + " " + (rank == null ? "-" : rank)
                + "\nprep " + (warm == null ? "-" : warm)
                + "\nthumb " + (thumbInfo == null ? "…" : thumbInfo)
                + "\nv=" + item.getViewCount() + " d=" + item.getDuration() + "s");
    }

    void stopPreview() {
        if (previewer != null) previewer.stop();
        previewThumb = null;
        previewUrl = null;
    }

    void checkPreview() {
        if (previewThumb == null) return;
        android.graphics.Rect shown = new android.graphics.Rect();
        boolean visible = previewThumb.isAttachedToWindow() && previewThumb.getGlobalVisibleRect(shown)
                && shown.height() * 3 >= previewThumb.getHeight();
        if (!visible) stopPreview();
    }

    private static boolean onThumb(View thumb, View card) {
        Object down = card.getTag(R.id.touch_down);
        if (thumb == null || !(down instanceof float[])) return false;
        int[] at = new int[2];
        thumb.getLocationOnScreen(at);
        float x = ((float[]) down)[0];
        float y = ((float[]) down)[1];
        return x >= at[0] && x < at[0] + thumb.getWidth() && y >= at[1] && y < at[1] + thumb.getHeight();
    }

    int add(List<StreamInfoItem> more) {
        return add(more, 0);
    }

    int add(List<StreamInfoItem> more, int frozen) {
        if (skeletons > 0) {
            skeletons = 0;
            items.clear();
            tail.clear();
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (StreamInfoItem i : items) seen.add(key(i));
        int at = items.size();
        for (int i = 0; i < items.size(); i++) {
            if (tail.contains(items.get(i))) {
                at = i;
                break;
            }
        }
        at = Math.max(at, Math.min(frozen, items.size()));
        int strict = com.orvid.search.Ranker.Ranked.strict(more);
        int added = 0;
        java.util.Set<StreamInfoItem> fresh = identities();
        for (int i = 0; i < more.size(); i++) {
            StreamInfoItem item = more.get(i);
            if (!seen.add(key(item))) continue;
            if (i < strict) {
                items.add(at++, item);
            } else {
                items.add(item);
                tail.add(item);
            }
            fresh.add(item);
            added++;
        }
        sweepCopies(fresh);
        notifyDataSetChanged();
        prefetch();
        return added;
    }

    int itemCount() {
        return items.size();
    }

    List<StreamInfoItem> itemsInRows(int firstRow, int lastRow) {
        int cols = columns(mode);
        List<StreamInfoItem> out = new ArrayList<>();
        if (skeletons > 0) return out;
        for (int i = Math.max(0, firstRow) * cols; i < Math.min(items.size(), (lastRow + 1) * cols); i++) out.add(items.get(i));
        return out;
    }

    List<StreamInfoItem> items() {
        return new ArrayList<>(items);
    }

    int columns() {
        return columns(mode);
    }

    @Override
    public int getCount() {
        int n = skeletons > 0 && items.isEmpty() ? skeletons : items.size();
        int cols = columns(mode);
        return (n + cols - 1) / cols;
    }

    @Override
    public Object getItem(int position) {
        return columns(mode) > 1 ? null : items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 5;
    }

    @Override
    public int getItemViewType(int position) {
        return mode;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (view == null) view = LayoutInflater.from(context).inflate(layout(), parent, false);
        int cols = columns(mode);
        int[] cells = cols == 3 ? CELLS_3 : CELLS_2;
        if (skeletons > 0 && items.isEmpty()) {
            if (cols > 1) {
                for (int id : cells) {
                    View cell = view.findViewById(id);
                    skeleton(cell);
                    cell.setVisibility(View.VISIBLE);
                }
            } else {
                skeleton(view);
            }
            return view;
        }
        if (cols > 1) {
            int first = position * cols;
            for (int i = 0; i < cols; i++) {
                View cell = view.findViewById(cells[i]);
                if (first + i < items.size()) {
                    cell.setVisibility(View.VISIBLE);
                    bindCell(cell, items.get(first + i), screenWidth / cols);
                } else {
                    cell.setVisibility(View.INVISIBLE);
                    cell.setOnClickListener(null);
                }
            }
            return view;
        }
        int thumbWidth = mode == COMPACT ? Math.min(360, screenWidth / 2) : Math.min(640, screenWidth);
        bindCell(view, items.get(position), thumbWidth);
        return view;
    }

    private int layout() {
        switch (mode) {
            case COMPACT: return R.layout.row_compact;
            case GRID: return R.layout.row_grid;
            case GRID3: return R.layout.row_grid3;
            case TEXT: return R.layout.row_text;
            default: return R.layout.row_video;
        }
    }

    private CharSequence highlighted(String title) {
        if (title == null || query == null) return title;
        java.util.List<int[]> spans = com.orvid.search.Ranker.highlights(query, title);
        if (spans.isEmpty()) return title;
        android.text.SpannableString out = new android.text.SpannableString(title);
        for (int[] s : spans) {
            out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s[0], s[1],
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private void skeleton(View view) {
        TextView name = view.findViewById(R.id.name);
        TextView sub = view.findViewById(R.id.sub);
        ThumbView thumb = view.findViewById(R.id.thumb);
        view.findViewById(R.id.duration).setVisibility(View.GONE);
        View badge = view.findViewById(R.id.source_icon);
        if (badge != null) badge.setVisibility(View.GONE);
        if (thumb != null) {
            thumb.setTag(R.id.image_key, null);
            thumb.setImageDrawable(null);
        }
        if (name != null) {
            name.setText("　");
            skeletonBar(name, new android.graphics.drawable.InsetDrawable(
                    context.getDrawable(R.drawable.bg_skeleton), 0, Ui.dp(context, 3), Ui.dp(context, 24), Ui.dp(context, 3)));
        }
        View sub2 = view.findViewById(R.id.sub2);
        if (sub2 != null) sub2.setVisibility(View.GONE);
        if (sub != null) {
            sub.setVisibility(View.VISIBLE);
            sub.setText("　");
            skeletonBar(sub, new android.graphics.drawable.InsetDrawable(
                    context.getDrawable(R.drawable.bg_skeleton), 0, Ui.dp(context, 3), Ui.dp(context, 120), Ui.dp(context, 3)));
        }
        view.setOnClickListener(null);
        view.setOnTouchListener(null);
        view.setClickable(false);
        if (view.getTag(R.id.skeleton_anim) == null) {
            android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.45f);
            pulse.setDuration(750);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.start();
            view.setTag(R.id.skeleton_anim, pulse);
        }
    }

    private static void skeletonBar(View v, android.graphics.drawable.Drawable bar) {
        int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setBackground(bar);
        v.setPadding(l, t, r, b);
    }

    private static void unskeleton(View view, TextView name, TextView sub) {
        Object running = view.getTag(R.id.skeleton_anim);
        if (running != null) {
            ((android.animation.ObjectAnimator) running).cancel();
            view.setTag(R.id.skeleton_anim, null);
            view.setAlpha(1f);
        }
        if (name != null) name.setBackground(null);
        if (sub != null) sub.setBackground(null);
    }

    private android.graphics.drawable.Drawable sourceIcon(int service) {
        if (service == com.orvid.dm.Dailymotion.SERVICE_ID) return context.getDrawable(R.drawable.ic_src_dailymotion);
        if (service == com.orvid.vk.Vk.SERVICE_ID) return context.getDrawable(R.drawable.ic_src_vk);
        com.orvid.search.Source extra = Sources.byService(service);
        if (extra != null) return Ui.sourceIcon(context, extra);
        return context.getDrawable(R.drawable.ic_src_youtube);
    }

    private void bindCell(View view, StreamInfoItem item, int thumbWidth) {
        TextView name = view.findViewById(R.id.name);
        TextView sub = view.findViewById(R.id.sub);
        TextView duration = view.findViewById(R.id.duration);
        ThumbView thumb = view.findViewById(R.id.thumb);

        unskeleton(view, name, sub);
        if (name != null) name.setText(highlighted(item.getName()));
        String views = item.getViewCount() >= 0
                ? context.getString(R.string.views, Ui.count(item.getViewCount())) : "";
        String date = item.getTextualUploadDate();
        if (item.getUploadDate() != null) {
            date = android.text.format.DateUtils.getRelativeTimeSpanString(
                    item.getUploadDate().getInstant().toEpochMilli()).toString();
        }
        String source = item.getServiceId() == com.orvid.dm.Dailymotion.SERVICE_ID
                ? context.getString(R.string.source_dailymotion)
                : item.getServiceId() == com.orvid.vk.Vk.SERVICE_ID ? "VK"
                : Sources.byService(item.getServiceId()) != null ? Sources.byService(item.getServiceId()).name : "";
        if (sub != null) {
            String text;
            if (mode == GRID) {
                text = Ui.joinParts(item.getUploaderName(), source);
            } else if (mode == COMPACT) {
                String first = Ui.joinParts(item.getUploaderName(), source);
                String second = Ui.joinParts(views, date);
                String more = first.isEmpty() ? "" : second;
                text = first.isEmpty() ? second : first;
                TextView sub2 = view.findViewById(R.id.sub2);
                if (sub2 != null) {
                    sub2.setText(more);
                    sub2.setVisibility(more.isEmpty() ? View.GONE : View.VISIBLE);
                } else if (!more.isEmpty()) {
                    text = text + "\n" + more;
                }
            } else {
                text = Ui.joinParts(item.getUploaderName(), source, views, date);
            }
            sub.setText(text);
            sub.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }

        boolean live = item.getStreamType() == StreamType.LIVE_STREAM
                || item.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
        String badge = live ? context.getString(R.string.live) : Ui.duration(item.getDuration());
        duration.setText(badge);
        duration.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
        android.widget.ImageView sourceIcon = view.findViewById(R.id.source_icon);
        if (sourceIcon != null) {
            sourceIcon.setImageDrawable(sourceIcon(item.getServiceId()));
            int sid = item.getServiceId();
            sourceIcon.setImageTintList(sid == com.orvid.dm.Dailymotion.SERVICE_ID || sid == com.orvid.ia.Archive.SERVICE_ID
                    ? android.content.res.ColorStateList.valueOf(0xFFFFFFFF) : null);
            sourceIcon.setVisibility(View.VISIBLE);
        }
        if (thumb != null) {
            if (thumb == previewThumb && !item.getUrl().equals(previewUrl)) stopPreview();
            devOverlay(thumb, item);
            OrvidApp.get().images().load(thumb, Ui.thumbnail(item.getThumbnails(), thumbWidth), thumbWidth, item.getUrl());
        }
        TextView avatar = view.findViewById(R.id.avatar);
        if (avatar != null) {
            String who = item.getUploaderName() == null || item.getUploaderName().trim().isEmpty()
                    ? (source.isEmpty() ? "YouTube" : source) : item.getUploaderName().trim();
            avatar.setText(who.substring(0, 1).toUpperCase());
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            float hue = Math.floorMod(who.hashCode(), 360);
            dot.setColor(android.graphics.Color.HSVToColor(new float[] {hue, 0.45f, 0.62f}));
            avatar.setBackground(dot);
        }
        android.widget.ImageView avatarImage = view.findViewById(R.id.avatar_image);
        if (avatarImage != null) {
            if (avatarImage.getOutlineProvider() != OVAL) {
                avatarImage.setOutlineProvider(OVAL);
                avatarImage.setClipToOutline(true);
            }
            int size = avatarImage.getLayoutParams().width;
            OrvidApp.get().images().loadAvatar(avatarImage, Ui.thumbnail(item.getUploaderAvatars(), size), size);
            channelTap(avatarImage, item);
        }
        if (avatar != null) channelTap(avatar, item);
        view.setOnClickListener(v -> onPick.pick(item));
        view.setOnLongClickListener(v -> {
            if (onThumb(thumb, v) && thumb.getParent() instanceof android.widget.FrameLayout) {
                if (previewer == null) previewer = new Previewer(context);
                previewer.start((android.widget.FrameLayout) thumb.getParent(), item);
                previewThumb = thumb;
                previewUrl = item.getUrl();
                com.orvid.play.Warm.prepare(previewUrl);
                showMenu(item, thumb);
                return true;
            }
            stopPreview();
            showMenu(item, thumb != null ? thumb : v);
            return true;
        });
        view.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) v.setTag(R.id.touch_down, new float[]{e.getRawX(), e.getRawY()});
            if (action == android.view.MotionEvent.ACTION_DOWN && idle) {
                String id = Visionos.videoId(item.getUrl());
                String page = item.getUrl();
                if (id != null || com.orvid.play.Resolve.preparable(page)) {
                    Runnable warm = () -> com.orvid.play.Warm.link(page);
                    v.setTag(R.id.prepare_job, warm);
                    v.setTag(R.id.prepare_down, new float[]{e.getX(), e.getY()});
                    v.postDelayed(warm, PREPARE_DELAY_MS);
                }
            } else if (action == android.view.MotionEvent.ACTION_MOVE || action == android.view.MotionEvent.ACTION_CANCEL) {
                Object warm = v.getTag(R.id.prepare_job);
                Object down = v.getTag(R.id.prepare_down);
                boolean moved = down instanceof float[] && Math.hypot(e.getX() - ((float[]) down)[0],
                        e.getY() - ((float[]) down)[1]) > slop(v);
                if (warm instanceof Runnable && (action == android.view.MotionEvent.ACTION_CANCEL || moved)) {
                    v.removeCallbacks((Runnable) warm);
                    v.setTag(R.id.prepare_job, null);
                }
            }
            return false;
        });
    }
}
