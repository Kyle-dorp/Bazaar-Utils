package com.github.mkram17.bazaarutils.features;

import java.util.ArrayDeque;

/**
 * Keeps fusing under Hypixel's "Sending packets too fast!" kick: a fuse click is let through only if the last accepted one was at
 * least {@code minIntervalMs} ago and fewer than {@code maxPerWindow} were accepted in the last {@code windowMs}. Blocked clicks
 * send nothing, so they don't count. It only ever blocks; it never clicks for you. Pure logic so it can be unit tested.
 */
final class FuseLimiter {
    /** Whether a click may go through; if not, how long until one can, and why ("too fast" or the rolling cap). */
    record Verdict(boolean allowed, long waitMs, String reason) {
        static final Verdict ALLOW = new Verdict(true, 0, "");
    }

    private final ArrayDeque<Long> accepted = new ArrayDeque<>();
    private final long windowMs;
    private volatile long minIntervalMs;
    private volatile int maxPerWindow;

    FuseLimiter(long minIntervalMs, int maxPerWindow, long windowMs) {
        this.minIntervalMs = minIntervalMs;
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    /** Whether a click in screen {@code title} on an item called {@code itemName} is a fuse click. */
    static boolean isFuseClick(String title, String itemName, boolean inMenu, boolean empty) {
        if (!inMenu || empty) return false;
        String t = title.replaceAll("§.", "").toLowerCase();
        if (!t.contains("fusion")) return false;
        if (t.contains("confirm fusion")) return true;
        String n = itemName.replaceAll("§.", "").toLowerCase();
        return n.contains("fus") || n.contains("confirm");
    }

    void setLimits(long minIntervalMs, int maxPerWindow) {
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.maxPerWindow = Math.max(0, maxPerWindow);
    }

    long minIntervalMs() {
        return minIntervalMs;
    }

    int maxPerWindow() {
        return maxPerWindow;
    }

    long windowMs() {
        return windowMs;
    }

    /** A fuse click at {@code now}: let through (and counted) or blocked. A cap of 0 means no cap. */
    synchronized Verdict click(long now) {
        while (!accepted.isEmpty() && now - accepted.peekFirst() >= windowMs) accepted.pollFirst();
        if (!accepted.isEmpty() && now - accepted.peekLast() < minIntervalMs) {
            return new Verdict(false, minIntervalMs - (now - accepted.peekLast()), "too fast");
        }
        if (maxPerWindow > 0 && accepted.size() >= maxPerWindow) {
            return new Verdict(false, windowMs - (now - accepted.peekFirst()), maxPerWindow + " per " + windowMs / 1000 + "s");
        }
        accepted.addLast(now);
        return Verdict.ALLOW;
    }
}
