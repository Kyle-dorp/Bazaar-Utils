package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SelfUpdateTest {
    @TempDir
    Path dir;

    private Path mods, running, staged;

    private void setUp(String runningBytes, String stagedBytes, boolean sidecar) throws Exception {
        mods = Files.createDirectories(dir.resolve(".minecraft").resolve("mods"));
        running = Files.writeString(mods.resolve("bazaarutils.jar"), runningBytes);
        staged = Files.writeString(Files.createDirectories(dir.resolve("dist")).resolve("bazaarutils-staged.jar"), stagedBytes);
        if (sidecar) Files.writeString(SelfUpdate.sidecar(staged), SelfUpdate.sha256(staged) + " *bazaarutils-staged.jar\n");
    }

    @Test
    void aNewStagedJarThatMatchesItsHashIsInstalledWithABackupOutsideMods() throws Exception {
        setUp("old", "new", true);
        SelfUpdate.Install i = SelfUpdate.plan(staged, running);
        assertNotNull(i);
        assertEquals(SelfUpdate.sha256(staged), i.sha256());
        assertEquals(running, i.target());
        assertEquals(dir.resolve(".minecraft").resolve("bazaarutils.jar.before-update"), i.backup(), "backup kept out of mods/");
    }

    @Test
    void theSameVersionAsTheRunningJarInstallsNothing() throws Exception {
        setUp("same", "same", true);
        assertNull(SelfUpdate.plan(staged, running));
    }

    @Test
    void aStagedJarThatDoesNotMatchItsHashInstallsNothing() throws Exception {
        setUp("old", "new", true);
        Files.writeString(staged, "half-written"); // replaced after its .sha256 was written
        assertNull(SelfUpdate.plan(staged, running));
    }

    @Test
    void noHashFileOrNoStagedJarInstallsNothing() throws Exception {
        setUp("old", "new", false);
        assertNull(SelfUpdate.plan(staged, running), "no .sha256");
        assertNull(SelfUpdate.plan(dir.resolve("missing.jar"), running), "nothing staged");
        assertNull(SelfUpdate.plan(staged, null), "dev run: not loaded from a jar");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Runs the real installer script (detached mode, the game already gone) and returns once it finishes. */
    private void runScript(SelfUpdate.Install i, Path log, Path marker) throws Exception {
        Process gone = new ProcessBuilder("cmd", "/c", "exit").start();
        gone.waitFor();
        Path script = Files.writeString(dir.resolve("update.ps1"), SelfUpdate.script(i, gone.pid(), log, marker, 60));
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                script.toString(), "-Detached").redirectErrorStream(true).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "installer finished");
    }

    @Test
    void theInstallerCopiesTheJarInKeepsABackupAndLeavesAMarker() throws Exception {
        Assumptions.assumeTrue(windows());
        setUp("old", "new", true);
        SelfUpdate.Install i = SelfUpdate.plan(staged, running);
        Path log = dir.resolve("update.log"), marker = dir.resolve("updated.txt");
        runScript(i, log, marker);
        assertEquals("new", Files.readString(running), Files.exists(log) ? Files.readString(log) : "no log");
        assertEquals("old", Files.readString(i.backup()));
        assertEquals(i.sha256(), Files.readString(marker).trim());
        try (var s = Files.list(mods)) {
            assertEquals(1, s.count(), "only the BazaarUtils jar in mods/, no leftovers");
        }
    }

    @Test
    void theInstallerChecksTheHashAgainAndCopiesNothingIfTheStagedJarChanged() throws Exception {
        Assumptions.assumeTrue(windows());
        setUp("old", "new", true);
        SelfUpdate.Install i = SelfUpdate.plan(staged, running);
        Files.writeString(staged, "rebuilt while the game was closing");
        Path log = dir.resolve("update.log"), marker = dir.resolve("updated.txt");
        runScript(i, log, marker);
        assertEquals("old", Files.readString(running), "running jar untouched");
        assertFalse(Files.exists(marker));
        assertFalse(Files.exists(i.backup()));
        assertTrue(Files.readString(log).contains("not installing"));
    }
    private Process runDetached(SelfUpdate.Install i, long gamePid, long maxWaitSeconds, Path log, Path marker) throws Exception {
        Path script = Files.writeString(dir.resolve("wait.ps1"), SelfUpdate.script(i, gamePid, log, marker, maxWaitSeconds));
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                script.toString(), "-Detached").redirectErrorStream(true).start();
    }

    @Test
    void theInstallerWaitsForTheGameToCloseThenInstalls() throws Exception {
        Assumptions.assumeTrue(windows());
        setUp("old", "new", true);
        SelfUpdate.Install i = SelfUpdate.plan(staged, running);
        Process game = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 4").start();
        Path log = dir.resolve("update.log"), marker = dir.resolve("updated.txt");
        Process installer = runDetached(i, game.pid(), 60, log, marker);
        Thread.sleep(1500);
        assertEquals("old", Files.readString(running), "nothing replaced while the game still runs");
        assertTrue(installer.waitFor(60, TimeUnit.SECONDS));
        assertFalse(game.isAlive());
        String text = Files.readString(log);
        assertEquals("new", Files.readString(running), text);
        assertTrue(text.contains("game closed; installing") && text.contains("installed "), text);
    }

    @Test
    void aGameThatNeverClosesIsLoggedAndNothingIsInstalled() throws Exception {
        Assumptions.assumeTrue(windows());
        setUp("old", "new", true);
        SelfUpdate.Install i = SelfUpdate.plan(staged, running);
        Process game = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 60").start();
        try {
            Path log = dir.resolve("update.log"), marker = dir.resolve("updated.txt");
            assertTrue(runDetached(i, game.pid(), 2, log, marker).waitFor(60, TimeUnit.SECONDS));
            assertEquals("old", Files.readString(running));
            assertTrue(Files.readString(log).contains("still running after 2 s; not installing"), Files.readString(log));
        } finally {
            game.destroyForcibly();
        }
    }
}
