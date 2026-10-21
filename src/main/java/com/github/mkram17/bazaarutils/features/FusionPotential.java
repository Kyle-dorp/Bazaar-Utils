package com.github.mkram17.bazaarutils.features;

import java.util.*;

/**
 * "What could I fuse from the shards I actually have?" Plans fusions from your real holdings (SkyHanni's Hunting Box counts),
 * using each shard at most once across the whole plan, and ranks the result by profit. Pure logic (no Minecraft classes) so
 * it can be unit tested.
 *
 * <p>Profit of one fusion is what the output sells for (sell offer after tax) minus what its input shards are worth (top buy
 * order): the same basis as the fusion list, so it answers "fuse these, or just sell them?". Shards are allocated greedily, most
 * profit per fusion first: since a fusion's profit doesn't depend on what's left, a single sorted pass does it.
 *
 * <p>Cost: only recipes whose inputs you hold are looked at, and each shard's price is asked for at most once, so the work
 * grows with what you hold, not with the size of the recipe list or your trade history.
 */
final class FusionPotential {
    /** Shard prices by id: what a shard you hold is worth as an input, and what one sells for as an output. NaN = unknown. */
    interface Prices {
        double input(String id);

        double output(String id);
    }

    /** A recipe by shard id (FusionCalc's ids): leftCount x left + rightCount x right -> outputCount x output. */
    record Recipe(String left, int leftCount, String right, int rightCount, String output, int outputCount) { }

    /** One planned line: run {@code recipe} {@code times} times for {@code profit} in total ({@code profitEach} per fusion). */
    record Fusion(Recipe recipe, int times, double profitEach, double profit) { }

    /** Planned fusions, most total profit first, and how many of your shards they use. */
    record Plan(List<Fusion> fusions, double total, long shardsUsed, long shardsHeld) { }

    static final Plan EMPTY = new Plan(List.of(), 0, 0, 0);

    /** The fusion minimum in force: the fusion-only setting when it's set (above 0), else the shared min profit per item. */
    static double minProfit(double fusionMin, double sharedMin) {
        return fusionMin > 0 ? fusionMin : sharedMin;
    }

    /** Whether a whole fusion's profit (the "= profit" on its row) is worth showing: positive and at least {@code min}. */
    static boolean worthIt(double profit, double min) {
        return profit > 0 && profit >= min;
    }

    private static double memo(Map<String, Double> cache, String id, java.util.function.ToDoubleFunction<String> f) {
        Double v = cache.get(id);
        if (v == null) {
            v = f.applyAsDouble(id);
            cache.put(id, v);
        }
        return v;
    }

    /**
     * @param held    shard id -> how many you have
     * @param recipes recipes to consider (all, or only those using shards you hold: anything else is skipped anyway)
     */
    static Plan plan(Map<String, Integer> held, Collection<Recipe> recipes, Prices prices) {
        return plan(held, recipes, prices, 0);
    }

    /** As {@link #plan(Map, Collection, Prices)}, leaving out fusions that make less than {@code minProfit} each. */
    static Plan plan(Map<String, Integer> held, Collection<Recipe> recipes, Prices prices, double minProfit) {
        Map<String, Double> in = new HashMap<>(), out = new HashMap<>();
        record Candidate(Recipe recipe, double profitEach) { }
        List<Candidate> candidates = new ArrayList<>();
        for (Recipe r : recipes) {
            boolean same = r.left().equals(r.right());
            int haveLeft = held.getOrDefault(r.left(), 0), haveRight = held.getOrDefault(r.right(), 0);
            if (same ? haveLeft < r.leftCount() + r.rightCount() : haveLeft < r.leftCount() || haveRight < r.rightCount()) continue;
            double vl = memo(in, r.left(), prices::input), vr = memo(in, r.right(), prices::input), vo = memo(out, r.output(), prices::output);
            if (!(vl > 0) || !(vr > 0) || !(vo > 0)) continue;
            double profitEach = r.outputCount() * vo - r.leftCount() * vl - r.rightCount() * vr;
            if (worthIt(profitEach, minProfit)) candidates.add(new Candidate(r, profitEach));
        }
        candidates.sort((a, b) -> {
            int c = Double.compare(b.profitEach(), a.profitEach());
            return c != 0 ? c : a.recipe().output().compareTo(b.recipe().output()); // deterministic among equals
        });

        Map<String, Integer> left = new HashMap<>(held);
        List<Fusion> fusions = new ArrayList<>();
        double total = 0;
        long used = 0;
        for (Candidate c : candidates) {
            Recipe r = c.recipe();
            boolean same = r.left().equals(r.right());
            int l = left.getOrDefault(r.left(), 0), rt = left.getOrDefault(r.right(), 0);
            int times = same ? l / (r.leftCount() + r.rightCount()) : Math.min(l / r.leftCount(), rt / r.rightCount());
            if (times < 1) continue;
            left.merge(r.left(), -r.leftCount() * times, Integer::sum);
            left.merge(r.right(), -r.rightCount() * times, Integer::sum);
            fusions.add(new Fusion(r, times, c.profitEach(), c.profitEach() * times));
            total += c.profitEach() * times;
            used += (long) (r.leftCount() + r.rightCount()) * times;
        }
        fusions.sort((a, b) -> Double.compare(b.profit(), a.profit()));
        long heldTotal = 0;
        for (int n : held.values()) heldTotal += Math.max(0, n);
        return new Plan(fusions, total, used, heldTotal);
    }
}
