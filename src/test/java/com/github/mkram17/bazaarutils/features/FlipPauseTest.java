package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlipPauseTest {
    @AfterEach
    void resume() {
        FlipPause.set(false, null);
    }

    @Test
    void whilePausedTheShownRowsStayExactlyAsTheyWereAndNothingIsComputed() {
        List<String> before = List.of("A", "B");
        assertSame(before, FlipPause.hold("list0", "", () -> before));
        FlipPause.set(true, null);
        AtomicInteger computed = new AtomicInteger();
        for (int frame = 0; frame < 100; frame++) {
            List<String> shown = FlipPause.hold("list0", "", () -> {
                computed.incrementAndGet();
                return List.of("B", "A", "C"); // re-sorted, new row: must not appear while paused
            });
            assertSame(before, shown);
        }
        assertEquals(0, computed.get(), "no compute while paused");
    }

    @Test
    void notPausedEveryFrameIsLive() {
        AtomicInteger computed = new AtomicInteger();
        for (int frame = 0; frame < 5; frame++) assertEquals(frame, (int) FlipPause.hold("list1", "", computed::getAndIncrement));
        assertEquals(5, computed.get());
    }

    @Test
    void aNewSearchWhilePausedFiltersOnceThenHoldsToo() {
        FlipPause.hold("list2", "", () -> "all");
        FlipPause.set(true, null);
        AtomicInteger computed = new AtomicInteger();
        assertEquals("wart", FlipPause.hold("list2", "wart", () -> { computed.incrementAndGet(); return "wart"; }));
        assertEquals("wart", FlipPause.hold("list2", "wart", () -> { computed.incrementAndGet(); return "changed"; }));
        assertEquals(1, computed.get());
    }

    @Test
    void unpausingRefreshesOnceRightAwayAndShowsFreshData() {
        FlipPause.hold("potential", null, () -> "old plan");
        AtomicInteger refreshes = new AtomicInteger();
        assertTrue(FlipPause.set(true, refreshes::incrementAndGet));
        assertEquals(0, refreshes.get(), "pausing doesn't refresh");
        assertFalse(FlipPause.set(true, refreshes::incrementAndGet), "already paused");
        assertTrue(FlipPause.set(false, refreshes::incrementAndGet));
        assertEquals(1, refreshes.get(), "one refresh on unpause");
        assertFalse(FlipPause.set(false, refreshes::incrementAndGet));
        assertEquals(1, refreshes.get());
        assertEquals("new plan", FlipPause.hold("potential", null, () -> "new plan"));
    }

    @Test
    void theCalculatorsDoNotStartARefreshWhilePaused() throws Exception {
        FlipPause.set(true, null);
        for (Class<?> c : List.of(FusionCalc.class, ForgeCalc.class)) {
            Field last = c.getDeclaredField("lastRefresh"), running = c.getDeclaredField("running");
            last.setAccessible(true);
            running.setAccessible(true);
            last.setLong(null, 0); // long overdue: would refresh at once if not paused
            c.getMethod("refreshIfStale").invoke(null);
            assertEquals(0, last.getLong(null), c.getSimpleName() + " started a refresh while paused");
            assertFalse(running.getBoolean(null), c.getSimpleName());
        }
    }

    @Test
    void bazaarFlipsPeriodicChecksAreSkippedWhilePausedAndResumeAfter() throws Exception {
        ScheduledExecutorService scheduler = FlipPause.pausableScheduler(1, Thread::new);
        try {
            AtomicInteger checks = new AtomicInteger();
            FlipPause.set(true, null);
            scheduler.scheduleAtFixedRate(checks::incrementAndGet, 0, 5, TimeUnit.MILLISECONDS);
            Thread.sleep(150);
            assertEquals(0, checks.get(), "checked the Bazaar while paused");
            FlipPause.set(false, null);
            long until = System.currentTimeMillis() + 2000;
            while (checks.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(5);
            assertTrue(checks.get() > 0, "checks run again after unpausing");
        } finally {
            scheduler.shutdownNow();
        }
    }
}
