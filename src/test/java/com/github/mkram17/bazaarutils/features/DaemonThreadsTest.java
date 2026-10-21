package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The game process must end when the game closes, so no thread the mod starts may hold the JVM open. Scans the mod's source:
 * every thread it creates is made a daemon, and nothing starts threads we can't make daemon (Swing/AWT timers, executors
 * without a thread factory, java.util.Timer without isDaemon).
 */
class DaemonThreadsTest {
    private static final Pattern NO_FACTORY = Pattern.compile(
            "Executors\\.new(CachedThreadPool|SingleThreadExecutor|SingleThreadScheduledExecutor|WorkStealingPool)\\(\\s*\\)"
                    + "|Executors\\.new(FixedThreadPool|ScheduledThreadPool)\\(\\s*[^,()]+\\s*\\)");

    private static final Pattern ASSIGNED = Pattern.compile("(\\w+)\\s*=\\s*new Thread\\(");

    static Path sources() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path s = p.resolve("src/main/java");
            if (Files.isDirectory(s.resolve("com/github/mkram17/bazaarutils"))) return s;
        }
        throw new IllegalStateException("can't find src/main/java from " + Path.of("").toAbsolutePath());
    }

    static List<String> problems(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                String name = root.relativize(f).toString();
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (l.trim().startsWith("//") || l.trim().startsWith("*")) continue;
                    if (l.contains("new Thread(")) {
                        // the thread must be assigned, and that same variable made a daemon within the next lines
                        java.util.regex.Matcher m = ASSIGNED.matcher(l);
                        String next = String.join("\n", lines.subList(i, Math.min(lines.size(), i + 25)));
                        if (!m.find() || !next.contains(m.group(1) + ".setDaemon(true)")) out.add(name + ":" + (i + 1) + " thread not made daemon");
                    }
                    if (l.contains("javax.swing.Timer") || l.contains("java.awt.")) out.add(name + ":" + (i + 1) + " Swing/AWT timer or thread");
                    if (l.matches(".*new (java\\.util\\.)?Timer\\(\\s*\\).*")) out.add(name + ":" + (i + 1) + " java.util.Timer not daemon");
                    if (NO_FACTORY.matcher(l).find()) out.add(name + ":" + (i + 1) + " executor without a daemon thread factory");
                }
            }
        }
        return out;
    }

    @Test
    void everyThreadTheModStartsIsADaemon() throws IOException {
        assertEquals(List.of(), problems(sources()));
    }

    @Test
    void theScanCatchesEachKindOfLeak(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), String.join("\n",
                "class A {",
                "  Thread t = new Thread(() -> {}, \"x\");",
                "  javax.swing.Timer s = null;",
                "  java.util.Timer u = new Timer();",
                "  Object e = java.util.concurrent.Executors.newCachedThreadPool();",
                "  Object f = java.util.concurrent.Executors.newFixedThreadPool(4);",
                "  Object g = java.util.concurrent.Executors.newFixedThreadPool(4, r -> { Thread d = new Thread(r); d.setDaemon(true); return d; });",
                "}"));
        List<String> found = problems(dir);
        assertEquals(5, found.size(), String.join("\n", found));
    }
}
