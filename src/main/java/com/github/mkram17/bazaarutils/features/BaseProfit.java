package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimum profit (in coins) per item for BazaarFlip's flip list. BazaarFlip is patched to call {@link #minMargin(double)}
 * in place of its fixed % margin floor, so the floor becomes whatever % each item needs to earn this many coins.
 * Set with /flipprofit <coins>; 0 turns it off and BazaarFlip's own % setting applies again.
 */
public class BaseProfit {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarflip-baseprofit.json");
    private static volatile double coins = load();

    private static double load() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                return o.get("minProfitPerItem").getAsDouble();
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("activeRanking", activeRanking);
            o.addProperty("reservePercent", reservePercent);
            o.addProperty("targetMinutes", targetMinutes);
            o.addProperty("minProfitPerItem", coins);
            o.addProperty("minFusionProfit", fusionMin);
            o.addProperty("sellTaxPercent", taxPercent);
            o.addProperty("minItemsPerHour", minItemsPerHour);
            o.addProperty("maxVsWeekAveragePercent", avgTolerance);
            o.addProperty("visibleRows", visibleRows);
            o.addProperty("dipMode", dipMode);
            o.addProperty("fusionMode", fusionMode);
            o.addProperty("minDipPercent", minDipPercent);
            o.addProperty("forgeMode", forgeMode);
            o.addProperty("forgeSlots", forgeSlots);
            o.addProperty("quickForgePercent", quickForgePercent);
            o.addProperty("forgeCheckHours", forgeCheckHours);
            o.addProperty("forgeUnlockFilter", forgeUnlockFilter);
            o.addProperty("hotmTier", hotmTier);
            o.addProperty("forgePurseFilter", forgePurseFilter);
            com.google.gson.JsonArray flippersOff = new com.google.gson.JsonArray();
            for (String name : Flippers.offNames()) flippersOff.add(name);
            o.add("flippersOff", flippersOff);
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception ignored) { }
    }

    private static volatile boolean activeRanking = loadBool("activeRanking", true);
    private static volatile double reservePercent = bounded(loadDouble("reservePercent", 10), 0, 95, 10);
    private static volatile double targetMinutes = bounded(loadDouble("targetMinutes", 30), 5, 120, 30);

    private static double bounded(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    public static boolean isActiveRanking() { return activeRanking; }
    public static double getReservePercent() { return reservePercent; }
    public static double getTargetMinutes() { return targetMinutes; }
    public static void setActiveRanking(boolean value) { activeRanking = value; save(); }
    public static void setReservePercent(double value) { reservePercent = bounded(value, 0, 95, 10); save(); }
    public static void setTargetMinutes(double value) { targetMinutes = bounded(value, 5, 120, 30); save(); }

    private static volatile boolean dipMode = loadBool("dipMode", false);
    private static volatile double minDipPercent = loadDouble("minDipPercent", 30);

    private static boolean loadBool(String key, boolean fallback) {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has(key)) return o.get(key).getAsBoolean();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private static double loadDouble(String key, double fallback) {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has(key)) return o.get(key).getAsDouble();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private static volatile boolean fusionMode = loadBool("fusionMode", false);
    private static volatile boolean forgeMode = loadBool("forgeMode", false);
    private static volatile int forgeSlots = clampSlots((int) loadDouble("forgeSlots", 7));
    private static volatile double quickForgePercent = clampQuickForge(loadDouble("quickForgePercent", 0));
    private static volatile double forgeCheckHours = clampCheckHours(loadDouble("forgeCheckHours", 2));

    private static volatile boolean forgeUnlockFilter = loadBool("forgeUnlockFilter", false);
    private static volatile int hotmTier = clampHotm((int) loadDouble("hotmTier", 10));
    private static volatile boolean forgePurseFilter = loadBool("forgePurseFilter", false);
    private static final boolean flippersLoaded = loadFlippers();

    // the /bazaarflip flipper checkboxes: names of the ones that are off (none = all on)
    private static boolean loadFlippers() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("flippersOff")) {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (var e : o.getAsJsonArray("flippersOff")) names.add(e.getAsString());
                    Flippers.load(names);
                }
            }
        } catch (Exception ignored) { }
        return true;
    }

    /** Turns a flipper on or off (the /bazaarflip checkboxes) and saves it. */
    public static void setFlipperOn(Flippers.Flipper f, boolean on) {
        Flippers.set(f, on);
        save();
    }

    private static int clampHotm(int value) {
        return Math.max(0, Math.min(10, value));
    }

    /** Forge list: only forge recipes your HotM tier allows (parts you can't forge are bought instead). Off by default. */
    public static boolean isForgeUnlockFilter() {
        return forgeUnlockFilter;
    }

    public static void setForgeUnlockFilter(boolean value) {
        forgeUnlockFilter = value;
        save();
    }

    /** Your Heart of the Mountain tier (0-10), used by the unlock filter. */
    public static int getHotmTier() {
        return hotmTier;
    }

    public static void setHotmTier(int value) {
        hotmTier = clampHotm(value);
        save();
    }

    /** Forge list: only routes whose up-front cost fits the purse on your scoreboard. Off by default. */
    public static boolean isForgePurseFilter() {
        return forgePurseFilter;
    }

    public static void setForgePurseFilter(boolean value) {
        forgePurseFilter = value;
        save();
    }

    private static double clampCheckHours(double value) {
        return Math.max(0, Math.min(48, value));
    }

    /**
     * How often you come back to collect and restart forges, in hours (0-48). A forge holds its slot until collected, so
     * this keeps 30-second forges from looking like they run 120 times an hour while you're logged off.
     */
    public static double getForgeCheckHours() {
        return forgeCheckHours;
    }

    public static void setForgeCheckHours(double value) {
        forgeCheckHours = clampCheckHours(value);
        save();
    }

    private static int clampSlots(int value) {
        return Math.max(1, Math.min(7, value));
    }

    private static double clampQuickForge(double value) {
        return Math.max(0, Math.min(30, value));
    }

    /** Forge slots unlocked (HOTM), 1-7: the forge list's profit per hour runs the route in every slot. */
    public static int getForgeSlots() {
        return forgeSlots;
    }

    public static void setForgeSlots(int value) {
        forgeSlots = clampSlots(value);
        save();
    }

    /** Quick Forge perk, 0-30%: every forge takes this much less time. */
    public static double getQuickForgePercent() {
        return quickForgePercent;
    }

    public static void setQuickForgePercent(double value) {
        quickForgePercent = clampQuickForge(value);
        save();
    }

    /** Dip mode: list items whose buy price is far below its 7-day normal while the sell price is still normal. */
    public static boolean isDipMode() {
        return dipMode;
    }

    public static void setDipMode(boolean value) {
        dipMode = value;
        if (value) {
            fusionMode = false;
            forgeMode = false;
        }
        save();
    }

    /** Fusion mode: the list shows attribute shard fusions instead of Bazaar flips. */
    public static boolean isFusionMode() {
        return fusionMode;
    }

    /** 0 = flips, 1 = dips, 2 = fusions, 3 = forge. */
    public static int getListMode() {
        return forgeMode ? 3 : fusionMode ? 2 : dipMode ? 1 : 0;
    }

    public static void setListMode(int mode) {
        dipMode = mode == 1;
        fusionMode = mode == 2;
        forgeMode = mode == 3;
        save();
    }

    public static double getMinDipPercent() {
        return minDipPercent;
    }

    public static void setMinDipPercent(double value) {
        minDipPercent = Math.max(0, Math.min(99, value));
        save();
    }

    /**
     * How far below its normal the buy price is, in percent (negative if above), or NaN if history isn't loaded. Normal is the
     * 7-day median, or the last 24 hours' when that is lower (MarketShift.normalBid): a level held all day isn't a dip.
     */
    public static double dipPercent(String id, double buyPrice) {
        Average a = averages.get(id);
        if (a == null || a.failed() || a.medianSell() <= 0) return Double.NaN;
        MarketShift s = MarketShift.of(id);
        if (s != null) return s.dipPercent(buyPrice);
        return (a.medianSell() - buyPrice) / a.medianSell() * 100.0;
    }

    /** The item's typical buy price (what the top buy order normally is, see {@link #dipPercent}), or NaN. */
    public static double typicalBuy(String id) {
        Average a = averages.get(id);
        if (a == null || a.failed()) return Double.NaN;
        MarketShift s = MarketShift.of(id);
        return s != null ? s.normalBid() : a.medianSell();
    }

    private static volatile int visibleRows = loadVisibleRows();

    private static int loadVisibleRows() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("visibleRows")) return Math.max(1, o.get("visibleRows").getAsInt());
            }
        } catch (Exception ignored) { }
        return 7;
    }

    /** How many flips the on-screen list shows at once; the rest are reached by scrolling. */
    public static int getVisibleRows() {
        return visibleRows;
    }

    public static void setVisibleRows(int value) {
        visibleRows = Math.max(1, value);
        save();
    }

    private static volatile double avgTolerance = loadAvgTolerance();

    private static double loadAvgTolerance() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("maxVsWeekAveragePercent")) return o.get("maxVsWeekAveragePercent").getAsDouble();
            }
        } catch (Exception ignored) { }
        return 20;
    }

    public static double getAvgTolerance() {
        return avgTolerance;
    }

    public static void setAvgTolerance(double value) {
        avgTolerance = Math.max(0, value);
        save();
    }

    public static double getTaxPercent() {
        return taxPercent;
    }

    public static void setTaxPercent(double value) {
        if (!Double.isFinite(value)) return;
        taxPercent = Math.max(0, Math.min(99.99, value));
        save();
    }

    // ---- 7-day average price filter (history from Coflnet) ----

    private record Average(double medianBuy, double medianSell, long fetchedAt, boolean failed) { }

    private static final java.util.Map<String, Average> averages = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ExecutorService historyFetcher = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "BazaarFlip-history");
        t.setDaemon(true);
        return t;
    });
    private static final long AVERAGE_TTL_MS = 20 * 60 * 1000L;
    private static final long FAILED_RETRY_MS = 2 * 60 * 1000L;

    private static double median(java.util.List<Double> values) {
        if (values.isEmpty()) return 0;
        java.util.Collections.sort(values);
        return values.get(values.size() / 2);
    }

    private static void fetchAverage(String id) {
        if (!inFlight.add(id)) return;
        historyFetcher.submit(() -> {
            Average result;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI
                        .create("https://sky.coflnet.com/api/bazaar/" + id + "/history/week").toURL().openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "BazaarUtils-FlipFilter");
                java.util.List<Double> buys = new java.util.ArrayList<>(), sells = new java.util.ArrayList<>();
                try (java.io.InputStream in = c.getInputStream()) {
                    com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(new String(in.readAllBytes())).getAsJsonArray();
                    for (com.google.gson.JsonElement e : arr) {
                        JsonObject o = e.getAsJsonObject();
                        if (o.has("buy") && o.get("buy").getAsDouble() > 0) buys.add(o.get("buy").getAsDouble());
                        if (o.has("sell") && o.get("sell").getAsDouble() > 0) sells.add(o.get("sell").getAsDouble());
                    }
                    MarketShift.store(id, arr); // the last 24 hours vs the 7 days, from this same download
                }
                result = buys.size() >= 6 && sells.size() >= 6
                        ? new Average(median(buys), median(sells), System.currentTimeMillis(), false)
                        : new Average(0, 0, System.currentTimeMillis(), true);
            } catch (Exception e) {
                result = new Average(0, 0, System.currentTimeMillis(), true);
            }
            averages.put(id, result);
            inFlight.remove(id);
        });
    }

    /**
     * Called by the patched BazaarFlip. Rejects flips whose prices are far from the 7-day average, which is what a bought-out
     * or dumped item looks like: the order book jumps so the spread looks huge, but nothing will actually fill at that price.
     * buyPrice is what you would pay (top buy order), sellPrice is what you would receive (lowest sell offer).
     * While the history for an item is still loading it is hidden; if history is unavailable it is let through.
     */
    public static boolean passesAverage(String id, double buyPrice, double sellPrice) {
        if (avgTolerance <= 0 && !dipMode) return true;
        Average a = averages.get(id);
        long now = System.currentTimeMillis();
        if (a == null || now - a.fetchedAt() > (a.failed() ? FAILED_RETRY_MS : AVERAGE_TTL_MS)) {
            fetchAverage(id);
            if (a == null) return false;
        }
        if (a.failed()) return !dipMode; // in dip mode an unverified item is not a dip
        double tol = (avgTolerance > 0 ? avgTolerance : 25) / 100.0;
        MarketShift s = MarketShift.of(id); // a market that moved and held: its new level is normal (null = 7-day rules)
        if (s != null) return dipMode ? s.isDip(buyPrice, sellPrice, minDipPercent, tol) : s.bandOk(buyPrice, sellPrice, tol);
        if (dipMode) {
            // cheap to buy (buy price well under its normal) but the sell price is still believable
            return buyPrice <= a.medianSell() * (1 - minDipPercent / 100.0) && sellPrice <= a.medianBuy() * (1 + tol);
        }
        return sellPrice <= a.medianBuy() * (1 + tol) && buyPrice >= a.medianSell() * (1 - tol);
    }

    /**
     * Single-price version of the average check, used by book crafting flips. isAsk is true for a price you would sell at (the
     * lowest sell offer) and false for one you would pay (the top buy order). Hidden while history loads, allowed if unavailable.
     */
    public static boolean priceOk(String id, double price, boolean isAsk) {
        if (avgTolerance <= 0) return true;
        Average a = averages.get(id);
        long now = System.currentTimeMillis();
        if (a == null || now - a.fetchedAt() > (a.failed() ? FAILED_RETRY_MS : AVERAGE_TTL_MS)) {
            fetchAverage(id);
            if (a == null) return false;
        }
        if (a.failed()) return true;
        double tol = avgTolerance / 100.0;
        MarketShift s = MarketShift.of(id);
        if (s != null) return s.priceOk(price, isAsk, tol);
        return isAsk ? price <= a.medianBuy() * (1 + tol) : price >= a.medianSell() * (1 - tol);
    }

    /** True while price history is still being downloaded in the background. */
    public static boolean historyLoading() {
        return !inFlight.isEmpty();
    }

    private static volatile double minItemsPerHour = loadMinItems();

    private static double loadMinItems() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("minItemsPerHour")) return o.get("minItemsPerHour").getAsDouble();
            }
        } catch (Exception ignored) { }
        return 0;
    }

    public static double getMinItemsPerHour() {
        return minItemsPerHour;
    }

    public static void setMinItemsPerHour(double value) {
        minItemsPerHour = Math.max(0, value);
        save();
    }

    /** Called by the patched BazaarFlip: true if the flip fills at least the minimum items per hour (slower side of 7-day volume). */
    public static boolean passesRate(JsonObject quickStatus) {
        if (minItemsPerHour <= 0) return true;
        return Math.min(MarketShift.perHour(quickStatus, "buyMovingWeek"), MarketShift.perHour(quickStatus, "sellMovingWeek")) >= minItemsPerHour;
    }

    private static volatile double taxPercent = loadTax();

    private static double loadTax() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("sellTaxPercent")) {
                    double value = o.get("sellTaxPercent").getAsDouble();
                    if (Double.isFinite(value) && value >= 0 && value < 100) return value;
                }
            }
        } catch (Exception ignored) { }
        return 1.25;
    }

    /** Called by the patched BazaarFlip on every sell price: what you actually receive after the Bazaar sell tax. */
    public static double afterTax(double sellPrice) {
        return sellPrice * (1.0 - taxPercent / 100.0);
    }

    /**
     * Called by the patched BazaarFlip in place of Executors.newScheduledThreadPool(n). Its stock scheduler threads are
     * not daemon threads, so they keep the JVM alive after the game closes and the launcher reports a crash.
     */
    public static java.util.concurrent.ScheduledExecutorService daemonScheduler(int threads) {
        // its periodic Bazaar checks are skipped while the flip list is paused
        return FlipPause.pausableScheduler(threads, runnable -> {
            Thread thread = new Thread(runnable, "BazaarFlip-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static double week(com.google.gson.JsonObject quickStatus, String field) {
        try {
            return quickStatus.get(field).getAsDouble();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Called by the patched BazaarFlip in place of "total profit". How many items you would fill in an hour as the top
     * offer: the 7-day moving volume divided by 168 hours, taking the slower of the buy and sell side because both have to
     * fill for a flip to complete. Multiplied by profit per item (already after tax) that is profit per hour.
     */
    public static double profitPerHour(com.google.gson.JsonObject quickStatus, double profitPerItem) {
        double perHour = Math.min(MarketShift.perHour(quickStatus, "buyMovingWeek"), MarketShift.perHour(quickStatus, "sellMovingWeek"));
        return profitPerItem * perHour;
    }

    private static final double FULL_INVENTORY = 71680.0;
    private static java.lang.reflect.Field maxSpendField;

    /**
     * Called by the patched BazaarFlip in place of its fixed 71680-item quantity. With a base profit set, expensive items
     * are sized to the Max Spend Limit instead of being rejected for not affording a full inventory.
     */
    public static double qty(double buyPrice) {
        if (coins <= 0 || buyPrice <= 0) return FULL_INVENTORY;
        try {
            if (maxSpendField == null) {
                maxSpendField = Class.forName("uwu.ramona.bazaar.config.BazaarConfig").getField("maxSpendLimit");
            }
            double affordable = Math.floor(maxSpendField.getInt(null) / buyPrice);
            if (affordable < 1) return FULL_INVENTORY; // can't afford even one: stays rejected
            return Math.min(FULL_INVENTORY, affordable);
        } catch (Exception e) {
            return FULL_INVENTORY;
        }
    }

    /**
     * Called by the patched BazaarFlip in place of its fixed min-margin setting. Both minimums apply: the margin has to
     * clear the configured % and also be enough to earn the min profit per item at this item's price.
     */
    public static double minMargin(double buyPrice, double configuredMinMargin) {
        if (coins <= 0 || buyPrice <= 0) return configuredMinMargin;
        return Math.max(configuredMinMargin, coins / buyPrice * 100.0);
    }

    @RunOnInit
    public static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("flipminrate")
                    .executes(ctx -> {
                        PlayerActionUtil.notifyAll(minItemsPerHour <= 0
                                ? "Min items per hour is off. Use /flipminrate <items per hour>."
                                : "Min items per hour: " + format(minItemsPerHour));
                        return 1;
                    })
                    .then(ClientCommands.argument("items", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> {
                                setMinItemsPerHour(DoubleArgumentType.getDouble(ctx, "items"));
                                PlayerActionUtil.notifyAll(minItemsPerHour <= 0
                                        ? "Min items per hour turned off."
                                        : "Min items per hour set to " + format(minItemsPerHour) + ".");
                                return 1;
                            })));
            dispatcher.register(ClientCommands.literal("fliptax")
                    .executes(ctx -> {
                        PlayerActionUtil.notifyAll("Bazaar sell tax for flips: " + format(taxPercent) + "%. Use /fliptax <percent> to change it.");
                        return 1;
                    })
                    .then(ClientCommands.argument("percent", DoubleArgumentType.doubleArg(0, 99.99))
                            .executes(ctx -> {
                                setTaxPercent(DoubleArgumentType.getDouble(ctx, "percent"));
                                PlayerActionUtil.notifyAll("Bazaar sell tax set to " + format(taxPercent) + "%.");
                                return 1;
                            })));
            dispatcher.register(ClientCommands.literal("flipfusionmin")
                        .executes(ctx -> {
                            PlayerActionUtil.notifyAll(fusionMin <= 0
                                    ? "Fusion min profit not set: fusions use the shared min (" + format(coins) + "). Use /flipfusionmin <coins>."
                                    : "Fusion min profit: " + format(fusionMin) + " coins per fusion.");
                            return 1;
                        })
                        .then(ClientCommands.argument("coins", DoubleArgumentType.doubleArg(0))
                                .executes(ctx -> {
                                    setFusionMin(DoubleArgumentType.getDouble(ctx, "coins"));
                                    PlayerActionUtil.notifyAll(fusionMin <= 0
                                            ? "Fusion min profit cleared: fusions use the shared min (" + format(coins) + ") again."
                                            : "Fusion min profit set to " + format(fusionMin) + " coins per fusion.");
                                    return 1;
                                })));
            dispatcher.register(ClientCommands.literal("flipprofit")
                        .executes(ctx -> {
                            PlayerActionUtil.notifyAll(coins <= 0
                                    ? "Base profit is off. Use /flipprofit <coins per item>."
                                    : "Base profit: " + format(coins) + " coins per item.");
                            return 1;
                        })
                        .then(ClientCommands.argument("coins", DoubleArgumentType.doubleArg(0))
                                .executes(ctx -> {
                                    coins = DoubleArgumentType.getDouble(ctx, "coins");
                                    save();
                                    PlayerActionUtil.notifyAll(coins <= 0
                                            ? "Base profit turned off."
                                            : "Base profit set to " + format(coins) + " coins per item.");
                                    return 1;
                                })));
        });
    }

    public static double getCoins() {
        return coins;
    }

    // min profit per whole shard fusion; 0 = use the shared min profit per item
    private static volatile double fusionMin = Math.max(0, loadDouble("minFusionProfit", 0));

    /** The fusion-only minimum as set (0 = not set). */
    public static double getFusionMin() {
        return fusionMin;
    }

    public static void setFusionMin(double value) {
        fusionMin = Math.max(0, value);
        save();
    }

    /** The minimum profit per fusion in force: the fusion-only one if set, else the shared min profit per item. */
    public static double fusionMinProfit() {
        return FusionPotential.minProfit(fusionMin, coins);
    }

    public static void setCoins(double value) {
        coins = Math.max(0, value);
        save();
    }

    public static String format(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }
}
