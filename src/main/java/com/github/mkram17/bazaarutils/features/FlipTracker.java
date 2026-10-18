package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.ProfitLedger.Entry;
import com.github.mkram17.bazaarutils.features.ProfitLedger.Mode;
import com.github.mkram17.bazaarutils.features.ProfitLedger.Realized;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks profit from chat: Bazaar buys and sells, auction house purchases and sales, fusions and forges, split by mode
 * (Bazaar flips, AH flips, Fusions, Forge). The accounting lives in {@link ProfitLedger}; this class reads chat, saves the
 * history and shows the totals. Profit is realized profit at average cost, after Bazaar tax / AH fees. Show it with
 * /bazaarflip profit.
 */
public class FlipTracker {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarflip-tracker.json");
    private static final long SESSION_START = System.currentTimeMillis();

    // "FUSION! You obtained Troglobyte Shard x2!" / "FUSION! You obtained a Stoneworm Shard!" (from your chat logs)
    private static final Pattern FUSION = Pattern.compile("^FUSION! You obtained (?:an? )?(.+? Shard)(?: x([\\d,]+))?!");

    /** Forge recipes for recognising forge sales, from the bundled forge data. */
    static final ProfitLedger.Forges FORGES = new ProfitLedger.Forges() {
        public String outputIdFor(String soldName) {
            return ForgeCalc.forgeOutputFor(soldName);
        }

        public ForgePlanner.Recipe recipe(String id) {
            return ForgeCalc.recipes().get(id);
        }

        public String name(String id) {
            return ForgeCalc.name(id);
        }
    };

    private static final List<Entry> entries = load();

    private static List<Entry> load() {
        try {
            if (Files.exists(FILE)) {
                List<Entry> list = new Gson().fromJson(Files.readString(FILE), new TypeToken<List<Entry>>() { }.getType());
                if (list != null) return Collections.synchronizedList(new ArrayList<>(list));
            }
        } catch (Exception ignored) { }
        return Collections.synchronizedList(new ArrayList<>());
    }

    private static void save() {
        try {
            synchronized (entries) {
                Files.writeString(FILE, new Gson().toJson(entries));
            }
        } catch (Exception ignored) { }
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    private static void onChat(String msg) {
        long now = System.currentTimeMillis();
        Entry trade = ProfitLedger.parseTrade(msg, now);
        if (trade != null) {
            if (!trade.buy) {
                // a forge item you may have forged: remember what its parts cost right now, for parts you didn't buy
                String forgeId = ForgeCalc.forgeOutputFor(trade.item);
                if (forgeId != null) trade.forgePrices = ForgeCalc.partPrices(forgeId);
            }
            entries.add(trade);
            changed();
            return;
        }
        Matcher m = FUSION.matcher(msg);
        if (m.find()) onFusion(m.group(1).trim(), m.group(2) == null ? 1 : num(m.group(2)));
    }

    private static void changed() {
        cachedAt = 0;
        save();
    }

    /**
     * The fusion message names only the output, so the inputs are worked out: of the wiki recipes that make this shard (in
     * this amount, or double for a Pure Reptile proc), keep the ones whose inputs you're holding according to your tracked
     * buys, and pick the one whose inputs you bought most recently. Those inputs are then used up at their average cost and
     * become the cost of the output. If nothing matches, the output has no known cost and its sale isn't counted.
     */
    private static void onFusion(String output, double qty) {
        ProfitLedger.Replay held = replay();
        FusionCalc.NamedRecipe best = null;
        long bestTime = -1;
        for (FusionCalc.NamedRecipe r : FusionCalc.recipesMaking(output)) {
            if (qty != r.outputCount() && qty != 2.0 * r.outputCount()) continue;
            boolean same = r.left().equalsIgnoreCase(r.right());
            double needLeft = same ? r.leftCount() + r.rightCount() : r.leftCount();
            if (held.qty(r.left()) < needLeft || (!same && held.qty(r.right()) < r.rightCount())) continue;
            long t = Math.max(held.lastBuy(r.left()), held.lastBuy(r.right()));
            if (t > bestTime) {
                bestTime = t;
                best = r;
            }
        }
        Entry e = new Entry();
        e.time = System.currentTimeMillis();
        e.item = output;
        e.qty = qty;
        e.fusion = true;
        if (best != null) {
            e.in1 = best.left();
            e.in1Qty = best.leftCount();
            e.in2 = best.right();
            e.in2Qty = best.rightCount();
        }
        entries.add(e);
        changed();
    }

    private static ProfitLedger.Replay replay() {
        List<Entry> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries);
        }
        return ProfitLedger.replay(copy, FORGES);
    }

    // ---- summary ----

    private static String coins(double v) {
        double a = Math.abs(v);
        String s;
        if (a >= 1_000_000_000) s = String.format("%.2fB", a / 1_000_000_000);
        else if (a >= 1_000_000) s = String.format("%.2fM", a / 1_000_000);
        else if (a >= 10_000) s = String.format("%.1fK", a / 1_000);
        else s = String.format("%,.0f", a);
        return (v < 0 ? "-" : "") + s;
    }

    /** "~" in front of a total that includes estimated forge costs. */
    private static String money(double[] t) {
        return (t[0] >= 0 ? "§a" : "§c") + (t[2] > 0 ? "~" : "") + coins(t[0]);
    }

    private static String line(String label, List<Realized> list, long since) {
        double[] t = ProfitLedger.total(list, since, null);
        return label + ": " + money(t) + " §7(" + (long) t[1] + " sales)";
    }

    private static volatile List<String> cachedLines = List.of();
    private static volatile long cachedAt;

    /** The profit summary shown beside the flip settings screen, recomputed at most once a second. */
    public static List<String> summaryLines() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 1000) return cachedLines;
        List<Realized> list = replay().realized();
        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<String> lines = new ArrayList<>();
        lines.add("§e§lProfit §7(after tax)");
        lines.add("§7" + line("Session", list, SESSION_START));
        lines.add("§7" + line("Today", list, today));
        lines.add("§7" + line("All time", list, 0));
        lines.add("");
        lines.add("§e§lBy mode §8today | all time");
        for (Mode mode : Mode.values()) {
            lines.add("§7" + mode.label + " " + money(ProfitLedger.total(list, today, mode)) + " §8| "
                    + money(ProfitLedger.total(list, 0, mode)));
        }
        if (ProfitLedger.total(list, 0, Mode.FORGE)[2] > 0) lines.add("§8~ forge parts you didn't buy at sale price");
        lines.add("");
        lines.add("§e§lTop items");
        Map<String, Double> byItem = new HashMap<>();
        for (Realized r : list) byItem.merge(r.item(), r.profit(), Double::sum);
        byItem.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(e -> lines.add((e.getValue() >= 0 ? "§a" : "§c") + coins(e.getValue()) + " §7" + e.getKey()));
        if (list.isEmpty()) lines.add("§7No completed flips yet.");
        cachedLines = lines;
        cachedAt = now;
        return lines;
    }

    /** Opens the flip settings screen, which shows the profit summary on the side (kept out of chat). */
    private static int show() {
        Minecraft mc = Minecraft.getInstance();
        mc.schedule(() -> com.github.mkram17.bazaarutils.utils.VersionCompat.setScreen(mc, new FlipSettingsScreen()));
        return 1;
    }

    private static int reset() {
        entries.clear();
        changed();
        PlayerActionUtil.notifyAll("Flip tracker reset.");
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> profitNode() {
        return ClientCommands.literal("profit")
                .executes(ctx -> show())
                .then(ClientCommands.literal("reset").executes(ctx -> reset()));
    }

    @RunOnInit
    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            try {
                onChat(message.getString().replaceAll("§.", ""));
            } catch (Exception ignored) { }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("bazaarflip").then(profitNode()));
            dispatcher.register(ClientCommands.literal("bzprofit")
                    .executes(ctx -> show())
                    .then(ClientCommands.literal("reset").executes(ctx -> reset())));
        });
    }
}
