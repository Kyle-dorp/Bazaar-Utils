package com.github.mkram17.bazaarutils.features;

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
 * Tracks Bazaar profit from chat messages. Every buy and sell is stored; profit is realized profit using the average
 * cost of what you bought: selling 100 items that cost you 50 each on average for 80 each is 3,000 profit.
 * Sell proceeds already have the Bazaar tax taken out. Show it with /bazaarflip profit.
 */
public class FlipTracker {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarflip-tracker.json");
    private static final long SESSION_START = System.currentTimeMillis();

    private static final Pattern BUY_CLAIM = Pattern.compile("^\\[Bazaar\\] Claimed ([\\d,]+)x (.+) worth [\\d,.]+ coins bought for ([\\d,.]+) each!");
    private static final Pattern SELL_CLAIM = Pattern.compile("^\\[Bazaar\\] Claimed ([\\d,.]+) coins from selling ([\\d,]+)x (.+) at [\\d,.]+ each!");
    private static final Pattern INSTA_SELL = Pattern.compile("^\\[Bazaar\\] Sold ([\\d,]+)x (.+) for ([\\d,.]+) coins!");
    private static final Pattern INSTA_BUY = Pattern.compile("^\\[Bazaar\\] Bought ([\\d,]+)x (.+) for ([\\d,.]+) coins!");

    // "FUSION! You obtained Troglobyte Shard x2!" / "FUSION! You obtained a Stoneworm Shard!" (from your chat logs)
    private static final Pattern FUSION = Pattern.compile("^FUSION! You obtained (?:an? )?(.+? Shard)(?: x([\\d,]+))?!");

    private static class Entry {
        long time;
        String item;
        boolean buy;
        double qty;
        double coins; // total paid (buy) or total received after tax (sell)
        // a fusion: `item` x `qty` came out of the Fusion Machine, made from these inputs (null if they couldn't be matched)
        boolean fusion;
        String in1;
        double in1Qty;
        String in2;
        double in2Qty;
    }

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

    private static void record(String item, boolean buy, double qty, double coins) {
        Entry e = new Entry();
        e.time = System.currentTimeMillis();
        e.item = item.trim();
        e.buy = buy;
        e.qty = qty;
        e.coins = coins;
        entries.add(e);
        save();
    }

    private static void onChat(String msg) {
        Matcher m;
        if ((m = BUY_CLAIM.matcher(msg)).find()) {
            double qty = num(m.group(1));
            record(m.group(2), true, qty, qty * num(m.group(3)));
        } else if ((m = SELL_CLAIM.matcher(msg)).find()) {
            record(m.group(3), false, num(m.group(2)), num(m.group(1)));
        } else if ((m = INSTA_SELL.matcher(msg)).find()) {
            record(m.group(2), false, num(m.group(1)), num(m.group(3)));
        } else if ((m = INSTA_BUY.matcher(msg)).find()) {
            record(m.group(2), true, num(m.group(1)), num(m.group(3)));
        } else if ((m = FUSION.matcher(msg)).find()) {
            onFusion(m.group(1).trim(), m.group(2) == null ? 1 : num(m.group(2)));
        }
    }

    /**
     * The fusion message names only the output, so the inputs are worked out: of the wiki recipes that make this shard (in
     * this amount, or double for a Pure Reptile proc), keep the ones whose inputs you're holding according to your tracked
     * Bazaar buys, and pick the one whose inputs you bought most recently. Those inputs are then used up at their average
     * cost and become the cost of the output. If nothing matches, the output has no known cost and its sale isn't counted.
     */
    private static void onFusion(String output, double qty) {
        Replay held = replay();
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
        save();
    }

    private record Realized(long time, String item, double profit) { }

    private record Replay(Map<String, double[]> stock, Map<String, Long> lastBuys, List<Realized> realized) {
        double qty(String item) {
            double[] s = stock.get(item);
            return s == null ? 0 : s[0];
        }

        long lastBuy(String item) {
            return lastBuys.getOrDefault(item, 0L);
        }
    }

    private static List<Realized> realized() {
        return replay().realized();
    }

    /** Replays every entry in order; each sale becomes one realized profit against that item's running average cost. */
    private static Replay replay() {
        List<Entry> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries);
        }
        copy.sort(Comparator.comparingLong(e -> e.time));
        Map<String, double[]> stock = new HashMap<>(); // item -> {qty held, total cost}
        Map<String, Long> lastBuys = new HashMap<>();
        List<Realized> out = new ArrayList<>();
        for (Entry e : copy) {
            double[] s = stock.computeIfAbsent(e.item, k -> new double[2]);
            if (e.fusion) {
                if (e.in1 == null) continue; // inputs unknown: the output has no cost basis
                double cost = 0;
                boolean covered = true;
                for (int i = 0; i < 2; i++) {
                    String in = i == 0 ? e.in1 : e.in2;
                    double need = i == 0 ? e.in1Qty : e.in2Qty;
                    double[] si = stock.computeIfAbsent(in, k -> new double[2]);
                    if (si[0] < need) {
                        covered = false;
                        break;
                    }
                    double basis = si[1] / si[0] * need;
                    si[0] -= need;
                    si[1] -= basis;
                    cost += basis;
                }
                if (covered) {
                    s[0] += e.qty;
                    s[1] += cost;
                }
                continue;
            }
            if (e.buy) {
                lastBuys.put(e.item, e.time);
                s[0] += e.qty;
                s[1] += e.coins;
            } else if (s[0] > 0) {
                double sold = Math.min(e.qty, s[0]);
                double avg = s[1] / s[0];
                double basis = avg * sold;
                double proceeds = e.coins * (sold / e.qty);
                s[0] -= sold;
                s[1] -= basis;
                out.add(new Realized(e.time, e.item, proceeds - basis));
            }
            // selling items with no recorded purchase has no cost basis, so it isn't counted
        }
        return new Replay(stock, lastBuys, out);
    }

    private static String coins(double v) {
        double a = Math.abs(v);
        String s;
        if (a >= 1_000_000_000) s = String.format("%.2fB", a / 1_000_000_000);
        else if (a >= 1_000_000) s = String.format("%.2fM", a / 1_000_000);
        else if (a >= 10_000) s = String.format("%.1fK", a / 1_000);
        else s = String.format("%,.0f", a);
        return (v < 0 ? "-" : "") + s;
    }

    private static String line(String label, List<Realized> list, long since) {
        double total = 0;
        int count = 0;
        for (Realized r : list) {
            if (r.time >= since) {
                total += r.profit;
                count++;
            }
        }
        return label + ": " + (total >= 0 ? "§a" : "§c") + coins(total) + " §7(" + count + " sales)";
    }

    private static volatile List<String> cachedLines = List.of();
    private static volatile long cachedAt;

    /** The profit summary shown beside the flip settings screen, recomputed at most once a second. */
    public static List<String> summaryLines() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 1000) return cachedLines;
        List<Realized> list = realized();
        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<String> lines = new ArrayList<>();
        lines.add("§e§lProfit §7(after tax)");
        lines.add("§7" + line("Session", list, SESSION_START));
        lines.add("§7" + line("Today", list, today));
        lines.add("§7" + line("All time", list, 0));
        lines.add("");
        lines.add("§e§lTop items");
        Map<String, Double> byItem = new HashMap<>();
        for (Realized r : list) byItem.merge(r.item, r.profit, Double::sum);
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
        save();
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
