package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Parental controls' settings lock (fork). While a parental PIN is set, any of the TV's settings apps
 * (`parental_locked_settings`, plus whatever answers ACTION_SETTINGS) coming to the front is covered by
 * SettingsLockActivity until the PIN is entered, however it was opened: the remote's Settings button, the
 * Action Menu, an app's own link. Home locks them again. Accessibility lives inside the locked settings, so a
 * child cannot switch this service off.
 *
 * Enabled once over ADB (docs/parental-controls.md). Window-state events only; it reads no window content.
 * Without a PIN, or with parental_controls false, it ignores every event.
 */
public final class SettingsGuardService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null || !Parental.hasPin(this)) return;
        String pkg = p.toString();
        // The current child's allowed apps, at once (the alarm only checks once a minute).
        // Activities only: overlays such as the volume bar or an input banner raise window events too, and must
        // not send a child Home from an app they may use.
        if (!pkg.equals(getPackageName()) && isActivity(pkg, event.getClassName())
                && Parental.blockIfNotAllowed(this, pkg, true)) return;
        if (Parental.isSettings(this, pkg)) {
            if (Parental.unlocked()) {
                // A parent moving between settings screens: keep it open while they work.
                Parental.keepUnlocked();
            } else {
                try {
                    startActivity(new Intent(this, SettingsLockActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (RuntimeException ignored) {
                }
            }
        } else if (pkg.equals(getPackageName()) && event.getClassName() != null
                && event.getClassName().toString().equals(MainActivity.class.getName())) {
            // Back on Home: the next visit to settings asks again.
            Parental.lockAgain();
        }
    }

    private boolean isActivity(String pkg, CharSequence cls) {
        if (cls == null) return false;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls.toString()), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The screen turning off or back on: nobody is watching any more (Parental.onScreenOn). */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            Parental.onScreenOn(c);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter power = new IntentFilter(Intent.ACTION_SCREEN_ON);
        power.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, power);
        // Connected within minutes of a boot: the TV was just switched on, which counts as coming back on.
        // (Later connections are a launcher update or the service being switched on, which keep the viewer.)
        if (SystemClock.elapsedRealtime() < 10 * 60_000L) Parental.onScreenOn(this);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    /** Whether this service is switched on in the TV's accessibility settings. */
    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/" + SettingsGuardService.class.getName());
    }
}
