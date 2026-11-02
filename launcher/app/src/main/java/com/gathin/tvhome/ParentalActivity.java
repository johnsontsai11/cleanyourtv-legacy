package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Launcher settings > Parental controls: the PIN, bedtime, and a daily limit for each child.
 * Behind the PIN once one is set.
 */
public final class ParentalActivity extends Activity {
    private static final int[] LIMITS = {0, 30, 60, 90, 120, 180, 240};
    private static final int STEP = 30;

    private SharedPreferences p;
    private float dp;
    private TextView status, pinRow, bedRow, kidsTitle, offRow;
    private LinearLayout kidsBox;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        p = Parental.prefs(this);
        dp = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (72 * dp), (int) (38 * dp), (int) (72 * dp), (int) (38 * dp));
        scroll.addView(col);

        col.addView(text(R.string.parental_controls, 34, 0xFFFFFFFF, 0));
        status = text(0, 16, 0xB3FFFFFF, 8);
        col.addView(status);

        pinRow = row(col, 20);
        pinRow.setOnClickListener(v -> PinDialog.create(this, this::refresh));
        bedRow = row(col, 18);
        bedRow.setOnClickListener(v -> pickBedStart());

        kidsTitle = text(R.string.parental_kids, 20, 0xFFFFFFFF, 0);
        kidsTitle.setPadding(0, (int) (28 * dp), 0, 0);
        col.addView(kidsTitle);
        kidsBox = new LinearLayout(this);
        kidsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(kidsBox);

        offRow = row(col, 36);
        offRow.setText(R.string.parental_turn_off);
        offRow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.parental_turn_off)
                .setMessage(R.string.parental_turn_off_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    p.edit().clear().commit();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        setContentView(scroll);
        if (Parental.pinNeeded(this)) {
            scroll.setVisibility(View.INVISIBLE);
            PinDialog.ask(this, R.string.parental_enter_pin, () -> {
                scroll.setVisibility(View.VISIBLE);
                pinRow.requestFocus();
            }, this::finish);
        }
        refresh();
        pinRow.requestFocus();
    }

    private TextView text(int res, int sp, int color, int padDp) {
        TextView t = new TextView(this);
        if (res != 0) t.setText(res);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setPadding(0, (int) (padDp * dp), 0, (int) (padDp * dp));
        return t;
    }

    private TextView row(LinearLayout col, int marginTop) {
        TextView t = new TextView(this, null, 0, R.style.SettingsRow);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (52 * dp));
        lp.topMargin = (int) (marginTop * dp);
        t.setLayoutParams(lp);
        col.addView(t);
        return t;
    }

    private void pickBedStart() {
        // 18:00 to 23:30, every half hour.
        int[] starts = new int[12];
        String[] labels = new String[starts.length + 1];
        labels[0] = getString(R.string.off);
        for (int i = 0; i < starts.length; i++) {
            starts[i] = 18 * 60 + i * STEP;
            labels[i + 1] = Parental.hhmm(starts[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.parental_bedtime_starts)
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        p.edit().remove(Parental.BED_START).apply();
                        refresh();
                    } else {
                        pickBedEnd(starts[which - 1]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickBedEnd(int start) {
        // 05:00 to 10:00, every half hour.
        int[] ends = new int[11];
        String[] labels = new String[ends.length];
        for (int i = 0; i < ends.length; i++) {
            ends[i] = 5 * 60 + i * STEP;
            labels[i] = Parental.hhmm(ends[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.parental_bedtime_ends)
                .setItems(labels, (d, which) -> {
                    p.edit().putInt(Parental.BED_START, start).putInt(Parental.BED_END, ends[which]).apply();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Off (0) or one of LIMITS, in minutes. */
    private void pickLimit(CharSequence title, IntConsumer chosen) {
        String[] labels = new String[LIMITS.length];
        for (int i = 0; i < LIMITS.length; i++) {
            labels[i] = LIMITS[i] == 0 ? getString(R.string.parental_unlimited)
                    : getString(R.string.parental_minutes, LIMITS[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (d, which) -> chosen.accept(LIMITS[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A name, typed with the on-screen keyboard. */
    private void askName(String current, java.util.function.Consumer<String> named) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (current != null) {
            field.setText(current);
            field.setSelection(current.length());
        }
        Runnable submit = () -> {
            String name = field.getText().toString().trim();
            if (!name.isEmpty()) named.accept(name);
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.parental_kid_name)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, w) -> submit.run())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // The keyboard's Enter (Sony's 進入) and the remote's OK submit; without this they did nothing and the
        // only way to 確定 was Back, then down, which nobody finds.
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (field.getText().toString().trim().isEmpty()) return false;
            if (d.isShowing()) {
                d.dismiss();
                submit.run();
            }
            return true;
        });
        field.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                    && keyCode != KeyEvent.KEYCODE_DPAD_CENTER) return false;
            // An empty name: OK opens the keyboard as usual.
            if (field.getText().toString().trim().isEmpty()) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && d.isShowing()) {
                d.dismiss();
                submit.run();
            }
            return true;
        });
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
        field.requestFocus();
    }

    private void addKid() {
        askName(null, name -> pickLimit(getString(R.string.parental_kid_limit_title, name), limit -> {
            Parental.addKid(p, name, limit);
            refresh();
        }));
    }

    /** Emoji the Android 7 emoji font can draw (Unicode 8 or older), so an avatar never shows as a blank box. */
    private static final String[] AVATARS = {
            "🐶", "🐱", "🐰", "🐻", "🐼", "🐨", "🐯", "🦁", "🐸", "🐵", "🐧", "🐢",
            "🐳", "🐙", "🦄", "🐝", "🌸", "🌈", "⭐", "🚀", "⚽", "🎨", "🎸", "🍓"};

    /** A grid of avatars, six to a row, for the D-pad; "None" removes the child's avatar. */
    private void pickAvatar(int id) {
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(6);
        LinearLayout box = new LinearLayout(this);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, (int) (12 * dp), 0, 0);
        box.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.parental_avatar_title, Parental.kidName(p, id)))
                .setView(box)
                .setNeutralButton(R.string.parental_avatar_none, (dialog, w) -> {
                    Parental.setKidEmoji(p, id, null);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        String current = Parental.kidEmoji(p, id);
        TextView focus = null;
        for (String e : AVATARS) {
            TextView b = new TextView(this, null, 0, R.style.SettingsRow);
            b.setText(e);
            b.setTextSize(30);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(0, 0, 0, 0);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = (int) (72 * dp);
            lp.height = (int) (64 * dp);
            lp.setMargins((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
            b.setLayoutParams(lp);
            b.setSelected(e.equals(current));
            b.setOnClickListener(v -> {
                Parental.setKidEmoji(p, id, e);
                d.dismiss();
                refresh();
            });
            grid.addView(b);
            if (focus == null || e.equals(current)) focus = b;
        }
        d.show();
        focus.requestFocus();
    }

    /** Preset colours as avatars (the child's own emoji on each), five to a row. */
    private void pickColor(int id) {
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(5);
        LinearLayout box = new LinearLayout(this);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, (int) (12 * dp), 0, 0);
        box.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.parental_color_title, Parental.kidName(p, id)))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        int current = Parental.kidColor(p, id);
        String e = Parental.kidEmoji(p, id);
        String name = Parental.kidName(p, id);
        String label = !e.isEmpty() ? e : name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
        TextView focus = null;
        for (int color : AvatarDrawable.COLORS) {
            TextView b = new TextView(this, null, 0, R.style.SettingsRow);
            b.setGravity(android.view.Gravity.CENTER);
            // A top drawable sits at the top padding: (68 - 48) / 2 centres the 48 dp circle in its 68 dp cell.
            b.setPadding(0, (int) (10 * dp), 0, 0);
            b.setCompoundDrawablesRelative(null, new AvatarDrawable(color, label, (int) (48 * dp)), null, null);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = (int) (80 * dp);
            lp.height = (int) (68 * dp);
            lp.setMargins((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
            b.setLayoutParams(lp);
            b.setSelected(color == current);
            b.setOnClickListener(v -> {
                Parental.setKidColor(p, id, color);
                d.dismiss();
                refresh();
            });
            grid.addView(b);
            if (focus == null || color == current) focus = b;
        }
        d.show();
        focus.requestFocus();
    }

    private void editKid(int id) {
        String name = Parental.kidName(p, id);
        String[] items = {getString(R.string.parental_daily_limit), getString(R.string.parental_allowed_apps),
                getString(R.string.parental_avatar), getString(R.string.parental_color),
                getString(R.string.parental_rename), getString(R.string.parental_remove_kid, name)};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickWeek(id);
                    } else if (which == 1) {
                        pickApps(id);
                    } else if (which == 2) {
                        pickAvatar(id);
                    } else if (which == 3) {
                        pickColor(id);
                    } else if (which == 4) {
                        askName(name, n -> {
                            Parental.renameKid(p, id, n);
                            refresh();
                        });
                    } else {
                        Parental.removeKid(p, id);
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A child's week: every day, weekdays, the weekend, or one day at a time. Reopens after each choice, so a
     * parent can set several days in a row; Cancel closes it.
     */
    private void pickWeek(int id) {
        String name = Parental.kidName(p, id);
        int[] w = Parental.kidWeek(p, id);
        // Monday first, as the week reads on a TV in Taiwan and most of Europe; Calendar numbers Sunday 1.
        int[] order = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY,
                Calendar.SATURDAY, Calendar.SUNDAY};
        String[] dayNames = new DateFormatSymbols(getResources().getConfiguration().locale).getWeekdays();
        String[] items = new String[3 + order.length];
        items[0] = getString(R.string.parental_every_day);
        items[1] = getString(R.string.parental_weekdays);
        items[2] = getString(R.string.parental_weekends);
        for (int i = 0; i < order.length; i++) {
            items[3 + i] = getString(R.string.parental_day_state, dayNames[order[i]], limitLabel(w[order[i] - 1]));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.parental_kid_limit_title, name))
                .setItems(items, (d, which) -> pickLimit(items[which].split("[:：]")[0], limit -> {
                    if (which == 0) {
                        Parental.setKidLimit(p, id, limit);
                    } else if (which == 1) {
                        Parental.setKidLimitOn(p, id, new int[]{Calendar.MONDAY, Calendar.TUESDAY,
                                Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY}, limit);
                    } else if (which == 2) {
                        Parental.setKidLimitOn(p, id, new int[]{Calendar.SATURDAY, Calendar.SUNDAY}, limit);
                    } else {
                        Parental.setKidLimitOn(p, id, new int[]{order[which - 3]}, limit);
                    }
                    refresh();
                    pickWeek(id);
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * The TV's apps (leanback entry points, the Live TV app among them) to tick for this child. Nothing ticked,
     * or "Allow all apps", means every app; a list means Home shows only those and the rest are refused.
     */
    private void pickApps(int id) {
        android.content.pm.PackageManager pm = getPackageManager();
        List<String[]> apps = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(new android.content.Intent(
                android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = r.activityInfo.packageName;
            if (!Parental.counted(this, pkg) || !seen.add(pkg)) continue;
            apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        java.util.Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        java.util.Set<String> current = Parental.kidApps(p, id);
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[0];
            checked[i] = current != null && current.contains(apps.get(i)[1]);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.parental_allowed_apps_title, Parental.kidName(p, id)))
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    java.util.Set<String> pkgs = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < apps.size(); i++) if (checked[i]) pkgs.add(apps.get(i)[1]);
                    Parental.setKidApps(p, id, pkgs);
                    refresh();
                })
                .setNeutralButton(R.string.parental_all_apps, (d, w) -> {
                    Parental.setKidApps(p, id, null);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Ann: 60 min a day", "Ann: weekdays 60 min, weekends 120 min", or "Ann: by day of the week", plus apps. */
    private String kidRow(int id) {
        String name = Parental.kidName(p, id);
        int[] w = Parental.kidWeek(p, id);
        String row;
        switch (Parental.weekShape(w)) {
            case Parental.SAME_EVERY_DAY:
                row = getString(R.string.parental_kid_row, name, limitLabel(w[1]));
                break;
            case Parental.WEEKDAYS_AND_WEEKEND:
                row = getString(R.string.parental_kid_row_week, name, limitLabel(w[1]), limitLabel(w[0]));
                break;
            default:
                row = getString(R.string.parental_kid_row_by_day, name);
        }
        java.util.Set<String> apps = Parental.kidApps(p, id);
        return apps == null ? row : row + "  ·  " + getString(R.string.parental_kid_apps, apps.size());
    }

    private String limitLabel(int limit) {
        return limit == 0 ? getString(R.string.parental_unlimited) : getString(R.string.parental_minutes, limit);
    }

    private void refresh() {
        boolean pin = p.contains(Parental.PIN);
        long now = System.currentTimeMillis();
        List<Integer> kids = Parental.kids(p);
        pinRow.setText(pin ? R.string.parental_change_pin : R.string.parental_set_pin);
        int bed = p.getInt(Parental.BED_START, Parental.OFF);
        bedRow.setText(getString(R.string.parental_bedtime_state, bed < 0 ? getString(R.string.off)
                : Parental.hhmm(bed) + " – " + Parental.hhmm(p.getInt(Parental.BED_END, Parental.DEFAULT_BED_END))));

        View focused = getCurrentFocus();
        boolean refocus = focused != null && focused.getParent() == kidsBox;
        kidsBox.removeAllViews();
        for (int id : kids) {
            TextView r = row(kidsBox, 10);
            r.setText(kidRow(id));
            r.setCompoundDrawablesRelative(Parental.kidAvatar(p, id, (int) (34 * dp)), null, null, null);
            r.setCompoundDrawablePadding((int) (14 * dp));
            r.setOnClickListener(v -> editKid(id));
        }
        TextView add = row(kidsBox, 10);
        add.setText(R.string.parental_add_kid);
        add.setOnClickListener(v -> addKid());
        if (refocus) add.requestFocus();

        for (int i = 0; i < kidsBox.getChildCount(); i++) enable(kidsBox.getChildAt(i), pin);
        enable(bedRow, pin);
        enable(offRow, pin);
        kidsTitle.setAlpha(pin ? 1f : 0.5f);

        if (!pin) {
            status.setText(R.string.parental_needs_pin);
        } else if (!Parental.hasUsageAccess(this)) {
            status.setText(R.string.parental_no_usage_access);
        } else if (!SettingsGuardService.enabled(this)) {
            status.setText(R.string.parental_guard_off);
        } else {
            Parental.Usage u = Parental.usage(this, now);
            StringBuilder s = new StringBuilder();
            if (kids.isEmpty()) s.append(getString(R.string.parental_minutes, (int) (u.total / 60_000L)));
            for (int id : kids) {
                if (s.length() > 0) s.append("  ·  ");
                s.append(Parental.kidName(p, id)).append(' ')
                        .append(getString(R.string.parental_minutes, (int) (u.by(p, id, now) / 60_000L)));
            }
            status.setText(getString(R.string.parental_used_today, s));
        }
        Parental.schedule(this);
    }

    private static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setFocusable(on);
        v.setAlpha(on ? 1f : 0.5f);
    }
}
