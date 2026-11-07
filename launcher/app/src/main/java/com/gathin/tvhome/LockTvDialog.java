package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork, parental controls: "Lock the TV…" in Launcher settings. Picks how long to lock the TV for (or, while
 * locked, Extend and Unlock now). With no PIN yet, one is set first, or nobody could be kept out of settings.
 */
final class LockTvDialog {
    /** Lock lengths, in minutes: 30 min, 1, 2 and 3 hours. */
    private static final int[] LOCKS = {30, 60, 120, 180};

    private LockTvDialog() {}

    /** The row's text: "Lock the TV…", or "The TV is locked until Sat 14:00". */
    static String rowText(Context c) {
        long now = System.currentTimeMillis();
        long until = Parental.lockedUntil(Parental.prefs(c), now);
        return until > 0 ? c.getString(R.string.parental_lock_state, Parental.lockEndLabel(c, until, now))
                : c.getString(R.string.parental_lock_tv);
    }

    /** Opens the choice; `changed` runs after a lock, an extension or an unlock. */
    static void open(Activity a, Runnable changed) {
        SharedPreferences p = Parental.prefs(a);
        if (!p.contains(Parental.PIN)) {
            PinDialog.create(a, () -> {
                changed.run();
                pickLock(a, 0, changed);
            });
            return;
        }
        long now = System.currentTimeMillis();
        long until = Parental.lockedUntil(p, now);
        if (until == 0) {
            pickLock(a, 0, changed);
            return;
        }
        String[] items = {a.getString(R.string.parental_lock_extend), a.getString(R.string.parental_lock_unlock)};
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.parental_lock_state, Parental.lockEndLabel(a, until, now)))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickLock(a, until, changed);
                    } else {
                        Parental.unlockTv(a);
                        changed.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** How long to lock for, or with `extendFrom` (the current end) how much to add; 0 for a new lock. */
    private static void pickLock(Activity a, long extendFrom, Runnable changed) {
        long now = System.currentTimeMillis();
        boolean extend = extendFrom > 0;
        List<String> labels = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
        for (int m : LOCKS) {
            labels.add(m < 60 ? a.getString(R.string.parental_minutes, m)
                    : a.getString(R.string.parental_lock_hours, m / 60));
            ends.add(Parental.extendLock(now, extendFrom, m * 60_000L));
        }
        if (!extend) {
            long morning = Parental.nextMinute(now, Parental.DEFAULT_BED_END);
            labels.add(a.getString(R.string.parental_lock_until, Parental.lockEndLabel(a, morning, now)));
            ends.add(morning);
            labels.add(a.getString(R.string.parental_lock_custom));
            ends.add(0L);
        }
        new AlertDialog.Builder(a)
                .setTitle(extend ? R.string.parental_lock_extend_title : R.string.parental_lock_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    long end = ends.get(which);
                    if (end == 0) pickLockEnd(a, changed);
                    else lock(a, end, changed);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Custom": an end time, every half hour for the next 24 hours. */
    private static void pickLockEnd(Activity a, Runnable changed) {
        long now = System.currentTimeMillis();
        long[] ends = Parental.customEnds(now);
        String[] labels = new String[ends.length];
        for (int i = 0; i < ends.length; i++) {
            String time = Parental.hhmm(Parental.minuteOfDay(ends[i]));
            labels[i] = Parental.sameDay(ends[i], now) ? time : a.getString(R.string.parental_lock_tomorrow, time);
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.parental_lock_custom_title)
                .setItems(labels, (d, which) -> lock(a, ends[which], changed))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void lock(Activity a, long until, Runnable changed) {
        Parental.lockTv(a, until);
        Toast.makeText(a, a.getString(R.string.parental_lock_state,
                Parental.lockEndLabel(a, until, System.currentTimeMillis())), Toast.LENGTH_LONG).show();
        changed.run();
    }
}
