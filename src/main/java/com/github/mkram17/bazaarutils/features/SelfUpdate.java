package com.github.mkram17.bazaarutils.features;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Install-on-exit: an update costs only a game restart. A jar staged at a fixed spot with a {@code .sha256} file beside it is
 * copied over the running BazaarUtils jar by a small PowerShell script that waits for this game to close (Java can't replace
 * its own jar while it runs). Only the BazaarUtils jar is touched; the old one is kept as {@code <name>.before-update} next to
 * the mods folder. Pure logic (no Minecraft classes) so it can be unit tested; {@link SelfUpdateHooks} wires it into the game.
 */
public final class SelfUpdate {
    /** What to install: {@code staged} (whose SHA-256 is {@code sha256}) over {@code target}, keeping the old one as {@code backup}. */
    public record Install(Path staged, String sha256, Path target, Path backup) { }

    private SelfUpdate() { }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** The staged jar's expected hash: the first word of its {@code .sha256} file (so {@code sha256sum} output works too). */
    static Path sidecar(Path staged) {
        return staged.resolveSibling(staged.getFileName() + ".sha256");
    }

    /**
     * Whether to install, and what. Nothing when there's no staged jar or no {@code .sha256} beside it, when the staged jar
     * doesn't match its {@code .sha256} (half-written, or replaced since), or when it's the jar already running.
     */
    public static Install plan(Path staged, Path running) {
        try {
            if (staged == null || running == null || !Files.isRegularFile(staged) || !Files.isRegularFile(running)) return null;
            Path sha = sidecar(staged);
            if (!Files.isRegularFile(sha)) return null;
            String[] words = Files.readString(sha).trim().split("\\s+");
            String expected = words.length == 0 ? "" : words[0].toLowerCase(Locale.ROOT);
            String actual = sha256(staged);
            if (expected.isEmpty() || !expected.equals(actual)) return null;
            if (actual.equals(sha256(running))) return null;
            Path home = running.getParent() == null || running.getParent().getParent() == null ? running.getParent() : running.getParent().getParent();
            return new Install(staged, actual, running, home.resolve(running.getFileName() + ".before-update"));
        } catch (IOException e) {
            return null;
        }
    }

    private static String q(Path p) {
        return "'" + p.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    /**
     * The installer script. Run without {@code -Detached} it re-launches itself outside the game's process tree (so a launcher
     * that kills the game's children can't stop it) and exits; detached, it waits for the game (process {@code gamePid}) to
     * close, copies the staged jar aside and checks its hash again, keeps a backup, moves it into place, verifies it, and writes
     * {@code marker} so the next start can say "updated". Every step is logged to {@code log}.
     */
    public static String script(Install i, long gamePid, Path log, Path marker) {
        Path incoming = i.backup().resolveSibling(i.target().getFileName() + ".incoming");
        return String.join("\r\n",
                "param([switch]$Detached)",
                "$log = " + q(log),
                "function Log($m) { Add-Content -LiteralPath $log -Value ((Get-Date -Format s) + ' ' + $m) }",
                "if (-not $Detached) {",
                "  try {",
                "    $cmd = 'powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File \"' + $PSCommandPath + '\" -Detached'",
                "    $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd }",
                "    if ($r.ReturnValue -eq 0) { Log ('waiting for the game to close (installer ' + $r.ProcessId + ')'); exit 0 }",
                "  } catch { }",
                "  Log 'could not detach; installing from this process'",
                "}",
                "try {",
                "  $game = Get-Process -Id " + gamePid + " -ErrorAction SilentlyContinue",
                "  if ($game -and -not $game.WaitForExit(600000)) { Log 'game still running after 10 minutes; not installing'; exit 1 }",
                "  Start-Sleep -Milliseconds 500",
                "  $expected = '" + i.sha256() + "'",
                "  $incoming = " + q(incoming),
                "  Copy-Item -LiteralPath " + q(i.staged()) + " -Destination $incoming -Force",
                "  $h = (Get-FileHash -LiteralPath $incoming -Algorithm SHA256).Hash.ToLower()",
                "  if ($h -ne $expected) { Remove-Item -LiteralPath $incoming -Force; Log ('staged jar changed since the game checked it (' + $h + '); not installing'); exit 1 }",
                "  Copy-Item -LiteralPath " + q(i.target()) + " -Destination " + q(i.backup()) + " -Force",
                "  Move-Item -LiteralPath $incoming -Destination " + q(i.target()) + " -Force",
                "  $after = (Get-FileHash -LiteralPath " + q(i.target()) + " -Algorithm SHA256).Hash.ToLower()",
                "  if ($after -ne $expected) { Copy-Item -LiteralPath " + q(i.backup()) + " -Destination " + q(i.target()) + " -Force; Log 'installed jar failed its hash check; restored the old one'; exit 1 }",
                "  Set-Content -LiteralPath " + q(marker) + " -Value $expected",
                "  Log ('installed ' + $expected + ', old jar kept as ' + " + q(i.backup()) + ")",
                "} catch { Log ('failed: ' + $_) }",
                "");
    }
}
