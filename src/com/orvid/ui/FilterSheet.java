package com.orvid.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.orvid.R;
import com.orvid.search.Filters;

final class FilterSheet {

    interface Listener {
        void onLayout(int mode);

        void onFilters(Filters filters);
    }

    private FilterSheet() {
    }

    static void show(Activity activity, int layout, Filters filters, Listener listener) {
        Dialog dialog = Panic.guard(activity, new Dialog(activity));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 20);
        column.setPadding(pad, Ui.dp(activity, 12), pad, pad);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(column);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.bg_sheet);
        sheet.addView(scroll);
        TextView save = new TextView(activity);
        save.setText(R.string.filter_save);
        save.setGravity(Gravity.CENTER);
        save.setTextSize(15);
        save.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        save.setTextColor(activity.getColor(R.color.on_accent));
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(activity.getColor(R.color.accent));
        pill.setCornerRadius(Ui.dp(activity, 24));
        save.setBackground(pill);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 48));
        saveLp.setMargins(pad, Ui.dp(activity, 8), pad, Ui.dp(activity, 16));
        sheet.addView(save, saveLp);
        save.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(sheet);

        boolean[] changed = {false};
        int[] currentLayout = {layout};

        section(activity, column, R.string.layout);
        chips(activity, column, new int[] {R.string.layout_card, R.string.layout_compact, R.string.layout_grid, R.string.layout_text, R.string.layout_grid3},
                layout, i -> {
                    currentLayout[0] = i;
                    listener.onLayout(i);
                });

        section(activity, column, R.string.filter_type);
        chips(activity, column, new int[] {R.string.filter_any, R.string.type_videos, R.string.type_shorts},
                filters.type, i -> {
                    filters.type = i;
                    changed[0] = true;
                });

        section(activity, column, R.string.filter_duration);
        chips(activity, column, new int[] {R.string.filter_any, R.string.filter_short, R.string.filter_medium, R.string.filter_long},
                filters.duration, i -> {
                    filters.duration = i;
                    changed[0] = true;
                });

        section(activity, column, R.string.filter_date);
        chips(activity, column, new int[] {R.string.filter_any, R.string.date_week, R.string.date_month, R.string.date_year},
                filters.date, i -> {
                    filters.date = i;
                    changed[0] = true;
                });

        section(activity, column, R.string.filter_sort);
        chips(activity, column, new int[] {R.string.sort_relevance, R.string.sort_latest, R.string.sort_longest, R.string.sort_views},
                filters.sort, i -> {
                    filters.sort = i;
                    changed[0] = true;
                });

        section(activity, column, R.string.repositories);
        Runnable reload = () -> {
            changed[0] = true;
            dialog.dismiss();
        };
        for (com.orvid.ext.Repos.Repo r : com.orvid.ext.Repos.list(com.orvid.app.OrvidApp.get().extRoot())) {
            if (r.adult && !com.orvid.search.Source.adultUnlocked) continue;
            TextView repoRow = row(activity, column, r.name + " (" + r.count + ")", R.drawable.ic_filter, () -> RepoDialog.manage(activity, r, reload));
            markStale(activity, repoRow, r);
        }
        for (com.orvid.ext.Repos.Link l : com.orvid.ext.Repos.suggested(com.orvid.app.OrvidApp.get().extRoot())) {
            if (l.adult && !com.orvid.search.Source.adultUnlocked) continue;
            row(activity, column, RepoDialog.label(activity, l), R.drawable.ic_add, () -> RepoDialog.add(activity, l.url, reload));
        }
        row(activity, column, activity.getString(R.string.add_repo), R.drawable.ic_add, () -> RepoDialog.add(activity, reload));

        section(activity, column, R.string.filter_sources);
        EditText sourceField = new EditText(activity);
        sourceField.setHint(R.string.filter_sources_search);
        sourceField.setSingleLine(true);
        sourceField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sourceField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        sourceField.setBackgroundResource(R.drawable.bg_search);
        sourceField.setTextColor(activity.getColor(R.color.text_primary));
        sourceField.setHintTextColor(activity.getColor(R.color.text_secondary));
        sourceField.setTextSize(15);
        sourceField.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44));
        fieldLp.bottomMargin = Ui.dp(activity, 6);
        column.addView(sourceField, fieldLp);
        int sourcesFrom = column.getChildCount();
        java.util.List<Switch> normalSwitches = new java.util.ArrayList<>();
        boolean everyOn = filters.youtube && filters.dailymotion && filters.vk;
        for (com.orvid.search.Source x : Sources.current()) if (!x.adult && !filters.on(x)) everyOn = false;
        source(activity, column, activity.getString(R.string.all_sources), R.drawable.ic_filter, everyOn, on -> {
            for (Switch child : normalSwitches) child.setChecked(on);
        });
        java.util.List<Object[]> core = new java.util.ArrayList<>();
        if (com.orvid.ext.Registry.engineOn("youtube")) {
            core.add(new Object[] {com.orvid.ext.Registry.rank("youtube"), (Runnable) () ->
                    normalSwitches.add(source(activity, column, "YouTube", R.drawable.ic_src_youtube, filters.youtube, on -> {
                        filters.youtube = on;
                        changed[0] = true;
                    }))});
        }
        if (com.orvid.ext.Registry.engineOn("dailymotion")) {
            core.add(new Object[] {com.orvid.ext.Registry.rank("dailymotion"), (Runnable) () ->
                    normalSwitches.add(source(activity, column, activity.getString(R.string.source_dailymotion),
                            R.drawable.ic_src_dailymotion, filters.dailymotion, on -> {
                        filters.dailymotion = on;
                        changed[0] = true;
                    }))});
        }
        if (com.orvid.ext.Registry.engineOn("vk")) {
            core.add(new Object[] {com.orvid.ext.Registry.rank("vk"), (Runnable) () ->
                    normalSwitches.add(source(activity, column, "VK Video", R.drawable.ic_src_vk, filters.vk, on -> {
                        filters.vk = on;
                        changed[0] = true;
                    }))});
        }
        core.sort((x, y) -> Integer.compare((Integer) x[0], (Integer) y[0]));
        java.util.List<Switch> adultSwitches = new java.util.ArrayList<>();
        boolean masterAdded = false;
        for (com.orvid.search.Source s : Sources.current()) {
            int sRank = s.adult ? Integer.MAX_VALUE : com.orvid.ext.Registry.rank(s.key);
            while (!core.isEmpty() && (Integer) core.get(0)[0] <= sRank) ((Runnable) core.remove(0)[1]).run();
            if (s.adult && !com.orvid.search.Source.adultUnlocked) continue;
            if (s.adult && !masterAdded) {
                masterAdded = true;
                boolean allOn = true;
                for (com.orvid.search.Source x : Sources.current()) if (x.adult && !filters.on(x)) allOn = false;
                Switch master = source(activity, column, activity.getString(R.string.all_adult), R.drawable.ic_eighteen, allOn, on -> {
                });
                android.widget.CompoundButton.OnCheckedChangeListener[] holder = new android.widget.CompoundButton.OnCheckedChangeListener[1];
                holder[0] = (b, checked) -> {
                    android.content.SharedPreferences prefs = activity.getSharedPreferences("settings", 0);
                    if (!checked || prefs.getBoolean("age_confirmed", false)) {
                        for (Switch child : adultSwitches) child.setChecked(checked);
                        return;
                    }
                    b.setOnCheckedChangeListener(null);
                    b.setChecked(false);
                    b.setOnCheckedChangeListener(holder[0]);
                    Panic.guard(activity, new android.app.AlertDialog.Builder(activity)
                            .setTitle(R.string.age_title)
                            .setMessage(R.string.age_message)
                            .setPositiveButton(R.string.age_yes, (d, w) -> {
                                prefs.edit().putBoolean("age_confirmed", true).apply();
                                b.setChecked(true);
                            })
                            .setNegativeButton(R.string.age_no, null)
                            .show());
                };
                master.setOnCheckedChangeListener(holder[0]);
            }
            Switch sw = source(activity, column, s.name, Ui.sourceIcon(activity, s), filters.on(s), on -> {
                filters.set(s, on);
                if (on) com.orvid.search.Health.reset(s.key);
                changed[0] = true;
            });
            int health = com.orvid.search.Health.state(s.key);
            if (s instanceof com.orvid.ext.ExtSource && ((com.orvid.ext.ExtSource) s).torless && com.orvid.net.Fetch.torMode()) {
                markNote(activity, column, sw, activity.getString(R.string.tor_off_only), 0xFF8A8A8A);
            } else if (s instanceof com.orvid.ext.ExtSource && !((com.orvid.ext.ExtSource) s).torPlayback && com.orvid.net.Fetch.torMode()) {
                markNote(activity, column, sw, activity.getString(R.string.tor_playback_blocked), 0xFF8A8A8A);
            } else if (health != com.orvid.search.Health.OK) markHealth(activity, column, sw, health);
            if (!s.adult) normalSwitches.add(sw);
            if (s.adult) {
                adultSwitches.add(sw);
                sw.setOnCheckedChangeListener((b, checked) -> {
                    if (!checked) {
                        filters.set(s, false);
                        changed[0] = true;
                        return;
                    }
                    android.content.SharedPreferences prefs = activity.getSharedPreferences("settings", 0);
                    if (prefs.getBoolean("age_confirmed", false)) {
                        filters.set(s, true);
                        com.orvid.search.Health.reset(s.key);
                        changed[0] = true;
                        return;
                    }
                    b.setChecked(false);
                    Panic.guard(activity, new android.app.AlertDialog.Builder(activity)
                            .setTitle(R.string.age_title)
                            .setMessage(R.string.age_message)
                            .setPositiveButton(R.string.age_yes, (d, w) -> {
                                prefs.edit().putBoolean("age_confirmed", true).apply();
                                filters.set(s, true);
                                changed[0] = true;
                                b.setOnCheckedChangeListener(null);
                                b.setChecked(true);
                                b.setOnCheckedChangeListener((bb, c2) -> {
                                    filters.set(s, c2);
                                    changed[0] = true;
                                });
                            })
                            .setNegativeButton(R.string.age_no, null)
                            .show());
                });
            }
        }
        for (Object[] c : core) ((Runnable) c[1]).run();
        int sourcesTo = column.getChildCount();
        sourceField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence t, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (int i = sourcesFrom; i < sourcesTo; i++) {
                    View v = column.getChildAt(i);
                    v.setVisibility(q.isEmpty() || label(v).toLowerCase(java.util.Locale.ROOT).contains(q) ? View.VISIBLE : View.GONE);
                }
            }
        });


        section(activity, column, R.string.privacy);
        com.orvid.app.AppLock lock = com.orvid.app.OrvidApp.get().lock();
        Switch[] lockSwitch = new Switch[1];
        lockSwitch[0] = source(activity, column, activity.getString(R.string.app_lock), R.drawable.ic_lock,
                lock.enabled(), on -> {
                    if (on && !com.orvid.app.AppLock.available(activity)) {
                        android.widget.Toast.makeText(activity, R.string.lock_no_screen, android.widget.Toast.LENGTH_LONG).show();
                        lockSwitch[0].setChecked(false);
                        return;
                    }
                    lock.setEnabled(on);
                });
        source(activity, column, activity.getString(R.string.block_screenshots), R.drawable.ic_no_screenshot,
                lock.blockScreenshots(), lock::setBlockScreenshots);
        source(activity, column, activity.getString(R.string.use_tor), R.drawable.ic_tor,
                com.orvid.app.OrvidApp.get().torOn(), on -> com.orvid.app.OrvidApp.get().setTor(on));
        source(activity, column, activity.getString(R.string.tor_bridges), R.drawable.ic_bridge,
                com.orvid.app.OrvidApp.get().bridges(), on -> com.orvid.app.OrvidApp.get().setBridges(on));
        source(activity, column, activity.getString(R.string.panic_exit), R.drawable.ic_panic,
                com.orvid.app.OrvidApp.get().panicExit(), on -> com.orvid.app.OrvidApp.get().setPanicExit(on));

        dialog.setOnDismissListener(d -> {
            boolean any = filters.youtube && com.orvid.ext.Registry.engineOn("youtube")
                    || filters.dailymotion && com.orvid.ext.Registry.engineOn("dailymotion")
                    || filters.vk && com.orvid.ext.Registry.engineOn("vk");
            for (com.orvid.search.Source s : Sources.current()) any |= filters.on(s) && !(s.adult && filters.hideAdult);
            if (!any) {
                filters.youtube = true;
                filters.dailymotion = true;
                filters.vk = true;
                for (com.orvid.search.Source s : Sources.current()) if (!s.adult) filters.set(s, true);
            }
            if (changed[0]) listener.onFilters(filters);
        });
        int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.62f);
        column.measure(View.MeasureSpec.makeMeasureSpec(activity.getResources().getDisplayMetrics().widthPixels,
                View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(column.getMeasuredHeight(), maxHeight)));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    private static String label(View v) {
        if (v instanceof LinearLayout && ((LinearLayout) v).getChildCount() > 0) v = ((LinearLayout) v).getChildAt(0);
        return v instanceof TextView ? String.valueOf(((TextView) v).getText()) : "";
    }

    private interface IntPick {
        void pick(int index);
    }

    private interface BoolPick {
        void pick(boolean on);
    }

    private static void markStale(Activity a, TextView row, com.orvid.ext.Repos.Repo r) {
        Boolean known = com.orvid.ext.Repos.knownStale(r);
        if (known != null) showStale(a, row, known);
        new com.orvid.app.Job<Boolean>(a) {
            @Override
            protected Boolean load() throws Exception {
                return com.orvid.ext.Repos.stale(com.orvid.app.OrvidApp.get().extRoot(), r,
                        com.orvid.net.Fetch.newCircuit("repo"));
            }

            @Override
            protected void show(Boolean stale) {
                showStale(a, row, stale);
            }

            @Override
            protected void fail(Exception error) {
            }
        }.startBackground();
    }

    private static void showStale(Activity a, TextView row, boolean stale) {
        android.graphics.drawable.Drawable[] now = row.getCompoundDrawablesRelative();
        android.graphics.drawable.Drawable mark = null;
        if (stale) {
            mark = a.getDrawable(R.drawable.ic_download_white);
            if (mark != null) {
                int size = Ui.dp(a, 20);
                mark.setBounds(0, 0, size, size);
                mark.setTint(a.getColor(R.color.accent));
            }
        }
        row.setCompoundDrawablesRelative(now[0], null, mark, null);
    }

    private static TextView row(Activity a, LinearLayout column, String text, int icon, Runnable click) {
        TextView row = new TextView(a);
        row.setText(text);
        android.graphics.drawable.Drawable d = a.getDrawable(icon);
        int size = Ui.dp(a, 24);
        if (d != null) {
            d.setBounds(0, 0, size, size);
            d.setTint(a.getColor(R.color.text_primary));
        }
        row.setCompoundDrawablesRelative(d, null, null, null);
        row.setCompoundDrawablePadding(Ui.dp(a, 14));
        row.setTextColor(a.getColor(R.color.text_primary));
        row.setTextSize(15);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
        row.setOnClickListener(v -> click.run());
        column.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void section(Activity a, LinearLayout column, int title) {
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(a.getColor(R.color.text_secondary));
        t.setTextSize(13);
        t.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 8));
        column.addView(t);
    }

    private static void chips(Activity a, LinearLayout column, int[] labels, int selected, IntPick pick) {
        FlowLayout flow = new FlowLayout(a, null);
        TextView[] views = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(a);
            chip.setText(labels[i]);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(a.getColorStateList(R.color.chip_text));
            chip.setTextSize(14);
            chip.setPadding(Ui.dp(a, 14), Ui.dp(a, 7), Ui.dp(a, 14), Ui.dp(a, 7));
            chip.setSelected(i == selected);
            final int index = i;
            chip.setOnClickListener(v -> {
                for (TextView other : views) other.setSelected(other == v);
                pick.pick(index);
            });
            views[i] = chip;
            flow.addView(chip);
        }
        column.addView(flow);
    }

    private static void markHealth(Activity a, LinearLayout column, Switch sw, int health) {
        boolean down = health == com.orvid.search.Health.DOWN;
        String note = a.getString(down ? R.string.health_down
                : health == com.orvid.search.Health.EMPTY ? R.string.health_empty
                : health == com.orvid.search.Health.LIMITED ? R.string.health_limited : R.string.health_flaky);
        markNote(a, column, sw, note, down ? 0xFFE5484D : 0xFFE5A000);
    }

    private static void markNote(Activity a, LinearLayout column, Switch sw, String note, int tint) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int at = column.indexOfChild(sw);
        column.removeView(sw);
        TextView name = new TextView(a);
        name.setText(sw.getText());
        name.setTextColor(sw.getCurrentTextColor());
        name.setTextSize(15);
        name.setCompoundDrawablesRelative(sw.getCompoundDrawablesRelative()[0], null, null, null);
        name.setCompoundDrawablePadding(sw.getCompoundDrawablePadding());
        name.setGravity(android.view.Gravity.CENTER_VERTICAL);
        name.setOnClickListener(v -> sw.toggle());
        sw.setText(null);
        sw.setCompoundDrawablesRelative(null, null, null, null);
        android.widget.ImageButton alert = new android.widget.ImageButton(a);
        alert.setImageResource(R.drawable.ic_alert);
        alert.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        alert.setBackground(null);
        alert.setContentDescription(note);
        int pad = Ui.dp(a, 10);
        alert.setPadding(pad, pad, pad, pad);
        alert.setOnClickListener(v -> android.widget.Toast.makeText(a, note, android.widget.Toast.LENGTH_LONG).show());
        int size = Ui.dp(a, 40);
        row.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(alert, new LinearLayout.LayoutParams(size, size));
        row.addView(sw, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(row, at, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static Switch source(Activity a, LinearLayout column, String name, int icon, boolean on, BoolPick pick) {
        return source(a, column, name, a.getDrawable(icon), on, pick);
    }

    private static Switch source(Activity a, LinearLayout column, String name, android.graphics.drawable.Drawable d,
                                 boolean on, BoolPick pick) {
        Switch sw = new Switch(a);
        sw.setText(name);
        int size = Ui.dp(a, 24);
        if (d != null) d.setBounds(0, 0, size, size);
        sw.setCompoundDrawablesRelative(d, null, null, null);
        sw.setCompoundDrawablePadding(Ui.dp(a, 14));
        sw.setTextColor(a.getColor(R.color.text_primary));
        sw.setTextSize(15);
        sw.setChecked(on);
        sw.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        sw.setOnCheckedChangeListener((b, checked) -> pick.pick(checked));
        column.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sw;
    }
}
