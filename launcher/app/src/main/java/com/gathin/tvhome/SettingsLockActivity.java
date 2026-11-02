package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * Covers the TV's settings until the parental PIN is entered (SettingsGuardService). The right PIN uncovers the
 * settings behind; Back or Cancel goes Home.
 */
public final class SettingsLockActivity extends Activity {
    private boolean asking;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = new TextView(this);
        t.setText(R.string.parental_settings_locked);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(34);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xFF101419);
        setContentView(t);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Parental.unlocked() || !Parental.hasPin(this)) {
            finish();
            return;
        }
        if (asking) return;
        asking = true;
        PinDialog.ask(this, R.string.parental_enter_pin, () -> {
            asking = false;
            finish();
        }, () -> {
            asking = false;
            goHome();
        });
    }

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
