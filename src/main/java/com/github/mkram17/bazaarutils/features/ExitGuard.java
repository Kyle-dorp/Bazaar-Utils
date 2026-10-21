package com.github.mkram17.bazaarutils.features;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Makes sure the game process ends when the game does. Once the game's own thread has finished, any thread still holding the
 * JVM open (a non-daemon thread from any mod or library) would leave a windowless javaw running for good. After a short grace
 * period this logs those threads (name and stack, so the culprit can be fixed) and exits. Pure logic plus plain threads, so it
 * can be unit tested.
 */
public final class ExitGuard {
    private ExitGuard() { }

    /** The threads that keep the JVM alive: alive, not daemon, and not in {@code except} (the guard itself, the game thread). */
    static List<Thread> holdingTheJvm(Collection<Thread> threads, Collection<Thread> except) {
        List<Thread> out = new ArrayList<>();
        for (Thread t : threads) {
            if (t.isAlive() && !t.isDaemon() && !except.contains(t) && !t.getName().equals("DestroyJavaVM")) out.add(t);
        }
        return out;
    }

    static String describe(Map<Thread, StackTraceElement[]> stacks, List<Thread> threads) {
        StringBuilder sb = new StringBuilder();
        for (Thread t : threads) {
            sb.append("  \"").append(t.getName()).append("\" ").append(t.getState()).append('\n');
            StackTraceElement[] st = stacks.get(t);
            if (st != null) for (int i = 0; i < Math.min(12, st.length); i++) sb.append("      at ").append(st[i]).append('\n');
        }
        return sb.toString();
    }

    private static void log(Path log, String text) {
        try {
            Files.writeString(log, java.time.LocalDateTime.now() + " " + text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /**
     * Starts the guard (a daemon thread): waits for {@code gameThread} to end, then {@code graceMs}; if threads still hold the
     * JVM, logs them to {@code log} and runs {@code exit} (System.exit in game) on its own daemon thread. System.exit can block
     * for good (it waits for every shutdown hook, and a mod's hook can hang), so if the process is still here {@code haltAfterMs}
     * later, {@code halt} (Runtime.halt in game, which skips the hooks) ends it.
     */
    static Thread start(Thread gameThread, long graceMs, Path log, Runnable exit, Runnable halt, long haltAfterMs) {
        Thread guard = new Thread(() -> {
            try {
                gameThread.join();
                Thread.sleep(graceMs);
            } catch (InterruptedException e) {
                return;
            }
            Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
            List<Thread> holding = holdingTheJvm(stacks.keySet(), List.of(Thread.currentThread(), gameThread));
            if (holding.isEmpty()) return;
            log(log, "game closed but " + holding.size() + " thread(s) kept the process alive; exiting anyway:\n"
                    + describe(stacks, holding).stripTrailing());
            Thread exiting = new Thread(exit, "BazaarUtils-exit");
            exiting.setDaemon(true);
            exiting.start();
            try {
                exiting.join(haltAfterMs);
            } catch (InterruptedException ignored) { }
            // a working exit never gets here: the process is gone
            log(log, "still running after exit (waited up to " + haltAfterMs + " ms; a shutdown hook is stuck?); halting");
            halt.run();
        }, "BazaarUtils-exit-guard");
        guard.setDaemon(true);
        guard.start();
        return guard;
    }
}
