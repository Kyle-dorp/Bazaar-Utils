package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Not a regression test: runs the advisor on live Bazaar, auction and Coflnet data with your own settings from
 * .minecraft/config, and writes what it recommends to a report file. Skipped unless ADVISOR_LIVE=1.
 *
 * <p>BaseProfit, ItemNames, BookCraft and FusionCalc read the game's config folder through FabricLoader, which doesn't exist
 * outside the game, so the flip, book and fusion rows are rebuilt here with the same rules (and the calculators' own record
 * types). Forge rows come from ForgeCalc.rank itself.
 *
 * <pre>ADVISOR_LIVE=1 ADVISOR_REPORT=out.txt ./gradlew :26.2:test --tests "*OpportunityAdvisorLiveProbe*"</pre>
 */
@EnabledIfEnvironmentVariable(named = "ADVISOR_LIVE", matches = "1")
class OpportunityAdvisorLiveProbe {
    private static final Path CONFIG = Path.of(System.getenv().getOrDefault("ADVISOR_MC_CONFIG",
            System.getenv("APPDATA") + "/.minecraft/config"));
    private static final double ORDER_CHECK_HOURS = 1;
    private static final int MAX_ACTIVE = 7;

    private final StringBuilder out = new StringBuilder();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final Map<String, double[]> history = new ConcurrentHashMap<>(); // id -> {medianBuy, medianSell}, empty = failed
    private Map<String, String> names = Map.of();

    // the player's settings (bazaarflip-baseprofit.json, bazaarflip.json)
    private double tax, minProfit, tolerance, quickForge, forgeCheckHours, minMargin, maxMargin, spendLimit;
    private int slots;

    private void line(String s) {
        out.append(s).append('\n');
    }

    @Test
    void reportWhatTheAdvisorRecommendsOnLiveData() throws Exception {
        long t0 = System.currentTimeMillis();
        loadSettings();
        JsonObject products = BazaarSnapshot.products();
        line("Live advisor run " + java.time.ZonedDateTime.now() + ", " + products.size() + " Bazaar products");
        line(String.format("Settings: tax %.2f%%, min profit/item %s, 7-day tolerance %.0f%%, flip margin >= %.0f%%, spend limit %s,"
                        + " %d forge slots, forge check-in %.1fh, order check-in %.1fh, max %d Bazaar items",
                tax * 100, OpportunityAdvisor.coins(minProfit), tolerance * 100, minMargin, OpportunityAdvisor.coins(spendLimit),
                slots, forgeCheckHours, ORDER_CHECK_HOURS, MAX_ACTIVE));

        List<Opportunity> flips = flips(products);
        List<Opportunity> books = books(products);
        List<Opportunity> fusions = fusions(products);
        Map<String, Double> lbin = ForgeCalc.fetchPrices("https://hysky.de/api/auctions/lowestbins");
        Map<String, Double> avg = ForgeCalc.fetchPrices("https://hysky.de/api/auctions/lowestbins/average?days=3");
        Map<String, Double> sales = new ConcurrentHashMap<>();
        line(String.format("Rows: %d flips, %d book crafts, %d fusions (data in %.1fs)", flips.size(), books.size(), fusions.size(),
                (System.currentTimeMillis() - t0) / 1000.0));

        double[] purses = {Double.POSITIVE_INFINITY, 15e6, 50e6, 250e6, 1e9};
        for (double purse : purses) scenario(products, lbin, avg, sales, flips, books, fusions, purse, false);
        scenario(products, lbin, avg, sales, flips, books, fusions, 50e6, true);

        pool.shutdownNow();
        String report = out.toString();
        String file = System.getenv("ADVISOR_REPORT");
        Files.writeString(Path.of(file != null ? file : "advisor-live.txt"), report);
        System.out.println(report);
    }

    private void scenario(JsonObject products, Map<String, Double> lbin, Map<String, Double> avg, Map<String, Double> sales,
                          List<Opportunity> flips, List<Opportunity> books, List<Opportunity> fusions, double purse, boolean afk)
            throws Exception {
        List<Opportunity> all = new ArrayList<>(flips);
        all.addAll(books);
        all.addAll(fusions);
        // the purse filter on ForgeCalc caps each route's up-front cost, as /forgesettings does in game
        List<ForgeCalc.Row> rows = forgeRows(products, lbin, avg, sales, purse);
        int forgeCount = 0;
        for (ForgeCalc.Row r : rows) {
            Opportunity o = Opportunity.forge(r, forgeName(r.route().id()), forgeCheckHours);
            if (o != null) {
                all.add(o);
                forgeCount++;
            }
        }
        OpportunityAdvisor.Settings s = new OpportunityAdvisor.Settings(purse, slots, MAX_ACTIVE, afk);
        long start = System.nanoTime();
        OpportunityAdvisor.Plan plan = OpportunityAdvisor.plan(all, s);
        double ms = (System.nanoTime() - start) / 1e6;

        line("");
        line("=== purse " + OpportunityAdvisor.coins(purse) + (afk ? ", AFK" : "") + " ===  (" + all.size() + " options incl. "
                + forgeCount + " forge routes; plan " + String.format("%.1f", ms) + " ms, " + plan.assignmentsTried()
                + " slot assignments scored)");
        for (String l : plan.summary()) line("  " + l);
        for (OpportunityAdvisor.Pick p : plan.forges()) line("  [forge] " + p.opportunity().name() + ": " + p.why());
        for (OpportunityAdvisor.Pick p : plan.active()) line("  [" + p.opportunity().kind().label.toLowerCase() + "] "
                + p.opportunity().name() + ": " + p.why());
        if (!plan.skipped().isEmpty()) line("  Left out:");
        for (OpportunityAdvisor.Skip k : plan.skipped()) line("    - " + k.why());
    }

    // ---- settings and names from the game's config folder ----

    private void loadSettings() throws Exception {
        JsonObject b = JsonParser.parseString(Files.readString(CONFIG.resolve("bazaarflip-baseprofit.json"))).getAsJsonObject();
        tax = num(b, "sellTaxPercent", 1.25) / 100;
        minProfit = num(b, "minProfitPerItem", 0);
        tolerance = num(b, "maxVsWeekAveragePercent", 20) / 100;
        slots = (int) num(b, "forgeSlots", 7);
        quickForge = num(b, "quickForgePercent", 0) / 100;
        forgeCheckHours = num(b, "forgeCheckHours", 2);
        JsonObject f = JsonParser.parseString(Files.readString(CONFIG.resolve("bazaarflip.json"))).getAsJsonObject();
        minMargin = num(f, "backgroundAlertThreshold", 0);
        maxMargin = num(f, "backgroundAlertMaxThreshold", 1e9);
        spendLimit = num(f, "maxSpendLimit", 1e9);
        Map<String, String> n = new HashMap<>();
        Path res = CONFIG.resolve("bazaarutils").resolve("bazaar-resources.json");
        if (Files.exists(res)) {
            JsonObject root = JsonParser.parseString(Files.readString(res)).getAsJsonObject();
            JsonObject src = root.has("bazaarConversions") && root.get("bazaarConversions").isJsonObject()
                    ? root.getAsJsonObject("bazaarConversions") : root;
            for (var e : src.entrySet()) if (e.getValue().isJsonPrimitive()) n.put(e.getKey(), e.getValue().getAsString());
        }
        names = n;
    }

    private static double num(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private String name(String id) {
        String n = names.get(id);
        if (n != null) return n;
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private String forgeName(String id) {
        try {
            return ForgeCalc.name(id);
        } catch (Throwable t) {
            return name(id);
        }
    }

    // ---- 7-day average check (BaseProfit.passesAverage / priceOk, same rules) ----

    private void fetchHistories(Collection<String> ids) throws Exception {
        List<Future<?>> jobs = new ArrayList<>();
        for (String id : new HashSet<>(ids)) {
            if (history.containsKey(id)) continue;
            jobs.add(pool.submit(() -> {
                double[] result = new double[0];
                try {
                    HttpURLConnection c = (HttpURLConnection) URI.create("https://sky.coflnet.com/api/bazaar/" + id + "/history/week")
                            .toURL().openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent", "BazaarUtils-AdvisorProbe");
                    List<Double> buys = new ArrayList<>(), sells = new ArrayList<>();
                    try (InputStream in = c.getInputStream()) {
                        for (JsonElement e : JsonParser.parseString(new String(in.readAllBytes())).getAsJsonArray()) {
                            JsonObject o = e.getAsJsonObject();
                            if (o.has("buy") && o.get("buy").getAsDouble() > 0) buys.add(o.get("buy").getAsDouble());
                            if (o.has("sell") && o.get("sell").getAsDouble() > 0) sells.add(o.get("sell").getAsDouble());
                        }
                    }
                    if (buys.size() >= 6 && sells.size() >= 6) result = new double[]{median(buys), median(sells)};
                } catch (Exception ignored) { }
                history.put(id, result);
            }));
        }
        for (Future<?> j : jobs) j.get(60, TimeUnit.SECONDS);
    }

    private static double median(List<Double> v) {
        Collections.sort(v);
        return v.get(v.size() / 2);
    }

    private boolean passesAverage(String id, double buy, double sell) {
        double[] a = history.get(id);
        if (a == null || a.length == 0) return true;
        return sell <= a[0] * (1 + tolerance) && buy >= a[1] * (1 - tolerance);
    }

    private boolean priceOk(String id, double price, boolean isAsk) {
        double[] a = history.get(id);
        if (a == null || a.length == 0) return true;
        return isAsk ? price <= a[0] * (1 + tolerance) : price >= a[1] * (1 - tolerance);
    }

    private double typicalBuy(String id) {
        double[] a = history.get(id);
        return a == null || a.length == 0 ? Double.NaN : a[1];
    }

    private static double week(JsonObject p, String field) {
        try {
            return p.getAsJsonObject("quick_status").get(field).getAsDouble();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- BazaarFlip as patched (Patch.java + BaseProfit hooks) ----

    private record FlipRow(String id, double buy, double sell, double profit, double perHour) { }

    private List<Opportunity> flips(JsonObject products) throws Exception {
        List<FlipRow> candidates = new ArrayList<>();
        for (var e : products.entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            double buy = BazaarSnapshot.bid(p), sell = BazaarSnapshot.ask(p);
            if (!(week(p, "buyVolume") > 0) || !(week(p, "sellVolume") > 0) || !(buy > 0)) continue;
            double profit = sell * (1 - tax) - buy, margin = profit / buy * 100;
            double qty = Math.min(71680, Math.floor(spendLimit / buy));
            if (qty < 1 || buy * qty > spendLimit) continue;
            if (margin < Math.max(minMargin, minProfit > 0 ? minProfit / buy * 100 : 0) || margin > maxMargin) continue;
            double perHour = profit * Math.min(week(p, "buyMovingWeek"), week(p, "sellMovingWeek")) / 168.0;
            if (!(perHour > 0)) continue;
            candidates.add(new FlipRow(e.getKey(), buy, sell, profit, perHour));
        }
        fetchHistories(candidates.stream().map(FlipRow::id).toList());
        List<Opportunity> out = new ArrayList<>();
        for (FlipRow f : candidates) {
            if (!passesAverage(f.id(), f.buy(), f.sell())) continue;
            Opportunity o = Opportunity.flip(f.id(), name(f.id()), f.buy(), f.sell(), f.profit(), f.perHour(), typicalBuy(f.id()),
                    false, ORDER_CHECK_HOURS);
            if (o != null) out.add(o);
        }
        return out;
    }

    // ---- BookCraft.compute, same rules ----

    private static final Pattern BOOK = Pattern.compile("^ENCHANTMENT_(.+)_(\\d+)$");

    private record Tier(String id, int tier, double bid, double ask, double sellRate, double buyRate) { }

    private List<Opportunity> books(JsonObject products) throws Exception {
        Map<String, Integer> caps = new HashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/assets/bazaarutils/book-craft-caps.json")) {
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject().getAsJsonObject("caps");
            for (var e : o.entrySet()) caps.put(e.getKey(), e.getValue().getAsJsonObject().get("maxCraftTier").getAsInt());
        }
        Map<String, TreeMap<Integer, Tier>> families = new HashMap<>();
        for (var e : products.entrySet()) {
            Matcher m = BOOK.matcher(e.getKey());
            if (!m.matches()) continue;
            JsonObject p = e.getValue().getAsJsonObject();
            double bid = BazaarSnapshot.bid(p), ask = BazaarSnapshot.ask(p);
            if (bid <= 0 && ask <= 0) continue;
            int tier = Integer.parseInt(m.group(2));
            families.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(tier, new Tier(e.getKey(), tier, bid, ask,
                    week(p, "sellMovingWeek") / 168.0, week(p, "buyMovingWeek") / 168.0));
        }
        List<Tier[]> pairs = new ArrayList<>();
        for (var fam : families.entrySet()) {
            for (Tier target : fam.getValue().values()) {
                Integer cap = caps.get(fam.getKey());
                if (target.tier() < 2 || target.ask() <= 0 || cap == null || target.tier() > cap) continue;
                for (Tier base : fam.getValue().headMap(target.tier()).values()) {
                    if (base.bid() <= 0) continue;
                    int units = 1 << (target.tier() - base.tier());
                    double profit = target.ask() * (1 - tax) - units * base.bid();
                    double rate = Math.min(base.sellRate() / units, target.buyRate());
                    if (profit > 0 && profit >= minProfit && rate > 0) pairs.add(new Tier[]{base, target});
                }
            }
        }
        List<String> ids = new ArrayList<>();
        for (Tier[] t : pairs) {
            ids.add(t[0].id());
            ids.add(t[1].id());
        }
        fetchHistories(ids);
        Map<String, BookCraft.Craft> best = new HashMap<>();
        for (Tier[] t : pairs) {
            Tier base = t[0], target = t[1];
            if (!priceOk(base.id(), base.bid(), false) || !priceOk(target.id(), target.ask(), true)) continue;
            int units = 1 << (target.tier() - base.tier());
            double cost = units * base.bid(), profit = target.ask() * (1 - tax) - cost;
            double perHour = profit * Math.min(base.sellRate() / units, target.buyRate());
            BookCraft.Craft prev = best.get(target.id());
            if (prev == null || perHour > prev.perHour()) {
                best.put(target.id(), new BookCraft.Craft(target.id(), name(target.id()), profit, profit / cost * 100, perHour,
                        name(base.id()), units, base.bid(), target.ask(), cost));
            }
        }
        List<Opportunity> out = new ArrayList<>();
        for (BookCraft.Craft c : best.values()) {
            Opportunity o = Opportunity.book(c, ORDER_CHECK_HOURS);
            if (o != null) out.add(o);
        }
        return out;
    }

    // ---- FusionCalc.compute, same rules ----

    private List<Opportunity> fusions(JsonObject products) throws Exception {
        JsonObject o;
        try (InputStream in = getClass().getResourceAsStream("/assets/bazaarutils/fusion-recipes.json")) {
            o = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject();
        }
        JsonArray t = o.getAsJsonArray("types"), i = o.getAsJsonArray("ids"), r = o.getAsJsonArray("recipes");
        int n = i.size();
        String[] ids = new String[n];
        double[] bid = new double[n], ask = new double[n], fill = new double[n], sell = new double[n];
        for (int k = 0; k < n; k++) {
            ids[k] = i.get(k).getAsString();
            if (!products.has(ids[k])) continue;
            JsonObject p = products.getAsJsonObject(ids[k]);
            bid[k] = BazaarSnapshot.bid(p);
            ask[k] = BazaarSnapshot.ask(p);
            fill[k] = BazaarSnapshot.fillRate(p);
            sell[k] = BazaarSnapshot.sellRate(p);
        }
        List<int[]> profitable = new ArrayList<>();
        Set<String> needed = new HashSet<>();
        for (JsonElement e : r) {
            JsonArray row = e.getAsJsonArray();
            int[] v = new int[7];
            for (int c = 0; c < 7; c++) v[c] = row.get(c).getAsInt();
            int l = v[1], lc = v[2], rt = v[3], rc = v[4], out = v[5], oc = v[6];
            if (bid[l] <= 0 || bid[rt] <= 0 || ask[out] <= 0) continue;
            double profit = oc * ask[out] * (1 - tax) - (lc * bid[l] + rc * bid[rt]);
            if (profit <= 0 || profit < minProfit) continue;
            profitable.add(v);
            needed.addAll(List.of(ids[l], ids[rt], ids[out]));
        }
        fetchHistories(needed);
        Map<Integer, FusionCalc.Fusion> best = new HashMap<>();
        for (int[] v : profitable) {
            int l = v[1], lc = v[2], rt = v[3], rc = v[4], out = v[5], oc = v[6];
            if (!priceOk(ids[l], bid[l], false) || !priceOk(ids[rt], bid[rt], false) || !priceOk(ids[out], ask[out], true)) continue;
            double cost = lc * bid[l] + rc * bid[rt], profit = oc * ask[out] * (1 - tax) - cost;
            double perHour = profit * Math.min(Math.min(fill[l] / lc, fill[rt] / rc), sell[out] / oc);
            if (!(perHour > 0)) continue;
            FusionCalc.Fusion prev = best.get(out);
            if (prev == null || perHour > prev.perHour()) {
                best.put(out, new FusionCalc.Fusion(t.get(v[0]).getAsString(), ids[l], lc, bid[l], ids[rt], rc, bid[rt], ids[out], oc,
                        ask[out], cost, profit, perHour));
            }
        }
        List<Opportunity> res = new ArrayList<>();
        for (FusionCalc.Fusion f : best.values()) {
            Opportunity op = Opportunity.fusion(f, this::name, ORDER_CHECK_HOURS);
            if (op != null) res.add(op);
        }
        return res;
    }

    // ---- ForgeCalc.rank with live prices; AH volumes fetched for the routes that need them ----

    private List<ForgeCalc.Row> forgeRows(JsonObject products, Map<String, Double> lbin, Map<String, Double> avg,
                                          Map<String, Double> sales, double maxCost) throws Exception {
        Map<String, ForgePlanner.Recipe> recipes = ForgeCalc.recipes();
        double timeFactor = 1 - quickForge, checkIn = forgeCheckHours * 3600;
        // first pass without the 7-day check, to learn which Bazaar items and AH volumes the leading routes need
        ForgePlanner.Prices loose = ForgeCalc.prices(products, lbin, avg, tax, (id, p, a) -> true);
        List<ForgePlanner.Route> routes = new ForgePlanner(recipes, loose, timeFactor, checkIn).rankAll(maxCost);
        Set<String> bazaar = new HashSet<>(), ah = new HashSet<>();
        for (ForgePlanner.Route route : routes.subList(0, Math.min(60, routes.size()))) {
            (products.has(route.id()) ? bazaar : ah).add(route.id());
            for (String part : ForgePlanner.boughtInputs(route.tree()).keySet()) (products.has(part) ? bazaar : ah).add(part);
        }
        fetchHistories(bazaar);
        List<Future<?>> jobs = new ArrayList<>();
        for (String id : ah) {
            if (sales.containsKey(id)) continue;
            jobs.add(pool.submit(() -> {
                try {
                    sales.put(id, ForgeCalc.fetchSalesPerDay(id));
                } catch (Exception e) {
                    sales.put(id, Double.NaN);
                }
            }));
        }
        for (Future<?> j : jobs) j.get(60, TimeUnit.SECONDS);
        ForgePlanner.Prices prices = ForgeCalc.prices(products, lbin, avg, tax, this::priceOk);
        return ForgeCalc.rank(recipes, prices, products, lbin, avg, id -> sales.getOrDefault(id, Double.NaN) / 24.0, slots,
                timeFactor, checkIn, minProfit, id -> true, maxCost);
    }
}
