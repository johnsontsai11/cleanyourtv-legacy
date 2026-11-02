package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/**
 * Fork: a one-press switch between Google's Android TV keyboard and the TV's usual one, from Launcher settings.
 * Sony's keyboard moves its highlight to the key the remote's number keys press, so anyone watching reads a
 * password off it; Google's keeps its highlight still, but has no Chinese input. Runs only when the row is pressed:
 * nothing watches the screen for it (a password box in a dialog, SmartTube's, sends no events to find it by).
 *
 * Changing the default keyboard needs WRITE_SECURE_SETTINGS
 * (adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS).
 */
final class PasswordKeyboard {
    static final String GOOGLE_TV_KEYBOARD =
            "com.google.android.leanback.ime/com.google.leanback.ime.LeanbackImeService";
    private static final String PREFS = "password_keyboard", BEFORE = "before_google";

    private PasswordKeyboard() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Google's keyboard is installed and enabled, so there is something to switch to. */
    static boolean available(Context c) {
        return enabled(c, GOOGLE_TV_KEYBOARD) != null;
    }

    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static InputMethodInfo enabled(Context c, String id) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null || id == null) return null;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (info.getId().equals(id)) return info;
        }
        return null;
    }

    static String current(Context c) {
        return Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    }

    /** The current keyboard's name, for the row. */
    static CharSequence currentLabel(Context c) {
        InputMethodInfo info = enabled(c, current(c));
        return info != null ? info.loadLabel(c.getPackageManager()) : "?";
    }

    /**
     * To Google's keyboard, or back to the one from before it (else the first other enabled one).
     * Returns false when the TV refused (no WRITE_SECURE_SETTINGS).
     */
    static boolean toggle(Context c) {
        String now = current(c);
        String target;
        if (GOOGLE_TV_KEYBOARD.equals(now)) {
            target = prefs(c).getString(BEFORE, null);
            if (enabled(c, target) == null) {
                target = null;
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                        if (!info.getId().equals(GOOGLE_TV_KEYBOARD)) {
                            target = info.getId();
                            break;
                        }
                    }
                }
            }
        } else {
            target = GOOGLE_TV_KEYBOARD;
        }
        if (target == null) return false;
        try {
            if (!Settings.Secure.putString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, target)) {
                return false;
            }
        } catch (SecurityException e) {
            return false;
        }
        if (GOOGLE_TV_KEYBOARD.equals(target)) prefs(c).edit().putString(BEFORE, now).apply();
        return true;
    }
}
