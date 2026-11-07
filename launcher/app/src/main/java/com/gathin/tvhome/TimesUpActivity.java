package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "TV time is over": shown instead of an app during bedtime, once the daily limit is used up, or while a parent has
 * locked the TV ("The TV is locked", with Unlock now in place of More time).
 */
public final class TimesUpActivity extends Activity {
    private static final String EXTRA_STATE = "com.gathin.tvhome.extra.PARENTAL_STATE";
    private static final int[] EXTRA_MINUTES = {15, 30, 60, 120};

    private TextView title;
    private TextView message;
    private TextView more;
    private TextView switchViewer;

    static Intent intent(Context c, int state) {
        return new Intent(c, TimesUpActivity.class).putExtra(EXTRA_STATE, state)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101419);
        int pad = (int) (72 * dp);
        root.setPadding(pad, pad, pad, pad);

        title = new TextView(this);
        title.setText(R.string.parental_times_up);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(40);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        message = new TextView(this);
        message.setTextColor(0xB3FFFFFF);
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, (int) (16 * dp), 0, (int) (36 * dp));
        root.addView(message);

        TextView home = row(R.string.parental_back_home, dp);
        home.setOnClickListener(v -> goHome());
        root.addView(home);
        more = row(R.string.parental_more_time, dp);
        more.setOnClickListener(v -> PinDialog.ask(this, R.string.parental_enter_pin, () -> {
            if (Parental.stateNow(this) == Parental.LOCKED) unlockNow();
            else pickMoreTime();
        }, null));
        root.addView(more);
        switchViewer = row(R.string.parental_switch_viewer, dp);
        switchViewer.setOnClickListener(v -> {
            startActivity(WhoActivity.intent(this, null));
            finish();
        });
        root.addView(switchViewer);

        setContentView(root);
        home.requestFocus();
    }

    private TextView row(int text, float dp) {
        TextView t = new TextView(this, null, 0, R.style.SettingsRow);
        t.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (420 * dp), (int) (52 * dp));
        lp.topMargin = (int) (12 * dp);
        t.setLayoutParams(lp);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        update();
    }

    /** Shows the rule in force now, or leaves when none is. */
    private void update() {
        Parental.Status st = Parental.status(this, false);
        int s = st.state;
        if (s == Parental.FREE || s == Parental.NEED_VIEWER) {
            // Bedtime ended, a parent added time, or the pick ended while this was in the background.
            if (s == Parental.NEED_VIEWER) startActivity(WhoActivity.intent(this, null));
            finish();
            return;
        }
        SharedPreferences p = Parental.prefs(this);
        title.setText(s == Parental.LOCKED ? R.string.parental_locked_title : R.string.parental_times_up);
        more.setText(s == Parental.LOCKED ? R.string.parental_unlock_parent : R.string.parental_more_time);
        if (s == Parental.LOCKED) {
            long now = System.currentTimeMillis();
            message.setText(getString(R.string.parental_locked_message,
                    Parental.lockEndLabel(this, Parental.lockedUntil(p, now), now)));
        } else if (s == Parental.BEDTIME) {
            message.setText(getString(R.string.parental_bedtime_message,
                    Parental.hhmm(p.getInt(Parental.BED_END, Parental.DEFAULT_BED_END))));
        } else {
            message.setText(getString(R.string.parental_kid_limit_message, Parental.kidName(p, st.viewer),
                    getString(R.string.parental_minutes, Parental.kidLimit(p, st.viewer))));
        }
        // Another child may still have time left today.
        switchViewer.setVisibility(s == Parental.LIMIT_REACHED && st.viewer >= 0 ? View.VISIBLE : View.GONE);
        Parental.schedule(this);
    }

    /** The parent's PIN lifts the lock; bedtime and the limits apply again straight away. */
    private void unlockNow() {
        Parental.unlockTv(this);
        Parental.lockAgain();
        update();
    }

    private void pickMoreTime() {
        String[] labels = new String[EXTRA_MINUTES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(R.string.parental_minutes, EXTRA_MINUTES[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.parental_more_time)
                .setItems(labels, (d, which) -> {
                    Parental.prefs(this).edit().putLong(Parental.EXTRA_UNTIL,
                            System.currentTimeMillis() + EXTRA_MINUTES[which] * 60_000L).apply();
                    Parental.lockAgain();
                    Parental.schedule(this);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Back goes Home rather than to the app behind this screen. */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            goHome();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void goHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
    }
}
