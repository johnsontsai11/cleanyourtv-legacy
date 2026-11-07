package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Parental controls (fork feature, `parental_controls` in config.xml): a PIN on Launcher settings and on the
 * TV's system settings, a bedtime window for everyone, and a daily limit for each child.
 *
 * With children set up, whoever opens an app picks their name first (WhoActivity); the time an app is in front
 * is charged to the child picked then. A pick lasts until the TV sleeps or for VIEWER_MS. "Parent", behind the
 * PIN, has no limits.
 *
 * Rules only apply once a PIN is set. Time comes from Android's usage events, so it includes apps started with
 * the remote's own buttons; that needs usage access, granted once over ADB:
 * `adb shell appops set com.gathin.tvhome GET_USAGE_STATS allow`. While rules are on, one non-waking alarm
 * (ParentalCheck) is kept for the next moment a rule could start; during a lockout it checks once a minute and
 * covers any app that is not exempt. With the switch off nothing here runs.
 */
final class Parental {
    static final String PREFS = "parental";
    static final String PIN = "pin", SALT = "salt", BED_START = "bed_start", BED_END = "bed_end",
            EXTRA_UNTIL = "extra_until", KID_IDS = "kid_ids", NEXT_KID = "next_kid",
            VIEWER = "viewer", VIEWER_WALL = "viewer_wall", VIEWER_ELAPSED = "viewer_elapsed",
            VIEWER_UPTIME = "viewer_uptime", SEGMENTS = "segments", WARNED = "warned",
            PIN_LEN = "pin_len", LOCK_UNTIL = "lock_until";
    /** Bedtime start when it is off. Minutes of the day otherwise. */
    static final int OFF = -1;
    static final int DEFAULT_BED_END = 7 * 60;
    static final int FREE = 0, BEDTIME = 1, LIMIT_REACHED = 2, NEED_VIEWER = 3, LOCKED = 4;
    /** VIEWER values besides a child's id. */
    static final int NOBODY = -1, PARENT = -2;
    /** The package name passed for an input on a TV with no Live TV app, so inputs are counted too. */
    static final String INPUTS = "inputs";

    private static final long UNLOCK_MS = 5 * 60_000L;
    private static final long LOCKED_CHECK_MS = 60_000L;
    private static final long MAX_SLEEP_MS = 30 * 60_000L;
    static final long VIEWER_MS = 3 * 3_600_000L;
    /** Deep sleep longer than this (elapsed time running ahead of uptime) ends the pick: the TV was off. */
    private static final long SLEEP_ENDS_PICK_MS = 5 * 60_000L;
    private static final int MAX_TRIES = 5;
    private static final long COOL_DOWN_MS = 60_000L;

    /** elapsedRealtime until which the PIN is not asked again (one visit to settings). */
    private static long unlockedUntil;
    private static int wrongTries;
    private static long coolUntil;

    private Parental() {}

    static boolean enabled(Context c) {
        return c.getResources().getBoolean(R.bool.parental_controls);
    }

    /**
     * MODE_MULTI_PROCESS: re-read from disk on every call. On this TV an update can leave the launcher running in two
     * processes for a while (Home in the old one, new screens in the new one); without this, a name picked in the
     * picker never reached Home's pill, which kept its own copy. The file is small, so the re-read is cheap.
     */
    @SuppressWarnings("deprecation")
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    static boolean hasPin(Context c) {
        return enabled(c) && prefs(c).contains(PIN);
    }

    /** A bedtime, a limit, a child or a lock is set (they need a PIN to count). */
    static boolean anyRule(SharedPreferences p) {
        return p.contains(PIN) && (p.getInt(BED_START, OFF) >= 0 || !kids(p).isEmpty()
                || p.getLong(LOCK_UNTIL, 0) > System.currentTimeMillis());
    }

    // ---- Lock the TV ----

    /**
     * Locks the TV for everyone until `until` (wall clock): apps and inputs show the lockout page, Home stays. Ends
     * extra time, signs Parent out (or the lock would wait for the next pick) and asks for the PIN again.
     */
    static void lockTv(Context c, long until) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(LOCK_UNTIL, until).remove(EXTRA_UNTIL).commit();
        if (p.getInt(VIEWER, NOBODY) == PARENT) setViewer(p, NOBODY);
        lockAgain();
        schedule(c);
    }

    static void unlockTv(Context c) {
        prefs(c).edit().remove(LOCK_UNTIL).commit();
        schedule(c);
    }

    /** The lock's end, or 0 when the TV is not locked. */
    static long lockedUntil(SharedPreferences p, long now) {
        long until = p.getLong(LOCK_UNTIL, 0);
        return until > now ? until : 0;
    }

    /** A lock's new end when `addMs` is added: from its current end, or from now if it has already ended. */
    static long extendLock(long now, long until, long addMs) {
        return Math.max(until, now) + addMs;
    }

    /** The last of tomorrow's small hours left out of "Custom" (00:00 to this, both in): nobody locks to 03:00. */
    static final int CUSTOM_SKIP_UNTIL = 6 * 60;

    /**
     * End times for "Custom": every half hour from the next one after `now`, for 24 hours, without tomorrow's
     * 00:00 to 06:00. (Before 06:00, today's are kept: they are the next few hours.)
     */
    static long[] customEnds(long now) {
        long first = nextMinute(now, (minuteOfDay(now) / 30 + 1) * 30 % (24 * 60));
        long[] all = new long[48];
        int n = 0;
        for (int i = 0; i < all.length; i++) {
            long end = first + i * 30 * 60_000L;
            if (!sameDay(end, now) && minuteOfDay(end) <= CUSTOM_SKIP_UNTIL) continue;
            all[n++] = end;
        }
        return java.util.Arrays.copyOf(all, n);
    }

    static boolean sameDay(long a, long b) {
        return startOfDay(a) == startOfDay(b);
    }

    /** "14:00" today, or "Sat 14:00" on another day. */
    static String lockEndLabel(Context c, long until, long now) {
        String time = hhmm(minuteOfDay(until));
        if (sameDay(until, now)) return time;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(until);
        String day = new java.text.DateFormatSymbols(c.getResources().getConfiguration().locale)
                .getShortWeekdays()[cal.get(Calendar.DAY_OF_WEEK)];
        return day + " " + time;
    }

    // ---- PIN ----

    static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        prefs(c).edit().putString(SALT, s).putString(PIN, hash(s, pin)).putInt(PIN_LEN, pin.length()).apply();
        unlock();
    }

    /** New PINs are this long, and the prompt checks one as soon as this many digits are in. */
    static final int PIN_DIGITS = 4;

    /** Digits in the saved PIN: the prompt checks after that many. A PIN from before this was kept counts as 4. */
    static int pinLength(Context c) {
        return prefs(c).getInt(PIN_LEN, PIN_DIGITS);
    }

    /** True for the right PIN. After MAX_TRIES wrong ones, every try fails for a minute. */
    static boolean checkPin(Context c, String pin) {
        long now = SystemClock.elapsedRealtime();
        if (now < coolUntil) return false;
        SharedPreferences p = prefs(c);
        boolean ok = pin != null && hash(p.getString(SALT, ""), pin).equals(p.getString(PIN, null));
        if (ok) {
            wrongTries = 0;
            unlock();
        } else if (++wrongTries >= MAX_TRIES) {
            wrongTries = 0;
            coolUntil = now + COOL_DOWN_MS;
        }
        return ok;
    }

    // ---- Profile passwords ----

    /** Whether this child's profile has its own password (opt-in, set by a parent). */
    static boolean kidHasPassword(SharedPreferences p, int id) {
        return p.contains("kid_" + id + "_pw");
    }

    static void setKidPassword(Context c, int id, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        prefs(c).edit().putString("kid_" + id + "_pw_salt", s).putString("kid_" + id + "_pw", hash(s, pin))
                .putInt("kid_" + id + "_pw_len", pin.length()).apply();
    }

    static void clearKidPassword(SharedPreferences p, int id) {
        p.edit().remove("kid_" + id + "_pw").remove("kid_" + id + "_pw_salt").remove("kid_" + id + "_pw_len").apply();
    }

    static int kidPasswordLength(SharedPreferences p, int id) {
        return p.getInt("kid_" + id + "_pw_len", PIN_DIGITS);
    }

    /**
     * True for the child's password, or for the parental PIN (a parent can always get into a profile). Wrong tries
     * share the PIN's counter, so guessing locks for a minute after MAX_TRIES. Does not open the TV's settings.
     */
    static boolean checkKidPassword(Context c, int id, String pw) {
        long now = SystemClock.elapsedRealtime();
        if (now < coolUntil) return false;
        SharedPreferences p = prefs(c);
        boolean ok = pw != null && (hash(p.getString("kid_" + id + "_pw_salt", ""), pw)
                .equals(p.getString("kid_" + id + "_pw", null))
                || hash(p.getString(SALT, ""), pw).equals(p.getString(PIN, null)));
        if (ok) {
            wrongTries = 0;
        } else if (++wrongTries >= MAX_TRIES) {
            wrongTries = 0;
            coolUntil = now + COOL_DOWN_MS;
        }
        return ok;
    }

    static boolean coolingDown() {
        return SystemClock.elapsedRealtime() < coolUntil;
    }

    static boolean unlocked() {
        return SystemClock.elapsedRealtime() < unlockedUntil;
    }

    private static void unlock() {
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS;
    }

    static void lockAgain() {
        unlockedUntil = 0;
    }

    /** Restarts the unlock window: a parent still busy in the settings. */
    static void keepUnlocked() {
        if (unlocked()) unlock();
    }

    static boolean validPin(String pin) {
        return pin != null && pin.matches("[0-9]{4,8}");
    }

    static String hash(String salt, String pin) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return hex(d.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    /** Runs `go` at once while no PIN is set or it was entered a moment ago; otherwise after the PIN. */
    static void withPin(Context c, Runnable go) {
        if (!pinNeeded(c) || !(c instanceof Activity)) {
            go.run();
            return;
        }
        PinDialog.ask((Activity) c, R.string.parental_enter_pin, go, null);
    }

    // ---- Children ----

    /** Child ids, in the order they were added. */
    static List<Integer> kids(SharedPreferences p) {
        List<Integer> ids = new ArrayList<>();
        for (String s : p.getString(KID_IDS, "").split(",")) {
            if (!s.isEmpty()) ids.add(Integer.parseInt(s));
        }
        return ids;
    }

    static String kidName(SharedPreferences p, int id) {
        return p.getString("kid_" + id + "_name", "?");
    }

    /** The child's avatar emoji, or "" for none. */
    static String kidEmoji(SharedPreferences p, int id) {
        return p.getString("kid_" + id + "_emoji", "");
    }

    static void setKidEmoji(SharedPreferences p, int id, String emoji) {
        if (emoji == null || emoji.isEmpty()) p.edit().remove("kid_" + id + "_emoji").apply();
        else p.edit().putString("kid_" + id + "_emoji", emoji).apply();
    }

    /** The child's profile colour: the one picked, else a preset from their id so every avatar has one. */
    static int kidColor(SharedPreferences p, int id) {
        return p.getInt("kid_" + id + "_color",
                AvatarDrawable.COLORS[Math.floorMod(id - 1, AvatarDrawable.COLORS.length)]);
    }

    static void setKidColor(SharedPreferences p, int id, int color) {
        p.edit().putInt("kid_" + id + "_color", color).apply();
    }

    /** The child's avatar: their emoji, or their first letter, on their colour. */
    static AvatarDrawable kidAvatar(SharedPreferences p, int id, int sizePx) {
        String e = kidEmoji(p, id);
        String name = kidName(p, id);
        String label = !e.isEmpty() ? e : name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
        return new AvatarDrawable(kidColor(p, id), label, sizePx);
    }

    /** "🐼 Ann", or the name alone without an avatar. */
    static String kidDisplay(SharedPreferences p, int id) {
        String e = kidEmoji(p, id);
        return e.isEmpty() ? kidName(p, id) : e + " " + kidName(p, id);
    }

    /** Today's minutes, 0 for no limit. */
    static int kidLimit(SharedPreferences p, int id) {
        return kidLimitOn(p, id, Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
    }

    /** Minutes on a Calendar.DAY_OF_WEEK (1 Sunday to 7 Saturday): that day's own limit, else the every-day one. */
    static int kidLimitOn(SharedPreferences p, int id, int dayOfWeek) {
        return p.getInt("kid_" + id + "_limit_d" + dayOfWeek, p.getInt("kid_" + id + "_limit", 0));
    }

    /** The week's limits, index 0 Sunday to 6 Saturday. */
    static int[] kidWeek(SharedPreferences p, int id) {
        int[] w = new int[7];
        for (int d = 1; d <= 7; d++) w[d - 1] = kidLimitOn(p, id, d);
        return w;
    }

    /** Sets these days (Calendar.DAY_OF_WEEK) only. */
    static void setKidLimitOn(SharedPreferences p, int id, int[] days, int limitMin) {
        SharedPreferences.Editor e = p.edit();
        for (int d : days) e.putInt("kid_" + id + "_limit_d" + d, limitMin);
        e.apply();
    }

    static final int SAME_EVERY_DAY = 0, WEEKDAYS_AND_WEEKEND = 1, BY_DAY = 2;

    /** How a week of limits (index 0 Sunday) reads: one number, weekdays plus weekend, or day by day. */
    static int weekShape(int[] w) {
        boolean weekdays = w[1] == w[2] && w[2] == w[3] && w[3] == w[4] && w[4] == w[5];
        boolean weekend = w[0] == w[6];
        if (weekdays && weekend && w[1] == w[0]) return SAME_EVERY_DAY;
        return weekdays && weekend ? WEEKDAYS_AND_WEEKEND : BY_DAY;
    }

    static int addKid(SharedPreferences p, String name, int limitMin) {
        int id = p.getInt(NEXT_KID, 1);
        List<Integer> ids = kids(p);
        ids.add(id);
        p.edit().putInt(NEXT_KID, id + 1).putString(KID_IDS, join(ids)).putString("kid_" + id + "_name", name)
                .putInt("kid_" + id + "_limit", limitMin).apply();
        return id;
    }

    static void renameKid(SharedPreferences p, int id, String name) {
        p.edit().putString("kid_" + id + "_name", name).apply();
    }

    /** The same limit every day: drops any day's own limit. */
    static void setKidLimit(SharedPreferences p, int id, int limitMin) {
        SharedPreferences.Editor e = p.edit().putInt("kid_" + id + "_limit", limitMin);
        for (int d = 1; d <= 7; d++) e.remove("kid_" + id + "_limit_d" + d);
        e.apply();
    }

    static void removeKid(SharedPreferences p, int id) {
        List<Integer> ids = kids(p);
        ids.remove(Integer.valueOf(id));
        SharedPreferences.Editor e = p.edit().putString(KID_IDS, join(ids))
                .remove("kid_" + id + "_name").remove("kid_" + id + "_limit");
        for (int d = 1; d <= 7; d++) e.remove("kid_" + id + "_limit_d" + d);
        e.remove("kid_" + id + "_apps").remove("kid_" + id + "_emoji").remove("kid_" + id + "_color")
                .remove("kid_" + id + "_pw").remove("kid_" + id + "_pw_salt").remove("kid_" + id + "_pw_len");
        if (p.getInt(VIEWER, NOBODY) == id) e.putInt(VIEWER, NOBODY);
        e.apply();
    }

    // ---- Allowed apps ----

    /** The apps this child may use, or null when every app is allowed (no list set). */
    static Set<String> kidApps(SharedPreferences p, int id) {
        String s = p.getString("kid_" + id + "_apps", null);
        if (s == null) return null;
        Set<String> out = new HashSet<>();
        for (String pkg : s.split(",")) if (!pkg.isEmpty()) out.add(pkg);
        return out;
    }

    /** Sets the child's allowed apps; null (or an empty set) allows every app again. */
    static void setKidApps(SharedPreferences p, int id, Set<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            p.edit().remove("kid_" + id + "_apps").apply();
            return;
        }
        StringBuilder s = new StringBuilder();
        for (String pkg : pkgs) {
            if (s.length() > 0) s.append(',');
            s.append(pkg);
        }
        p.edit().putString("kid_" + id + "_apps", s.toString()).apply();
    }

    /** Whether this package may open for this child: no list, or the list names it. */
    static boolean allowedFor(Set<String> kidApps, String pkg) {
        return kidApps == null || kidApps.contains(pkg);
    }

    /** For Home's tiles: the current child's allowed apps, or null when Home shows every app. */
    static Set<String> homeApps(Context c) {
        if (!hasPin(c)) return null;
        SharedPreferences p = prefs(c);
        int v = viewer(p);
        return v >= 0 ? kidApps(p, v) : null;
    }

    /** Changes when Home's tiles should change: who is watching, and their allowed apps. */
    static String homeKey(Context c) {
        if (!hasPin(c)) return "";
        SharedPreferences p = prefs(c);
        int v = viewer(p);
        return v + ":" + (v >= 0 ? p.getString("kid_" + v + "_apps", "*") : "*");
    }

    /**
     * The current child may not use this app: says so, and (for an app already on screen, opened with the
     * remote's own button) goes Home. False when the app is fine, there is no child picked, or it never counts.
     */
    static boolean blockIfNotAllowed(Context c, String pkg, boolean sendHome) {
        if (!hasPin(c) || !counted(c, pkg)) return false;
        SharedPreferences p = prefs(c);
        int v = viewer(p);
        if (v < 0 || allowedFor(kidApps(p, v), pkg)) return false;
        android.widget.Toast.makeText(c, c.getString(R.string.parental_not_allowed, kidName(p, v)),
                android.widget.Toast.LENGTH_LONG).show();
        if (sendHome) {
            try {
                c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(c.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
        return true;
    }

    /** The Home pill's text: the child's name, "Parent", or "Who's watching?"; null hides it (no children). */
    static String viewerLabel(Context c) {
        if (!hasPin(c)) return null;
        SharedPreferences p = prefs(c);
        if (kids(p).isEmpty()) return null;
        int v = viewer(p);
        return v >= 0 ? kidName(p, v) : c.getString(v == PARENT ? R.string.parental_parent : R.string.parental_who_short);
    }

    /** The Home pill's icon: the child's avatar, or null for the plain person icon (Parent, nobody). */
    static AvatarDrawable viewerAvatar(Context c, int sizePx) {
        if (!hasPin(c)) return null;
        SharedPreferences p = prefs(c);
        int v = viewer(p);
        return v >= 0 ? kidAvatar(p, v, sizePx) : null;
    }

    private static String join(List<Integer> ids) {
        StringBuilder s = new StringBuilder();
        for (int id : ids) {
            if (s.length() > 0) s.append(',');
            s.append(id);
        }
        return s.toString();
    }

    // ---- Who is watching ----

    /**
     * The child id, PARENT, or NOBODY ("Who's watching?"). The TV having been off since the last pick (a reboot,
     * or standby long enough to suspend) or three hours on the same pick ends it, so whoever turns the TV on next
     * picks their own name instead of watching on someone else's time.
     */
    static int viewer(SharedPreferences p) {
        int v = p.getInt(VIEWER, NOBODY);
        if (v == NOBODY) return NOBODY;
        int end = pickEnd(System.currentTimeMillis(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(),
                p.getLong(VIEWER_WALL, 0), p.getLong(VIEWER_ELAPSED, 0), p.getLong(VIEWER_UPTIME, 0));
        if (end != PICK_GOES_ON || (v >= 0 && !kids(p).contains(v))) {
            setViewer(p, NOBODY);
            return NOBODY;
        }
        return v;
    }

    static final int PICK_GOES_ON = 0, PICK_TIMED_OUT = 1, PICK_TV_WAS_OFF = 2;

    /**
     * Whether a pick made at (pickWall, pickElapsed, pickUptime) still holds: the TV was off since (elapsed went
     * backwards in a reboot, or ran ahead of uptime while the TV slept), it timed out after VIEWER_MS, or it holds.
     */
    static int pickEnd(long wall, long elapsed, long uptime, long pickWall, long pickElapsed, long pickUptime) {
        if (elapsed < pickElapsed || (elapsed - pickElapsed) - (uptime - pickUptime) > SLEEP_ENDS_PICK_MS) {
            return PICK_TV_WAS_OFF;
        }
        if (wall - pickWall >= VIEWER_MS || wall < pickWall) return PICK_TIMED_OUT;
        return PICK_GOES_ON;
    }

    /** Back-compat for callers that only need to know whether the pick is over. */
    static boolean pickEnded(long wall, long elapsed, long uptime, long pickWall, long pickElapsed, long pickUptime) {
        return pickEnd(wall, elapsed, uptime, pickWall, pickElapsed, pickUptime) != PICK_GOES_ON;
    }



    /** Parent is the one watching: nothing is blocked, and no PIN is asked (picking Parent took the PIN). */
    static boolean parentWatching(Context c) {
        return hasPin(c) && viewer(prefs(c)) == PARENT;
    }

    /** Whether opening something behind the PIN asks for it now: a PIN is set, it was not just entered, and no
     *  parent is watching. */
    static boolean pinNeeded(Context c) {
        return hasPin(c) && !unlocked() && !parentWatching(c);
    }

    /**
     * The TV is switching off, coming back on, or just booted: nobody is watching any more, so the Home pill reads
     * "Who's watching?" and the next app asks for a name. Nothing happens without children or a PIN.
     */
    static void onScreenOn(Context c) {
        if (!hasPin(c)) return;
        SharedPreferences p = prefs(c);
        if (!kids(p).isEmpty() && p.getInt(VIEWER, NOBODY) != NOBODY) setViewer(p, NOBODY);
    }

    /** Back to this launcher's Home, with parental controls set up; the foreground app is left behind. */
    static void goHome(Context c) {
        if (!hasPin(c)) return;
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(c.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /** Picks who is watching from now on; time from here is charged to that child. */
    static void setViewer(SharedPreferences p, int v) {
        long now = System.currentTimeMillis();
        String segs = pickSegment(p.getString(SEGMENTS, ""), v, now, Parental.startOfDay(now));
        p.edit().putInt(VIEWER, v).putLong(VIEWER_WALL, now).putLong(VIEWER_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(VIEWER_UPTIME, SystemClock.uptimeMillis()).putString(SEGMENTS, segs).commit();
        // commit, not apply: another process (Home's pill) reads the file next, so it must be on disk now.
    }

    /**
     * The pick log, "id,start,end;" with end 0 for the open one: closes the open segment at `now`, opens one
     * for a child (not for PARENT or NOBODY), and drops segments that ended before `dayStart`.
     */
    static String pickSegment(String segs, int v, long now, long dayStart) {
        StringBuilder out = new StringBuilder();
        for (long[] s : parseSegments(segs)) {
            long end = s[2] == 0 ? now : s[2];
            if (end < dayStart) continue;
            out.append(s[0]).append(',').append(s[1]).append(',').append(end).append(';');
        }
        if (v >= 0) out.append(v).append(',').append(now).append(",0;");
        return out.toString();
    }

    static List<long[]> parseSegments(String segs) {
        List<long[]> out = new ArrayList<>();
        for (String part : segs.split(";")) {
            String[] f = part.split(",");
            if (f.length != 3) continue;
            try {
                out.add(new long[]{Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Milliseconds of the counted intervals that fall in this child's segments (an open one runs to `now`). */
    static long usedBy(int kid, List<long[]> intervals, List<long[]> segs, long now) {
        long total = 0;
        for (long[] s : segs) {
            if (s[0] != kid) continue;
            long end = s[2] == 0 ? now : s[2];
            for (long[] i : intervals) total += Math.max(0, Math.min(end, i[1]) - Math.max(s[1], i[0]));
        }
        return total;
    }

    // ---- Rules (pure, unit-tested) ----

    /** minute in [start, end), a window that may run past midnight. Off when start is OFF or start == end. */
    static boolean inWindow(int minute, int start, int end) {
        if (start < 0 || end < 0 || start == end) return false;
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    /** FREE, BEDTIME or LIMIT_REACHED for the time of day and the minutes used today. */
    static int state(int minuteOfDay, long usedMs, long now, int bedStart, int bedEnd, int limitMin,
                     long extraUntil) {
        if (extraUntil > now) return FREE;
        if (inWindow(minuteOfDay, bedStart, bedEnd)) return BEDTIME;
        if (limitMin > 0 && usedMs >= limitMin * 60_000L) return LIMIT_REACHED;
        return FREE;
    }

    /**
     * Foreground time of the counted packages between `from` and `now`, from usage events in time order
     * (type 1 foreground, 2 background, anything else ignored). front[0] gets the package still in front at
     * `now`, or null; `intervals` (may be null) gets each counted [start, end]. A package that came to the front
     * before `from` counts only from `from`.
     */
    static long used(long[] times, String[] pkgs, int[] types, int n, long from, long now,
                     Predicate<String> counted, String[] front, List<long[]> intervals) {
        return used(times, pkgs, null, types, n, from, now, counted, front, intervals);
    }

    /**
     * As above, with each event's activity class (`classes`, may be null). A background event ends the app only
     * when it is for the activity in front: an app opening a second screen of its own (SmartTube's dialogs)
     * sends the first screen's background event after the new screen's foreground one, and that used to read as
     * the app leaving, so nothing counted as in front and bedtime never covered it.
     */
    static long used(long[] times, String[] pkgs, String[] classes, int[] types, int n, long from, long now,
                     Predicate<String> counted, String[] front, List<long[]> intervals) {
        long total = 0;
        String cur = null, curClass = null;
        long start = 0;
        for (int i = 0; i < n; i++) {
            String cls = classes == null ? null : classes[i];
            if (types[i] == 1) {
                // Another screen of the app already in front is not a new start: keep counting from its start.
                if (cur != null && !pkgs[i].equals(cur)) {
                    total += overlap(cur, start, times[i], from, now, counted, intervals);
                    start = times[i];
                } else if (cur == null) {
                    start = times[i];
                }
                cur = pkgs[i];
                curClass = cls;
            } else if (types[i] == 2 && pkgs[i].equals(cur)
                    && (cls == null || curClass == null || cls.equals(curClass))) {
                total += overlap(cur, start, times[i], from, now, counted, intervals);
                cur = null;
                curClass = null;
            }
        }
        if (cur != null) total += overlap(cur, start, now, from, now, counted, intervals);
        if (front != null) front[0] = cur;
        return total;
    }

    private static long overlap(String pkg, long start, long end, long from, long now, Predicate<String> counted,
                                List<long[]> intervals) {
        if (!counted.test(pkg)) return 0;
        long a = Math.max(start, from), b = Math.min(end, now);
        if (b <= a) return 0;
        if (intervals != null) intervals.add(new long[]{a, b});
        return b - a;
    }

    static String hhmm(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    // ---- On the TV ----

    /** Exempt from the rules: the launcher, system UI, settings (behind the PIN instead) and the config list. */
    static boolean counted(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName()) || pkg.equals("android")
                || pkg.equals("com.android.systemui") || isSettings(c, pkg)) return false;
        return !Arrays.asList(c.getResources().getStringArray(R.array.parental_exempt_apps)).contains(pkg);
    }

    private static Set<String> settingsPkgs;

    static boolean isSettings(Context c, String pkg) {
        if (settingsPkgs == null) {
            Set<String> s = new HashSet<>(Arrays.asList(
                    c.getResources().getStringArray(R.array.parental_locked_settings)));
            ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
            if (r != null && r.activityInfo != null) s.add(r.activityInfo.packageName);
            settingsPkgs = s;
        }
        return pkg != null && settingsPkgs.contains(pkg);
    }

    /** The package an intent opens, or null. */
    static String target(Context c, Intent i) {
        if (i.getComponent() != null) return i.getComponent().getPackageName();
        if (i.getPackage() != null) return i.getPackage();
        ResolveInfo r = c.getPackageManager().resolveActivity(i, 0);
        return r == null || r.activityInfo == null ? null : r.activityInfo.packageName;
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static long startOfDay(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    static int minuteOfDay(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    /** Today's counted foreground time, read from usage events. Empty without usage access. */
    static final class Usage {
        long total;
        String front;
        final List<long[]> intervals = new ArrayList<>();

        /** This child's milliseconds today. */
        long by(SharedPreferences p, int kid, long now) {
            return usedBy(kid, intervals, parseSegments(p.getString(SEGMENTS, "")), now);
        }
    }

    @SuppressWarnings("deprecation")
    static Usage usage(Context c, long now) {
        Usage u = new Usage();
        UsageStatsManager m = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (m == null || !hasUsageAccess(c)) return u;
        long day = startOfDay(now);
        // From six hours before midnight, so an app already in front at midnight is still seen.
        UsageEvents ev = m.queryEvents(day - 6 * 3_600_000L, now);
        if (ev == null) return u;
        List<UsageEvents.Event> list = new ArrayList<>();
        while (ev.hasNextEvent()) {
            UsageEvents.Event e = new UsageEvents.Event();
            ev.getNextEvent(e);
            int t = e.getEventType();
            if (t == UsageEvents.Event.MOVE_TO_FOREGROUND || t == UsageEvents.Event.MOVE_TO_BACKGROUND) list.add(e);
        }
        int n = list.size();
        long[] times = new long[n];
        String[] pkgs = new String[n];
        String[] classes = new String[n];
        int[] types = new int[n];
        for (int i = 0; i < n; i++) {
            UsageEvents.Event e = list.get(i);
            times[i] = e.getTimeStamp();
            pkgs[i] = e.getPackageName();
            classes[i] = e.getClassName();
            types[i] = e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND ? 1 : 2;
        }
        String[] front = new String[1];
        u.total = used(times, pkgs, classes, types, n, day, now, p -> counted(c, p), front, u.intervals);
        u.front = front[0];
        return u;
    }

    /** The rule in force now, the viewer it was worked out for, and the milliseconds left (-1: no limit). */
    static final class Status {
        int state = FREE;
        int viewer = NOBODY;
        long leftMs = -1;
        /** Milliseconds until the TV locks for this viewer if they keep watching, -1 when nothing is coming. */
        long untilLockMs = -1;
        Usage usage;
    }

    static Status status(Context c, boolean needFront) {
        Status s = new Status();
        if (!enabled(c)) return s;
        SharedPreferences p = prefs(c);
        if (!anyRule(p)) return s;
        long now = System.currentTimeMillis();
        List<Integer> kids = kids(p);
        s.viewer = viewer(p);
        int limit = s.viewer >= 0 ? kidLimit(p, s.viewer) : 0;
        if (limit > 0 || needFront) s.usage = usage(c, now);
        long used = limit <= 0 ? 0 : s.usage.by(p, s.viewer, now);
        if (limit > 0) s.leftMs = Math.max(0, limit * 60_000L - used);
        if (s.viewer == PARENT) return s;
        // A lock comes before everything else, extra time and the pick included.
        if (lockedUntil(p, now) > 0) {
            s.state = LOCKED;
            return s;
        }
        s.state = state(minuteOfDay(now), used, now, p.getInt(BED_START, OFF), p.getInt(BED_END, DEFAULT_BED_END),
                limit, p.getLong(EXTRA_UNTIL, 0));
        // Who is watching comes first, extra time or not: extra time lifts bedtime and the limits, but the time
        // still belongs to a child. (Skipping the pick during extra time left nobody charged and no picker shown.)
        if (s.state != BEDTIME && s.state != LIMIT_REACHED && !kids.isEmpty() && s.viewer == NOBODY) {
            s.state = NEED_VIEWER;
        }
        if (s.state == FREE) {
            int bedStart = p.getInt(BED_START, OFF), bedEnd = p.getInt(BED_END, DEFAULT_BED_END);
            long extra = p.getLong(EXTRA_UNTIL, 0);
            boolean extraEndsLocked = extra > now && (inWindow(minuteOfDay(extra), bedStart, bedEnd)
                    || (limit > 0 && used + (extra - now) >= limit * 60_000L));
            s.untilLockMs = untilLock(now, s.leftMs, bedStart < 0 ? 0 : nextMinute(now, bedStart), extra,
                    extraEndsLocked);
        }
        return s;
    }

    static final long WARN_MS = 60_000L;

    /**
     * Milliseconds until the TV locks for someone who keeps watching, or -1: the end of extra time when it ends in
     * a lockout, otherwise the sooner of the limit running out (leftMs, -1 for none) and bedtime starting
     * (bedStartAt, 0 for no bedtime).
     */
    static long untilLock(long now, long leftMs, long bedStartAt, long extraUntil, boolean extraEndsLocked) {
        if (extraUntil > now) return extraEndsLocked ? extraUntil - now : -1;
        long best = leftMs;
        if (bedStartAt > now && (best < 0 || bedStartAt - now < best)) best = bedStartAt - now;
        return best;
    }

    /** The next wall-clock time at this minute of the day (today if still ahead, else tomorrow). */
    static long nextMinute(long now, int minuteOfDay) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    /**
     * "1 minute of TV left", once per coming lockout, when it is a minute away or less. The key is the minute the
     * lock falls on, so a second check in the same minute (the alarm and an app opened from Home) stays quiet.
     */
    static void warnIfClose(Context c, Status s) {
        if (s.state != FREE || s.untilLockMs <= 0 || s.untilLockMs > WARN_MS + 5_000L) return;
        SharedPreferences p = prefs(c);
        long lockMinute = (System.currentTimeMillis() + s.untilLockMs + 30_000L) / 60_000L;
        String key = s.viewer + ":" + lockMinute;
        if (key.equals(p.getString(WARNED, ""))) return;
        p.edit().putString(WARNED, key).apply();
        String text = s.viewer >= 0 ? c.getString(R.string.parental_one_minute_kid, kidName(p, s.viewer))
                : c.getString(R.string.parental_one_minute);
        android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show();
    }

    static int stateNow(Context c) {
        return status(c, false).state;
    }

    /**
     * Whether Home may open this package now. When a rule is in force, opens TimesUpActivity (or, with children
     * set up and nobody picked, WhoActivity) instead and returns false. Always true with the switch off.
     */
    static boolean allowApp(Context c, String pkg) {
        if (!enabled(c) || !counted(c, pkg)) return true;
        Status st = status(c, false);
        int s = st.state;
        if (s == FREE) {
            if (blockIfNotAllowed(c, pkg, false)) return false;
            warnIfClose(c, st);
            schedule(c);
            return true;
        }
        c.startActivity(s == NEED_VIEWER ? WhoActivity.intent(c, pkg) : TimesUpActivity.intent(c, s));
        return false;
    }

    /** Opens a settings intent behind the PIN and an app intent behind the rules; `go` does the opening. */
    static void guard(Context c, Intent i, Runnable go) {
        if (!enabled(c)) {
            go.run();
            return;
        }
        String pkg = target(c, i);
        if (isSettings(c, pkg)) withPin(c, go);
        else if (allowApp(c, pkg)) go.run();
    }

    /** Keeps one alarm for the next moment a rule could start, or cancels it when there are no rules. */
    static void schedule(Context c) {
        if (!enabled(c)) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, ParentalCheck.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences p = prefs(c);
        if (!anyRule(p)) {
            am.cancel(pi);
            return;
        }
        long now = System.currentTimeMillis();
        Status s = status(c, false);
        long next;
        if (s.state != FREE) {
            next = now + LOCKED_CHECK_MS;
        } else {
            next = now + MAX_SLEEP_MS;
            long extra = p.getLong(EXTRA_UNTIL, 0);
            if (extra > now) next = Math.min(next, extra);
            if (s.leftMs >= 0) next = Math.min(next, now + Math.max(LOCKED_CHECK_MS, s.leftMs));
            if (s.viewer != NOBODY) next = Math.min(next, p.getLong(VIEWER_WALL, now) + VIEWER_MS);
            int bedStart = p.getInt(BED_START, OFF);
            if (bedStart >= 0) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(now);
                cal.set(Calendar.HOUR_OF_DAY, bedStart / 60);
                cal.set(Calendar.MINUTE, bedStart % 60);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_MONTH, 1);
                next = Math.min(next, cal.getTimeInMillis());
            }
            // A minute before the lock, for the "1 minute left" toast; the lock itself is the alarm after that.
            if (s.untilLockMs > WARN_MS + 1_000L) next = Math.min(next, now + s.untilLockMs - WARN_MS);
            next = Math.max(next, now + 1_000L);
        }
        // RTC, not RTC_WAKEUP: a TV in standby is not woken for this.
        am.setExact(AlarmManager.RTC, next, pi);
    }
}
