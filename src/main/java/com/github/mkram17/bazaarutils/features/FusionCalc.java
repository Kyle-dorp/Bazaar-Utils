package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Attribute shard fusion profits. Every recipe comes from the Hypixel SkyBlock wiki's Module:Fusion/<Shard>.json pages
 * (the data behind each shard page's fusion tables), bundled as fusion-recipes.json and rebuilt with
 * tools/fusion/build_fusion_recipes.py. Prices are live Bazaar: inputs bought with buy orders, the output sold with a sell
 * offer after tax. Fusions per hour is the slowest of: how fast each input fills for the amount a fusion needs, and how
 * fast the output sells. Only the best recipe for each output shard is listed.
 */
public class FusionCalc {
    public record Fusion(String type, String left, int leftCount, double leftBid, String right, int rightCount, double rightBid,
                         String output, int outputCount, double outputAsk, double cost, double profit, double perHour) { }

    private static final long REFRESH_MS = 45_000;
    private static String[] types, ids;
    private static int[][] recipes;
    private static volatile List<Fusion> fusions = List.of();
    private static volatile long lastRefresh;
    private static volatile boolean running;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BazaarFlip-fusion");
        t.setDaemon(true);
        return t;
    });

    private static synchronized void loadRecipes() {
        if (recipes != null) return;
        try (InputStream in = FusionCalc.class.getResourceAsStream("/assets/bazaarutils/fusion-recipes.json")) {
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject();
            JsonArray t = o.getAsJsonArray("types"), i = o.getAsJsonArray("ids"), r = o.getAsJsonArray("recipes");
            types = new String[t.size()];
            for (int k = 0; k < t.size(); k++) types[k] = t.get(k).getAsString();
            ids = new String[i.size()];
            for (int k = 0; k < i.size(); k++) ids[k] = i.get(k).getAsString();
            recipes = new int[r.size()][];
            for (int k = 0; k < r.size(); k++) {
                JsonArray row = r.get(k).getAsJsonArray();
                int[] v = new int[7];
                for (int c = 0; c < 7; c++) v[c] = row.get(c).getAsInt();
                recipes[k] = v;
            }
        } catch (Exception e) {
            recipes = new int[0][];
            types = new String[0];
            ids = new String[0];
        }
    }

    /** A recipe as display names, for matching against what the flip tracker saw you buy. */
    public record NamedRecipe(String left, int leftCount, String right, int rightCount, String output, int outputCount) { }

    /** Every wiki recipe that makes the shard with this display name (e.g. "Hideoncave Shard"). */
    /** Every wiki fusion recipe as display names. */
    public static List<NamedRecipe> allNamedRecipes() {
        loadRecipes();
        List<NamedRecipe> out = new ArrayList<>();
        for (int[] r : recipes) {
            out.add(new NamedRecipe(ItemNames.of(ids[r[1]]), r[2], ItemNames.of(ids[r[3]]), r[4], ItemNames.of(ids[r[5]]), r[6]));
        }
        return out;
    }

    /**
     * What one shard with this display name sells for with a sell offer after tax, from the last Bazaar snapshot (no
     * network); NaN if unknown.
     */
    public static double sellValueByName(String name, double taxFraction) {
        loadRecipes();
        JsonObject products = BazaarSnapshot.cachedProducts();
        if (products == null) return Double.NaN;
        String wanted = ProfitLedger.norm(name);
        for (String id : ids) {
            if (!ProfitLedger.norm(ItemNames.of(id)).equals(wanted) || !products.has(id)) continue;
            double ask = BazaarSnapshot.ask(products.getAsJsonObject(id));
            return ask > 0 ? ask * (1 - taxFraction) : Double.NaN;
        }
        return Double.NaN;
    }

    public static List<NamedRecipe> recipesMaking(String outputName) {
        loadRecipes();
        List<NamedRecipe> out = new ArrayList<>();
        for (int[] r : recipes) {
            if (ItemNames.of(ids[r[5]]).equalsIgnoreCase(outputName)) {
                out.add(new NamedRecipe(ItemNames.of(ids[r[1]]), r[2], ItemNames.of(ids[r[3]]), r[4], ItemNames.of(ids[r[5]]), r[6]));
            }
        }
        return out;
    }

    public static List<Fusion> get() {
        return fusions;
    }

    public static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (running || now - lastRefresh < REFRESH_MS) return;
        running = true;
        lastRefresh = now;
        worker.submit(() -> {
            try {
                fusions = compute();
                if (BaseProfit.historyLoading()) lastRefresh = System.currentTimeMillis() - (REFRESH_MS - 8000);
            } catch (Exception ignored) {
            } finally {
                running = false;
            }
        });
    }

    static List<Fusion> compute() throws Exception {
        loadRecipes();
        JsonObject products = BazaarSnapshot.products();
        int n = ids.length;
        double[] bid = new double[n], ask = new double[n], fill = new double[n], sell = new double[n];
        for (int k = 0; k < n; k++) {
            JsonObject p = products.has(ids[k]) ? products.getAsJsonObject(ids[k]) : null;
            if (p == null) continue;
            bid[k] = BazaarSnapshot.bid(p);
            ask[k] = BazaarSnapshot.ask(p);
            fill[k] = BazaarSnapshot.fillRate(p);
            sell[k] = BazaarSnapshot.sellRate(p);
        }
        double tax = BaseProfit.getTaxPercent() / 100.0;
        double minProfit = BaseProfit.getCoins();
        double minRate = BaseProfit.getMinItemsPerHour();

        // the same 7-day average check as the flip list, done once per shard (catches bought-out or dumped shards)
        boolean[] bidOk = new boolean[n], askOk = new boolean[n];
        for (int k = 0; k < n; k++) {
            if (bid[k] > 0) bidOk[k] = BaseProfit.priceOk(ids[k], bid[k], false);
            if (ask[k] > 0) askOk[k] = BaseProfit.priceOk(ids[k], ask[k], true);
        }

        // best recipe per output by profit per hour
        Map<Integer, int[]> bestRecipe = new HashMap<>();
        Map<Integer, double[]> bestNumbers = new HashMap<>();
        for (int[] r : recipes) {
            int l = r[1], lc = r[2], rt = r[3], rc = r[4], o = r[5], oc = r[6];
            if (bid[l] <= 0 || bid[rt] <= 0 || ask[o] <= 0) continue;
            if (!bidOk[l] || !bidOk[rt] || !askOk[o]) continue;
            double cost = lc * bid[l] + rc * bid[rt];
            double profit = oc * ask[o] * (1 - tax) - cost;
            if (profit <= 0 || profit < minProfit) continue;
            double fusionsPerHour = Math.min(Math.min(fill[l] / lc, fill[rt] / rc), sell[o] / oc);
            if (fusionsPerHour * oc < minRate || fusionsPerHour <= 0) continue;
            double perHour = profit * fusionsPerHour;
            double[] prev = bestNumbers.get(o);
            if (prev == null || perHour > prev[2]) {
                bestRecipe.put(o, r);
                bestNumbers.put(o, new double[]{cost, profit, perHour});
            }
        }

        List<Fusion> out = new ArrayList<>();
        for (var e : bestRecipe.entrySet()) {
            int[] r = e.getValue();
            double[] v = bestNumbers.get(e.getKey());
            out.add(new Fusion(types[r[0]], ids[r[1]], r[2], bid[r[1]], ids[r[3]], r[4], bid[r[3]],
                    ids[r[5]], r[6], ask[r[5]], v[0], v[1], v[2]));
        }
        out.sort((a, b) -> Double.compare(b.perHour(), a.perHour()));
        return out;
    }
}
