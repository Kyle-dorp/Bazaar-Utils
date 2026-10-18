package com.github.mkram17.bazaarutils.features;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * "What could I make from the shards I already hold?" Given your tracked shards (quantity and what you paid), the fusion
 * recipes and what each output sells for, plans fusions greedily: the most profitable fusion per run first, as many times
 * as your shards allow, then the next with what's left, so a shard two recipes want is never counted twice. Pure logic
 * (no Minecraft classes) so it can be unit tested.
 */
final class FusionPotential {
    /** One planned fusion: the recipe, how many times, and the profit over what the shards cost you. */
    record Fusion(FusionCalc.NamedRecipe recipe, int times, double profit) { }

    record Plan(List<Fusion> fusions, double total) { }

    private static double avg(double[] h) {
        return h[0] > 0 ? h[1] / h[0] : 0;
    }

    /**
     * @param held      shard name -> {quantity, total cost}; names are matched loosely (case, icons)
     * @param sellValue output shard name -> coins per shard after tax, NaN if unknown
     */
    static Plan plan(Map<String, double[]> held, List<FusionCalc.NamedRecipe> recipes, ToDoubleFunction<String> sellValue) {
        Map<String, double[]> left = new HashMap<>();
        for (var e : held.entrySet()) {
            if (e.getValue()[0] > 0) left.merge(ProfitLedger.norm(e.getKey()), e.getValue().clone(), (a, b) -> new double[]{a[0] + b[0], a[1] + b[1]});
        }
        List<Fusion> out = new ArrayList<>();
        double total = 0;
        while (true) {
            FusionCalc.NamedRecipe best = null;
            double bestProfit = 0;
            int bestTimes = 0;
            for (FusionCalc.NamedRecipe r : recipes) {
                String l = ProfitLedger.norm(r.left()), rt = ProfitLedger.norm(r.right());
                boolean same = l.equals(rt);
                double[] hl = left.get(l), hr = left.get(rt);
                if (hl == null || hr == null) continue;
                int times = same ? (int) Math.floor(hl[0] / (r.leftCount() + r.rightCount()))
                        : (int) Math.floor(Math.min(hl[0] / r.leftCount(), hr[0] / r.rightCount()));
                if (times < 1) continue;
                double value = sellValue.applyAsDouble(r.output());
                if (!(value > 0)) continue;
                double cost = r.leftCount() * avg(hl) + r.rightCount() * avg(hr);
                double profit = r.outputCount() * value - cost;
                if (profit > bestProfit) {
                    best = r;
                    bestProfit = profit;
                    bestTimes = times;
                }
            }
            if (best == null) break;
            use(left.get(ProfitLedger.norm(best.left())), best.leftCount() * (double) bestTimes);
            use(left.get(ProfitLedger.norm(best.right())), best.rightCount() * (double) bestTimes);
            out.add(new Fusion(best, bestTimes, bestProfit * bestTimes));
            total += bestProfit * bestTimes;
        }
        return new Plan(out, total);
    }

    private static void use(double[] h, double qty) {
        double a = avg(h);
        h[0] -= qty;
        h[1] -= a * qty;
    }
}
