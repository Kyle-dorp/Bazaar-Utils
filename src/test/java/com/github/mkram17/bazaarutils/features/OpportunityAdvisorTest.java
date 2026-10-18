package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.Opportunity.Kind;
import com.github.mkram17.bazaarutils.features.OpportunityAdvisor.Pick;
import com.github.mkram17.bazaarutils.features.OpportunityAdvisor.Plan;
import com.github.mkram17.bazaarutils.features.OpportunityAdvisor.Settings;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OpportunityAdvisorTest {
    private static final double INF = Double.POSITIVE_INFINITY;

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

    private static ForgeCalc.Row forgeRow(double revenue, double cost, double slotHours, double marketPerHour, double runsPerHour,
                                          boolean onAh, double lowestBin, double avgBin) {
        double secs = slotHours * 3600;
        ForgePlanner.Step tree = new ForgePlanner.Step("DRILL", 1, true, 1, secs, cost, secs, secs, List.of());
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
        Opportunity o = Opportunity.flip("X", "X", 100, 130, 28, 28 * 50, 100, false, 1);
        assertEquals(50, o.maxPerHour(), 1e-9, "50 items/hour: profit per hour over profit per item");
        assertEquals(100, o.unitCost(), 1e-9, "each item ties up its buy price");
        assertEquals(2, o.holdHours(), 1e-9, "an hour to fill (your next check-in), an hour to sell");
        assertEquals(200, o.capitalPerRate(), 1e-9);

        Opportunity thin = Opportunity.flip("T", "T", 100, 130, 28, 28 * 0.1, 100, false, 1);
        assertEquals(20, thin.holdHours(), 1e-9, "one trade every 10 hours: 10h to fill and 10h to sell, not your check-in");
        assertNull(Opportunity.flip("L", "L", 100, 90, -10, -100, 100, false, 1), "a losing row can't be planned with");
    }

    /** A forge run's caps and coin time come from the route and its throughput, not the slot count alone. */
    @Test
    void forgeRunCapsAndHoldTimeComeFromTheRoute() {
        // sells 12/day, so 0.5 runs/hour although 7 slots of a 4-hour forge could do 1.75
        ForgeCalc.Row row = forgeRow(3_000_000, 2_000_000, 4, 0.5, 0.5, true, 3_200_000, 2_000_000);
        Opportunity o = Opportunity.forge(row, "Drill", 2);
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
                2100, 1000), 1);
        assertEquals(5, book.maxPerHour(), 1e-9);
        assertEquals(1000, book.unitCost(), 1e-9);
        assertEquals(Kind.BOOK, book.kind());

        Opportunity fusion = Opportunity.fusion(new FusionCalc.Fusion("Combat", "A", 2, 100, "B", 3, 100, "OUT", 2, 400,
                500, 200, 2000), id -> id.toLowerCase(), 1);
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

        Opportunity flip = Opportunity.flip("I", "I", 70, 130, 50, 50 * 100, 100, false, 1);
        Opportunity dip = Opportunity.flip("I", "I", 70, 130, 50, 50 * 100, 100, true, 1);
        assertTrue(flip.confidence() < dip.confidence(), "a flip 30% off its normal buy price is suspect; a dip is that by design");
        assertEquals(Kind.DIP, dip.kind());
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

    private static void checkLimits(Plan p, Settings s) {
        assertTrue(p.capitalUsed() <= s.purse() * (1 + 1e-9) + 1e-6, "purse " + s.purse() + " but ties up " + p.capitalUsed());
        assertTrue(p.slotsUsed() <= s.forgeSlots());
        assertTrue(p.active().size() <= s.maxActive());
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
        OpportunityAdvisor.ActiveChooser chooser = new OpportunityAdvisor.ActiveChooser(all.subList(30, all.size()), 7);
        // building each shortlist sorts every flip, so the guard is on how many capital prices are tried, not on how many
        // distinct shortlists survive (most prices give the same one)
        assertTrue(chooser.pricesTried <= OpportunityAdvisor.MAX_CAPITAL_PRICES, chooser.pricesTried + " capital prices for 300 flips");
        assertTrue(chooser.sets.size() <= chooser.pricesTried);
    }
}
