package com.github.mkram17.bazaarutils.features;

import java.util.function.Consumer;

public final class MenuSearch {
    private final long cooldownMs;
    private long lastSent;
    private boolean sent;

    public MenuSearch(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public boolean run(long nowMs, String command, Runnable close, Consumer<String> send) {
        if (sent && nowMs - lastSent < cooldownMs) return false;
        close.run();
        send.accept(command);
        lastSent = nowMs;
        sent = true;
        return true;
    }
}
