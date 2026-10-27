package com.orvid.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.orvid.R;
import com.orvid.app.Job;
import com.orvid.app.OrvidApp;
import com.orvid.ext.Repos;
import com.orvid.net.Fetch;

final class RepoDialog {

    private interface Install {
        Repos.Repo run() throws Exception;
    }

    private final Activity a;
    private final Dialog dialog;
    private final LinearLayout column;
    private final TextView status;
    private final ProgressBar spinner;
    private boolean busy;

    private RepoDialog(Activity a, String title) {
        this.a = a;
        dialog = Panic.guard(a, new Dialog(a));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundResource(R.drawable.bg_sheet);
        int pad = Ui.dp(a, 20);
        column.setPadding(pad, Ui.dp(a, 18), pad, Ui.dp(a, 16));
        TextView heading = new TextView(a);
        heading.setText(title);
        heading.setTextColor(a.getColor(R.color.text_primary));
        heading.setTextSize(18);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        column.addView(heading);
        status = new TextView(a);
        status.setTextColor(a.getColor(R.color.text_secondary));
        status.setTextSize(13);
        status.setVisibility(View.GONE);
        spinner = new ProgressBar(a, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.accent)));
        spinner.setVisibility(View.GONE);
        dialog.setContentView(column);
        dialog.setCanceledOnTouchOutside(true);
    }

    static void add(Activity a, Runnable done) {
        add(a, null, done);
    }

    static void add(Activity a, String prefill, Runnable done) {
        RepoDialog d = new RepoDialog(a, a.getString(R.string.add_repo));
        TextView hint = new TextView(a);
        hint.setText(R.string.repo_find_hint);
        hint.setTextColor(a.getColor(R.color.text_secondary));
        hint.setTextSize(13);
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(0, Ui.dp(a, 6), 0, 0);
        d.column.addView(hint);
        EditText field = new EditText(a);
        field.setHint(R.string.repo_url_hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setBackgroundResource(R.drawable.bg_search);
        field.setTextColor(a.getColor(R.color.text_primary));
        field.setHintTextColor(a.getColor(R.color.text_secondary));
        field.setTextSize(15);
        int side = Ui.dp(a, 16);
        field.setPadding(side, 0, side, 0);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 44));
        fieldLp.topMargin = Ui.dp(a, 14);
        d.column.addView(field, fieldLp);
        if (prefill != null) field.setText(prefill);
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[][] suggested = {
                {a.getString(R.string.repo_official), Repos.OFFICIAL},
                {"Orvid films", Repos.FILMS},
                {"Orvid secret (18+)", Repos.SECRET},
        };
        for (String[] s : suggested) {
            if (Repos.installed(OrvidApp.get().extRoot(), s[1])) continue;
            if (Repos.SECRET.equals(s[1]) && !com.orvid.search.Source.adultUnlocked) continue;
            TextView chip = new TextView(a);
            chip.setText(s[0]);
            chip.setTextColor(a.getColor(R.color.accent));
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.bg_search);
            int chipPad = Ui.dp(a, 14);
            chip.setPadding(chipPad, 0, chipPad, 0);
            chip.setOnClickListener(v -> {
                field.setText(s[1]);
                field.setSelection(field.length());
            });
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 34));
            chipLp.setMarginEnd(Ui.dp(a, 8));
            chips.addView(chip, chipLp);
        }
        if (chips.getChildCount() > 0) {
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Ui.dp(a, 10);
            d.column.addView(chips, rowLp);
        }
        d.addStatus();
        Runnable submit = () -> {
            String url = field.getText().toString().trim();
            if (url.isEmpty() || d.busy) return;
            d.run(() -> Repos.add(OrvidApp.get().extRoot(), url, Fetch.newCircuit("repo")), done);
        };
        field.setOnEditorActionListener((v, id, e) -> {
            submit.run();
            return true;
        });
        d.buttons(a.getString(R.string.repo_add), submit, null, null);
        d.show(true);
        field.requestFocus();
    }

    static void manage(Activity a, Repos.Repo r, Runnable done) {
        RepoDialog d = new RepoDialog(a, r.name);
        TextView info = new TextView(a);
        info.setText(a.getString(R.string.repo_info, r.url, r.count));
        info.setTextColor(a.getColor(R.color.text_secondary));
        info.setTextSize(13);
        info.setLineSpacing(0, 1.2f);
        info.setTextIsSelectable(true);
        info.setPadding(0, Ui.dp(a, 8), 0, 0);
        d.column.addView(info);
        java.util.List<Repos.Link> others = Repos.others(OrvidApp.get().extRoot(), r);
        boolean header = false;
        for (Repos.Link l : others) {
            if (l.adult && !com.orvid.search.Source.adultUnlocked) continue;
            if (Repos.installed(OrvidApp.get().extRoot(), l.url)) continue;
            if (!header) {
                TextView t = new TextView(a);
                t.setText(R.string.other_repos);
                t.setTextColor(a.getColor(R.color.text_secondary));
                t.setTextSize(13);
                t.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 4));
                d.column.addView(t);
                header = true;
            }
            d.column.addView(d.linkRow(l, () -> d.run(() -> Repos.add(OrvidApp.get().extRoot(), l.url, Fetch.newCircuit("repo")), done)));
        }
        d.addStatus();
        d.buttons(a.getString(R.string.repo_update),
                () -> d.run(() -> Repos.update(OrvidApp.get().extRoot(), r, Fetch.newCircuit("repo")), done),
                a.getString(R.string.repo_remove), () -> {
                    if (d.busy) return;
                    Repos.remove(OrvidApp.get().extRoot(), r.id);
                    d.dialog.dismiss();
                    done.run();
                });
        d.show(false);
    }

    static String label(Activity a, Repos.Link l) {
        return l.adult ? l.name + " (18+)" : l.name;
    }

    private View linkRow(Repos.Link l, Runnable click) {
        TextView row = new TextView(a);
        row.setText(label(a, l));
        android.graphics.drawable.Drawable plus = a.getDrawable(R.drawable.ic_add);
        int size = Ui.dp(a, 22);
        if (plus != null) {
            plus.setBounds(0, 0, size, size);
            plus.setTint(a.getColor(R.color.accent));
        }
        row.setCompoundDrawablesRelative(plus, null, null, null);
        row.setCompoundDrawablePadding(Ui.dp(a, 12));
        row.setTextColor(a.getColor(R.color.text_primary));
        row.setTextSize(15);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private void addStatus() {
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, Ui.dp(a, 10), 0, 0);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16));
        spinLp.setMarginEnd(Ui.dp(a, 8));
        line.addView(spinner, spinLp);
        line.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        column.addView(line);
    }

    private void buttons(String primary, Runnable onPrimary, String secondary, Runnable onSecondary) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, Ui.dp(a, 16), 0, 0);
        if (secondary != null) {
            row.addView(textButton(secondary, onSecondary));
            View gap = new View(a);
            row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        }
        row.addView(textButton(a.getString(android.R.string.cancel), dialog::dismiss));
        TextView main = new TextView(a);
        main.setText(primary);
        main.setGravity(Gravity.CENTER);
        main.setTextSize(15);
        main.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        main.setTextColor(a.getColor(R.color.on_accent));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(a.getColor(R.color.accent));
        pill.setCornerRadius(Ui.dp(a, 22));
        main.setBackground(pill);
        int side = Ui.dp(a, 22);
        main.setPadding(side, 0, side, 0);
        main.setOnClickListener(v -> onPrimary.run());
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 44));
        mainLp.setMarginStart(Ui.dp(a, 8));
        row.addView(main, mainLp);
        column.addView(row);
    }

    private TextView textButton(String label, Runnable click) {
        TextView b = new TextView(a);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(15);
        b.setTextColor(a.getColor(R.color.text_secondary));
        int side = Ui.dp(a, 14);
        b.setPadding(side, 0, side, 0);
        b.setMinHeight(Ui.dp(a, 44));
        android.util.TypedValue ripple = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        b.setBackgroundResource(ripple.resourceId);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void say(String text, boolean working) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        spinner.setVisibility(working ? View.VISIBLE : View.GONE);
    }

    private void run(Install work, Runnable done) {
        if (busy) return;
        busy = true;
        say(a.getString(R.string.repo_adding), true);
        new Job<Repos.Repo>(a) {
            @Override
            protected Repos.Repo load() throws Exception {
                return work.run();
            }

            @Override
            protected void show(Repos.Repo r) {
                busy = false;
                if (r.adult && !com.orvid.search.Source.adultUnlocked) {
                    explainHidden(done);
                    return;
                }
                dialog.dismiss();
                done.run();
            }

            @Override
            protected void fail(Exception error) {
                busy = false;
                say(a.getString(R.string.repo_failed, String.valueOf(error.getMessage())), false);
            }
        }.start();
    }

    private void explainHidden(Runnable done) {
        column.removeAllViews();
        TextView heading = new TextView(a);
        heading.setText(R.string.secret_added);
        heading.setTextColor(a.getColor(R.color.text_primary));
        heading.setTextSize(18);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        column.addView(heading);
        TextView how = new TextView(a);
        how.setText(R.string.secret_how);
        how.setTextColor(a.getColor(R.color.text_secondary));
        how.setTextSize(14);
        how.setLineSpacing(0, 1.25f);
        how.setPadding(0, Ui.dp(a, 10), 0, 0);
        column.addView(how);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END);
        row.setPadding(0, Ui.dp(a, 16), 0, 0);
        TextView ok = new TextView(a);
        ok.setText(android.R.string.ok);
        ok.setGravity(Gravity.CENTER);
        ok.setTextSize(15);
        ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ok.setTextColor(a.getColor(R.color.on_accent));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(a.getColor(R.color.accent));
        pill.setCornerRadius(Ui.dp(a, 22));
        ok.setBackground(pill);
        int side = Ui.dp(a, 26);
        ok.setPadding(side, 0, side, 0);
        ok.setOnClickListener(v -> dialog.dismiss());
        row.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 44)));
        column.addView(row);
        dialog.setOnDismissListener(d -> done.run());
        android.view.inputmethod.InputMethodManager imm = a.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(column.getWindowToken(), 0);
    }

    private void show(boolean keyboard) {
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.4f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            if (keyboard) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }
}
