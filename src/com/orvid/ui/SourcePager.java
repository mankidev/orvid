package com.orvid.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.orvid.R;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SourcePager extends FrameLayout {

    interface Host {
        List<StreamInfoItem> results();

        boolean inTail(StreamInfoItem item);

        boolean pageable();

        int mode();

        com.orvid.search.Query query();

        void pick(StreamInfoItem item, long at);

        void turnOff(int serviceId);

        void more();
    }

    private static final long SLIDE_MS = 260;
    private static final float COMMIT_SHARE = 0.3f;
    private static final float FLING_DP = 500;
    private static final float TAIL_WEIGHT = 0.2f;

    private Host host;
    private View main;
    private final Page[] pages = new Page[2];
    private final List<Integer> order = new ArrayList<>();
    private int index;
    private View current;

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean refused;
    private float offset;
    private View neighbour;
    private int neighbourIndex = -1;
    private VelocityTracker velocity;
    private ValueAnimator slide;
    private final int slop;

    public SourcePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void attach(Host host) {
        this.host = host;
        main = getChildAt(0);
        current = main;
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new Page(LayoutInflater.from(getContext()).inflate(R.layout.page_source, this, false));
            pages[i].root.setVisibility(INVISIBLE);
            addView(pages[i].root);
        }
    }

    int index() {
        return index;
    }

    void setMode(int mode) {
        for (Page p : pages) p.adapter.setMode(mode);
    }

    void reset() {
        if (slide != null) slide.cancel();
        slide = null;
        dragging = false;
        order.clear();
        index = 0;
        current = main;
        main.setTranslationX(0);
        main.setVisibility(VISIBLE);
        for (Page p : pages) {
            p.root.setVisibility(INVISIBLE);
            p.root.setTranslationX(0);
            p.sid = Integer.MIN_VALUE;
            p.adapter.releasePreview();
            p.adapter.set(new ArrayList<>());
        }
    }

    void back() {
        if (index == 0 || slide != null) return;
        slideTo(0, 1);
    }

    void refresh() {
        if (index == 0 && slide == null) return;
        List<StreamInfoItem> results = host.results();
        if (results.isEmpty()) {
            reset();
            return;
        }
        java.util.Set<Integer> known = new java.util.HashSet<>(order);
        for (StreamInfoItem item : results) if (known.add(item.getServiceId())) order.add(item.getServiceId());
        for (Page p : pages) if (p.root.getVisibility() == VISIBLE && order.contains(p.sid)) p.fill(results);
    }

    void removed(int sid) {
        int at = order.indexOf(sid);
        if (at < 0) return;
        if (index > 0 && at == index - 1 && slide == null) {
            eliminate(false);
            return;
        }
        order.remove(at);
        if (index > at + 1) index--;
        if (index > order.size()) index = order.size();
        if (index == 0 && current != main) reset();
    }

    private void eliminate(boolean turnOff) {
        if (index == 0 || slide != null) return;
        int sid = order.get(index - 1);
        order.remove(index - 1);
        Page leaving = pageOf(current);
        if (leaving != null) leaving.sid = Integer.MIN_VALUE;
        if (order.isEmpty()) {
            index = 1;
            slideTo(0, 1);
        } else if (index - 1 < order.size()) {
            index--;
            slideTo(index + 1, -1);
        } else {
            slideTo(index - 1, 1);
        }
        if (turnOff) host.turnOff(sid);
    }

    private static List<Integer> ranked(List<StreamInfoItem> items, Host host) {
        com.orvid.search.Query q = host.query();
        Map<Integer, Integer> full = new HashMap<>();
        Map<Integer, Double> score = new HashMap<>();
        Map<Integer, Integer> first = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            StreamInfoItem item = items.get(i);
            int sid = item.getServiceId();
            double gain = 1.0 / (Math.log(i + 2) / Math.log(2));
            if (host.inTail(item)) gain *= TAIL_WEIGHT;
            score.merge(sid, gain, Double::sum);
            first.putIfAbsent(sid, i);
            int hit = q != null && com.orvid.search.Ranker.covers(q, item) ? 1 : 0;
            full.merge(sid, hit, Integer::sum);
        }
        List<Integer> out = new ArrayList<>(score.keySet());
        out.sort((a, b) -> {
            int c = Integer.compare(full.get(b), full.get(a));
            if (c != 0) return c;
            c = Double.compare(score.get(b), score.get(a));
            return c != 0 ? c : Integer.compare(first.get(a), first.get(b));
        });
        return out;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (host == null) return false;
        return watch(e);
    }

    private boolean watch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                refused = slide != null;
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                refused = true;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (refused || dragging || e.getPointerCount() > 1) return dragging;
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
                    refused = true;
                    return false;
                }
                if (Math.abs(dx) <= slop || Math.abs(dx) < Math.abs(dy) * 2) return false;
                if (!startDrag(dx < 0 ? -1 : 1)) {
                    refused = true;
                    return false;
                }
                downX = e.getX();
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                return true;
            default:
                return false;
        }
    }

    private boolean startDrag(int direction) {
        if (index == 0) {
            if (direction > 0 || !host.pageable()) return false;
            List<StreamInfoItem> results = host.results();
            order.clear();
            order.addAll(ranked(results, host));
            if (order.isEmpty()) return false;
        }
        offset = 0;
        neighbour = null;
        neighbourIndex = -1;
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) {
            if (host == null) return super.onTouchEvent(e);
            boolean started = watch(e);
            return started || e.getActionMasked() == MotionEvent.ACTION_DOWN;
        }
        if (velocity != null) velocity.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                drag(e.getX() - downX);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                float vx = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    vx = velocity.getXVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                release(e.getActionMasked() == MotionEvent.ACTION_CANCEL ? 0 : vx);
                return true;
            default:
                return true;
        }
    }

    private void drag(float dx) {
        int target = dx < 0 ? index + 1 : index - 1;
        if (target < 0 || target > order.size()) {
            offset = dx / 4;
            if (neighbour != null) neighbour.setVisibility(INVISIBLE);
            neighbour = null;
            neighbourIndex = -1;
            current.setTranslationX(offset);
            return;
        }
        offset = dx;
        if (neighbourIndex != target) {
            if (neighbour != null) neighbour.setVisibility(INVISIBLE);
            neighbour = prepare(target);
            neighbourIndex = target;
        }
        current.setTranslationX(offset);
        neighbour.setTranslationX(offset + (dx < 0 ? getWidth() : -getWidth()));
    }

    private void release(float vx) {
        float fling = FLING_DP * getResources().getDisplayMetrics().density;
        boolean commit = neighbour != null && (Math.abs(offset) > getWidth() * COMMIT_SHARE
                || Math.abs(vx) > fling && Math.signum(vx) == Math.signum(offset));
        if (commit) {
            finishSlide(neighbourIndex, offset < 0 ? -1 : 1, Math.abs(vx));
        } else {
            settleBack();
        }
    }

    private void settleBack() {
        View cur = current;
        View other = neighbour;
        float start = offset;
        int sign = offset < 0 ? 1 : -1;
        animate(1f, 0f, SLIDE_MS * 2 / 3, f -> {
            cur.setTranslationX(start * f);
            if (other != null) other.setTranslationX(start * f + sign * getWidth());
        }, () -> {
            if (other != null && other != current) other.setVisibility(INVISIBLE);
        });
        neighbour = null;
        neighbourIndex = -1;
    }

    private void slideTo(int target, int direction) {
        offset = 0;
        neighbour = prepare(target);
        neighbourIndex = target;
        neighbour.setTranslationX(-direction * getWidth());
        finishSlide(target, direction, 0);
    }

    private void finishSlide(int target, int direction, float speed) {
        View cur = current;
        View next = neighbour;
        float width = getWidth();
        float from = offset;
        float to = direction * width;
        long ms = SLIDE_MS;
        if (speed > 0) {
            float left = Math.abs(to - from);
            ms = Math.max(140, Math.min(SLIDE_MS, (long) (left / speed * 1000 * 1.6f)));
        }
        Page leaving = pageOf(cur);
        if (leaving != null) leaving.adapter.stopPreview();
        animate(0f, 1f, ms, f -> {
            float x = from + (to - from) * f;
            cur.setTranslationX(x);
            next.setTranslationX(x - direction * width);
        }, () -> {
            cur.setVisibility(INVISIBLE);
            cur.setTranslationX(0);
            next.setTranslationX(0);
            current = next;
            index = target;
            neighbour = null;
            neighbourIndex = -1;
            if (leaving != null) leaving.adapter.releasePreview();
            if (index == 0) order.clear();
        });
    }

    private interface Frame {
        void at(float f);
    }

    private void animate(float a, float b, long ms, Frame frame, Runnable end) {
        if (slide != null) slide.cancel();
        ValueAnimator anim = ValueAnimator.ofFloat(a, b);
        anim.setDuration(ms);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        anim.addUpdateListener(v -> frame.at((float) v.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (slide == anim) slide = null;
                if (!cancelled) end.run();
            }
        });
        slide = anim;
        anim.start();
    }

    private View prepare(int target) {
        if (target == 0) {
            main.setVisibility(VISIBLE);
            return main;
        }
        Page page = pages[0].root == current ? pages[1] : pages[0];
        page.bind(order.get(target - 1), target, order.size(), host.results());
        page.root.setVisibility(VISIBLE);
        return page.root;
    }

    private Page pageOf(View v) {
        for (Page p : pages) if (p.root == v) return p;
        return null;
    }

    private final class Page {
        final View root;
        final ImageView logo;
        final TextView name;
        final TextView count;
        final TextView position;
        final ListView list;
        final VideoAdapter adapter;
        int sid = Integer.MIN_VALUE;
        int shown = -1;

        Page(View root) {
            this.root = root;
            logo = root.findViewById(R.id.source_logo);
            name = root.findViewById(R.id.source_name);
            count = root.findViewById(R.id.source_count);
            position = root.findViewById(R.id.source_position);
            list = root.findViewById(R.id.source_list);
            TextView off = root.findViewById(R.id.source_off);
            int icon = Ui.dp(getContext(), 16);
            android.graphics.drawable.Drawable x = getContext().getDrawable(R.drawable.ic_close);
            if (x != null) {
                x.setBounds(0, 0, icon, icon);
                off.setCompoundDrawablesRelative(x, null, null, null);
            }
            off.setOnClickListener(v -> {
                if (current == this.root && sid != Integer.MIN_VALUE) eliminate(true);
            });
            adapter = new VideoAdapter(getContext(), host.mode(), item -> host.pick(item, Page.this.adapter.previewPosition(item.getUrl())));
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int state) {
                    adapter.setIdle(state == SCROLL_STATE_IDLE);
                }

                @Override
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    adapter.checkPreview();
                    adapter.prefetchAfter((first + visible) * adapter.columns());
                    if (total > 0 && first + visible >= total - 3 && Page.this.root == current) host.more();
                }
            });
        }

        void bind(int sid, int position, int of, List<StreamInfoItem> results) {
            boolean same = this.sid == sid;
            this.sid = sid;
            Label info = Label.of(getContext(), sid);
            logo.setImageDrawable(info.icon);
            name.setText(info.name);
            adapter.setQuery(host.query());
            adapter.setMode(host.mode());
            shown = -1;
            fill(results);
            this.position.setText(position + " / " + of);
            if (!same) list.setSelection(0);
        }

        void fill(List<StreamInfoItem> results) {
            List<StreamInfoItem> mine = new ArrayList<>();
            for (StreamInfoItem i : results) if (i.getServiceId() == sid) mine.add(i);
            if (mine.size() == shown) return;
            shown = mine.size();
            adapter.set(mine);
            label();
        }

        private void label() {
            int n = Math.max(0, shown);
            count.setText(getResources().getQuantityString(R.plurals.result_count, n, n));
        }
    }

    private static final class Label {
        final String name;
        final android.graphics.drawable.Drawable icon;

        private Label(String name, android.graphics.drawable.Drawable icon) {
            this.name = name;
            this.icon = icon;
        }

        static Label of(Context c, int sid) {
            if (sid == org.schabi.newpipe.extractor.ServiceList.YouTube.getServiceId()) {
                return new Label("YouTube", c.getDrawable(R.drawable.ic_src_youtube));
            }
            if (sid == com.orvid.dm.Dailymotion.SERVICE_ID) {
                return new Label(c.getString(R.string.source_dailymotion), c.getDrawable(R.drawable.ic_src_dailymotion));
            }
            if (sid == com.orvid.vk.Vk.SERVICE_ID) return new Label("VK Video", c.getDrawable(R.drawable.ic_src_vk));
            com.orvid.search.Source s = Sources.byService(sid);
            if (s != null) return new Label(s.name, Ui.sourceIcon(c, s));
            return new Label("?", null);
        }
    }
}
