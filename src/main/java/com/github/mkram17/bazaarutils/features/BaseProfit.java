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
            o.addProperty("minProfitPerItem", coins);
            o.addProperty("sellTaxPercent", taxPercent);
            o.addProperty("minItemsPerHour", minItemsPerHour);
            o.addProperty("maxVsWeekAveragePercent", avgTolerance);
            o.addProperty("visibleRows", visibleRows);
            o.addProperty("dipMode", dipMode);
            o.addProperty("minDipPercent", minDipPercent);
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception ignored) { }
    }

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

    /** Dip mode: list items whose buy price is far below its 7-day normal while the sell price is still normal. */
    public static boolean isDipMode() {
        return dipMode;
    }

    public static void setDipMode(boolean value) {
        dipMode = value;
        save();
    }

    public static double getMinDipPercent() {
        return minDipPercent;
    }

    public static void setMinDipPercent(double value) {
        minDipPercent = Math.max(0, Math.min(99, value));
        save();
    }

    /** How far below its 7-day normal the buy price is, in percent (negative if above), or NaN if history isn't loaded. */
    public static double dipPercent(String id, double buyPrice) {
        Average a = averages.get(id);
        if (a == null || a.failed() || a.medianSell() <= 0) return Double.NaN;
        return (a.medianSell() - buyPrice) / a.medianSell() * 100.0;
    }

    /** The item's typical 7-day buy price (what the top buy order normally is), or NaN. */
    public static double typicalBuy(String id) {
        Average a = averages.get(id);
        return a == null || a.failed() ? Double.NaN : a.medianSell();
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
        taxPercent = Math.max(0, Math.min(100, value));
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
        if (dipMode) {
            // cheap to buy (buy price well under its normal) but the sell price is still believable
            return buyPrice <= a.medianSell() * (1 - minDipPercent / 100.0) && sellPrice <= a.medianBuy() * (1 + tol);
        }
        return sellPrice <= a.medianBuy() * (1 + tol) && buyPrice >= a.medianSell() * (1 - tol);
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
        return Math.min(week(quickStatus, "buyMovingWeek"), week(quickStatus, "sellMovingWeek")) / 168.0 >= minItemsPerHour;
    }

    private static volatile double taxPercent = loadTax();

    private static double loadTax() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("sellTaxPercent")) return o.get("sellTaxPercent").getAsDouble();
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
        return java.util.concurrent.Executors.newScheduledThreadPool(threads, runnable -> {
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
        double perHour = Math.min(week(quickStatus, "buyMovingWeek"), week(quickStatus, "sellMovingWeek")) / 168.0;
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
                    .then(ClientCommands.argument("percent", DoubleArgumentType.doubleArg(0, 100))
                            .executes(ctx -> {
                                taxPercent = DoubleArgumentType.getDouble(ctx, "percent");
                                save();
                                PlayerActionUtil.notifyAll("Bazaar sell tax set to " + format(taxPercent) + "%.");
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

    public static void setCoins(double value) {
        coins = Math.max(0, value);
        save();
    }

    public static String format(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }
}
