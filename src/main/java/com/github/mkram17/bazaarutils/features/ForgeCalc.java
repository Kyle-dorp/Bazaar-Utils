package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.ForgePlanner.Recipe;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Route;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Step;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forge flips: for every Dwarven Forge recipe, the most profitable route when each part can be bought or forged yourself
 * (see {@link ForgePlanner}), ranked by profit per hour across your forge slots. Recipes come from the NEU item repo, bundled
 * as forge-recipes.json and rebuilt with tools/forge/build_forge_recipes.py.
 *
 * <p>Prices: Bazaar items are bought with buy orders (top bid) and sold with sell offers after tax, both passing the same
 * 7-day average check as the flip list. Everything else is priced from the auction house via Skyblocker's lowest-BIN feed:
 * bought at the lowest BIN, sold at the lower of the lowest BIN and its 3-day average (a single cheap or inflated listing
 * swings the BIN a lot; the SX-R326 sat at 16.8M BIN against a 9.4M 3-day average) minus the AH listing fee and claim tax.
 * Profit per hour is capped by how many the market takes: Bazaar outputs by their 7-day sell volume, AH outputs by Coflnet's
 * recent sales per day (without that cap a 30-second AH upgrade "earns" billions an hour). An AH item stays hidden until its
 * volume has loaded rather than showing an uncapped number.
 */
public class ForgeCalc {
    /** A ranked forge flip: the route, the recipe it forges, profit per hour across your slots, and how the output sells. */
    public record Row(Route route, Recipe recipe, double perHour, boolean soldOnAh, double lowestBin, double avgBin,
                      double marketPerHour) {
        public double perDay() {
            return perHour * 24;
        }
    }

    private static final long REFRESH_MS = 60_000;
    private static final long AH_MAX_AGE_MS = 5 * 60_000;
    private static final String LOWEST_BIN = "https://hysky.de/api/auctions/lowestbins";
    private static final String AVERAGE_BIN = "https://hysky.de/api/auctions/lowestbins/average?days=3";
    private static final String COFLNET_PRICE = "https://sky.coflnet.com/api/item/price/";
    private static final long VOLUME_TTL_MS = 30 * 60_000, VOLUME_RETRY_MS = 5 * 60_000;
    private static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

    private record Volume(double perDay, long fetchedAt, boolean failed) { }

    private static final Map<String, Volume> volumes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> volumeInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ExecutorService volumeFetcher = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "BazaarFlip-forge-volume");
        t.setDaemon(true);
        return t;
    });

    private static Map<String, Recipe> recipes;
    private static Map<String, String> names = Map.of();
    private static Map<String, Double> lowestBins = Map.of(), averageBins = Map.of();
    private static long ahFetchedAt;
    private static volatile List<Row> rows = List.of();
    private static volatile long lastRefresh;
    private static volatile boolean running;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BazaarFlip-forge");
        t.setDaemon(true);
        return t;
    });

    // ---- data ----

    static synchronized Map<String, Recipe> recipes() {
        if (recipes != null) return recipes;
        Map<String, Recipe> map = new LinkedHashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        try (InputStream in = ForgeCalc.class.getResourceAsStream("/assets/bazaarutils/forge-recipes.json")) {
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.getAsJsonArray("recipes")) {
                JsonObject r = e.getAsJsonObject();
                Map<String, Double> inputs = new LinkedHashMap<>();
                for (var i : r.getAsJsonObject("inputs").entrySet()) inputs.put(i.getKey(), i.getValue().getAsDouble());
                String id = r.get("id").getAsString();
                map.put(id, new Recipe(id, r.get("count").getAsInt(), r.get("seconds").getAsDouble(), r.get("coins").getAsDouble(), inputs));
            }
            for (var n : o.getAsJsonObject("names").entrySet()) nameMap.put(n.getKey(), n.getValue().getAsString());
        } catch (Exception e) {
            map.clear();
        }
        names = nameMap;
        recipes = map;
        return recipes;
    }

    /** In-game name for any forge item or part, including AH items the Bazaar name list doesn't know. */
    public static String name(String id) {
        recipes();
        String n = names.get(id);
        return n != null ? n : ItemNames.of(id);
    }

    static Map<String, Double> fetchPrices(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "BazaarUtils-ForgeFlips");
        Map<String, Double> out = new HashMap<>();
        try (InputStream in = c.getInputStream()) {
            for (var e : JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsDouble());
            }
        }
        return out;
    }

    private static synchronized void refreshAuctionPrices() {
        if (System.currentTimeMillis() - ahFetchedAt < AH_MAX_AGE_MS && !lowestBins.isEmpty()) return;
        try {
            lowestBins = fetchPrices(LOWEST_BIN);
            averageBins = fetchPrices(AVERAGE_BIN);
            ahFetchedAt = System.currentTimeMillis();
        } catch (Exception ignored) {
            // keep the previous prices; recipes needing AH items drop out if there never were any
        }
    }

    // ---- AH item keys and sales volume ----

    /** NEU writes pets as NAME;rarity (AMMONITE;4). The lowest-BIN feed keys forged (level 1) pets as LVL_1_LEGENDARY_AMMONITE. */
    static String ahKey(String id) {
        int semi = id.indexOf(';');
        if (semi < 0) return id;
        int rarity = Integer.parseInt(id.substring(semi + 1));
        return "LVL_1_" + RARITIES[Math.max(0, Math.min(RARITIES.length - 1, rarity))] + "_" + id.substring(0, semi);
    }

    /** Coflnet's item tag: pets are PET_NAME (its volume then covers every level and rarity of that pet). */
    static String coflnetTag(String id) {
        int semi = id.indexOf(';');
        return semi < 0 ? id : "PET_" + id.substring(0, semi);
    }

    static boolean isPet(String id) {
        return id.indexOf(';') >= 0;
    }

    /** Recent AH sales per day from Coflnet ("volume" tracks the last day's hourly history closely). */
    static double fetchSalesPerDay(String id) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(COFLNET_PRICE + coflnetTag(id)).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "BazaarUtils-ForgeFlips");
        try (InputStream in = c.getInputStream()) {
            return JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject().get("volume").getAsDouble();
        }
    }

    /** AH sales per hour for an item, NaN while it is loading or unavailable (a background fetch is started). */
    static double ahSalesPerHour(String id) {
        Volume v = volumes.get(id);
        long now = System.currentTimeMillis();
        if (v == null || now - v.fetchedAt() > (v.failed() ? VOLUME_RETRY_MS : VOLUME_TTL_MS)) {
            if (volumeInFlight.add(id)) {
                volumeFetcher.submit(() -> {
                    Volume result;
                    try {
                        result = new Volume(fetchSalesPerDay(id), System.currentTimeMillis(), false);
                    } catch (Exception e) {
                        result = new Volume(0, System.currentTimeMillis(), true);
                    }
                    volumes.put(id, result);
                    volumeInFlight.remove(id);
                });
            }
        }
        return v == null || v.failed() ? Double.NaN : v.perDay() / 24.0;
    }

    // ---- AH pricing rules (pure, unit tested) ----

    /** Auction house cost of selling at this price: listing fee (1% under 10M, 2% under 100M, 2.5% above) + 1% claim tax over 1M. */
    static double ahFeeFraction(double price) {
        double listing = price < 10_000_000 ? 0.01 : price < 100_000_000 ? 0.02 : 0.025;
        double claim = price >= 1_000_000 ? 0.01 : 0;
        return listing + claim;
    }

    /** What selling one on the AH realistically brings: the lower of lowest BIN and its 3-day average, after fees. NaN if unlisted. */
    static double ahSellValue(Double lowestBin, Double averageBin) {
        if (lowestBin == null || !(lowestBin > 0)) return Double.NaN;
        double price = averageBin != null && averageBin > 0 ? Math.min(lowestBin, averageBin) : lowestBin;
        return price * (1 - ahFeeFraction(price));
    }

    // ---- ranking ----

    public static List<Row> get() {
        return rows;
    }

    public static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (running || now - lastRefresh < REFRESH_MS) return;
        running = true;
        lastRefresh = now;
        worker.submit(() -> {
            try {
                rows = compute();
                // the 7-day average check hides Bazaar prices until their history arrives; look again shortly
                if (BaseProfit.historyLoading() || !volumeInFlight.isEmpty()) {
                    lastRefresh = System.currentTimeMillis() - (REFRESH_MS - 8000);
                }
            } catch (Exception ignored) {
            } finally {
                running = false;
            }
        });
    }

    static List<Row> compute() throws Exception {
        Map<String, Recipe> recipeMap = recipes();
        JsonObject products = BazaarSnapshot.products();
        refreshAuctionPrices();
        Map<String, Double> lbin = lowestBins, avg = averageBins;
        ForgePlanner.Prices prices = prices(products, lbin, avg, BaseProfit.getTaxPercent() / 100.0, BaseProfit::priceOk);
        return rank(recipeMap, prices, products, lbin, avg, ForgeCalc::ahSalesPerHour, BaseProfit.getForgeSlots(),
                1 - BaseProfit.getQuickForgePercent() / 100.0, BaseProfit.getForgeCheckHours() * 3600, BaseProfit.getCoins());
    }

    /** The 7-day average sanity check for a Bazaar price (BaseProfit.priceOk in game). */
    interface PriceCheck {
        boolean ok(String id, double price, boolean isAsk);
    }

    /** Bazaar first (buy orders in, sell offers out after tax), otherwise the auction house. */
    static ForgePlanner.Prices prices(JsonObject products, Map<String, Double> lbin, Map<String, Double> avg, double tax,
                                      PriceCheck check) {
        return new ForgePlanner.Prices() {
            @Override
            public double buy(String id) {
                if (products.has(id)) {
                    double bid = BazaarSnapshot.bid(products.getAsJsonObject(id));
                    return bid > 0 && check.ok(id, bid, false) ? bid : Double.NaN;
                }
                Double l = lbin.get(ahKey(id));
                return l != null && l > 0 ? l : Double.NaN;
            }

            @Override
            public double sell(String id) {
                if (products.has(id)) {
                    double ask = BazaarSnapshot.ask(products.getAsJsonObject(id));
                    return ask > 0 && check.ok(id, ask, true) ? ask * (1 - tax) : Double.NaN;
                }
                return ahSellValue(lbin.get(ahKey(id)), avg.get(ahKey(id)));
            }
        };
    }

    /**
     * Every profitable route, ranked by profit per hour across your slots, capped by how many the market takes per hour
     * (Bazaar: 7-day sell volume; AH: {@code ahSalesPerHour}, NaN = not loaded yet, so the item is left out for now).
     */
    static List<Row> rank(Map<String, Recipe> recipeMap, ForgePlanner.Prices prices, JsonObject products, Map<String, Double> lbin,
                          Map<String, Double> avg, java.util.function.ToDoubleFunction<String> ahSalesPerHour, int slots,
                          double timeFactor, double checkInSeconds, double minProfit) {
        List<Row> out = new ArrayList<>();
        for (Route route : new ForgePlanner(recipeMap, prices, timeFactor, checkInSeconds).rankAll()) {
            if (route.profit() < minProfit) continue;
            Recipe recipe = recipeMap.get(route.id());
            boolean onAh = !products.has(route.id());
            double market = onAh ? ahSalesPerHour.applyAsDouble(route.id())
                    : BazaarSnapshot.sellRate(products.getAsJsonObject(route.id()));
            if (!(market > 0)) continue; // no sales data yet, or nobody buys it
            double perHour = ForgePlanner.profitPerHour(route, recipe, slots, market);
            out.add(new Row(route, recipe, perHour, onAh, lbin.getOrDefault(ahKey(route.id()), Double.NaN),
                    avg.getOrDefault(ahKey(route.id()), Double.NaN), market));
        }
        out.sort((a, b) -> Double.compare(b.perHour(), a.perHour()));
        return out;
    }

    // ---- /forgeroute: the whole route in chat ----

    static String duration(double seconds) {
        if (seconds < 60) return String.format("%.0fs", seconds);
        if (seconds < 3600) return String.format("%.0fm", seconds / 60);
        double h = seconds / 3600;
        return h == Math.floor(h) ? String.format("%.0fh", h) : String.format("%.1fh", h);
    }

    static String qty(double q) {
        return q == Math.floor(q) ? String.valueOf((long) q) : String.format("%.2f", q);
    }

    private static void describe(Step s, int depth, List<String> lines) {
        String indent = "  ".repeat(depth);
        if (s.forged()) {
            lines.add(indent + "§aForge §f" + qty(s.quantity()) + "x " + name(s.id()) + " §7(" + qty(s.forges()) + " x "
                    + duration(s.secondsPerForge()) + ", parts " + FlipHud.coins(s.coins()) + ")");
            for (Step p : s.parts()) describe(p, depth + 1, lines);
        } else {
            lines.add(indent + "§eBuy §f" + qty(s.quantity()) + "x " + name(s.id()) + " §7@ " + FlipHud.coins(s.coins() / s.quantity())
                    + " = " + FlipHud.coins(s.coins()));
        }
    }

    /** The route as chat lines: the tree of forge/buy decisions, then the totals. */
    public static List<String> routeLines(Row row, int slots, double checkHours) {
        Route r = row.route();
        List<String> lines = new ArrayList<>();
        describe(r.tree(), 0, lines);
        String sold = row.soldOnAh()
                ? "§7(AH: BIN " + FlipHud.coins(row.lowestBin()) + ", 3-day avg " + FlipHud.coins(row.avgBin()) + ", after fees)"
                : "§7(Bazaar sell offer after tax)";
        lines.add("§aSell §f" + FlipHud.coins(r.revenue()) + " " + sold);
        lines.add("§7Cost §f" + FlipHud.coins(r.cost()) + " §7= §6Profit " + FlipHud.coins(r.profit()));
        lines.add("§7Slot time §f" + duration(r.slotSeconds()) + " §7over §f" + r.forgeSteps() + " §7forge steps, done in ~§f"
                + duration(r.hoursWith(slots) * 3600) + " §7with " + slots + " slots"
                + (checkHours > 0 ? ", collecting every " + duration(checkHours * 3600) : ""));
        lines.add("§a" + FlipHud.coins(r.profitPerSlotHour()) + "/hr per slot §7| §a" + FlipHud.coins(row.perHour())
                + "/hr §7| §a" + FlipHud.coins(row.perDay()) + "/day §7with " + slots + " slots");
        lines.add("§7Market takes ~§f" + qty(Math.round(row.marketPerHour() * 24 * 10) / 10.0) + "/day"
                + (row.soldOnAh() ? " §7(AH sales, Coflnet)" : " §7(Bazaar 7-day volume)")
                + (isPet(row.route().id()) ? " §8- counts every level and rarity of this pet" : ""));
        return lines;
    }

    @RunOnInit
    public static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("forgeroute")
                        .executes(ctx -> {
                            printRoute(1);
                            return 1;
                        })
                        .then(ClientCommands.argument("rank", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    printRoute(IntegerArgumentType.getInteger(ctx, "rank"));
                                    return 1;
                                }))));
    }

    private static void printRoute(int rank) {
        refreshIfStale();
        List<Row> list = rows;
        if (list.isEmpty()) {
            PlayerActionUtil.notifyAll("Forge flips are still loading (or none are profitable). Try again in a few seconds.");
            return;
        }
        if (rank > list.size()) {
            PlayerActionUtil.notifyAll("Only " + list.size() + " forge flips right now.");
            return;
        }
        Row row = list.get(rank - 1);
        PlayerActionUtil.notifyAll("Forge route #" + rank + ": " + name(row.route().id()));
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        for (String line : routeLines(row, BaseProfit.getForgeSlots(), BaseProfit.getForgeCheckHours())) {
            player.sendSystemMessage(Component.literal(line));
        }
    }
}
