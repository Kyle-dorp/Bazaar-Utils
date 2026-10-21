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
 * recent sales per day (without that cap a 30-second AH upgrade "earns" billions an hour), and by how fast you can get every
 * part you buy: Bazaar buy orders fill at the part's 7-day insta-sell volume, AH parts at their own sales rate. An AH item or
 * part stays hidden until its volume has loaded rather than showing an uncapped number.
 */
public class ForgeCalc {
    /** A ranked forge flip: the route, the recipe it forges, profit per hour across your slots, and how the output sells. */
    public record Row(Route route, Recipe recipe, double perHour, boolean soldOnAh, double lowestBin, double avgBin,
                      double marketPerHour, ForgePlanner.Throughput throughput) {
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

    /** What forging an item needs (NEU crafttext): a HotM tier, and sometimes a collection tier like "Gemstone X". */
    public record Requirement(int hotm, String collection) { }

    private static final Requirement NONE = new Requirement(0, null);
    private static Map<String, Requirement> requirements = Map.of();
    /** Purse from the scoreboard, read on the client thread at each refresh; -1 or NaN when unknown. */
    private static volatile double purse = Double.NaN;
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
        Map<String, Requirement> reqMap = new HashMap<>();
        try (InputStream in = ForgeCalc.class.getResourceAsStream("/assets/bazaarutils/forge-recipes.json")) {
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("requirements")) {
                for (var e : o.getAsJsonObject("requirements").entrySet()) {
                    JsonObject r = e.getValue().getAsJsonObject();
                    reqMap.put(e.getKey(), new Requirement(r.get("hotm").getAsInt(),
                            r.has("collection") ? r.get("collection").getAsString() : null));
                }
            }
        } catch (Exception ignored) {
            // no requirements: the unlock filter lets everything through
        }
        requirements = reqMap;
        recipes = map;
        return recipes;
    }

    /** In-game name for any forge item or part, including AH items the Bazaar name list doesn't know. */
    /**
     * The forge item a sold item is, ignoring reforges, stars and icons ("Fleet Titanium Drill DR-X455 ✪✪" is
     * TITANIUM_DRILL_2; "[Lvl 1] Ammonite" is AMMONITE;4), or null. The longest matching name wins.
     */
    public static String forgeOutputFor(String soldName) {
        String sold = ProfitLedger.norm(soldName);
        String best = null;
        int bestLen = 0;
        for (String id : recipes().keySet()) {
            String n = ProfitLedger.norm(name(id));
            if (n.isEmpty() || n.length() <= bestLen) continue;
            if (sold.equals(n) || sold.endsWith(" " + n)) {
                best = id;
                bestLen = n.length();
            }
        }
        return best;
    }

    /**
     * Unit prices of every part in an item's forge tree (not the item itself) from the prices already cached: Bazaar buy
     * order, else lowest BIN. Used to value parts you didn't buy when a forge item sells; never touches the network.
     */
    public static Map<String, Double> partPrices(String id) {
        Map<String, Double> out = new HashMap<>();
        JsonObject products = BazaarSnapshot.cachedProducts();
        addPartPrices(id, products, out, new HashSet<>());
        return out;
    }

    private static void addPartPrices(String id, JsonObject products, Map<String, Double> out, Set<String> seen) {
        Recipe r = recipes().get(id);
        if (r == null || !seen.add(id)) return;
        for (String part : r.inputs().keySet()) {
            double price = Double.NaN;
            if (products != null && products.has(part)) price = BazaarSnapshot.bid(products.getAsJsonObject(part));
            else if (lowestBins.containsKey(ahKey(part))) price = lowestBins.get(ahKey(part));
            if (price > 0) out.put(part, price);
            addPartPrices(part, products, out, seen);
        }
    }

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

    /** What forging {@code id} needs; HotM 0 and no collection if NEU lists nothing. */
    public static Requirement requirement(String id) {
        recipes();
        return requirements.getOrDefault(id, NONE);
    }

    /** The purse read at the last refresh, NaN or -1 if it couldn't be read (not on SkyBlock, no sidebar). */
    public static double lastPurse() {
        return purse;
    }

    /** "[HotM 7, purse]" style marker for the list heading when a filter is on, "" otherwise. */
    public static String filterTag() {
        List<String> on = new ArrayList<>();
        if (BaseProfit.isForgeUnlockFilter()) on.add("HotM " + BaseProfit.getHotmTier());
        if (BaseProfit.isForgePurseFilter()) on.add(purse > 0 ? "purse " + FlipHud.coins(purse) : "purse unknown");
        return on.isEmpty() ? "" : " §8[§7" + String.join("§8, §7", on) + "§8]";
    }

    public static void refreshIfStale() {
        if (FlipPause.paused() || !Flippers.on(Flippers.Flipper.FORGE)) return; // frozen while paused, nothing when off
        // the scoreboard can only be read on the client thread; other callers keep the last reading
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) purse = MaxBuyOrder.readPurse(mc);
        } catch (Throwable ignored) { }
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
        java.util.function.Predicate<String> canForge = BaseProfit.isForgeUnlockFilter() ? unlockedAt(BaseProfit.getHotmTier()) : id -> true;
        double maxCost = budget(BaseProfit.isForgePurseFilter(), purse);
        return rank(recipeMap, prices, products, lbin, avg, ForgeCalc::ahSalesPerHour, BaseProfit.getForgeSlots(),
                1 - BaseProfit.getQuickForgePercent() / 100.0, BaseProfit.getForgeCheckHours() * 3600, BaseProfit.getCoins(),
                canForge, maxCost);
    }

    /** Recipes a player at this HotM tier can forge (recipes NEU lists no tier for count as tier 0). */
    static java.util.function.Predicate<String> unlockedAt(int hotmTier) {
        return id -> requirement(id).hotm() <= hotmTier;
    }

    /** Most a route may cost up front: the purse when the filter is on and the purse is known, otherwise no limit. */
    static double budget(boolean purseFilter, double purse) {
        return purseFilter && purse > 0 ? purse : Double.POSITIVE_INFINITY;
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
        return rank(recipeMap, prices, products, lbin, avg, ahSalesPerHour, slots, timeFactor, checkInSeconds, minProfit,
                id -> true, Double.POSITIVE_INFINITY);
    }

    /**
     * {@link #rank} limited to what you can do: {@code canForge} says which recipes you have unlocked (parts you can't forge
     * are bought instead) and {@code maxCost} caps the up-front cost of a route (your purse).
     */
    static List<Row> rank(Map<String, Recipe> recipeMap, ForgePlanner.Prices prices, JsonObject products, Map<String, Double> lbin,
                          Map<String, Double> avg, java.util.function.ToDoubleFunction<String> ahSalesPerHour, int slots,
                          double timeFactor, double checkInSeconds, double minProfit,
                          java.util.function.Predicate<String> canForge, double maxCost) {
        List<Row> out = new ArrayList<>();
        for (Route route : new ForgePlanner(recipeMap, prices, timeFactor, checkInSeconds, canForge).rankAll(maxCost)) {
            if (route.profit() < minProfit) continue;
            Recipe recipe = recipeMap.get(route.id());
            boolean onAh = !products.has(route.id());
            double market = onAh ? ahSalesPerHour.applyAsDouble(route.id())
                    : BazaarSnapshot.sellRate(products.getAsJsonObject(route.id()));
            if (!(market > 0)) continue; // no sales data yet, or nobody buys it
            // every bought part needs a supply rate: Bazaar buy orders fill at the part's insta-sell volume, AH parts at
            // their sales rate (NaN while loading: leave the route out until it's known instead of showing it uncapped)
            java.util.function.ToDoubleFunction<String> supply = id -> products.has(id)
                    ? BazaarSnapshot.fillRate(products.getAsJsonObject(id)) : ahSalesPerHour.applyAsDouble(id);
            boolean loading = false;
            for (String part : ForgePlanner.boughtInputs(route.tree()).keySet()) loading |= Double.isNaN(supply.applyAsDouble(part));
            if (loading) continue;
            ForgePlanner.Throughput t = ForgePlanner.throughput(route, recipe, slots, market, supply);
            if (!(t.profitPerHour() > 0)) continue; // a part nobody sells
            out.add(new Row(route, recipe, t.profitPerHour(), onAh, lbin.getOrDefault(ahKey(route.id()), Double.NaN),
                    avg.getOrDefault(ahKey(route.id()), Double.NaN), market, t));
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

    /** What caps the route, short (for the HUD) or with numbers (for /forgeroute). */
    static String limitText(Row row, int slots, boolean detailed) {
        ForgePlanner.Throughput t = row.throughput();
        String input = t.limitingInput();
        if (input == null && ForgePlanner.Throughput.MARKET.equals(t.limit())) {
            return detailed ? "demand (~" + qty(Math.round(t.limitPerHour() * 24 * 10) / 10.0) + " sold/day)" : "demand";
        }
        if (input == null) return detailed ? "your " + slots + " forge slots" : "slots";
        if (!detailed) return name(input) + " supply";
        double perRun = ForgePlanner.boughtInputs(row.route().tree()).get(input);
        return name(input) + " supply (~" + qty(Math.round(t.limitPerHour() * 24 * 10) / 10.0) + "/day, " + qty(perRun) + " per run)";
    }

    /** The highest HotM tier and the collections a route's forges need, e.g. "HotM 5, Gemstone X". */
    static String requirementText(Step tree) {
        int[] hotm = {0};
        Set<String> collections = new TreeSet<>();
        collectRequirements(tree, hotm, collections);
        List<String> parts = new ArrayList<>();
        if (hotm[0] > 0) parts.add("HotM " + hotm[0]);
        parts.addAll(collections);
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    private static void collectRequirements(Step s, int[] hotm, Set<String> collections) {
        if (!s.forged()) return;
        Requirement r = requirement(s.id());
        hotm[0] = Math.max(hotm[0], r.hotm());
        if (r.collection() != null) collections.add(r.collection());
        for (Step p : s.parts()) collectRequirements(p, hotm, collections);
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
        lines.add("§7Requires §f" + requirementText(r.tree()));
        lines.add("§7Limited by §f" + limitText(row, slots, true) + " §7- §f"
                + qty(Math.round(row.throughput().runsPerHour() * 24 * 10) / 10.0) + " §7runs/day");
        lines.add("§7Market takes ~§f" + qty(Math.round(row.marketPerHour() * 24 * 10) / 10.0) + "/day"
                + (row.soldOnAh() ? " §7(AH sales, Coflnet)" : " §7(Bazaar 7-day volume)")
                + (isPet(row.route().id()) ? " §8- counts every level and rarity of this pet" : ""));
        return lines;
    }

    @RunOnInit
    public static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("forgesettings").executes(ctx -> {
                    ForgeSettingsScreen.open();
                    return 1;
                })));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("forgetree")
                        .executes(ctx -> {
                            openTree(1);
                            return 1;
                        })
                        .then(ClientCommands.argument("rank", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    openTree(IntegerArgumentType.getInteger(ctx, "rank"));
                                    return 1;
                                }))));
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

    /** /forgetree [rank]: the route as a tree screen (the same one clicking a forge row opens). */
    private static void openTree(int rank) {
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
        ForgeTreeScreen.open(list.get(rank - 1));
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
