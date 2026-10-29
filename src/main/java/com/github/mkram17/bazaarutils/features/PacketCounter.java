package com.github.mkram17.bazaarutils.features;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts packets by type in a rolling window of whole seconds (a ring of {@code seconds} slots). Read-only diagnostics: it only
 * counts. {@link #count} is cheap: one lock, one map lookup, and an allocation only the first time a type shows up in a second.
 * Pure logic so it can be unit tested.
 */
final class PacketCounter {
    record TypeCount(String type, int count) { }

    private final int seconds;
    private final long[] secondOf;
    private final int[] totals;
    private final List<Map<String, int[]>> counts = new ArrayList<>();

    PacketCounter(int seconds) {
        this.seconds = seconds;
        secondOf = new long[seconds];
        java.util.Arrays.fill(secondOf, Long.MIN_VALUE);
        totals = new int[seconds];
        for (int i = 0; i < seconds; i++) counts.add(new HashMap<>());
    }

    private int slot(long second) {
        return (int) Math.floorMod(second, (long) seconds);
    }

    synchronized void count(String type, long nowMs) {
        long s = Math.floorDiv(nowMs, 1000);
        int i = slot(s);
        if (secondOf[i] != s) {
            secondOf[i] = s;
            totals[i] = 0;
            counts.get(i).clear();
        }
        totals[i]++;
        int[] c = counts.get(i).get(type);
        if (c == null) counts.get(i).put(type, c = new int[1]);
        c[0]++;
    }

    /** Packets counted in the given whole second (0 if it has left the window). */
    synchronized int total(long second) {
        int i = slot(second);
        return secondOf[i] == second ? totals[i] : 0;
    }

    /** Packets in the last full second before {@code nowMs}. */
    int lastSecond(long nowMs) {
        return total(Math.floorDiv(nowMs, 1000) - 1);
    }

    /** The busiest single second among the last {@code window} seconds (including the current one). */
    synchronized int peak(long nowMs, int window) {
        long now = Math.floorDiv(nowMs, 1000);
        int max = 0;
        for (long s = now - Math.min(window, seconds) + 1; s <= now; s++) max = Math.max(max, total(s));
        return max;
    }

    /** The {@code n} most-sent types over the last {@code window} seconds, most first (ties by name). */
    synchronized List<TypeCount> top(long nowMs, int window, int n) {
        long now = Math.floorDiv(nowMs, 1000);
        Map<String, Integer> sum = new HashMap<>();
        for (long s = now - Math.min(window, seconds) + 1; s <= now; s++) {
            int i = slot(s);
            if (secondOf[i] != s) continue;
            for (var e : counts.get(i).entrySet()) sum.merge(e.getKey(), e.getValue()[0], Integer::sum);
        }
        List<TypeCount> out = new ArrayList<>();
        for (var e : sum.entrySet()) out.add(new TypeCount(e.getKey(), e.getValue()));
        out.sort((a, b) -> a.count() != b.count() ? Integer.compare(b.count(), a.count()) : a.type().compareTo(b.type()));
        return out.subList(0, Math.min(n, out.size()));
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** One line per second for the last {@code window} seconds, oldest first: time, total, and every type's count. */
    synchronized List<String> dump(long nowMs, int window) {
        long now = Math.floorDiv(nowMs, 1000);
        List<String> lines = new ArrayList<>();
        for (long s = now - Math.min(window, seconds) + 1; s <= now; s++) {
            int i = slot(s);
            StringBuilder sb = new StringBuilder(CLOCK.format(Instant.ofEpochSecond(s))).append("  total ").append(total(s));
            if (secondOf[i] == s) {
                List<Map.Entry<String, int[]>> types = new ArrayList<>(counts.get(i).entrySet());
                types.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
                for (var e : types) sb.append("  ").append(e.getKey()).append('=').append(e.getValue()[0]);
            }
            lines.add(sb.toString());
        }
        return lines;
    }
}
