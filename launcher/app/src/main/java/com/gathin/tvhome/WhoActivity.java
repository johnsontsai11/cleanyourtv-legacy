package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * "Who's watching?": with children set up, picks whose time an app counts against. Each child shows the time
 * left today; "Parent" asks for the PIN and has no limits. After the pick, the app that was being opened opens.
 */
public final class WhoActivity extends Activity {
    private static final String EXTRA_PKG = "com.gathin.tvhome.extra.PARENTAL_PKG";

    static Intent intent(Context c, String pkg) {
        return new Intent(c, WhoActivity.class).putExtra(EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = Parental.prefs(this);
        float dp = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101419);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (72 * dp);
        col.setPadding(pad, (int) (56 * dp), pad, pad);
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText(R.string.parental_who);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(40);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, (int) (24 * dp));
        col.addView(title);

        long now = System.currentTimeMillis();
        Parental.Usage usage = Parental.usage(this, now);
        TextView first = null;
        for (int id : Parental.kids(p)) {
            int limit = Parental.kidLimit(p, id);
            String left;
            if (limit <= 0) {
                left = getString(R.string.parental_unlimited);
            } else {
                long ms = Math.max(0, limit * 60_000L - usage.by(p, id, now));
                left = ms == 0 ? getString(R.string.parental_used_up)
                        : getString(R.string.parental_left, getString(R.string.parental_minutes,
                        (int) ((ms + 59_999L) / 60_000L)));
            }
            TextView row = row(col, dp, Parental.kidName(p, id) + "  ·  " + left);
            row.setCompoundDrawablesRelative(Parental.kidAvatar(p, id, (int) (36 * dp)), null, null, null);
            row.setCompoundDrawablePadding((int) (14 * dp));
            row.setOnClickListener(v -> pick(id));
            if (first == null) first = row;
        }
        TextView parent = row(col, dp, getString(R.string.parental_parent));
        parent.setOnClickListener(v -> PinDialog.ask(this, R.string.parental_enter_pin,
                () -> pick(Parental.PARENT), null));
        setContentView(scroll);
        // Sign the current viewer out: back to "Who's watching?", and the next app asks again.
        int current = Parental.viewer(p);
        if (current != Parental.NOBODY) {
            String who = current == Parental.PARENT ? getString(R.string.parental_parent) : Parental.kidName(p, current);
            TextView exit = row(col, dp, getString(R.string.parental_sign_out, who));
            ((LinearLayout.LayoutParams) exit.getLayoutParams()).topMargin = (int) (28 * dp);
            exit.setOnClickListener(v -> {
                Parental.setViewer(Parental.prefs(this), Parental.NOBODY);
                finish();
            });
        }
        (first != null ? first : parent).requestFocus();
    }

    private TextView row(LinearLayout col, float dp, String text) {
        TextView t = new TextView(this, null, 0, R.style.SettingsRow);
        t.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (640 * dp), (int) (52 * dp));
        lp.topMargin = (int) (12 * dp);
        t.setLayoutParams(lp);
        col.addView(t);
        return t;
    }

    private void pick(int viewer) {
        Parental.setViewer(Parental.prefs(this), viewer);
        int s = Parental.stateNow(this);
        if (s != Parental.FREE) {
            startActivity(TimesUpActivity.intent(this, s));
        } else {
            openPending();
            Parental.schedule(this);
        }
        finish();
    }

    private void openPending() {
        String pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null || pkg.equals(Parental.INPUTS) || Parental.blockIfNotAllowed(this, pkg, false)) return;
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    /** Back goes Home rather than to an app this screen covers. */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
            finish();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
