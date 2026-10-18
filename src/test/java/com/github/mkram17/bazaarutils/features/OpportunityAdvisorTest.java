package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.Opportunity.Kind;
import com.github.mkram17.bazaarutils.features.OpportunityAdvisor.Pick;
import com.github.mkram17.bazaarutils.features.OpportunityAdvisor.Plan;
import com.github.mkram17.bazaarutils.features.OpportunityAdvisor.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OpportunityAdvisorTest {
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final MarketShare FULL = MarketShare.fixed(1);

    // ---- fixtures: opportunities in the advisor's own units ----

    private static Opportunity flip(String id, double profit, double cost, double maxPerHour, double holdHours) {
        return new Opportunity(Kind.FLIP, id, id, profit, cost, maxPerHour, holdHours, 0, 1, List.of(), "flip " + id);
    }

    private static Opportunity forge(String id, double profit, double cost, double maxPerHour, double holdHours, double slotHours) {
        return new Opportunity(Kind.FORGE, id, id, profit, cost, maxPerHour, holdHours, slotHours, 1, List.of(), "forge " + id);
    }

    private static Plan plan(Settings s, Opportunity... options) {
        return OpportunityAdvisor.plan(Arrays.asList(options), s);
    }

    private static Pick pick(List<Pick> picks, String id) {
        return picks.stream().filter(p -> p.opportunity().id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id + " not picked: " + picks.stream().map(p -> p.opportunity().id()).toList()));
    }

    private static List<String> ids(List<Pick> picks) {
        return picks.stream().map(p -> p.opportunity().id()).toList();
    }

    private static Opportunity craft(String id, double profit, double cost, double maxPerHour, double holdHours, double combines) {
        return new Opportunity(Kind.BOOK, id, id, profit, cost, maxPerHour, holdHours, 0, 1, List.of(), "craft " + id, 1, combines);
    }

    /** A Bazaar product with buyOrders orders at its best buy order and sellOffers at its best sell offer. */
    private static JsonObject product(int buyOrders, int sellOffers) {
        JsonObject p = new JsonObject();
        p.add("sell_summary", JsonParser.parseString("[{\"amount\": 10, \"pricePerUnit\": 100.0, \"orders\": " + buyOrders + "}]"));
        p.add("buy_summary", JsonParser.parseString("[{\"amount\": 10, \"pricePerUnit\": 110.0, \"orders\": " + sellOffers + "}]"));
        return p;
    }

    static ForgeCalc.Row forgeRow(double revenue, double cost, double slotHours, double marketPerHour, double runsPerHour,
                                  boolean onAh, double lowestBin, double avgBin) {
        return forgeRow(revenue, cost, slotHours, marketPerHour, runsPerHour, onAh, lowestBin, avgBin, List.of());
    }

    private static ForgeCalc.Row forgeRow(double revenue, double cost, double slotHours, double marketPerHour, double runsPerHour,
                                          boolean onAh, double lowestBin, double avgBin, List<ForgePlanner.Step> parts) {
        double secs = slotHours * 3600;
        ForgePlanner.Step tree = new ForgePlanner.Step("DRILL", 1, true, 1, secs, cost, secs, secs, parts);
        ForgePlanner.Route route = new ForgePlanner.Route("DRILL", tree, revenue, cost, revenue - cost, secs, secs, 1,
                (revenue - cost) / slotHours);
        ForgePlanner.Recipe recipe = new ForgePlanner.Recipe("DRILL", 1, secs, 0, Map.of());
        ForgePlanner.Throughput t = new ForgePlanner.Throughput(runsPerHour, runsPerHour * (revenue - cost),
                ForgePlanner.Throughput.MARKET, marketPerHour);
        return new ForgeCalc.Row(route, recipe, t.profitPerHour(), onAh, lowestBin, avgBin, marketPerHour, t);
    }

    // ---- each calculator's row in common units ----

    /** BazaarFlip's totalProfit is profit/item x items/hour, so items/hour is the ratio; coins wait for the fill, then the sale. */
    @Test
    void flipRateAndHoldTimeComeFromTheListRow() {
        Opportunity o = Opportunity.flip("X", "X", 100, 130, 28, 28 * 50, 100, false, 1, FULL);
        assertEquals(50, o.maxPerHour(), 1e-9, "50 items/hour: profit per hour over profit per item");
        assertEquals(100, o.unitCost(), 1e-9, "each item ties up its buy price");
        assertEquals(2, o.holdHours(), 1e-9, "an hour to fill (your next check-in), an hour to sell");
        assertEquals(200, o.capitalPerRate(), 1e-9);

        Opportunity thin = Opportunity.flip("T", "T", 100, 130, 28, 28 * 0.1, 100, false, 1, FULL);
        assertEquals(20, thin.holdHours(), 1e-9, "one trade every 10 hours: 10h to fill and 10h to sell, not your check-in");
        assertNull(Opportunity.flip("L", "L", 100, 90, -10, -100, 100, false, 1, FULL), "a losing row can't be planned with");
    }

    /** A forge run's caps and coin time come from the route and its throughput, not the slot count alone. */
    @Test
    void forgeRunCapsAndHoldTimeComeFromTheRoute() {
        // sells 12/day, so 0.5 runs/hour although 7 slots of a 4-hour forge could do 1.75
        ForgeCalc.Row row = forgeRow(3_000_000, 2_000_000, 4, 0.5, 0.5, true, 3_200_000, 2_000_000);
        Opportunity o = Opportunity.forge(row, "Drill", 2, FULL, id -> Double.NaN);
        assertEquals(Kind.FORGE, o.kind());
        assertTrue(o.passive());
        assertEquals(1_000_000, o.unitProfit(), 1e-6);
        assertEquals(2_000_000, o.unitCost(), 1e-6);
        assertEquals(4, o.slotHours(), 1e-9);
        assertEquals(0.5, o.maxPerHour(), 1e-9, "the throughput's cap, not 7 slots' worth");
        assertEquals(2 + 4 + 2, o.holdHours(), 1e-9, "parts fill by your check-in, 4h forging, sold by the next check-in");
        double expected = (0.5 / (0.5 + 1 / 24.0)) * (1 - 0.5 * (1 - 2.0 / 3.2));
        assertEquals(expected, o.confidence(), 1e-12, "thin-ish market times BIN/average disagreement");
        assertTrue(o.doubts().stream().anyMatch(d -> d.contains("3-day average")), o.doubts().toString());
    }

    @Test
    void bookAndFusionRatesAreProfitPerHourOverProfit() {
        Opportunity book = Opportunity.book(new BookCraft.Craft("ENCHANTMENT_X_5", "X V", 1000, 100, 5000, "X I", 16, 62.5,
                2100, 1000), 1, FULL);
        assertEquals(5, book.maxPerHour(), 1e-9);
        assertEquals(1000, book.unitCost(), 1e-9);
        assertEquals(Kind.BOOK, book.kind());

        Opportunity fusion = Opportunity.fusion(new FusionCalc.Fusion("Combat", "A", 2, 100, "B", 3, 100, "OUT", 2, 400,
                500, 200, 2000), id -> id.toLowerCase(), 1, FULL);
        assertEquals(10, fusion.maxPerHour(), 1e-9);
        assertEquals(500, fusion.unitCost(), 1e-9);
        assertEquals("2x out", fusion.name());
        assertEquals(2, fusion.holdHours(), 1e-9);
    }

    /** Confidence only drops for a thin market or two prices that disagree; a dip's cheap buy price is the point, not a doubt. */
    @Test
    void confidenceDropsForThinMarketsAndDisagreeingPrices() {
        assertEquals(0.5, Opportunity.volumeFactor(1 / 24.0), 1e-12, "one trade a day: even odds");
        assertTrue(Opportunity.volumeFactor(10) > Opportunity.volumeFactor(1));
        assertTrue(Opportunity.volumeFactor(1) > Opportunity.volumeFactor(0.1));
        assertEquals(0, Opportunity.volumeFactor(0), 1e-12);

        assertEquals(1, Opportunity.agreementFactor(5, 5), 1e-12);
        assertEquals(1, Opportunity.agreementFactor(5, Double.NaN), 1e-12, "one price alone can't disagree");
        // the SX-R326: 16.77M lowest BIN against a 9.40M 3-day average
        double sx = Opportunity.agreementFactor(16_767_676, 9_399_500);
        assertEquals(1 - 0.5 * (1 - 9_399_500 / 16_767_676.0), sx, 1e-12);
        assertEquals(sx, Opportunity.agreementFactor(9_399_500, 16_767_676), 1e-12, "order doesn't matter");
        assertTrue(Opportunity.agreementFactor(100, 70) < Opportunity.agreementFactor(100, 90));

        Opportunity flip = Opportunity.flip("I", "I", 70, 130, 50, 50 * 100, 100, false, 1, FULL);
        Opportunity dip = Opportunity.flip("I", "I", 70, 130, 50, 50 * 100, 100, true, 1, FULL);
        assertTrue(flip.confidence() < dip.confidence(), "a flip 30% off its normal buy price is suspect; a dip is that by design");
        assertEquals(Kind.DIP, dip.kind());
    }

    // ---- your share of each market ----

    /**
     * A flip runs at your share of its market: the smaller of your buy order's and your sell offer's. Coins wait for your own
     * trades, at your share; but a thin market's doubt is about the market's pace, not your share of it.
     */
    @Test
    void flipRunsAtTheSmallerOfItsTwoSharesAndWaitsForYourOwnTrades() {
        JsonObject products = new JsonObject();
        products.add("X", product(9, 19)); // 10 buy orders near the top: 10%; 20 sell offers: 5%
        MarketShare shares = MarketShare.fromOrderBook(products, 0.05, 0.10);
        Opportunity o = Opportunity.flip("X", "X", 100, 130, 28, 28 * 50, 100, false, 1, shares);
        assertEquals(0.05, o.share(), 1e-12);
        assertEquals(50 * 0.05, o.maxPerHour(), 1e-9, "5% of the market's 50 an hour");
        assertEquals(2, o.holdHours(), 1e-9, "2.5 an hour still trade by your hourly check-in");

        Opportunity thin = Opportunity.flip("X", "X", 100, 130, 28, 28 * 0.5, 100, false, 1, shares);
        assertEquals(0.025, thin.maxPerHour(), 1e-12);
        assertEquals(2 * 40, thin.holdHours(), 1e-9, "your one trade in 40 hours on each side, not the market's one in 2");
        assertEquals(Opportunity.volumeFactor(0.5), thin.confidence(), 1e-12, "the market's own pace decides the doubt");
    }

    /** A craft buys its base book with buy orders and sells the finished book with a sell offer; the tighter share counts. */
    @Test
    void bookCraftShareIsTheTighterOfItsBaseAndFinishedBook() {
        assertEquals("ENCHANTMENT_ULTIMATE_WISDOM_1", Opportunity.baseBook("ENCHANTMENT_ULTIMATE_WISDOM_5", 16));
        assertEquals("ENCHANTMENT_LAST_STAND_2", Opportunity.baseBook("ENCHANTMENT_LAST_STAND_5", 8));
        assertNull(Opportunity.baseBook("ENCHANTMENT_X_2", 4), "there is no tier 0");
        assertNull(Opportunity.baseBook("ENCHANTMENT_X_5", 3), "not a power of two");

        JsonObject products = new JsonObject();
        products.add("ENCHANTMENT_X_1", product(3, 0)); // 4 buy orders fighting: 25%
        products.add("ENCHANTMENT_X_4", product(9, 0)); // 10: 10%
        products.add("ENCHANTMENT_X_5", product(0, 1)); // 2 sell offers: 50%
        MarketShare shares = MarketShare.fromOrderBook(products, 0.01, 0.5);
        Opportunity fromOne = Opportunity.book(new BookCraft.Craft("ENCHANTMENT_X_5", "X V", 1000, 100, 5000, "X I", 16, 62.5,
                2100, 1000), 1, shares);
        assertEquals(0.25, fromOne.share(), 1e-12, "the tier I buy orders are the tighter side");
        assertEquals(5 * 0.25, fromOne.maxPerHour(), 1e-9);
        assertEquals(15, fromOne.labour(), 1e-12, "16 books into one: 8 + 4 + 2 + 1 anvil combines");
        Opportunity fromFour = Opportunity.book(new BookCraft.Craft("ENCHANTMENT_X_5", "X V", 1000, 100, 5000, "X IV", 2, 500,
                2100, 1000), 1, shares);
        assertEquals(0.10, fromFour.share(), 1e-12);
        assertEquals(1, fromFour.labour(), 1e-12, "two tier IV books: one combine");
    }

    @Test
    void fusionShareIsTheSmallestOfItsThreeMarkets() {
        JsonObject products = new JsonObject();
        products.add("A", product(1, 0));   // buying A: 50%
        products.add("B", product(4, 0));   // buying B: 20%
        products.add("OUT", product(0, 3)); // selling OUT: 25%
        MarketShare shares = MarketShare.fromOrderBook(products, 0.01, 0.5);
        Opportunity f = Opportunity.fusion(new FusionCalc.Fusion("Combat", "A", 2, 100, "B", 3, 100, "OUT", 2, 400, 500, 200,
                2000), id -> id, 1, shares);
        assertEquals(0.20, f.share(), 1e-12);
        assertEquals(10 * 0.20, f.maxPerHour(), 1e-9);
    }

    /**
     * A forge run: your share cuts what the output's market takes and how fast each bought part comes in, but not your slots.
     * The parts take as long to come in as your share of the scarcest one needs. An AH output has no order book: the middle.
     */
    @Test
    void forgeShareCutsTheMarketAndThePartsButNotTheSlots() {
        JsonObject products = new JsonObject();
        products.add("DRILL", product(0, 9)); // selling the output: 10%
        products.add("PART", product(19, 0)); // buying the part: 5%
        MarketShare shares = MarketShare.fromOrderBook(products, 0.05, 0.10);
        ForgePlanner.Step part = new ForgePlanner.Step("PART", 4, false, 0, 0, 100, 0, 0, List.of());
        // the market takes 10 an hour, the part comes in at 20 an hour (4 a run); 7 slots of a 4-hour forge do 1.75
        ForgeCalc.Row row = forgeRow(3_000_000, 2_000_000, 4, 10, 1.75, false, Double.NaN, Double.NaN, List.of(part));
        Opportunity o = Opportunity.forge(row, "Drill", 2, shares, id -> id.equals("PART") ? 20 : Double.NaN);
        assertEquals(0.10, o.share(), 1e-12);
        assertEquals(0.05 * 20 / 4, o.maxPerHour(), 1e-12, "5% of the part's 20 an hour at 4 a run, under 10% of the market");
        assertEquals(4 + 4 + 2, o.holdHours(), 1e-9, "4h for your share of the parts, 4h forging, your 1 sale an hour by check-in");

        ForgeCalc.Row slotBound = forgeRow(3_000_000, 2_000_000, 4, 100, 1.75, false, Double.NaN, Double.NaN);
        assertEquals(1.75, Opportunity.forge(slotBound, "Drill", 2, shares, id -> Double.NaN).maxPerHour(), 1e-12,
                "10% of 100 an hour is more than 7 slots forge: the slots stay the cap");

        ForgeCalc.Row ah = forgeRow(3_000_000, 2_000_000, 4, 10, 1.75, true, 3_000_000, 3_000_000);
        Opportunity onAh = Opportunity.forge(ah, "Drill", 2, shares, id -> Double.NaN);
        assertEquals(0.075, onAh.share(), 1e-12, "no order book on the AH: the middle of 5-10%");
        assertEquals(0.75, onAh.maxPerHour(), 1e-12);

        // 5 sales a day on the AH: yours, at 7.5%, is one every 64 hours, not the market's one every 4.8
        ForgeCalc.Row thin = forgeRow(3_000_000, 2_000_000, 4, 5 / 24.0, 5 / 24.0, true, 3_000_000, 3_000_000);
        assertEquals(2 + 4 + 1 / (0.075 * 5 / 24.0), Opportunity.forge(thin, "Drill", 2, shares, id -> Double.NaN).holdHours(), 1e-9);
    }

    @Test
    void sharedMarketsSayHowMuchIsYours() {
        Opportunity y = new Opportunity(Kind.FLIP, "Y", "Y", 30_000, 500_000, 1, 2, 0, 1, List.of(), "flip Y", 0.05, 0);
        Opportunity z = new Opportunity(Kind.FLIP, "Z", "Z", 10_000, 500_000, 2, 2, 0, 1, List.of(), "flip Z", 0.075, 0);
        Plan p = plan(new Settings(INF, 0, 1, false), y, z);
        assertTrue(pick(p.active(), "Y").why().endsWith("That's your ~5% of what its market trades."), pick(p.active(), "Y").why());
        assertTrue(p.skipped().stream().anyMatch(sk -> sk.why().startsWith("Flip Z (20.00K/hr at your ~7.5% of its market): ")),
                p.skipped().toString());
    }

    // ---- anvil time ----

    /**
     * Anvil time goes to the crafts that earn most per combine. Coins to spare and 150 combines/hr: B (400K a combine) and C
     * (100K) get all they can use, A (20K) the 85 left. The flip needs no anvil and runs at its limit regardless.
     */
    @Test
    void anvilTimeGoesToTheCraftsThatEarnMostPerCombine() {
        Opportunity a = craft("A", 300_000, 50_000, 10, 2, 15);      // wants 150 combines an hour
        Opportunity b = craft("B", 6_000_000, 13_000_000, 1, 2, 15); // wants 15
        Opportunity c = craft("C", 100_000, 400_000, 50, 2, 1);      // wants 50
        Opportunity f = flip("F", 1_000, 10_000, 100, 2);
        Settings s = new Settings(INF, 0, 7, false, 150);
        Plan p = plan(s, a, b, c, f);
        checkLimits(p, s);
        assertEquals(1, pick(p.active(), "B").unitsPerHour(), 1e-9);
        assertEquals(50, pick(p.active(), "C").unitsPerHour(), 1e-9);
        assertEquals(85 / 15.0, pick(p.active(), "A").unitsPerHour(), 1e-9, "the 85 combines left");
        assertEquals(100, pick(p.active(), "F").unitsPerHour(), 1e-9);
        assertEquals(150, p.labourUsed(), 1e-9);
        assertEquals(6_000_000 + 5_000_000 + 85 / 15.0 * 300_000 + 100_000, p.profitPerHour(), 1e-3);
        assertTrue(pick(p.active(), "A").why().contains("15 anvil combines each: 85 of your 150 combines/hr. More would need more anvil time."),
                pick(p.active(), "A").why());
        assertTrue(p.summary().contains("Anvil: 150 of your 150 combines/hr go to book crafts."), p.summary().toString());

        Plan unlimited = plan(new Settings(INF, 0, 7, false), a, b, c, f);
        assertEquals(10, pick(unlimited.active(), "A").unitsPerHour(), 1e-9, "no anvil limit: every craft at its market's limit");
    }

    /** A craft left out because your anvil time earns more on the others says so, with what it earns per combine. */
    @Test
    void craftLeftOutForAnvilTimeSaysSo() {
        Opportunity b = craft("B", 6_000_000, 13_000_000, 10, 2, 15); // 400K a combine, wants all 150
        Opportunity a = craft("A", 300_000, 50_000, 10, 2, 15);       // 20K a combine
        Plan p = plan(new Settings(INF, 0, 7, false, 150), a, b);
        assertEquals(List.of("B"), ids(p.active()));
        assertTrue(p.skipped().stream().anyMatch(sk -> sk.why().equals("Book craft A (3.00M/hr at its market's limit): needs 15 anvil"
                + " combines each (20.00K per combine); your 150 combines/hr earn more on the picked crafts")), p.skipped().toString());
    }

    /**
     * Fitting the anvil time mixes two fills: one runs the craft D at 0.525 an hour, the other leaves it out for the flip B.
     * 10% of the first would run D at 0.05 an hour, but starting D still costs one whole 1M unit, which the purse can't also
     * cover; the plan must not break the purse for it.
     */
    @Test
    void fittingTheAnvilTimeNeverRunsSomethingBelowOneUnitPastThePurse() {
        Opportunity a = flip("A", 100, 1_000, 100, 2);                         // 5%/hr per coin, 200K at its limit
        Opportunity d = craft("D", 30_000, 1_000_000, 1, 2, 15);              // 1.5%/hr per coin, 2K a combine
        Opportunity b = flip("B", 10, 1_000, 1_000, 2);                        // 0.5%/hr per coin
        Settings s = new Settings(1_250_000, 0, 7, false, 0.7875);             // a tenth of D's 7.875 combines at 0.525/hr
        Plan p = plan(s, a, d, b);
        checkLimits(p, s);
        assertEquals(100 * 100 + 525 * 10, p.profitPerHour(), 1e-6, "A at its limit, B with the rest, D left out");
    }

    @Test
    void noAnvilTimeLeavesCraftsOutAndSaysWhy() {
        Opportunity a = craft("A", 300_000, 50_000, 10, 2, 15);
        Opportunity f = flip("F", 1_000, 10_000, 100, 2);
        Plan p = plan(new Settings(INF, 0, 7, false, 0), a, f);
        assertEquals(List.of("F"), ids(p.active()));
        assertTrue(p.skipped().stream().anyMatch(sk -> sk.why().endsWith("needs anvil time, and yours is set to 0 combines/hr")),
                p.skipped().toString());
        assertTrue(Double.isInfinite(new Settings(INF, 0, 7, false, Double.NaN).labourPerHour()), "unknown anvil time: no limit");

        // one item at a time and ten crafts that each beat the flip on paper: with no anvil time, none of them can run, so
        // the one item has to be the flip, not a craft that earns nothing
        List<Opportunity> options = new ArrayList<>();
        for (int i = 0; i < 10; i++) options.add(craft("K" + i, 10_000 + 1_000 * i, 10_000, 100, 2, 15 - i));
        options.add(flip("F", 100, 10_000, 100, 2));
        Plan one = OpportunityAdvisor.plan(options, new Settings(INF, 0, 1, false, 0));
        assertEquals(List.of("F"), ids(one.active()));
    }

    // ---- allocation ----

    /**
     * The plan the advisor exists for: forges earn the best return per coin and run on their own, so they get every slot;
     * the rest of the purse goes into flips, best return per coin first. It beats both forging alone and flipping alone.
     */
    @Test
    void bestPlanMixesPassiveForgesWithActiveFlips() {
        Opportunity a = forge("A", 400_000, 1_000_000, 100, 5, 4);  // 100K/hr per slot, 8%/hr per coin
        Opportunity b = forge("B", 150_000, 500_000, 100, 5, 4);    // 37.5K/hr per slot, 6%/hr per coin
        Opportunity y = flip("Y", 30_000, 500_000, 20, 1);          // 6%/hr per coin, saturates at 10M
        Opportunity z = flip("Z", 9_000, 200_000, 100, 1);          // 4.5%/hr per coin, saturates at 20M
        Settings s = new Settings(30_000_000, 7, 7, false);
        Plan p = plan(s, a, b, y, z);

        assertEquals(List.of("A"), ids(p.forges()));
        assertEquals(7, pick(p.forges(), "A").slots());
        assertEquals(8_750_000, pick(p.forges(), "A").capital(), 1e-3, "1.75 runs/hr x 1M x 5h");
        assertEquals(List.of("Y", "Z"), ids(p.active()), "best return per coin first");
        assertEquals(10_000_000, pick(p.active(), "Y").capital(), 1e-3, "Y runs at its market's limit");
        assertEquals(11_250_000, pick(p.active(), "Z").capital(), 1e-3, "Z gets what's left");
        assertEquals(30_000_000, p.capitalUsed(), 1e-3);
        assertEquals(700_000, p.passivePerHour(), 1e-6);
        assertEquals(700_000 + 600_000 + 506_250, p.profitPerHour(), 1e-6);

        double forgeOnly = plan(s, a, b).profitPerHour(), flipOnly = plan(s, y, z).profitPerHour();
        assertTrue(p.profitPerHour() > forgeOnly && p.profitPerHour() > flipOnly, "mixing beats either alone");
        assertEquals(600_000 + 900_000, flipOnly, 1e-6, "flips alone: Y 10M and Z 20M");
    }

    /** A route its market can't absorb gets only the slots it can keep busy; the next route gets the rest. */
    @Test
    void marketCappedRouteGetsOnlyTheSlotsItCanUse() {
        Opportunity capped = forge("CAPPED", 500_000, 100_000, 0.6, 5, 4); // 0.6 runs/hr keeps 2.4 slots busy
        Opportunity next = forge("NEXT", 200_000, 100_000, 100, 5, 4);
        Plan p = plan(new Settings(INF, 7, 7, false), capped, next);
        assertEquals(3, pick(p.forges(), "CAPPED").slots());
        assertEquals(4, pick(p.forges(), "NEXT").slots());
        assertEquals(0.6 * 500_000 + 4 / 4.0 * 200_000, p.profitPerHour(), 1e-6);
        assertTrue(pick(p.forges(), "CAPPED").why().contains("market takes only"), pick(p.forges(), "CAPPED").why());
    }

    /** A tight purse goes to the best return per coin first, even when another option earns more per hour at its limit. */
    @Test
    void tightPurseGoesToTheBestReturnPerCoin() {
        Opportunity big = flip("BIG", 10_000, 1_000_000, 1000, 1);   // 10M/hr at its limit, but 1%/hr per coin
        Opportunity small = flip("SMALL", 50_000, 1_000_000, 5, 1);  // 250K/hr at its limit, 5%/hr per coin
        Plan p = plan(new Settings(10_000_000, 0, 7, false), big, small);
        assertEquals(List.of("SMALL", "BIG"), ids(p.active()));
        assertEquals(5_000_000, pick(p.active(), "SMALL").capital(), 1e-3);
        assertEquals(5_000_000, pick(p.active(), "BIG").capital(), 1e-3);
        assertEquals(250_000 + 50_000, p.profitPerHour(), 1e-6);
    }

    /** With coins to spare, a cap on how many Bazaar items you run keeps the ones that earn the most per hour. */
    @Test
    void loosePurseKeepsTheMostProfitPerHourWithinTheItemLimit() {
        Opportunity p300 = flip("P", 3_000, 100_000, 100, 1);  // 300K/hr, 3%/hr per coin
        Opportunity q200 = flip("Q", 2_000, 100_000, 100, 1);  // 200K/hr, 2%
        Opportunity r100 = flip("R", 10_000, 100_000, 10, 1);  // 100K/hr, 10%
        Plan p = plan(new Settings(INF, 0, 2, false), p300, q200, r100);
        assertEquals(Set.of("P", "Q"), new HashSet<>(ids(p.active())));
        assertEquals(500_000, p.profitPerHour(), 1e-6);
        Settings unknown = new Settings(Double.NaN, 0, 2, false);
        assertFalse(unknown.purseLimited(), "an unknown purse doesn't limit the plan");
        assertEquals(500_000, plan(unknown, p300, q200, r100).profitPerHour(), 1e-6);
        assertTrue(p.skipped().stream().anyMatch(sk -> sk.opportunity().id().equals("R") && sk.why().contains("already running 2")),
                p.skipped().toString());
    }

    /** With one item allowed and a tight purse, the one to run is the best per coin, not the best at its (unaffordable) limit. */
    @Test
    void tightPurseAndOneItemPicksTheBestUseOfTheCoins() {
        Opportunity big = flip("BIG", 10_000, 1_000_000, 1000, 1);
        Opportunity small = flip("SMALL", 50_000, 1_000_000, 5, 1);
        Plan p = plan(new Settings(10_000_000, 0, 1, false), big, small);
        assertEquals(List.of("SMALL"), ids(p.active()), "SMALL earns 250K/hr on 5M; BIG only 100K/hr on all 10M");
        assertEquals(250_000, p.profitPerHour(), 1e-6);
    }

    /** A route whose single run costs more than the purse can't be started; the plan says so instead of silently dropping it. */
    @Test
    void forgeRunCostingMoreThanThePurseIsLeftOutWithTheReason() {
        Opportunity rich = forge("RICH", 20_000_000, 50_000_000, 100, 5, 4);
        Opportunity cheap = forge("CHEAP", 100_000, 1_000_000, 100, 5, 4);
        Plan p = plan(new Settings(20_000_000, 3, 7, false), rich, cheap);
        assertEquals(List.of("CHEAP"), ids(p.forges()));
        assertEquals(3, pick(p.forges(), "CHEAP").slots());
        assertTrue(p.capitalUsed() <= 20_000_000);
        assertTrue(p.skipped().stream().anyMatch(sk -> sk.opportunity().id().equals("RICH")
                && sk.why().contains("one run costs 50.00M, more than your purse")), p.skipped().toString());
    }

    /** Planning for time away: only forges count; flips are listed as needing you online. */
    @Test
    void afkPlansOnlyPassiveOptions() {
        Opportunity f = forge("F", 100_000, 1_000_000, 100, 5, 4);
        Opportunity y = flip("Y", 30_000, 500_000, 20, 1);
        Plan p = plan(new Settings(30_000_000, 2, 7, true), f, y);
        assertTrue(p.active().isEmpty());
        assertEquals(List.of("F"), ids(p.forges()));
        assertTrue(p.skipped().stream().anyMatch(sk -> sk.opportunity().id().equals("Y") && sk.why().contains("online")));
        assertTrue(p.summary().stream().anyMatch(l -> l.startsWith("AFK")), p.summary().toString());
        // live run 2026-10-17: AFK with 50M blamed "the picks' markets" for coins left over, when the slots were full
        assertTrue(p.summary().contains("27.50M of your 30.00M purse stays free: every forge slot is busy and the Bazaar waits"
                + " until you're back."), p.summary().toString());
    }

    /**
     * Live run 2026-10-17, 1B purse: the Titanium Drill was "left out because your slots earn more on Perfect Plate", but a
     * slot of it earns more than a Perfect Plate slot; it was left out because the 565M it ties up earns more in the picks.
     */
    @Test
    void forgeLeftOutForItsCapitalSaysSoNotThatTheSlotsEarnMore() {
        Opportunity rich = forge("FX", 1_000_000, 9_000_000, 100, 4, 1);  // 1M/hr a slot, but 36M a slot: 2.8%/hr per coin
        Opportunity cheap = forge("FC", 10_000, 10_000, 100, 2, 1);       // 10K/hr a slot, 50%/hr per coin
        Opportunity f = flip("F", 1_000, 10_000, 1000, 1);                // 10%/hr per coin
        Plan p = plan(new Settings(10_000_000, 2, 7, false), rich, cheap, f);
        assertEquals(List.of("FC"), ids(p.forges()));
        assertEquals(List.of("F"), ids(p.active()));
        assertTrue(p.skipped().stream().anyMatch(sk -> sk.why().equals("Forge FX (100.00M/hr at its market's limit): a slot ties up"
                + " 36.00M for 1.00M/hr: 27.78K/hr per 1M, less than your picks earn")), p.skipped().toString());
    }

    /** A thin market's Little's-law capital can be less than one unit, but you still have to buy a whole one. */
    @Test
    void anOptionNeedsAtLeastOneUnitsCost() {
        Opportunity thin = flip("THIN", 2_000_000, 10_000_000, 0.1, 2); // 0.1/hr x 10M x 2h = 2M by Little's law
        assertTrue(plan(new Settings(5_000_000, 0, 7, false), thin).active().isEmpty(), "can't buy one with 5M");
        Plan p = plan(new Settings(15_000_000, 0, 7, false), thin);
        assertEquals(10_000_000, pick(p.active(), "THIN").capital(), 1e-3, "one whole unit");
        assertEquals(0.1 * 2_000_000, p.profitPerHour(), 1e-6);

        // same for a forge whose market takes one run every 100 hours: you still buy a whole run's parts to start it
        Opportunity slow = forge("SLOW", 5_000_000, 30_000_000, 0.01, 10, 4);
        assertTrue(plan(new Settings(20_000_000, 3, 7, false), slow).forges().isEmpty(), "can't start a 30M run with 20M");
        assertEquals(30_000_000, pick(plan(new Settings(40_000_000, 3, 7, false), slow).forges(), "SLOW").capital(), 1e-3);
    }

    /** The plan counts profit after the confidence haircut, and says by how much and why. */
    @Test
    void doubtfulOptionsArePlannedAfterTheHaircut() {
        Opportunity sure = new Opportunity(Kind.FLIP, "SURE", "Sure", 1000, 10_000, 10, 1, 0, 1, List.of(), "flip sure");
        Opportunity iffy = new Opportunity(Kind.FLIP, "IFFY", "Iffy", 1500, 10_000, 10, 1, 0, 0.5, List.of("trades only ~2/day"), "flip iffy");
        Plan p = plan(new Settings(100_000, 0, 1, false), sure, iffy);
        assertEquals(List.of("SURE"), ids(p.active()), "1500 at 50% is worth less than a sure 1000");
        Plan both = plan(new Settings(INF, 0, 7, false), sure, iffy);
        assertEquals(10 * 1000 + 10 * 750, both.profitPerHour(), 1e-6);
        assertTrue(pick(both.active(), "IFFY").why().contains("Planned at 50% (7.50K/hr): trades only ~2/day"),
                pick(both.active(), "IFFY").why());
    }

    // ---- the plan in words ----

    @Test
    void explanationsQuoteThePlansNumbers() {
        Opportunity a = forge("A", 400_000, 1_000_000, 100, 5, 4);
        Opportunity y = flip("Y", 30_000, 500_000, 20, 1);
        Opportunity z = flip("Z", 9_000, 200_000, 100, 1);
        Plan p = plan(new Settings(30_000_000, 7, 7, false), a, y, z);

        assertEquals("7 slots: forge A. 400.00K/run x 42 runs/day = 700.00K/hr. Ties up 8.75M (5h from buying parts to sale)."
                + " Keeps running while you're AFK.", pick(p.forges(), "A").why());
        assertEquals("flip Y. 30.00K each x 480/day = 600.00K/hr on 10.00M of your purse (back in ~1h), 60.00K/hr per 1M tied up."
                + " That's all its market trades.", pick(p.active(), "Y").why());
        assertTrue(pick(p.active(), "Z").why().endsWith("More would need more purse."), pick(p.active(), "Z").why());
        assertEquals(List.of(
                "Fill 7 forge slots with 7x A: 700.00K/hr, running while you're AFK.",
                "Put 21.25M into Y and Z: 1.11M/hr while you're online.",
                "Expected 1.81M/hr: 700.00K passive + 1.11M active."), p.summary());
    }

    // ---- against exhaustive search ----

    /**
     * Small random cases checked against trying every whole-slot assignment of every route and every set of Bazaar items.
     * With no binding limit on how many items you run the advisor must match exactly; it must never break the purse, the slots
     * or the item limit.
     */
    @Test
    void matchesExhaustiveSearchAndNeverBreaksALimit() {
        Random rnd = new Random(20261017);
        for (int c = 0; c < 400; c++) {
            List<Opportunity> forges = new ArrayList<>(), active = new ArrayList<>();
            int nf = rnd.nextInt(5), na = rnd.nextInt(6), slots = rnd.nextInt(5);
            for (int i = 0; i < nf; i++) {
                double slotHours = 0.5 + rnd.nextDouble() * 8, hold = slotHours + 0.5 + rnd.nextDouble() * 3;
                double max = rnd.nextBoolean() ? 100 : Math.max(1 / hold, rnd.nextDouble());
                forges.add(forge("F" + i, 1e5 * (1 + rnd.nextDouble() * 9), 1e6 * (0.2 + rnd.nextDouble() * 5), max, hold, slotHours));
            }
            for (int i = 0; i < na; i++) {
                double hold = 0.5 + rnd.nextDouble() * 4;
                active.add(flip("A" + i, 1e3 * (1 + rnd.nextDouble() * 50), 1e4 * (1 + rnd.nextDouble() * 100),
                        Math.max(1 / hold, rnd.nextDouble() * 200), hold));
            }
            double purse = rnd.nextInt(4) == 0 ? INF : 1e6 * (0.5 + rnd.nextDouble() * 60);
            List<Opportunity> all = new ArrayList<>(forges);
            all.addAll(active);

            Settings s = new Settings(purse, slots, 10, false);
            Plan p = OpportunityAdvisor.plan(all, s);
            checkLimits(p, s);
            double exact = exhaustive(forges, active, s);
            assertEquals(exact, p.profitPerHour(), Math.max(1e-6, exact * 1e-9), "case " + c);
        }
    }

    /** With a binding item limit the shortlists are a heuristic: measure how close they get to exhaustive search. */
    @Test
    void itemLimitHeuristicStaysCloseToExhaustiveSearch() {
        Random rnd = new Random(17);
        double worst = 1;
        for (int c = 0; c < 300; c++) {
            List<Opportunity> active = new ArrayList<>();
            int na = 3 + rnd.nextInt(5);
            for (int i = 0; i < na; i++) {
                double hold = 0.5 + rnd.nextDouble() * 4;
                active.add(flip("A" + i, 1e3 * (1 + rnd.nextDouble() * 50), 1e4 * (1 + rnd.nextDouble() * 100),
                        Math.max(1 / hold, rnd.nextDouble() * 200), hold));
            }
            double purse = rnd.nextInt(4) == 0 ? INF : 1e6 * (0.5 + rnd.nextDouble() * 60);
            Settings s = new Settings(purse, 0, 1 + rnd.nextInt(3), false);
            Plan p = OpportunityAdvisor.plan(active, s);
            checkLimits(p, s);
            double exact = exhaustive(List.of(), active, s);
            assertTrue(p.profitPerHour() <= exact * (1 + 1e-9) + 1e-6, "can't beat exhaustive search, case " + c);
            if (exact > 0) worst = Math.min(worst, p.profitPerHour() / exact);
        }
        // measured 0.9817 with this seed (2026-10-17); price 0 alone (top k by profit per hour) falls far below
        assertTrue(worst >= 0.98, "worst case reached " + worst + " of the exhaustive optimum");
    }

    /** Bazaar options as the factories make them: two waits of at least an hour each, so never less than two units in flight. */
    private static Opportunity randomCraft(Random rnd, String id, double maxCost) {
        double max = 10 + rnd.nextDouble() * 990, cost = maxCost * (0.1 + rnd.nextDouble() * 0.9);
        double combines = rnd.nextInt(3) == 0 ? 0 : 1 + rnd.nextInt(15);
        return craft(id, cost * (0.05 + rnd.nextDouble() * 0.5), cost, max, 2 * Math.max(1, 1 / max), combines);
    }

    /**
     * Crafts sharing your anvil time with flips sharing your purse, against the linear programme solved by brute force (every
     * vertex). With no limit on how many items you run the plan must match it exactly, purse or not. (Unit costs here are at
     * most 100 coins against purses of 10K and up, so paying for whole units never matters; it's guarded on its own above.)
     */
    @Test
    void anvilTimeAndPurseMatchTheLinearProgramme() {
        Random rnd = new Random(20261018);
        int bothBind = 0;
        for (int c = 0; c < 400; c++) {
            List<Opportunity> active = new ArrayList<>();
            int n = 1 + rnd.nextInt(6);
            for (int i = 0; i < n; i++) active.add(randomCraft(rnd, "C" + i, 100));
            double purse = rnd.nextInt(3) == 0 ? INF : 1e4 * (1 + rnd.nextDouble() * 9);
            Settings s = new Settings(purse, 0, 10, false, rnd.nextDouble() * 3000);
            Plan p = OpportunityAdvisor.plan(active, s);
            checkLimits(p, s);
            double exact = lp(active, purse, s.labourPerHour());
            assertEquals(exact, p.profitPerHour(), Math.max(1e-6, exact * 1e-9), "case " + c);
            if (p.labourBinding() && p.capitalUsed() >= purse * (1 - 1e-6)) bothBind++;
        }
        assertTrue(bothBind > 50, bothBind + " cases where purse and anvil time both bind");
    }

    /** With a binding item limit as well, the shortlists are a heuristic: measured against the best subset's linear programme. */
    @Test
    void itemLimitWithAnvilTimeStaysCloseToTheLinearProgramme() {
        Random rnd = new Random(18);
        double worst = 1;
        for (int c = 0; c < 300; c++) {
            List<Opportunity> active = new ArrayList<>();
            int n = 3 + rnd.nextInt(5);
            for (int i = 0; i < n; i++) active.add(randomCraft(rnd, "C" + i, 100));
            double purse = rnd.nextInt(3) == 0 ? INF : 1e5 * (1 + rnd.nextDouble() * 14);
            Settings s = new Settings(purse, 0, 1 + rnd.nextInt(3), false, rnd.nextDouble() * 3000);
            Plan p = OpportunityAdvisor.plan(active, s);
            checkLimits(p, s);
            double exact = 0;
            for (int mask = 1; mask < 1 << n; mask++) {
                if (Integer.bitCount(mask) > s.maxActive()) continue;
                List<Opportunity> subset = new ArrayList<>();
                for (int i = 0; i < n; i++) if ((mask & 1 << i) != 0) subset.add(active.get(i));
                exact = Math.max(exact, lp(subset, purse, s.labourPerHour()));
            }
            assertTrue(p.profitPerHour() <= exact * (1 + 1e-9) + 1e-6, "can't beat the linear programme, case " + c);
            if (exact > 0) worst = Math.min(worst, p.profitPerHour() / exact);
        }
        assertTrue(worst >= WORST_WITH_ITEM_LIMIT, "worst case reached " + worst + " of the best subset's linear programme");
    }

    /** Measured 0.9377 with this seed (2026-10-18). */
    private static final double WORST_WITH_ITEM_LIMIT = 0.93;

    /**
     * max sum(p x) with sum(cost x) <= budget, sum(combines x) <= labour, 0 <= x <= maxPerHour, by brute force: an optimal
     * vertex has at most two options strictly between 0 and their limit, so try every such pair with the rest at 0 or the limit.
     */
    private static double lp(List<Opportunity> os, double budget, double labour) {
        int n = os.size();
        double best = vertices(os, -1, -1, budget, labour);
        for (int i = 0; i < n; i++) {
            best = Math.max(best, vertices(os, i, -1, budget, labour));
            for (int j = i + 1; j < n; j++) best = Math.max(best, vertices(os, i, j, budget, labour));
        }
        return best;
    }

    /** Best vertex with options i and j (-1: none) free and every other option at 0 or its limit. */
    private static double vertices(List<Opportunity> os, int i, int j, double budget, double labour) {
        int n = os.size();
        double best = 0;
        List<Integer> fixed = new ArrayList<>();
        for (int k = 0; k < n; k++) if (k != i && k != j) fixed.add(k);
        for (int mask = 0; mask < 1 << fixed.size(); mask++) {
            double b = budget, l = labour, profit = 0;
            for (int f = 0; f < fixed.size(); f++) {
                if ((mask & 1 << f) == 0) continue;
                Opportunity o = os.get(fixed.get(f));
                b -= o.maxPerHour() * o.capitalPerRate();
                l -= o.maxPerHour() * o.labour();
                profit += o.maxPerHour() * o.expectedProfit();
            }
            if (b < -1e-6 || l < -1e-6) continue;
            if (i < 0) {
                best = Math.max(best, profit);
            } else if (j < 0) {
                Opportunity o = os.get(i);
                for (double x : new double[]{b / o.capitalPerRate(), o.labour() > 0 ? l / o.labour() : Double.NaN}) {
                    if (fits(x, o) && x * o.capitalPerRate() <= b * (1 + 1e-9) + 1e-6 && x * o.labour() <= l * (1 + 1e-9) + 1e-6) {
                        best = Math.max(best, profit + x * o.expectedProfit());
                    }
                }
            } else {
                Opportunity p = os.get(i), q = os.get(j);
                double det = p.capitalPerRate() * q.labour() - q.capitalPerRate() * p.labour();
                if (Math.abs(det) < 1e-12) continue;
                double x = (b * q.labour() - q.capitalPerRate() * l) / det, y = (p.capitalPerRate() * l - b * p.labour()) / det;
                if (fits(x, p) && fits(y, q)) best = Math.max(best, profit + x * p.expectedProfit() + y * q.expectedProfit());
            }
        }
        return best;
    }

    private static boolean fits(double x, Opportunity o) {
        return x >= -1e-9 && x <= o.maxPerHour() * (1 + 1e-9);
    }

    private static void checkLimits(Plan p, Settings s) {
        assertTrue(p.capitalUsed() <= s.purse() * (1 + 1e-9) + 1e-6, "purse " + s.purse() + " but ties up " + p.capitalUsed());
        assertTrue(p.slotsUsed() <= s.forgeSlots());
        assertTrue(p.active().size() <= s.maxActive());
        double labour = p.active().stream().mapToDouble(x -> x.unitsPerHour() * x.opportunity().labour()).sum();
        assertEquals(labour, p.labourUsed(), 1e-9 + labour * 1e-12, "anvil time is the sum of the picks'");
        assertTrue(labour <= s.labourPerHour() * (1 + 1e-9) + 1e-9, "anvil time " + s.labourPerHour() + " but plans " + labour);
        double sum = 0;
        for (Pick x : p.forges()) sum += x.profitPerHour();
        for (Pick x : p.active()) sum += x.profitPerHour();
        assertEquals(sum, p.profitPerHour(), 1e-6 + sum * 1e-12, "total is the sum of the picks");
        for (Pick x : p.active()) assertTrue(x.unitsPerHour() <= x.opportunity().maxPerHour() * (1 + 1e-9));
        for (Pick x : p.forges()) assertTrue(x.unitsPerHour() <= x.opportunity().maxPerHour() * (1 + 1e-9));
    }

    /** Every whole-slot assignment of every route (any number of slots), then the best subset of Bazaar items for the coins left. */
    private static double exhaustive(List<Opportunity> forges, List<Opportunity> active, Settings s) {
        double[] best = {0};
        assign(forges, 0, s.forgeSlots(), 0, 0, active, s, best);
        return best[0];
    }

    private static void assign(List<Opportunity> forges, int i, int slotsLeft, double capital, double profit,
                               List<Opportunity> active, Settings s, double[] best) {
        if (i == forges.size()) {
            best[0] = Math.max(best[0], profit + bestSubset(active, s.purse() - capital, s.maxActive()));
            return;
        }
        Opportunity o = forges.get(i);
        for (int n = 0; n <= slotsLeft; n++) {
            double c = capital, pr = profit;
            if (n > 0) {
                double rate = Math.min(n / o.slotHours(), o.maxPerHour());
                c += Math.max(o.unitCost(), rate * o.unitCost() * o.holdHours());
                pr += rate * o.expectedProfit();
                if (c > s.purse()) continue;
            }
            assign(forges, i + 1, slotsLeft - n, c, pr, active, s, best);
        }
    }

    private static double bestSubset(List<Opportunity> active, double budget, int k) {
        double best = 0;
        for (int mask = 0; mask < 1 << active.size(); mask++) {
            if (Integer.bitCount(mask) > k) continue;
            List<Opportunity> set = new ArrayList<>();
            for (int i = 0; i < active.size(); i++) if ((mask & 1 << i) != 0) set.add(active.get(i));
            set.sort(Comparator.comparingDouble((Opportunity o) -> o.expectedProfit() / (o.unitCost() * o.holdHours())).reversed());
            double left = budget, profit = 0;
            for (Opportunity o : set) {
                if (left < o.unitCost()) continue;
                double rate = Math.min(o.maxPerHour(), left / (o.unitCost() * o.holdHours()));
                left -= Math.max(o.unitCost(), rate * o.unitCost() * o.holdHours());
                profit += rate * o.expectedProfit();
            }
            best = Math.max(best, profit);
        }
        return best;
    }

    // ---- cost ----

    /**
     * The forge search scores whole-slot assignments of a short list of routes, so its cost depends on the slot count, not on
     * how many routes the forge list has. 30 routes and 3 slots: at most C(10 + 3, 3) = 286 assignments, not C(33, 3) = 5456.
     */
    @Test
    void searchCostIsBoundedByTheShortlistNotTheRouteCount() {
        List<Opportunity> all = new ArrayList<>();
        Random rnd = new Random(5);
        for (int i = 0; i < 30; i++) {
            all.add(forge("F" + i, 1e5 * (1 + rnd.nextDouble()), 1e6 * (0.5 + rnd.nextDouble()), 100, 6, 4 + rnd.nextDouble()));
        }
        for (int i = 0; i < 300; i++) all.add(flip("B" + i, 1e3 * (1 + rnd.nextDouble()), 1e5, 50, 1 + rnd.nextDouble()));
        Plan p = OpportunityAdvisor.plan(all, new Settings(50_000_000, 3, 7, false));
        assertTrue(OpportunityAdvisor.forgePool(all.subList(0, 30), 3).size() <= 2 * OpportunityAdvisor.FORGE_POOL_EACH);
        assertTrue(p.assignmentsTried() <= 286, "scored " + p.assignmentsTried() + " assignments");
        OpportunityAdvisor.ActiveChooser chooser = new OpportunityAdvisor.ActiveChooser(all.subList(30, all.size()), 7, INF);
        // building each shortlist sorts every flip, so the guard is on how many capital prices are tried, not on how many
        // distinct shortlists survive (most prices give the same one)
        assertTrue(chooser.pricesTried <= OpportunityAdvisor.MAX_CAPITAL_PRICES, chooser.pricesTried + " capital prices for 300 flips");
        assertTrue(chooser.sets.size() <= chooser.pricesTried);
    }

    /**
     * When the crafts need more anvil time than you have, the fill is searched over the labour prices where a shortlist's order
     * changes, by bisection: 7 crafts have up to 28 such prices, so at most 2 + ceil(log2(28)) = 7 fills, not one per price.
     * With no purse limit every forge assignment leaves the same budget, so the Bazaar side is worked out once.
     */
    @Test
    void anvilTimeSearchCostIsLogarithmicInTheBreakPoints() {
        Random rnd = new Random(9);
        List<Opportunity> crafts = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            crafts.add(craft("K" + i, 1e4 * (1 + rnd.nextDouble()), 1e5 * (1 + rnd.nextDouble()), 10 + rnd.nextDouble() * 10, 2,
                    1 + rnd.nextInt(15)));
        }
        OpportunityAdvisor.ActiveChooser chooser = new OpportunityAdvisor.ActiveChooser(crafts, 7, 50);
        OpportunityAdvisor.ActiveChooser.Shortlist all = chooser.sets.stream().filter(x -> x.options.size() == 7).findFirst().orElseThrow();
        int breaks = all.breaks().length;
        assertTrue(breaks >= 7, breaks + " break points");
        long before = chooser.passes;
        double[] x = chooser.rates(all, 5e6);
        long used = chooser.passes - before;
        double labour = 0;
        for (int i = 0; i < x.length; i++) labour += x[i] * all.options.get(i).labour();
        assertEquals(50, labour, 1e-9, "the anvil limit binds here, so the search ran");
        assertTrue(used <= 2 + Math.ceil(Math.log(breaks) / Math.log(2)), used + " fills for " + breaks + " break points");

        OpportunityAdvisor.ActiveChooser again = new OpportunityAdvisor.ActiveChooser(crafts, 7, 50);
        again.best(INF);
        long once = again.passes;
        assertTrue(once > 0);
        again.best(INF);
        assertEquals(once, again.passes, "the same budget again costs nothing");
    }
}
