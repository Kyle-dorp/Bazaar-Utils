package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The game side of {@link MayorWatch}: the election download, SkyHanni's Perkpocalypse reading, the data file and /flipmayor.
 *
 * <pre>
 * /flipmayor                      mayor, minister, active perks, the items they tag and how those moved in 24 hours
 * /flipmayor now &lt;mayor&gt;          Jerry's Perkpocalypse mayor right now (what the Calendar shows)
 * /flipmayor order &lt;6 mayors&gt;     this term's rotation, slot A first (the wiki's Perkpocalypse table)
 * /flipmayor forget               forget this term's rotation
 * </pre>
 */
public final class MayorCommand {
    private static final String ELECTION = "https://api.hypixel.net/v2/resources/skyblock/election";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir();
    private static volatile MayorWatch watch;

    private MayorCommand() { }

    public static MayorWatch watch() {
        MayorWatch w = watch;
        if (w != null) return w;
        synchronized (MayorCommand.class) {
            if (watch == null) {
                ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "BazaarUtils-mayor");
                    t.setDaemon(true);
                    return t;
                });
                watch = new MayorWatch(MayorCommand::fetchElection, MayorCommand::skyHanni, System::currentTimeMillis, pool, mapping(),
                        CONFIG.resolve("bazaarutils-perkpocalypse.json"));
            }
            return watch;
        }
    }

    /** config/bazaarutils-mayor-perks.json if you made one, else the bundled data file. */
    static MayorPerks.Mapping mapping() {
        Path own = CONFIG.resolve("bazaarutils-mayor-perks.json");
        if (Files.exists(own)) {
            try (Reader r = Files.newBufferedReader(own, StandardCharsets.UTF_8)) {
                return MayorPerks.readMapping(r);
            } catch (Exception ignored) { }
        }
        return MayorPerks.bundledMapping();
    }

    private static String fetchElection() throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(ELECTION).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "BazaarUtils-Mayor");
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- SkyHanni's ElectionApi.getJerryExtraMayor(): Pair<ElectionCandidate, SimpleTimeMark>, read by reflection ----

    private static volatile boolean skyHanniMissing;
    private static Object electionApi;
    private static Method jerryExtraMayor;

    private static MayorWatch.JerryReading skyHanni() {
        if (skyHanniMissing) return null;
        try {
            if (jerryExtraMayor == null) {
                Class<?> api = Class.forName("at.hannibal2.skyhanni.data.ElectionApi");
                electionApi = api.getField("INSTANCE").get(null);
                jerryExtraMayor = api.getMethod("getJerryExtraMayor");
            }
            Object pair = jerryExtraMayor.invoke(electionApi);
            if (pair == null) return null;
            Object candidate = pair.getClass().getMethod("getFirst").invoke(pair);
            Object mark = pair.getClass().getMethod("getSecond").invoke(pair);
            if (candidate == null || mark == null) return null;
            String name = (String) candidate.getClass().getMethod("getMayorName").invoke(candidate);
            Field millis = mark.getClass().getDeclaredField("millis");
            millis.setAccessible(true);
            return new MayorWatch.JerryReading(name, millis.getLong(mark));
        } catch (ClassNotFoundException e) {
            skyHanniMissing = true;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- /flipmayor ----

    private static String clock(long epochMs) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs));
    }

    /** Refreshes off the client thread (it may download the election), then prints back on it. */
    private static void show() {
        Thread t = new Thread(() -> {
            List<String> lines = lines();
            Minecraft.getInstance().execute(() -> lines.forEach(PlayerActionUtil::notifyAll));
        }, "BazaarUtils-mayor-command");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> lines() {
        List<String> out = new ArrayList<>();
        MayorWatch w = watch();
        w.refresh();
        MayorWatch.State s = w.state();
        MayorPerks.Election e = s.election();
        if (e == null) {
            out.add("Mayor: not loaded (the Hypixel election API didn't answer). Try again in a minute.");
            return out;
        }
        out.add("Mayor: " + e.mayorName() + (e.ministerName() == null ? "" : ", minister " + e.ministerName()
                + " (" + e.ministerPerk() + ")") + ". Term ends " + clock(MayorPerks.termEnd(System.currentTimeMillis())) + ".");
        if (e.isJerry()) {
            out.add(s.perkpocalypseMayor() == null
                    ? "Perkpocalypse mayor: not known. Open the Calendar with SkyHanni, or /flipmayor now <mayor>."
                    : "Perkpocalypse: " + s.perkpocalypseMayor() + " until " + clock(s.perkpocalypseUntil()) + " (" + s.source() + ").");
            List<String> order = w.order();
            StringBuilder b = new StringBuilder("This term's rotation (6 h each, from the election end):");
            for (int i = 0; i < order.size(); i++) {
                b.append(' ').append((char) ('A' + i)).append(' ').append(order.get(i) == null ? "?" : order.get(i));
                if (s.slot() >= 0 && s.slot() % order.size() == i) b.append(" (now)");
            }
            out.add(b.toString());
        }
        out.add("Active perks: " + (s.perks().isEmpty() ? "none" : String.join(", ", s.perks())) + ".");
        Map<String, List<String>> byPerk = new TreeMap<>();
        for (var t : s.tags().entrySet()) {
            String id = t.getKey();
            String item = id.startsWith("list:") ? "the " + id.substring(5) + " list" : ItemNames.of(id) + shift(id);
            byPerk.computeIfAbsent(t.getValue().perk() + " (" + t.getValue().note() + ")", k -> new ArrayList<>()).add(item);
        }
        if (byPerk.isEmpty()) out.add("No item in the data file is tied to these perks.");
        for (var p : byPerk.entrySet()) out.add(" " + p.getKey() + ": " + String.join(", ", p.getValue()));
        if (s.tags().containsKey("list:forge")) out.add(" The forge list's times don't include Molten Forge yet.");
        return out;
    }

    /** " (24h -22%)" for a tagged item whose history is loaded and confirmed shifted, else "". */
    private static String shift(String id) {
        MarketShift m = MarketShift.of(id);
        if (m == null || !m.confirmed()) return "";
        long pct = Math.round(m.bidChange() * 100);
        return " (24h " + (pct > 0 ? "+" : "") + pct + "%)";
    }

    @RunOnInit
    public static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("flipmayor")
                .executes(ctx -> { show(); return 1; })
                .then(ClientCommands.literal("now").then(ClientCommands.argument("mayor", StringArgumentType.word()).executes(ctx -> {
                    String err = watch().setCurrent(StringArgumentType.getString(ctx, "mayor"));
                    if (err != null) PlayerActionUtil.notifyAll(err);
                    else show();
                    return 1;
                })))
                .then(ClientCommands.literal("order").then(ClientCommands.argument("mayors", StringArgumentType.greedyString()).executes(ctx -> {
                    List<String> names = Arrays.asList(StringArgumentType.getString(ctx, "mayors").trim().split("[\\s,]+"));
                    String err = watch().setOrder(names);
                    if (err != null) PlayerActionUtil.notifyAll(err);
                    else show();
                    return 1;
                })))
                .then(ClientCommands.literal("forget").executes(ctx -> {
                    watch().forget();
                    PlayerActionUtil.notifyAll("Forgot this term's Perkpocalypse rotation.");
                    return 1;
                }))));
    }
}
