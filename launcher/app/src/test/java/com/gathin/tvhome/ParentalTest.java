package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ParentalTest {
    private static final long MIN = 60_000L;

    @Test
    public void bedtimeWindowRunsPastMidnight() {
        int start = 21 * 60, end = 7 * 60;
        assertTrue(Parental.inWindow(21 * 60, start, end));
        assertTrue(Parental.inWindow(23 * 60 + 59, start, end));
        assertTrue(Parental.inWindow(0, start, end));
        assertTrue(Parental.inWindow(6 * 60 + 59, start, end));
        assertFalse(Parental.inWindow(7 * 60, start, end));
        assertFalse(Parental.inWindow(20 * 60 + 59, start, end));
    }

    @Test
    public void sameDayWindowAndOff() {
        assertTrue(Parental.inWindow(13 * 60, 12 * 60, 14 * 60));
        assertFalse(Parental.inWindow(14 * 60, 12 * 60, 14 * 60));
        assertFalse(Parental.inWindow(13 * 60, Parental.OFF, 14 * 60));
        assertFalse(Parental.inWindow(13 * 60, 600, 600));
    }

    @Test
    public void stateOrderExtraTimeThenBedtimeThenLimit() {
        long now = 1_000_000;
        assertEquals(Parental.FREE, Parental.state(600, 0, now, Parental.OFF, 420, 0, 0));
        assertEquals(Parental.BEDTIME, Parental.state(22 * 60, 0, now, 21 * 60, 420, 60, 0));
        assertEquals(Parental.LIMIT_REACHED, Parental.state(600, 60 * MIN, now, 21 * 60, 420, 60, 0));
        assertEquals(Parental.FREE, Parental.state(600, 59 * MIN, now, 21 * 60, 420, 60, 0));
        assertEquals(Parental.FREE, Parental.state(22 * 60, 99 * MIN, now, 21 * 60, 420, 60, now + 1));
    }

    @Test
    public void usedCountsOnlyCountedAppsFromMidnight() {
        // Netflix since 23:30 the day before, Home at 00:10, YouTube 00:20 to 00:50, the launcher again.
        long day = 100 * 24 * 60 * MIN;
        long[] t = {day - 30 * MIN, day + 10 * MIN, day + 10 * MIN, day + 20 * MIN, day + 20 * MIN, day + 50 * MIN};
        String[] p = {"netflix", "netflix", "home", "home", "youtube", "youtube"};
        int[] ty = {1, 2, 1, 2, 1, 2};
        String[] front = new String[1];
        long used = Parental.used(t, p, ty, t.length, day, day + 60 * MIN, pkg -> !pkg.equals("home"), front, null);
        assertEquals(40 * MIN, used);
        assertNull(front[0]);
    }

    @Test
    public void appStillInFrontCountsUntilNow() {
        long[] t = {0, 5 * MIN};
        String[] p = {"youtube", "netflix"};
        int[] ty = {1, 1};
        String[] front = new String[1];
        long used = Parental.used(t, p, ty, 2, 0, 20 * MIN, pkg -> true, front, null);
        assertEquals(20 * MIN, used);
        assertEquals("netflix", front[0]);
    }

    @Test
    public void backgroundOfAnEarlierActivityDoesNotEndTheNewApp() {
        // B comes to the front before A's background event arrives.
        long[] t = {0, 10 * MIN, 10 * MIN};
        String[] p = {"a", "b", "a"};
        int[] ty = {1, 1, 2};
        String[] front = new String[1];
        assertEquals(30 * MIN, Parental.used(t, p, ty, 3, 0, 30 * MIN, pkg -> true, front, null));
        assertEquals("b", front[0]);
    }

    @Test
    public void pinRulesAndHash() {
        assertTrue(Parental.validPin("1234"));
        assertTrue(Parental.validPin("12345678"));
        assertFalse(Parental.validPin("123"));
        assertFalse(Parental.validPin("123456789"));
        assertFalse(Parental.validPin("12a4"));
        assertEquals(Parental.hash("s", "1234"), Parental.hash("s", "1234"));
        assertNotEquals(Parental.hash("s", "1234"), Parental.hash("t", "1234"));
        assertEquals("21:00", Parental.hhmm(21 * 60));
        assertEquals("07:30", Parental.hhmm(7 * 60 + 30));
    }
    @Test
    public void eachChildIsChargedOnlyForTheTimeTheyWerePicked() {
        // Child 1 picked at 0, child 2 at 30 min, nobody (a parent) at 50 min; an app in front 10 to 60 min.
        long day = 0;
        String segs = Parental.pickSegment("", 1, 0, day);
        segs = Parental.pickSegment(segs, 2, 30 * MIN, day);
        segs = Parental.pickSegment(segs, Parental.PARENT, 50 * MIN, day);
        List<long[]> intervals = new ArrayList<>();
        long total = Parental.used(new long[]{10 * MIN, 60 * MIN}, new String[]{"tv", "tv"}, new int[]{1, 2}, 2,
                day, 70 * MIN, pkg -> true, null, intervals);
        assertEquals(50 * MIN, total);
        List<long[]> parsed = Parental.parseSegments(segs);
        assertEquals(20 * MIN, Parental.usedBy(1, intervals, parsed, 70 * MIN));
        assertEquals(20 * MIN, Parental.usedBy(2, intervals, parsed, 70 * MIN));
    }

    @Test
    public void anOpenPickRunsUntilNow() {
        String segs = Parental.pickSegment("", 3, 0, 0);
        List<long[]> intervals = new ArrayList<>();
        intervals.add(new long[]{5 * MIN, 25 * MIN});
        assertEquals(15 * MIN, Parental.usedBy(3, intervals, Parental.parseSegments(segs), 20 * MIN));
    }

    @Test
    public void yesterdaysPicksAreDropped() {
        long day = 1000 * MIN;
        String segs = Parental.pickSegment("", 1, day - 100 * MIN, 0);
        segs = Parental.pickSegment(segs, 2, day - 50 * MIN, 0);
        // Child 2's pick runs past midnight and is kept; child 1's ended before it.
        segs = Parental.pickSegment(segs, 1, day + 10 * MIN, day);
        List<long[]> parsed = Parental.parseSegments(segs);
        assertEquals(2, parsed.size());
        assertEquals(2, parsed.get(0)[0]);
        assertEquals(1, parsed.get(1)[0]);
        assertEquals(0, parsed.get(1)[2]);
    }

    @Test
    public void aPickEndsWhenTheTvSleptAfterThreeHoursOrAReboot() {
        // Awake for an hour: still the same viewer.
        assertFalse(Parental.pickEnded(60 * MIN, 60 * MIN, 60 * MIN, 0, 0, 0));
        // Three hours later.
        assertTrue(Parental.pickEnded(180 * MIN, 180 * MIN, 180 * MIN, 0, 0, 0));
        // Elapsed ran 30 minutes ahead of uptime: the TV was asleep.
        assertTrue(Parental.pickEnded(40 * MIN, 40 * MIN, 10 * MIN, 0, 0, 0));
        // Elapsed went backwards: a reboot.
        assertTrue(Parental.pickEnded(10 * MIN, 1 * MIN, 1 * MIN, 0, 5 * MIN, 5 * MIN));
    }

    @Test
    public void aWeekOfLimitsReadsAsOneNumberWeekdaysAndWeekendOrByDay() {
        // Index 0 is Sunday, as Calendar.DAY_OF_WEEK - 1.
        assertEquals(Parental.SAME_EVERY_DAY, Parental.weekShape(new int[]{60, 60, 60, 60, 60, 60, 60}));
        assertEquals(Parental.WEEKDAYS_AND_WEEKEND, Parental.weekShape(new int[]{120, 60, 60, 60, 60, 60, 120}));
        assertEquals(Parental.BY_DAY, Parental.weekShape(new int[]{120, 60, 60, 90, 60, 60, 120}));
        assertEquals(Parental.BY_DAY, Parental.weekShape(new int[]{120, 60, 60, 60, 60, 60, 90}));
    }

    @Test
    public void theLockComesFromTheLimitOrBedtimeWhicheverIsSooner() {
        long now = 1_000 * MIN;
        // 10 minutes left, bedtime in 30: the limit.
        assertEquals(10 * MIN, Parental.untilLock(now, 10 * MIN, now + 30 * MIN, 0, false));
        // 40 minutes left, bedtime in 30: bedtime.
        assertEquals(30 * MIN, Parental.untilLock(now, 40 * MIN, now + 30 * MIN, 0, false));
        // No limit, no bedtime: nothing is coming.
        assertEquals(-1, Parental.untilLock(now, -1, 0, 0, false));
        // No limit, bedtime in 5.
        assertEquals(5 * MIN, Parental.untilLock(now, -1, now + 5 * MIN, 0, false));
    }

    @Test
    public void extraTimeLocksAtItsEndOnlyWhenARuleIsWaiting() {
        long now = 1_000 * MIN;
        assertEquals(15 * MIN, Parental.untilLock(now, 0, now + 60 * MIN, now + 15 * MIN, true));
        assertEquals(-1, Parental.untilLock(now, 0, now + 60 * MIN, now + 15 * MIN, false));
    }

    @Test
    public void noListAllowsEveryAppAndAListOnlyItsOwn() {
        assertTrue(Parental.allowedFor(null, "com.netflix.ninja"));
        java.util.Set<String> list = new java.util.HashSet<>(java.util.Arrays.asList("com.google.android.youtube.tvkids"));
        assertTrue(Parental.allowedFor(list, "com.google.android.youtube.tvkids"));
        assertFalse(Parental.allowedFor(list, "com.netflix.ninja"));
    }

    @Test
    public void aTvThatWasOffIsToldApartFromATimedOutPick() {
        // Awake the whole hour: the pick goes on.
        assertEquals(Parental.PICK_GOES_ON, Parental.pickEnd(60 * MIN, 60 * MIN, 60 * MIN, 0, 0, 0));
        // Awake three hours on one pick: timed out, ask again.
        assertEquals(Parental.PICK_TIMED_OUT, Parental.pickEnd(180 * MIN, 180 * MIN, 180 * MIN, 0, 0, 0));
        // Thirty minutes of deep sleep in between: the TV was off.
        assertEquals(Parental.PICK_TV_WAS_OFF, Parental.pickEnd(40 * MIN, 40 * MIN, 10 * MIN, 0, 0, 0));
        // Elapsed went backwards: a reboot, even long after the pick.
        assertEquals(Parental.PICK_TV_WAS_OFF, Parental.pickEnd(500 * MIN, 1 * MIN, 1 * MIN, 0, 5 * MIN, 5 * MIN));
    }

    @Test
    public void anAppOpeningItsOwnSecondScreenStaysInFront() {
        // SmartTube on the BRAVIA: its main screen, then a dialog screen of its own, and only then the main
        // screen's background event. The app is still in front, and its time keeps counting.
        long[] t = {0, 10 * MIN, 10 * MIN};
        String[] p = {"tube", "tube", "tube"};
        String[] cls = {"Browse", "Dialog", "Browse"};
        int[] ty = {1, 1, 2};
        String[] front = new String[1];
        assertEquals(30 * MIN, Parental.used(t, p, cls, ty, 3, 0, 30 * MIN, pkg -> true, front, null));
        assertEquals("tube", front[0]);
    }

    @Test
    public void theBackgroundEventOfTheScreenInFrontStillEndsIt() {
        long[] t = {0, 10 * MIN};
        String[] p = {"tube", "tube"};
        String[] cls = {"Browse", "Browse"};
        int[] ty = {1, 2};
        String[] front = new String[1];
        assertEquals(10 * MIN, Parental.used(t, p, cls, ty, 2, 0, 30 * MIN, pkg -> true, front, null));
        assertNull(front[0]);
    }

    @Test
    public void extendingALockAddsToItsEndOrToNowOnceItHasEnded() {
        long now = 10_000 * MIN;
        assertEquals(now + 60 * MIN + 30 * MIN, Parental.extendLock(now, now + 60 * MIN, 30 * MIN));
        assertEquals(now + 30 * MIN, Parental.extendLock(now, now - 5 * MIN, 30 * MIN));
        assertEquals(now + 30 * MIN, Parental.extendLock(now, 0, 30 * MIN));
    }

    @Test
    public void customLockEndsAreEveryHalfHourForADayWithoutTomorrowsSmallHours() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(2026, java.util.Calendar.NOVEMBER, 7, 14, 10, 20);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long now = cal.getTimeInMillis();
        long[] ends = Parental.customEnds(now);
        // Today 14:30 to 23:30 (19), then tomorrow 06:30 to 14:00 (16): 00:00 to 06:00 tomorrow are left out.
        assertEquals(35, ends.length);
        assertEquals(14 * 60 + 30, Parental.minuteOfDay(ends[0]));
        assertTrue(ends[0] > now && ends[0] - now <= 30 * MIN);
        assertEquals(23 * 60 + 30, Parental.minuteOfDay(ends[18]));
        assertEquals(6 * 60 + 30, Parental.minuteOfDay(ends[19]));
        assertFalse(Parental.sameDay(ends[19], now));
        assertEquals(14 * 60, Parental.minuteOfDay(ends[34]));
        assertTrue(Parental.sameDay(ends[0], now));
        for (long end : ends) {
            assertFalse(!Parental.sameDay(end, now) && Parental.minuteOfDay(end) <= 6 * 60);
        }
        // Just before midnight the first end is tomorrow 06:30.
        cal.set(2026, java.util.Calendar.NOVEMBER, 7, 23, 45, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long late = cal.getTimeInMillis();
        long[] lateEnds = Parental.customEnds(late);
        assertEquals(6 * 60 + 30, Parental.minuteOfDay(lateEnds[0]));
        assertEquals(6 * 60 * MIN + 45 * MIN, lateEnds[0] - late);
        // After midnight, today's small hours are the next ones and stay.
        cal.set(2026, java.util.Calendar.NOVEMBER, 8, 1, 10, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long night = cal.getTimeInMillis();
        long[] nightEnds = Parental.customEnds(night);
        assertEquals(60 + 30, Parental.minuteOfDay(nightEnds[0]));
        assertTrue(Parental.sameDay(nightEnds[0], night));
    }
}
