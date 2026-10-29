package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CachedComputeTest {
    private long now;
    private int computed;
    private final List<Runnable> queued = new ArrayList<>();

    /** Runs background work only when the test says so, like a worker thread that hasn't got to it yet. */
    private CachedCompute<String, String> cache(boolean runImmediately) {
        return new CachedCompute<>(k -> {
            computed++;
            return "plan for " + k;
        }, r -> {
            if (runImmediately) r.run();
            else queued.add(r);
        }, () -> now, 30_000, "nothing yet");
    }

    /** Called every frame with the same inputs, it computes once: the frame never pays for the plan again. */
    @Test
    void sameInputsComputeOnce() {
        var c = cache(true);
        for (int frame = 0; frame < 1000; frame++) {
            now += 16;
            assertEquals("plan for shards A", c.get("shards A"));
        }
        assertEquals(1, computed);
    }

    @Test
    void newInputsRecompute() {
        var c = cache(true);
        c.get("shards A");
        assertEquals("plan for shards B", c.get("shards B"));
        assertEquals(2, computed);
    }

    @Test
    void aStaleValueIsRefreshedEvenWithTheSameInputs() {
        var c = cache(true);
        c.get("shards A");
        now += 29_999;
        c.get("shards A");
        assertEquals(1, computed, "not stale yet");
        now += 1;
        c.get("shards A");
        assertEquals(2, computed, "30 seconds old: refresh for new prices");
    }

    @Test
    void failuresAreVisibleAndClearAfterRecovery() {
        var c = new CachedCompute<String, String>(key -> {
            if (key.equals("bad")) throw new IllegalStateException("unavailable");
            return key;
        }, Runnable::run, () -> now, 30_000, "initial");
        assertEquals("good", c.get("good"));
        assertEquals("good", c.get("bad"));
        assertInstanceOf(IllegalStateException.class, c.failure());
        assertEquals("recovered", c.get("recovered"));
        assertNull(c.failure());
    }

    @Test
    void rejectedSubmissionDoesNotBreakRenderingOrPermanentlyStopRefreshes() {
        var c = new CachedCompute<String, String>(key -> key, task -> {
            if (now == 0) throw new java.util.concurrent.RejectedExecutionException();
            task.run();
        }, () -> now, 30_000, "initial");
        assertEquals("initial", assertDoesNotThrow(() -> c.get("first")));
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, c.failure());
        now = 30_000;
        assertEquals("first", c.get("first"));
        assertNull(c.failure());
    }

    /** While a computation runs the caller gets the previous value at once, and no second computation is started. */
    @Test
    void theCallerNeverWaitsAndWorkIsNotQueuedTwice() {
        var c = cache(false);
        assertEquals("nothing yet", c.get("shards A"), "returns immediately");
        assertEquals("nothing yet", c.get("shards B"), "still running: nothing new started");
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, computed);
        assertEquals("plan for shards A", c.get("shards B"), "finished value shown, and B now starts");
        assertEquals(1, queued.size());
    }
}
