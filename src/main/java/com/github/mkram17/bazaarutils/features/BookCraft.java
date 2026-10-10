package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enchanted book crafting flips. Two books of one tier make one book of the next tier, so a tier 5 book is 16 tier 1 books,
 * tier 4 is 8, tier 3 is 4, tier 2 is 2. If the higher tier sells for more than the lower tiers cost to buy, combining them is a
 * flip. For every book this tries each lower tier as the starting point and keeps the one with the best profit per hour.
 */
public class BookCraft {
    public record Craft(String id, String title, double profit, double profitMargin, double perHour,
                        String fromName, int units, double bid, double ask, double cost) { }

    private static final Pattern BOOK = Pattern.compile("^ENCHANTMENT_(.+)_(\\d+)$");
    private static final long REFRESH_MS = 45_000;

    private static volatile List<Craft> crafts = List.of();
    private static volatile long lastRefresh;
    private static volatile boolean running;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BazaarFlip-books");
        t.setDaemon(true);
        return t;
    });

    public static List<Craft> get() {
        return crafts;
    }

    /** Starts a background recompute if the data is older than 45 seconds. Cheap to call every frame. */
    public static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (running || now - lastRefresh < REFRESH_MS) return;
        running = true;
        lastRefresh = now;
        worker.submit(() -> {
            try {
                crafts = compute();
                // price history for the candidates loads in the background; look again soon instead of waiting the full interval
                if (BaseProfit.historyLoading()) lastRefresh = System.currentTimeMillis() - (REFRESH_MS - 8000);
            } catch (Exception ignored) {
            } finally {
                running = false;
            }
        });
    }

    private static double price(JsonObject product, String summary) {
        JsonArray a = product.getAsJsonArray(summary);
        return a == null || a.isEmpty() ? 0 : a.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
    }

    private record Tier(String id, int tier, double bid, double ask, double sellRate, double buyRate) { }

    static List<Craft> compute() throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create("https://api.hypixel.net/skyblock/bazaar").toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        JsonObject products;
        try (InputStream in = c.getInputStream()) {
            products = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject().getAsJsonObject("products");
        }

        Map<String, TreeMap<Integer, Tier>> families = new HashMap<>();
        for (var e : products.entrySet()) {
            Matcher m = BOOK.matcher(e.getKey());
            if (!m.matches()) continue;
            JsonObject p = e.getValue().getAsJsonObject();
            JsonObject qs = p.getAsJsonObject("quick_status");
            double bid = price(p, "sell_summary"), ask = price(p, "buy_summary");
            if (bid <= 0 && ask <= 0) continue;
            int tier = Integer.parseInt(m.group(2));
            families.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(tier, new Tier(e.getKey(), tier, bid, ask,
                    week(qs, "sellMovingWeek") / 168.0, week(qs, "buyMovingWeek") / 168.0));
        }

        loadOverrides();
        double tax = BaseProfit.getTaxPercent() / 100.0;
        double minProfit = BaseProfit.getCoins();
        double minRate = BaseProfit.getMinItemsPerHour();
        List<Craft> out = new ArrayList<>();
        for (TreeMap<Integer, Tier> fam : families.values()) {
            for (Tier target : fam.values()) {
                if (target.tier() < 2 || target.ask() <= 0) continue;
                if (neverCraft.contains(target.id())) continue;
                if (!alwaysCraft.contains(target.id()) && !craftable(target)) continue;
                Craft best = null;
                for (Tier base : fam.headMap(target.tier()).values()) {
                    if (base.bid() <= 0) continue;
                    int units = 1 << (target.tier() - base.tier());
                    double cost = units * base.bid();
                    double profit = target.ask() * (1 - tax) - cost;
                    if (profit <= 0 || profit < minProfit) continue;
                    // how many finished books per hour: buy side (instant sells into your orders for the base book, divided
                    // by how many it takes) and sell side (instant buys of the finished book)
                    double rate = Math.min(base.sellRate() / units, target.buyRate());
                    if (rate < minRate || rate <= 0) continue;
                    // evaluate both so both price histories start loading together
                    boolean baseOk = BaseProfit.priceOk(base.id(), base.bid(), false);
                    boolean targetOk = BaseProfit.priceOk(target.id(), target.ask(), true);
                    if (!baseOk || !targetOk) continue;
                    double perHour = profit * rate;
                    if (best == null || perHour > best.perHour()) {
                        best = new Craft(target.id(), ItemNames.of(target.id()), profit, profit / cost * 100.0, perHour,
                                ItemNames.of(base.id()), units, base.bid(), target.ask(), cost);
                    }
                }
                if (best != null) out.add(best);
            }
        }
        out.sort((a, b) -> Double.compare(b.perHour(), a.perHour()));
        return out;
    }

    /**
     * Highest tier each enchant can be combined up to on an anvil, from the Hypixel SkyBlock wiki (bundled
     * book-craft-caps.json, built by tools/book-caps/build_caps.py). Each entry keeps the wiki sentence or table row it came
     * from, e.g. "can be combined on an Anvil up to Sharpness V". Enchants the wiki doesn't state are absent, and absent
     * means not craftable: no craft is shown rather than a guessed one. The override file can add or remove any book.
     */
    private static final Map<String, Integer> CAPS = loadCaps();

    private static Map<String, Integer> loadCaps() {
        Map<String, Integer> caps = new HashMap<>();
        try (InputStream in = BookCraft.class.getResourceAsStream("/assets/bazaarutils/book-craft-caps.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject().getAsJsonObject("caps");
                for (var e : o.entrySet()) caps.put(e.getKey(), e.getValue().getAsJsonObject().get("maxCraftTier").getAsInt());
            }
        } catch (Exception ignored) { }
        return caps;
    }

    private static boolean craftable(Tier target) {
        Matcher m = BOOK.matcher(target.id());
        if (!m.matches()) return false;
        Integer cap = CAPS.get(m.group(1));
        return cap != null && target.tier() <= cap;
    }

    private static final java.nio.file.Path OVERRIDES = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
            .resolve("bazaarflip-books.json");
    private static Set<String> neverCraft = Set.of();
    private static Set<String> alwaysCraft = Set.of();

    /** config/bazaarflip-books.json: {"neverCraft": ["ENCHANTMENT_LOOTING_5"], "alwaysCraft": ["ENCHANTMENT_SOMETHING_6"]} */
    private static void loadOverrides() {
        Set<String> never = new HashSet<>(), always = new HashSet<>();
        try {
            if (java.nio.file.Files.exists(OVERRIDES)) {
                JsonObject o = JsonParser.parseString(java.nio.file.Files.readString(OVERRIDES)).getAsJsonObject();
                if (o.has("neverCraft")) o.getAsJsonArray("neverCraft").forEach(e -> never.add(e.getAsString()));
                if (o.has("alwaysCraft")) o.getAsJsonArray("alwaysCraft").forEach(e -> always.add(e.getAsString()));
            } else {
                JsonObject o = new JsonObject();
                o.add("neverCraft", new JsonArray());
                o.add("alwaysCraft", new JsonArray());
                java.nio.file.Files.writeString(OVERRIDES, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
            }
        } catch (Exception ignored) { }
        neverCraft = never;
        alwaysCraft = always;
    }

    private static double week(JsonObject qs, String field) {
        try {
            JsonElement e = qs.get(field);
            return e == null ? 0 : e.getAsDouble();
        } catch (Exception ex) {
            return 0;
        }
    }
}
