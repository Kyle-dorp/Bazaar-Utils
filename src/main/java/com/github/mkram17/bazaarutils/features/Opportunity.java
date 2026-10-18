package com.github.mkram17.bazaarutils.features;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One way to make money, in the units {@link OpportunityAdvisor} compares across every list: what one unit (an item flipped,
 * a book crafted, a fusion, a forge run) earns and costs, how many units an hour you can get through the market, how long its
 * coins are tied up, how many forge slot-hours and anvil combines it takes, and how far to trust the numbers. Built from each
 * calculator's own rows; pure (no Minecraft or network access) so it can be unit-tested.
 *
 * <p>The lists rank by an item's whole 7-day volume, but other players' orders compete for the same fills. {@code share} is
 * the fraction of the market you capture ({@link MarketShare}: from how many orders sit near the top of the order book), and
 * {@code maxPerHour} is already your share, not the whole market.
 *
 * <p>Capital follows Little's law: running {@code x} units an hour with each unit's coins tied up for {@code holdHours} keeps
 * {@code x * unitCost * holdHours} coins busy on average. Forge slots the same way: {@code x * slotHours} slots busy. Anvil
 * work too: {@code x * labour} combines an hour.
 *
 * <p>{@code confidence} (0-1) scales the profit the advisor plans with. It only drops for two things the rows can show: a thin
 * market (few trades a day, so yours may not fill or sell at the listed price) and two prices for the same thing that disagree
 * (lowest BIN vs its 3-day average, a buy price vs its 7-day normal). {@code doubts} says which, in words.
 */
public record Opportunity(Kind kind, String id, String name, double unitProfit, double unitCost, double maxPerHour,
                          double holdHours, double slotHours, double confidence, List<String> doubts, String how,
                          double share, double labour) {

    /** An option on the lists' full volume that takes no anvil work. */
    public Opportunity(Kind kind, String id, String name, double unitProfit, double unitCost, double maxPerHour, double holdHours,
                       double slotHours, double confidence, List<String> doubts, String how) {
        this(kind, id, name, unitProfit, unitCost, maxPerHour, holdHours, slotHours, confidence, doubts, how, 1, 0);
    }

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
    private static final Pattern BOOK = Pattern.compile("^ENCHANTMENT_(.+)_(\\d+)$");

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

    /** Expected profit per hour at your share of the market, ignoring purse, slots and anvil time. */
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

    /** Hours until one of your trades completes: no sooner than you come back to it, nor than you get one unit through. */
    static double waitHours(double checkHours, double unitsPerHour) {
        return Math.max(Math.max(0, checkHours), 1 / unitsPerHour);
    }

    private static boolean usable(double profit, double cost, double perHour) {
        return profit > 0 && cost > 0 && perHour > 0 && Double.isFinite(profit) && Double.isFinite(cost) && Double.isFinite(perHour);
    }

    /** The book a craft starts from: units = 2^(target tier - base tier), so 16 units of a tier V craft are tier I books. */
    static String baseBook(String targetId, int units) {
        Matcher m = BOOK.matcher(targetId == null ? "" : targetId);
        if (!m.matches() || units < 1 || Integer.bitCount(units) != 1) return null;
        int tier = Integer.parseInt(m.group(2)) - Integer.numberOfTrailingZeros(units);
        return tier >= 1 ? "ENCHANTMENT_" + m.group(1) + "_" + tier : null;
    }

    // ---- one factory per calculator ----

    /**
     * A BazaarFlip row (flip or dip, from {@link BazaarFlipRows}). profitPerHour is the patched totalProfit: profit per item
     * after tax times the slower side of the 7-day volume, so the market's units per hour is profitPerHour / profitPerItem.
     * You get your share of that: the smaller of your buy order's and your sell offer's. Coins sit in the buy order until it
     * fills, then in the items until they sell. typicalBuy is the item's 7-day normal buy price (BaseProfit.typicalBuy, NaN if
     * unknown); a dip is cheap against it on purpose, so only flips are marked down for it. Null if the row can't be planned with.
     */
    public static Opportunity flip(String id, String name, double buyPrice, double sellPrice, double profitPerItem,
                                   double profitPerHour, double typicalBuy, boolean dip, double orderCheckHours, MarketShare shares) {
        if (!usable(profitPerItem, buyPrice, profitPerHour)) return null;
        double market = profitPerHour / profitPerItem;
        double share = Math.min(shares.buying(id), shares.selling(id));
        double rate = market * share;
        if (!(rate > 0)) return null;
        List<String> doubts = new ArrayList<>();
        double volume = volumeFactor(market);
        if (volume < MENTION_BELOW) doubts.add("trades only ~" + OpportunityAdvisor.perDay(market) + "/day");
        double agreement = dip ? 1 : agreementFactor(buyPrice, typicalBuy);
        if (agreement < MENTION_BELOW) {
            doubts.add("buy price " + OpportunityAdvisor.coins(buyPrice) + " vs 7-day normal " + OpportunityAdvisor.coins(typicalBuy));
        }
        double hold = 2 * waitHours(orderCheckHours, rate);
        return new Opportunity(dip ? Kind.DIP : Kind.FLIP, id, name, profitPerItem, buyPrice, rate, hold, 0,
                volume * agreement, List.copyOf(doubts),
                "buy order at " + OpportunityAdvisor.coins(buyPrice) + ", sell offer at " + OpportunityAdvisor.coins(sellPrice),
                share, 0);
    }

    /**
     * A {@link BookCraft} row: the market's crafts per hour is perHour / profit (the slower of base-book fills and finished-book
     * sales); you get the smaller of your share of the base book's buy orders and of the finished book's sell offers. Combining
     * {@code units} books into one takes {@code units - 1} anvil combines.
     */
    public static Opportunity book(BookCraft.Craft c, double orderCheckHours, MarketShare shares) {
        if (!usable(c.profit(), c.cost(), c.perHour())) return null;
        double market = c.perHour() / c.profit();
        double share = Math.min(shares.buying(baseBook(c.id(), c.units())), shares.selling(c.id()));
        double rate = market * share;
        if (!(rate > 0)) return null;
        double volume = volumeFactor(market);
        List<String> doubts = volume < MENTION_BELOW ? List.of("only ~" + OpportunityAdvisor.perDay(market) + " crafts/day fill") : List.of();
        return new Opportunity(Kind.BOOK, c.id(), c.title(), c.profit(), c.cost(), rate, 2 * waitHours(orderCheckHours, rate), 0,
                volume, doubts, "buy " + c.units() + "x " + c.fromName() + " at " + OpportunityAdvisor.coins(c.bid())
                        + ", combine, sell at " + OpportunityAdvisor.coins(c.ask()), share, Math.max(0, c.units() - 1));
    }

    /** A {@link FusionCalc} row: the market's fusions per hour is perHour / profit; your share is the smallest of the three markets'. */
    public static Opportunity fusion(FusionCalc.Fusion f, UnaryOperator<String> nameOf, double orderCheckHours, MarketShare shares) {
        if (!usable(f.profit(), f.cost(), f.perHour())) return null;
        double market = f.perHour() / f.profit();
        double share = Math.min(Math.min(shares.buying(f.left()), shares.buying(f.right())), shares.selling(f.output()));
        double rate = market * share;
        if (!(rate > 0)) return null;
        double volume = volumeFactor(market);
        List<String> doubts = volume < MENTION_BELOW ? List.of("only ~" + OpportunityAdvisor.perDay(market) + " fusions/day fill") : List.of();
        return new Opportunity(Kind.FUSION, f.output(), f.outputCount() + "x " + nameOf.apply(f.output()), f.profit(), f.cost(),
                rate, 2 * waitHours(orderCheckHours, rate), 0, volume, doubts,
                "fuse " + f.leftCount() + "x " + nameOf.apply(f.left()) + " + " + f.rightCount() + "x " + nameOf.apply(f.right()),
                share, 0);
    }

    /**
     * A {@link ForgeCalc} row. One run is the whole route; its slot time already counts how long a slot is held until you
     * collect. The row's runs per hour is the least of the market, part supply and all your slots at full volume; here the
     * market and every bought part (supplyPerHour: units per hour, NaN if unknown) are cut to your share, slots are not. Coins
     * are tied up from buying the parts (by your next check-in, or as fast as your share of the scarcest part comes in)
     * through the forging to the sale. An AH output gets the base share (no order book) and is marked down when its lowest BIN
     * and 3-day average disagree.
     */
    public static Opportunity forge(ForgeCalc.Row row, String name, double forgeCheckHours, MarketShare shares,
                                    ToDoubleFunction<String> supplyPerHour) {
        ForgePlanner.Route r = row.route();
        if (!usable(r.profit(), r.cost(), row.throughput().runsPerHour()) || !(r.slotSeconds() > 0)) return null;
        double slotHours = r.slotSeconds() / 3600.0;
        double marketRuns = row.marketPerHour() / row.recipe().count();
        double share = row.soldOnAh() ? shares.base() : shares.selling(r.id());
        double saleRuns = share * marketRuns;
        double partRuns = Double.POSITIVE_INFINITY;
        for (var e : ForgePlanner.boughtInputs(r.tree()).entrySet()) {
            double supply = supplyPerHour.applyAsDouble(e.getKey());
            if (supply >= 0 && e.getValue() > 0) partRuns = Math.min(partRuns, shares.buying(e.getKey()) * supply / e.getValue());
        }
        double runs = Math.min(row.throughput().runsPerHour(), Math.min(saleRuns, partRuns));
        if (!(runs > 0)) return null;
        List<String> doubts = new ArrayList<>();
        double volume = volumeFactor(marketRuns);
        if (volume < MENTION_BELOW) doubts.add("sells only ~" + OpportunityAdvisor.perDay(marketRuns) + "/day");
        double agreement = row.soldOnAh() ? agreementFactor(row.lowestBin(), row.avgBin()) : 1;
        if (agreement < MENTION_BELOW) {
            doubts.add("lowest BIN " + OpportunityAdvisor.coins(row.lowestBin()) + " vs 3-day average " + OpportunityAdvisor.coins(row.avgBin()));
        }
        double hold = waitHours(forgeCheckHours, partRuns) + slotHours + waitHours(forgeCheckHours, saleRuns);
        return new Opportunity(Kind.FORGE, r.id(), name, r.profit(), r.cost(), runs, hold, slotHours,
                volume * agreement, List.copyOf(doubts),
                "buy parts for " + OpportunityAdvisor.coins(r.cost()) + ", " + r.forgeSteps() + (r.forgeSteps() == 1 ? " forge" : " forges")
                        + ", sell for " + OpportunityAdvisor.coins(r.revenue()) + (row.soldOnAh() ? " on the AH" : " on the Bazaar"),
                share, 0);
    }
}
