package com.github.mkram17.bazaarutils.features;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A value computed in the background from some inputs, for code on the render thread: {@link #get} never computes, it starts a
 * background computation when the inputs changed or the value is older than {@code maxAgeMs} (and none is running), and
 * returns the last finished value meanwhile. Pure (executor and clock are injected) so it can be unit tested.
 */
final class CachedCompute<K, V> {
    private final Function<K, V> compute;
    private final Executor executor;
    private final LongSupplier clock;
    private final long maxAgeMs;
    private volatile V value;
    private volatile K lastKey;
    private volatile long startedAt = Long.MIN_VALUE / 2;
    private volatile boolean running;

    CachedCompute(Function<K, V> compute, Executor executor, LongSupplier clock, long maxAgeMs, V initial) {
        this.compute = compute;
        this.executor = executor;
        this.clock = clock;
        this.maxAgeMs = maxAgeMs;
        this.value = initial;
    }

    /** The latest finished value; kicks off a background recompute if the inputs changed or it's stale. */
    V get(K key) {
        long now = clock.getAsLong();
        if (!running && (!Objects.equals(key, lastKey) || now - startedAt >= maxAgeMs)) {
            running = true;
            lastKey = key;
            startedAt = now;
            executor.execute(() -> {
                try {
                    value = compute.apply(key);
                } catch (RuntimeException ignored) {
                    // keep the previous value
                } finally {
                    running = false;
                }
            });
        }
        return value;
    }
}
