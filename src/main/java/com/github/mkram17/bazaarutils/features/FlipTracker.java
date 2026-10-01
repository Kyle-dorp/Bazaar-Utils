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

    private static class Entry {
        long time;
        String item;
        boolean buy;
        double qty;
        double coins; // total paid (buy) or total received after tax (sell)
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
        }
    }

    private record Realized(long time, String item, double profit) { }

    /** Replays every entry in order; each sale becomes one realized profit against that item's running average cost. */
    private static List<Realized> realized() {
        List<Entry> copy;
        synchronized (entries) {
            copy = new ArrayList<>(entries);
        }
        copy.sort(Comparator.comparingLong(e -> e.time));
        Map<String, double[]> stock = new HashMap<>(); // item -> {qty held, total cost}
        List<Realized> out = new ArrayList<>();
        for (Entry e : copy) {
            double[] s = stock.computeIfAbsent(e.item, k -> new double[2]);
            if (e.buy) {
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
        return out;
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
        return label + ": " + coins(total) + " coins (" + count + " sales)";
    }

    private static int show() {
        List<Realized> list = realized();
        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        PlayerActionUtil.notifyAll("Bazaar profit (after tax, average cost)");
        PlayerActionUtil.notifyAll(line("Session", list, SESSION_START));
        PlayerActionUtil.notifyAll(line("Today", list, today));
        PlayerActionUtil.notifyAll(line("All time", list, 0));

        Map<String, Double> byItem = new HashMap<>();
        for (Realized r : list) byItem.merge(r.item, r.profit, Double::sum);
        byItem.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> PlayerActionUtil.notifyAll("  " + e.getKey() + ": " + coins(e.getValue())));
        if (list.isEmpty()) {
            PlayerActionUtil.notifyAll("No completed flips yet. Buy and sell on the Bazaar and they will show up here.");
        }
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
