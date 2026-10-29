package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FuseLimiterTest {
    @Test
    void aClickInsideTheIntervalIsBlockedAndOneAfterItPasses() {
        FuseLimiter l = new FuseLimiter(1500, 0, 30_000);
        assertTrue(l.click(10_000).allowed());
        FuseLimiter.Verdict v = l.click(10_900);
        assertFalse(v.allowed(), "900 ms after the last fuse");
        assertEquals(600, v.waitMs());
        assertFalse(l.click(11_499).allowed());
        assertTrue(l.click(11_500).allowed(), "1,500 ms after the last accepted fuse");
    }

    @Test
    void blockedClicksDoNotCountSoSpammingDoesNotLockYouOut() {
        FuseLimiter l = new FuseLimiter(1500, 0, 30_000);
        assertTrue(l.click(0).allowed());
        for (long t = 100; t < 1500; t += 100) assertFalse(l.click(t).allowed());
        assertTrue(l.click(1500).allowed(), "measured from the last fuse that went through, not the last click");
    }

    /** The user's kick: 12 fusions in 9 seconds. With the defaults that can't happen. */
    @Test
    void theRollingCapHolds() {
        FuseLimiter l = new FuseLimiter(1500, 10, 30_000);
        int accepted = 0;
        long firstBlockedByCap = -1;
        for (long t = 0; t < 30_000; t += 1500) {
            FuseLimiter.Verdict v = l.click(t);
            if (v.allowed()) accepted++;
            else if (firstBlockedByCap < 0) firstBlockedByCap = t;
        }
        assertEquals(10, accepted, "at most 10 in any 30 s");
        assertEquals(15_000, firstBlockedByCap, "the 11th, at 15 s, is held back");
        assertFalse(l.click(29_999).allowed());
        assertTrue(l.click(30_000).allowed(), "the first one has left the 30 s window");

        FuseLimiter burst = new FuseLimiter(1500, 10, 30_000);
        int inNineSeconds = 0;
        for (long t = 0; t <= 9_000; t += 750) if (burst.click(t).allowed()) inNineSeconds++; // 12 clicks in 9 s, as in the log
        assertTrue(inNineSeconds <= 7, "12 fuse clicks in 9 s get through only " + inNineSeconds);
    }

    @Test
    void settingsApplyAndZeroMeansNoLimit() {
        FuseLimiter l = new FuseLimiter(1500, 10, 30_000);
        l.setLimits(0, 0);
        for (long t = 0; t < 100; t++) assertTrue(l.click(t).allowed());
    }

    @Test
    void onlyFuseClicksInFusionScreensAreLimited() {
        assertTrue(FuseLimiter.isFuseClick("Confirm Fusion", "Fuse", true, false));
        assertTrue(FuseLimiter.isFuseClick("§8Confirm Fusion", "Confirm", true, false));
        assertFalse(FuseLimiter.isFuseClick("Confirm Fusion", "Cancel", true, false));
        assertFalse(FuseLimiter.isFuseClick("Confirm Fusion", "Go Back", true, false));
        assertFalse(FuseLimiter.isFuseClick("Confirm Fusion", "Close", true, false));
        assertFalse(FuseLimiter.isFuseClick("Fusion Box", "Fusion Information", true, false));
        assertTrue(FuseLimiter.isFuseClick("Fusion Box", "§aRepeat Previous Fusion", true, false));
        assertFalse(FuseLimiter.isFuseClick("Fusion Box", "Lunar Moth Shard", true, false), "picking shards is not limited");
        assertFalse(FuseLimiter.isFuseClick("Confirm Fusion", "Fuse", false, false), "your own inventory is not limited");
        assertFalse(FuseLimiter.isFuseClick("Confirm Fusion", "", true, true), "empty slots are not limited");
        assertFalse(FuseLimiter.isFuseClick("Bazaar", "Repeat Previous Fusion", true, false), "only Fusion screens");
    }
}
