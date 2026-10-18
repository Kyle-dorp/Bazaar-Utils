package com.github.mkram17.bazaarutils.features;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One way to make money, in the units {@link OpportunityAdvisor} compares across every list: what one unit (an item flipped,
 * a book crafted, a fusion, a forge run) earns and costs, how many units an hour the market allows, how long its coins are
 * tied up, how many forge slot-hours it takes, and how far to trust the numbers. Built from each calculator's own rows; pure
 * (no Minecraft or network access) so it can be unit-tested.
 *
 * <p>Capital follows Little's law: running {@code x} units an hour with each unit's coins tied up for {@code holdHours} keeps
 * {@code x * unitCost * holdHours} coins busy on average. Forge slots the same way: {@code x * slotHours} slots busy.
 *
 * <p>{@code confidence} (0-1) scales the profit the advisor plans with. It only drops for two things the rows can show: a thin
 * market (few trades a day, so yours may not fill or sell at the listed price) and two prices for the same thing that disagree
 * (lowest BIN vs its 3-day average, a buy price vs its 7-day normal). {@code doubts} says which, in words.
 */
public record Opportunity(Kind kind, String id, String name, double unitProfit, double unitCost, double maxPerHour,
                          double holdHours, double slotHours, double confidence, List<String> doubts, String how) {

    public enum Kind {
        FLIP("Flip"), DIP("Dip"), BOOK("Book craft"), FUSION("Fusion"), FORGE("Forge");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** A market trading once a day gets half confidence: even odds that yours is the one that trades today. */
    static final double HALF_CONFIDENCE_TRADES_PER_HOUR = 1 / 24.0;
    /** How much of the gap between two prices for the same thing comes off: prices 2x apart cost a quarter of the profit. */
    static final double DISAGREEMENT_WEIGHT = 0.5;
    /** Factors at or above this are not worth a line in the explanation. */
    private static final double MENTION_BELOW = 0.95;

    /** Forges keep running while you're away; anything on the Bazaar needs you online to keep your orders on top. */
    public boolean passive() {
        return kind == Kind.FORGE;
    }

    /** Profit per unit after the confidence haircut: what the advisor maximises. */
    public double expectedProfit() {
        return unitProfit * confidence;
    }

    /** Coins kept busy per unit-per-hour of rate (Little's law). */
    public double capitalPerRate() {
        return unitCost * holdHours;
    }

    /** Expected profit per hour for each coin tied up: decides who gets a tight purse. */
    public double returnPerCoinHour() {
        return expectedProfit() / capitalPerRate();
    }

    /** Expected profit per hour at the market's limit, ignoring purse and slots (the flip lists' own ranking, after the haircut). */
    public double perHourAtLimit() {
        return expectedProfit() * maxPerHour;
    }

    // ---- shared rules ----

    /** Confidence from how often the market trades (units or runs per hour). */
    static double volumeFactor(double tradesPerHour) {
        if (!(tradesPerHour > 0)) return 0;
        return tradesPerHour / (tradesPerHour + HALF_CONFIDENCE_TRADES_PER_HOUR);
    }

    /** Confidence from two prices for the same thing: 1 when they agree, down to 1 - weight when one is a tiny fraction of the other. */
    static double agreementFactor(double a, double b) {
        if (!(a > 0) || !(b > 0)) return 1; // only one price known: nothing to disagree with
        return 1 - DISAGREEMENT_WEIGHT * (1 - Math.min(a, b) / Math.max(a, b));
    }

    /** Hours until one trade completes: no sooner than you come back to it, nor than the market trades one unit. */
    static double waitHours(double checkHours, double unitsPerHour) {
        return Math.max(Math.max(0, checkHours), 1 / unitsPerHour);
    }

    private static boolean usable(double profit, double cost, double perHour) {
        return profit > 0 && cost > 0 && perHour > 0 && Double.isFinite(profit) && Double.isFinite(cost) && Double.isFinite(perHour);
    }

    // ---- one factory per calculator ----

    /**
     * A BazaarFlip row (flip or dip; FlipHud reads these by reflection). profitPerHour is the patched totalProfit: profit per
     * item after tax times the slower side of the 7-day volume, so units per hour is profitPerHour / profitPerItem. Coins sit
     * in the buy order until it fills, then in the items until they sell. typicalBuy is the item's 7-day normal buy price
     * (BaseProfit.typicalBuy, NaN if unknown); a dip is cheap against it on purpose, so only flips are marked down for it.
     * Null if the row can't be planned with.
     */
    public static Opportunity flip(String id, String name, double buyPrice, double sellPrice, double profitPerItem,
                                   double profitPerHour, double typicalBuy, boolean dip, double orderCheckHours) {
        if (!usable(profitPerItem, buyPrice, profitPerHour)) return null;
        double rate = profitPerHour / profitPerItem;
        List<String> doubts = new ArrayList<>();
        double volume = volumeFactor(rate);
        if (volume < MENTION_BELOW) doubts.add("trades only ~" + OpportunityAdvisor.perDay(rate) + "/day");
        double agreement = dip ? 1 : agreementFactor(buyPrice, typicalBuy);
        if (agreement < MENTION_BELOW) {
            doubts.add("buy price " + OpportunityAdvisor.coins(buyPrice) + " vs 7-day normal " + OpportunityAdvisor.coins(typicalBuy));
        }
        double hold = 2 * waitHours(orderCheckHours, rate);
        return new Opportunity(dip ? Kind.DIP : Kind.FLIP, id, name, profitPerItem, buyPrice, rate, hold, 0,
                volume * agreement, List.copyOf(doubts),
                "buy order at " + OpportunityAdvisor.coins(buyPrice) + ", sell offer at " + OpportunityAdvisor.coins(sellPrice));
    }

    /** A {@link BookCraft} row: crafts per hour is perHour / profit (the slower of base-book fills and finished-book sales). */
    public static Opportunity book(BookCraft.Craft c, double orderCheckHours) {
        if (!usable(c.profit(), c.cost(), c.perHour())) return null;
        double rate = c.perHour() / c.profit();
        double volume = volumeFactor(rate);
        List<String> doubts = volume < MENTION_BELOW ? List.of("only ~" + OpportunityAdvisor.perDay(rate) + " crafts/day fill") : List.of();
        return new Opportunity(Kind.BOOK, c.id(), c.title(), c.profit(), c.cost(), rate, 2 * waitHours(orderCheckHours, rate), 0,
                volume, doubts, "buy " + c.units() + "x " + c.fromName() + " at " + OpportunityAdvisor.coins(c.bid())
                        + ", combine, sell at " + OpportunityAdvisor.coins(c.ask()));
    }

    /** A {@link FusionCalc} row: fusions per hour is perHour / profit (the slowest input fill or output sale). */
    public static Opportunity fusion(FusionCalc.Fusion f, UnaryOperator<String> nameOf, double orderCheckHours) {
        if (!usable(f.profit(), f.cost(), f.perHour())) return null;
        double rate = f.perHour() / f.profit();
        double volume = volumeFactor(rate);
        List<String> doubts = volume < MENTION_BELOW ? List.of("only ~" + OpportunityAdvisor.perDay(rate) + " fusions/day fill") : List.of();
        return new Opportunity(Kind.FUSION, f.output(), f.outputCount() + "x " + nameOf.apply(f.output()), f.profit(), f.cost(),
                rate, 2 * waitHours(orderCheckHours, rate), 0, volume, doubts,
                "fuse " + f.leftCount() + "x " + nameOf.apply(f.left()) + " + " + f.rightCount() + "x " + nameOf.apply(f.right()));
    }

    /**
     * A {@link ForgeCalc} row. One run is the whole route; its slot time already counts how long a slot is held until you
     * collect. runsPerHour from the row's throughput is the most the market and part supply allow (and never more than all
     * your slots), so the advisor can't plan past either. Coins are tied up from buying the parts (filled by your next
     * check-in) through the forging to the sale. An AH output is marked down when its lowest BIN and 3-day average disagree.
     */
    public static Opportunity forge(ForgeCalc.Row row, String name, double forgeCheckHours) {
        ForgePlanner.Route r = row.route();
        if (!usable(r.profit(), r.cost(), row.throughput().runsPerHour()) || !(r.slotSeconds() > 0)) return null;
        double slotHours = r.slotSeconds() / 3600.0;
        double marketRuns = row.marketPerHour() / row.recipe().count();
        List<String> doubts = new ArrayList<>();
        double volume = volumeFactor(marketRuns);
        if (volume < MENTION_BELOW) doubts.add("sells only ~" + OpportunityAdvisor.perDay(marketRuns) + "/day");
        double agreement = row.soldOnAh() ? agreementFactor(row.lowestBin(), row.avgBin()) : 1;
        if (agreement < MENTION_BELOW) {
            doubts.add("lowest BIN " + OpportunityAdvisor.coins(row.lowestBin()) + " vs 3-day average " + OpportunityAdvisor.coins(row.avgBin()));
        }
        double hold = Math.max(0, forgeCheckHours) + slotHours + waitHours(forgeCheckHours, marketRuns);
        return new Opportunity(Kind.FORGE, r.id(), name, r.profit(), r.cost(), row.throughput().runsPerHour(), hold, slotHours,
                volume * agreement, List.copyOf(doubts),
                "buy parts for " + OpportunityAdvisor.coins(r.cost()) + ", " + r.forgeSteps() + (r.forgeSteps() == 1 ? " forge" : " forges")
                        + ", sell for " + OpportunityAdvisor.coins(r.revenue()) + (row.soldOnAh() ? " on the AH" : " on the Bazaar"));
    }
}
