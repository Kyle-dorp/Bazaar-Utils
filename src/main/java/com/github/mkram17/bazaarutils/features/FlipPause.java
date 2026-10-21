package com.github.mkram17.bazaarutils.features;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The "stop updating while I click" switch: while paused nothing refreshes or recomputes (the flip list in every mode, the Fusion
 * Machine panel, the fusion/forge/book calculators, BazaarFlip's periodic checks) and what's on screen stays exactly as it was,
 * so nothing re-sorts under the cursor. Off at every start. Unpausing refreshes once, right away. Pure logic (no Minecraft
 * classes) so it can be unit tested; the keybind and command live in {@link FlipHud}.
 */
public final class FlipPause {
    private record Held(Object variant, Object value) { }

    private static volatile boolean paused;
    private static final Map<String, Held> held = new ConcurrentHashMap<>();

    private FlipPause() { }

    public static boolean paused() {
        return paused;
    }

    /**
     * Pauses or resumes. Resuming forgets every frozen value, so the next frame shows fresh data, and runs {@code onResume}
     * (start the one immediate refresh) exactly once. Returns false if it was already in that state.
     */
    static boolean set(boolean pause, Runnable onResume) {
        if (paused == pause) return false;
        paused = pause;
        if (!pause) {
            held.clear();
            if (onResume != null) onResume.run();
        }
        return true;
    }

    /**
     * What to show in {@code slot}. Not paused: {@code live} is computed and remembered. Paused: the remembered value, without
     * calling {@code live}, as long as {@code variant} (e.g. the search typed) is the same; a new variant is computed from
     * {@code live} once, which only re-filters data the calculators already have since they don't refresh while paused.
     */
    @SuppressWarnings("unchecked")
    static <T> T hold(String slot, Object variant, Supplier<T> live) {
        if (paused) {
            Held h = held.get(slot);
            if (h != null && Objects.equals(h.variant(), variant)) return (T) h.value();
        }
        T v = live.get();
        held.put(slot, new Held(variant, v));
        return v;
    }

    /**
     * A scheduler whose periodic tasks do nothing while paused, or while both lists they feed (flips and dips) are turned off
     * (BazaarFlip's Bazaar checks run on one).
     */
    static ScheduledExecutorService pausableScheduler(int threads, ThreadFactory factory) {
        return new ScheduledThreadPoolExecutor(threads, factory) {
            @Override
            public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
                return super.scheduleAtFixedRate(skipWhilePaused(task), initialDelay, period, unit);
            }

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
                return super.scheduleWithFixedDelay(skipWhilePaused(task), initialDelay, delay, unit);
            }
        };
    }

    private static Runnable skipWhilePaused(Runnable task) {
        return () -> {
            if (!paused && Flippers.bazaarChecksOn()) task.run();
        };
    }
}
