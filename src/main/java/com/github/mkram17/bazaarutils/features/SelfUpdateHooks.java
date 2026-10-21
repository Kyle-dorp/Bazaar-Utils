package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.BazaarUtils;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * In game: on close, installs a staged update ({@link SelfUpdate}) and makes sure the process really ends ({@link ExitGuard});
 * on the next start, says "BazaarUtils updated" in chat. /flipautoupdate [on|off] turns install-on-exit off or on (on by
 * default). Settings live in config/bazaarutils-selfupdate.json ({"enabled": true, "stagedJar": "..."}).
 */
public final class SelfUpdateHooks {
    static final String DEFAULT_STAGED = "D:\\projects\\bazaar-utils-src\\dist\\bazaarutils-staged.jar";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir();
    private static final Path FILE = CONFIG.resolve("bazaarutils-selfupdate.json");
    private static final Path LOG = CONFIG.resolve("bazaarutils-selfupdate.log");
    private static final Path MARKER = CONFIG.resolve("bazaarutils-updated.txt");
    private static volatile boolean enabled = true;
    private static volatile String stagedJar = DEFAULT_STAGED;
    private static boolean announced;

    private SelfUpdateHooks() { }

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
                if (o.has("stagedJar")) stagedJar = o.get("stagedJar").getAsString();
            }
        } catch (Exception ignored) { }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("stagedJar", stagedJar);
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception ignored) { }
    }

    private static void log(String line) {
        try {
            Files.writeString(LOG, java.time.LocalDateTime.now() + " " + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    /** The jar this game loaded BazaarUtils from (null in a dev run, where it's a folder). */
    private static Path runningJar() {
        try {
            for (Path p : FabricLoader.getInstance().getModContainer(BazaarUtils.MODID).orElseThrow().getOrigin().getPaths()) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) return p;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static void installOnExit() {
        if (!enabled) return;
        try {
            SelfUpdate.Install install = SelfUpdate.plan(Path.of(stagedJar), runningJar());
            if (install == null) return; // nothing staged, staged jar doesn't match its .sha256, or it's already this one
            Path script = CONFIG.resolve("bazaarutils-selfupdate.ps1");
            Files.writeString(script, SelfUpdate.script(install, ProcessHandle.current().pid(), LOG, MARKER, 6 * 3600));
            log("game closing; installing " + install.sha256() + " once it has");
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", script.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) {
            log("could not start the installer: " + e);
        }
    }

    @RunOnInit
    public static void register() {
        load();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            installOnExit();
            ExitGuard.start(Thread.currentThread(), 5000, CONFIG.resolve("bazaarutils-exit.log"), () -> System.exit(0),
                    () -> Runtime.getRuntime().halt(0), 3000);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (announced || client.player == null) return;
            announced = true;
            try {
                if (Files.exists(MARKER)) {
                    String hash = Files.readString(MARKER).trim();
                    Files.delete(MARKER);
                    PlayerActionUtil.notifyAll("§aBazaarUtils updated§r §8(" + (hash.length() > 8 ? hash.substring(0, 8) : hash) + ")");
                }
            } catch (Exception ignored) { }
            if (!enabled) return;
            // an update that's staged but not installed (the last install didn't happen): say so once, off the render thread
            Thread check = new Thread(() -> {
                SelfUpdate.Install pending = SelfUpdate.plan(Path.of(stagedJar), runningJar());
                if (pending == null) return;
                log("started with an update staged but not installed (" + pending.sha256() + "); it installs at the next close");
                client.execute(() -> PlayerActionUtil.notifyAll("§eA BazaarUtils update is waiting§r - it installs when you quit the game."));
            }, "BazaarUtils-update-check");
            check.setDaemon(true);
            check.start();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("flipautoupdate")
                .executes(ctx -> {
                    PlayerActionUtil.notifyAll("Install-on-exit is " + (enabled ? "ON" : "OFF") + ": an update staged at " + stagedJar
                            + " is installed when you close the game. /flipautoupdate on|off");
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    enabled = true;
                    save();
                    PlayerActionUtil.notifyAll("Install-on-exit ON.");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    enabled = false;
                    save();
                    PlayerActionUtil.notifyAll("Install-on-exit OFF.");
                    return 1;
                }))));
    }
}
