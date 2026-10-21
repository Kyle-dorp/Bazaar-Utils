package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExitGuardTest {
    @Test
    void aStuckNonDaemonThreadAfterTheGameEndsIsLoggedAndTheProcessExits(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread stuck = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) { }
        }, "some-mod-pool-1");
        stuck.start(); // not a daemon: would keep a real JVM alive
        try {
            Thread game = new Thread(() -> { }, "Render thread");
            AtomicInteger exits = new AtomicInteger();
            CountDownLatch exited = new CountDownLatch(1);
            Path log = dir.resolve("exit.log");
            Thread guard = ExitGuard.start(game, 50, log, () -> { exits.incrementAndGet(); exited.countDown(); }, () -> { }, 10_000);
            assertTrue(guard.isDaemon(), "the guard itself never holds the JVM");
            game.start();
            assertTrue(exited.await(5, TimeUnit.SECONDS), "exited although a thread was still holding the JVM");
            assertEquals(1, exits.get());
            assertTrue(Files.readString(log).contains("some-mod-pool-1"), "the culprit is named in the log");
        } finally {
            release.countDown();
        }
    }

    @Test
    void nothingHappensWhileTheGameIsStillRunning(@TempDir Path dir) throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        Thread game = new Thread(() -> {
            try {
                end.await();
            } catch (InterruptedException ignored) { }
        }, "Render thread");
        game.start();
        AtomicInteger exits = new AtomicInteger();
        ExitGuard.start(game, 0, dir.resolve("exit.log"), exits::incrementAndGet, exits::incrementAndGet, 0);
        Thread.sleep(200);
        assertEquals(0, exits.get(), "never exits before the game thread has ended");
        end.countDown();
    }

    /** The real hang: System.exit blocked on a stuck shutdown hook. The guard must still end the process (halt). */
    @Test
    void anExitThatBlocksStillEndsInHalt(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread stuck = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) { }
        }, "Thread-143");
        stuck.start();
        try {
            Thread game = new Thread(() -> { }, "Render thread");
            CountDownLatch halted = new CountDownLatch(1);
            Path log = dir.resolve("exit.log");
            ExitGuard.start(game, 0, log, () -> {
                try {
                    release.await(); // like System.exit waiting on a hook that never returns
                } catch (InterruptedException ignored) { }
            }, halted::countDown, 200);
            game.start();
            assertTrue(halted.await(5, TimeUnit.SECONDS), "halted although exit never returned");
            String text = Files.readString(log);
            assertTrue(text.contains("Thread-143") && text.contains("halting"), text);
        } finally {
            release.countDown();
        }
    }

    @Test
    void onlyLiveNonDaemonThreadsCount() throws Exception {
        Thread daemon = new Thread(() -> { });
        daemon.setDaemon(true);
        Thread done = new Thread(() -> { });
        done.start();
        done.join();
        assertEquals(List.of(), ExitGuard.holdingTheJvm(List.of(daemon, done, Thread.currentThread()), List.of(Thread.currentThread())));
    }
}
