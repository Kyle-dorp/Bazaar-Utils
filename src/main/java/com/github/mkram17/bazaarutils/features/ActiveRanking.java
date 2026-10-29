package com.github.mkram17.bazaarutils.features;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ActiveRanking {
    public record Pick(Opportunity opportunity, int quantity, double cost, double estimatedProfit, double hours) { }

    private ActiveRanking() { }

    public static double budget(double purse, double reservePercent) {
        if (!Double.isFinite(purse) || purse <= 0 || !Double.isFinite(reservePercent)) return 0;
        return purse * (1 - Math.max(0, Math.min(100, reservePercent)) / 100);
    }

    public static List<Pick> rank(List<Opportunity> options, double budget, double minutes, double combinesPerHour) {
        List<Pick> picks = new ArrayList<>();
        if (!(budget > 0) || !Double.isFinite(budget) || !(minutes > 0) || !Double.isFinite(minutes)) return picks;
        double hours = minutes / 60;
        for (Opportunity o : options) {
            if (o.passive() || !(o.unitCost() > 0) || !(o.maxPerHour() > 0) || !(o.expectedProfit() > 0)
                    || !Double.isFinite(o.unitCost()) || !Double.isFinite(o.maxPerHour())
                    || !Double.isFinite(o.expectedProfit()) || o.confidence() > 1
                    || !Double.isFinite(o.holdHours()) || o.holdHours() < 0 || o.holdHours() > hours) continue;
            double manual = o.kind() == Opportunity.Kind.FUSION ? 1.0 / 120 : 0;
            if (o.labour() > 0) {
                if (!(combinesPerHour > 0) || !Double.isFinite(combinesPerHour)) continue;
                manual += o.labour() / combinesPerHour;
            }
            double unitHours = 2 / o.maxPerHour() + manual;
            double timeLimit = hours / unitHours;
            if (manual > 0) timeLimit = Math.min(timeLimit, (hours - o.holdHours()) / manual);
            int quantity = (int) Math.floor(Math.min(71680, Math.min(budget * 0.25 / o.unitCost(), timeLimit)));
            if (quantity < 1) continue;
            double cost = quantity * o.unitCost(), profit = quantity * o.expectedProfit();
            if (!Double.isFinite(cost) || !Double.isFinite(profit)) continue;
            picks.add(new Pick(o, quantity, cost, profit, Math.max(o.holdHours(), 2 * quantity / o.maxPerHour()) + quantity * manual));
        }
        picks.sort(Comparator.comparingDouble(Pick::estimatedProfit).reversed()
                .thenComparingDouble(Pick::hours).thenComparingDouble(Pick::cost)
                .thenComparing(p -> p.opportunity().id()).thenComparing(p -> p.opportunity().how()));
        java.util.Map<String, Pick> best = new java.util.LinkedHashMap<>();
        for (Pick pick : picks) best.putIfAbsent(pick.opportunity().kind() + ":" + pick.opportunity().id(), pick);
        return List.copyOf(best.values());
    }
}
