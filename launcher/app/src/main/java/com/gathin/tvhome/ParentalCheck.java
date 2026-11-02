package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Parental's one alarm: if a rule is in force and a counted app is in front (opened from Home or with the
 * remote's own button), TimesUpActivity covers it, or WhoActivity when a child has to be picked first. Then the
 * next alarm is set. Only scheduled while rules exist.
 */
public final class ParentalCheck extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Parental.enabled(c)) return;
        Parental.Status s = Parental.status(c, true);
        String front = s.usage == null ? null : s.usage.front;
        // An app the child may not use, opened with the remote's own button: back Home.
        if (s.state == Parental.FREE && Parental.blockIfNotAllowed(c, front, true)) {
            Parental.schedule(c);
            return;
        }
        // A minute before the lock, while something that counts is on screen.
        if (Parental.counted(c, front)) Parental.warnIfClose(c, s);
        if (s.state != Parental.FREE && Parental.counted(c, front)) {
            try {
                c.startActivity(s.state == Parental.NEED_VIEWER ? WhoActivity.intent(c, null)
                        : TimesUpActivity.intent(c, s.state));
            } catch (RuntimeException ignored) {
            }
        }
        Parental.schedule(c);
    }
}
