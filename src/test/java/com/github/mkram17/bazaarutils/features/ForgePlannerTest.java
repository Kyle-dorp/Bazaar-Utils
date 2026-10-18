package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.ForgePlanner.Prices;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Recipe;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Route;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Step;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ForgePlannerTest {
    private static final double HOUR = 3600;

    // ---- fixtures ----

    private final Map<String, Recipe> recipes = new HashMap<>();
    private final Map<String, Double> buy = new HashMap<>(), sell = new HashMap<>();

    private void recipe(String id, double seconds, double coins, Object... inputs) {
        Map<String, Double> in = new LinkedHashMap<>();
        for (int i = 0; i < inputs.length; i += 2) in.put((String) inputs[i], ((Number) inputs[i + 1]).doubleValue());
        recipes.put(id, new Recipe(id, 1, seconds, coins, in));
    }

    private Prices prices() {
        return new Prices() {
            public double buy(String id) {
                return buy.getOrDefault(id, Double.NaN);
            }

            public double sell(String id) {
                return sell.getOrDefault(id, Double.NaN);
            }
        };
    }

    private Route best(String id) {
        return new ForgePlanner(recipes, prices(), 1.0).best(id);
    }

    private static Step part(Step s, String id) {
        return s.parts().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    // ---- the complaint that started this: a calculator that only prices the last forge ----

    /** Forging a fast sub-part yourself adds its margin to the route instead of paying the market for it. */
    @Test
    void forgingAFastSubPartCountsItsMargin() {
        recipe("DRILL", 30, 0, "PLATE", 1, "PART", 1);
        recipe("PLATE", 30, 0, "RAW", 2);
        sell.put("DRILL", 200.0);
        buy.put("PART", 50.0);
        buy.put("PLATE", 100.0);
        buy.put("RAW", 10.0);

        Route r = best("DRILL");
        assertTrue(part(r.tree(), "PLATE").forged(), "plate should be forged: it saves 80 coins for 30s of slot time");
        assertEquals(70, r.cost(), 1e-9);
        assertEquals(130, r.profit(), 1e-9);
        assertEquals(60, r.slotSeconds(), 1e-9);
        assertEquals(2, r.forgeSteps());
    }

    /** The same saving is not worth a 6-hour slot when the 30-second forge alone earns far more per hour. */
    @Test
    void aSlowSubPartWithASmallSavingIsBought() {
        recipe("DRILL", 30, 0, "PLATE", 1, "PART", 1);
        recipe("PLATE", 6 * HOUR, 0, "RAW", 2);
        sell.put("DRILL", 200.0);
        buy.put("PART", 50.0);
        buy.put("PLATE", 100.0);
        buy.put("RAW", 10.0);

        Route r = best("DRILL");
        assertFalse(part(r.tree(), "PLATE").forged());
        assertEquals(50, r.profit(), 1e-9);
        assertEquals(50 / (30 / HOUR), r.profitPerSlotHour(), 1e-6);
    }

    @Test
    void forgeCoinCostsAreSpent() {
        recipe("DIVAN", 30, 50_000_000, "T4", 1, "ALLOY", 1);
        sell.put("DIVAN", 1_700_000_000.0);
        buy.put("T4", 320_000_000.0);
        buy.put("ALLOY", 1_200_000_000.0);

        Route r = best("DIVAN");
        assertEquals(1_570_000_000.0, r.cost(), 1e-3);
        assertEquals(130_000_000.0, r.profit(), 1e-3);
    }

    @Test
    void quickForgeShortensSlotTimeAndRaisesTheRate() {
        recipe("REFINED", 6 * HOUR, 0, "RAW", 160);
        sell.put("REFINED", 30_000.0);
        buy.put("RAW", 100.0);

        Route normal = new ForgePlanner(recipes, prices(), 1.0).best("REFINED");
        Route quick = new ForgePlanner(recipes, prices(), 0.7).best("REFINED");
        assertEquals(normal.profit(), quick.profit(), 1e-9);
        assertEquals(normal.slotSeconds() * 0.7, quick.slotSeconds(), 1e-9);
        assertEquals(normal.profitPerSlotHour() / 0.7, quick.profitPerSlotHour(), 1e-6);
    }

    /** You collect and restart forges when you log in, so a forge holds its slot until then. */
    @Test
    void aShortForgeHoldsItsSlotUntilYouCheckIn() {
        recipe("KEY", 30, 0, "RAW", 1);
        recipe("PLATE", 6 * HOUR, 0, "RAW", 1);
        recipe("UPGRADED_KEY", 30, 0, "KEY", 1);
        sell.put("KEY", 1_000.0);
        sell.put("PLATE", 10_000.0);
        sell.put("UPGRADED_KEY", 5_000.0);
        buy.put("RAW", 100.0);

        ForgePlanner every4h = new ForgePlanner(recipes, prices(), 1.0, 4 * HOUR);
        Route key = every4h.best("KEY"), plate = every4h.best("PLATE"), upgraded = every4h.best("UPGRADED_KEY");
        assertEquals(4 * HOUR, key.slotSeconds(), 1e-9, "a 30s forge started before logging off is collected 4h later");
        assertEquals(6 * HOUR, plate.slotSeconds(), 1e-9, "longer than the check-in: unchanged");
        assertEquals(900 / 4.0, key.profitPerSlotHour(), 1e-9);
        assertTrue(plate.profitPerSlotHour() > key.profitPerSlotHour(), "with collection waiting on you, the plate earns more per slot");
        assertEquals(8 * HOUR, upgraded.chainSeconds(), 1e-9, "each step of a chain waits for the next check-in");

        Route instant = new ForgePlanner(recipes, prices(), 1.0, 0).best("KEY");
        assertEquals(30, instant.slotSeconds(), 1e-9, "check-in 0: collected the moment it finishes");
    }

    @Test
    void aPartThatCanNeitherBeBoughtNorForgedDropsTheRecipe() {
        recipe("THING", 60, 0, "MYSTERY", 1);
        sell.put("THING", 1000.0);
        assertNull(best("THING"));
        assertTrue(new ForgePlanner(recipes, prices(), 1).rankAll().isEmpty());
    }

    @Test
    void unprofitableRecipesAreNotListed() {
        recipe("THING", 60, 0, "RAW", 1);
        sell.put("THING", 90.0);
        buy.put("RAW", 100.0);
        assertNull(best("THING"));
    }

    @Test
    void aRecipeLoopEndsInsteadOfRecursingForever() {
        recipe("A", 60, 0, "B", 1);
        recipe("B", 60, 0, "A", 1);
        sell.put("A", 500.0);
        buy.put("B", 100.0);

        Route r = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> best("A"));
        assertNotNull(r);
        assertFalse(part(r.tree(), "B").forged(), "B can't be forged from A while forging A");
        assertEquals(400, r.profit(), 1e-9);
    }

    /** Buying MID earns 50 per 60s, forging it earns 100 per 120s: the same rate, so take the one that frees the slot sooner. */
    @Test
    void anEqualRateGoesToTheRouteWithLessSlotTime() {
        recipe("TOP", 60, 0, "MID", 1);
        recipe("MID", 60, 0, "RAW", 1);
        sell.put("TOP", 150.0);
        buy.put("MID", 100.0);
        buy.put("RAW", 50.0);

        Route r = best("TOP");
        assertFalse(part(r.tree(), "MID").forged(), "equal rate: buy MID and keep the slot free");
        assertEquals(60, r.slotSeconds(), 1e-9);
        assertEquals(50 / (60 / HOUR), r.profitPerSlotHour(), 1e-6);
    }

    @Test
    void theReportedRateIsTheRouteProfitOverItsSlotTime() {
        recipe("DRILL", 30, 0, "PLATE", 2, "ENGINE", 1);
        recipe("PLATE", 2 * HOUR, 0, "RAW", 3);
        recipe("ENGINE", 30 * HOUR, 1000, "PLATE", 1, "RAW", 5);
        sell.put("DRILL", 100_000.0);
        buy.put("PLATE", 9_000.0);
        buy.put("ENGINE", 60_000.0);
        buy.put("RAW", 500.0);

        Route r = best("DRILL");
        assertEquals(r.profit() / (r.slotSeconds() / HOUR), r.profitPerSlotHour(), 1e-6);
        assertEquals(r.revenue() - r.cost(), r.profit(), 1e-9);
    }

    @Test
    void longestChainIsTheSlowestPathNotTheSum() {
        recipe("TOP", 30, 0, "LEFT", 1, "RIGHT", 1);
        recipe("LEFT", 10 * HOUR, 0, "RAW", 1);
        recipe("RIGHT", 2 * HOUR, 0, "RAW", 1);
        sell.put("TOP", 1_000_000.0);
        buy.put("RAW", 1.0); // LEFT and RIGHT can't be bought, so both must be forged

        Route r = best("TOP");
        assertTrue(part(r.tree(), "LEFT").forged() && part(r.tree(), "RIGHT").forged());
        assertEquals(10 * HOUR + 30, r.chainSeconds(), 1e-9);
        assertEquals(12 * HOUR + 30, r.slotSeconds(), 1e-9);
        assertEquals((10 * HOUR + 30) / HOUR, r.hoursWith(7), 1e-9);
        assertEquals((12 * HOUR + 30) / HOUR, r.hoursWith(1), 1e-9);
    }

    @Test
    void marketVolumeCapsBazaarProfitPerHour() {
        recipe("REFINED", HOUR, 0, "RAW", 1);
        sell.put("REFINED", 200.0);
        buy.put("RAW", 100.0);
        Route r = best("REFINED"); // 100 profit per 1-hour forge
        Recipe rec = recipes.get("REFINED");
        assertEquals(700, ForgePlanner.profitPerHour(r, rec, 7, Double.NaN), 1e-9, "no volume data (AH): every slot counts");
        assertEquals(200, ForgePlanner.profitPerHour(r, rec, 7, 2), 1e-9, "market only buys 2 an hour");
        assertEquals(700, ForgePlanner.profitPerHour(r, rec, 7, 1000), 1e-9);
    }

    // ---- input supply ----

    /** 7 slots could run it 7 times an hour, but only 20 RAW an hour can be bought and each run needs 10. */
    @Test
    void aPartYouCantBuyFastEnoughCapsTheRoute() {
        recipe("TOP", HOUR, 0, "RAW", 10);
        sell.put("TOP", 1000.0);
        buy.put("RAW", 10.0);
        Route r = best("TOP");

        ForgePlanner.Throughput t = ForgePlanner.throughput(r, recipes.get("TOP"), 7, 100, id -> id.equals("RAW") ? 20 : Double.NaN);
        assertEquals(2, t.runsPerHour(), 1e-9);
        assertEquals(900 * 2, t.profitPerHour(), 1e-9);
        assertEquals("RAW", t.limitingInput());
        assertEquals(20, t.limitPerHour(), 1e-9);
    }

    @Test
    void aPartNeededInSeveralPlacesIsCountedOnce() {
        recipe("TOP", 30, 0, "LEFT", 1, "RIGHT", 1);
        recipe("LEFT", HOUR, 0, "RAW", 5);
        recipe("RIGHT", HOUR, 0, "RAW", 5);
        sell.put("TOP", 1000.0);
        buy.put("RAW", 10.0); // LEFT and RIGHT can't be bought: both forged, both eat RAW
        Route r = best("TOP");

        assertEquals(Map.of("RAW", 10.0), ForgePlanner.boughtInputs(r.tree()));
        ForgePlanner.Throughput t = ForgePlanner.throughput(r, recipes.get("TOP"), 7, Double.NaN, id -> 10);
        assertEquals(1, t.runsPerHour(), 1e-9, "10 RAW an hour, 10 per run");
    }

    @Test
    void theTightestLimitIsTheOneNamed() {
        recipe("TOP", HOUR, 0, "RAW", 1);
        sell.put("TOP", 1000.0);
        buy.put("RAW", 10.0);
        Route r = best("TOP");
        Recipe rec = recipes.get("TOP");

        var slots = ForgePlanner.throughput(r, rec, 7, 1000, id -> 1000);
        assertEquals(ForgePlanner.Throughput.SLOTS, slots.limit());
        assertEquals(7, slots.runsPerHour(), 1e-9);
        assertNull(slots.limitingInput());

        var market = ForgePlanner.throughput(r, rec, 7, 3, id -> 1000);
        assertEquals(ForgePlanner.Throughput.MARKET, market.limit());
        assertEquals(3, market.runsPerHour(), 1e-9);

        var unknown = ForgePlanner.throughput(r, rec, 7, Double.NaN, id -> Double.NaN);
        assertEquals(7, unknown.runsPerHour(), 1e-9, "no data is no cap here (ForgeCalc hides loading items itself)");
    }

    // ---- forge queue (what to start, in what order) ----

    /** The SX-R326 tree with every intermediate forged: Golden Plate appears three times, Refined Mithril in two places. */
    private Route sxR326ForgedFromScratch() {
        recipe("SX326", 30, 0, "GOLDEN_PLATE", 1, "SX226", 1, "MITHRIL_PLATE", 1);
        recipe("GOLDEN_PLATE", 6 * HOUR, 0, "GOLD_BLOCK", 2, "JEWEL", 5, "REFINED_DIAMOND", 1);
        recipe("REFINED_DIAMOND", 8 * HOUR, 0, "DIAMOND_BLOCK", 2);
        recipe("SX226", 4 * HOUR, 0, "REFINED_MITHRIL", 3, "DRILL_MOTOR", 1, "FUEL", 1);
        recipe("DRILL_MOTOR", 30 * HOUR, 0, "IRON_BLOCK", 1, "REDSTONE_BLOCK", 3, "TREASURITE", 10, "GOLDEN_PLATE", 1);
        recipe("MITHRIL_PLATE", 18 * HOUR, 0, "IRON_BLOCK", 1, "GOLDEN_PLATE", 1, "REFINED_TITANIUM", 1, "REFINED_MITHRIL", 5);
        recipe("REFINED_MITHRIL", 6 * HOUR, 0, "ENCH_MITHRIL", 160);
        recipe("REFINED_TITANIUM", 12 * HOUR, 0, "ENCH_TITANIUM", 16);
        for (String raw : List.of("GOLD_BLOCK", "JEWEL", "DIAMOND_BLOCK", "IRON_BLOCK", "REDSTONE_BLOCK", "TREASURITE", "FUEL",
                "ENCH_MITHRIL", "ENCH_TITANIUM")) buy.put(raw, 10.0);
        sell.put("SX326", 10_000_000.0); // the intermediates can't be bought, so all of them are forged
        return best("SX326");
    }

    @Test
    void theForgeQueueGroupsEachPartAndOrdersItByWhenItCanStart() {
        List<ForgePlanner.QueueItem> queue = ForgePlanner.forgeQueue(sxR326ForgedFromScratch().tree());
        List<String> order = queue.stream().map(q -> q.stage() + ":" + q.id() + "x" + qty(q.forges())).toList();
        assertEquals(List.of(
                "1:REFINED_TITANIUMx1", "1:REFINED_DIAMONDx3", "1:REFINED_MITHRILx8", // parts all bought: start now, longest first
                "2:GOLDEN_PLATEx3",
                "3:DRILL_MOTORx1", "3:MITHRIL_PLATEx1",
                "4:SX226x1",
                "5:SX326x1"), order);
        assertEquals(6 * HOUR, queue.stream().filter(q -> q.id().equals("GOLDEN_PLATE")).findFirst().orElseThrow().secondsPerForge(), 1e-9);
    }

    @Test
    void theShoppingListAddsUpEveryPlaceAPartIsUsed() {
        Map<String, Double> shopping = ForgePlanner.boughtInputs(sxR326ForgedFromScratch().tree());
        assertEquals(8 * 160, shopping.get("ENCH_MITHRIL"), 1e-9, "3 + 5 Refined Mithril");
        assertEquals(3 * 2, shopping.get("GOLD_BLOCK"), 1e-9, "three Golden Plates");
        assertEquals(2, shopping.get("IRON_BLOCK"), 1e-9);
    }

    private static String qty(double q) {
        return q == Math.floor(q) ? String.valueOf((long) q) : String.valueOf(q);
    }

    // ---- optimality: compare against trying every make-or-buy combination ----

    private record Option(double coins, double slotSeconds) { }

    /** Recipes the player hasn't unlocked: the brute force never forges these. */
    private final Set<String> locked = new HashSet<>();

    /** Every way to obtain {@code qty} of {@code id}: buy it, or forge it with every combination of ways to get its parts. */
    private List<Option> allOptions(String id, double qty, boolean mustForge, Set<String> stack) {
        List<Option> out = new ArrayList<>();
        Double b = buy.get(id);
        if (!mustForge && b != null && b > 0) out.add(new Option(qty * b, 0));
        Recipe r = recipes.get(id);
        if (r == null || stack.contains(id) || locked.contains(id)) return out;
        double forges = qty / r.count();
        List<Option> combos = List.of(new Option(forges * r.coins(), forges * r.seconds()));
        stack.add(id);
        for (var e : r.inputs().entrySet()) {
            List<Option> partOptions = allOptions(e.getKey(), e.getValue() * forges, false, stack);
            List<Option> next = new ArrayList<>();
            for (Option c : combos) for (Option p : partOptions) next.add(new Option(c.coins() + p.coins(), c.slotSeconds() + p.slotSeconds()));
            combos = next;
        }
        stack.remove(id);
        out.addAll(combos);
        return out;
    }

    private double bruteForceBestRate(String id) {
        double revenue = sell.get(id) * recipes.get(id).count();
        double best = Double.NaN;
        for (Option o : allOptions(id, 1, true, new HashSet<>())) {
            double profit = revenue - o.coins();
            if (profit <= 0) continue;
            double rate = profit / (o.slotSeconds() / HOUR);
            if (Double.isNaN(best) || rate > best) best = rate;
        }
        return best;
    }

    /** A random recipe tree rooted at I0 (fills recipes, buy and sell). */
    private void randomTree(Random rnd, double[] durations) {
        recipes.clear();
        buy.clear();
        sell.clear();
        int n = 4 + rnd.nextInt(3); // up to 6 items: every-combination enumeration explodes past that
        for (int k = 0; k < n; k++) {
            String id = "I" + k;
            // items only use higher-numbered items, so the tree has no loops but parts can be shared
            if (k == 0 || (k < n - 1 && rnd.nextDouble() < 0.7)) {
                Map<String, Double> in = new LinkedHashMap<>();
                int parts = 1 + rnd.nextInt(2);
                for (int p = 0; p < parts; p++) in.merge("I" + (k + 1 + rnd.nextInt(n - k - 1)), 1.0 + rnd.nextInt(3), Double::sum);
                double coins = rnd.nextDouble() < 0.2 ? 1000 * rnd.nextInt(50) : 0;
                recipes.put(id, new Recipe(id, rnd.nextDouble() < 0.15 ? 2 : 1, durations[rnd.nextInt(durations.length)], coins, in));
            }
            if (k > 0 && rnd.nextDouble() < 0.85) buy.put(id, 100.0 + rnd.nextInt(100_000));
        }
        sell.put("I0", 1000.0 + rnd.nextInt(400_000));
    }

    @Test
    void plannerMatchesTryingEveryCombinationOnRandomTrees() {
        Random rnd = new Random(20261015);
        double[] durations = {30, 600, HOUR, 6 * HOUR, 10 * HOUR, 30 * HOUR};
        int profitable = 0;
        for (int trial = 0; trial < 500; trial++) {
            randomTree(rnd, durations);

            Route r = best("I0");
            double expected = bruteForceBestRate("I0");
            if (Double.isNaN(expected)) {
                assertNull(r, "trial " + trial + ": nothing is profitable, planner returned a route");
            } else {
                profitable++;
                assertNotNull(r, "trial " + trial + ": a profitable route exists");
                assertEquals(expected, r.profitPerSlotHour(), Math.abs(expected) * 1e-9, "trial " + trial);
            }
        }
        assertTrue(profitable > 100, "fixture should exercise plenty of profitable trees, got " + profitable);
    }

    // ---- unlocks and purse ----

    /** Forging the plate is the better route, but you haven't unlocked it: buy it and the drill is still worth forging. */
    @Test
    void aPartYouHaventUnlockedIsBoughtInstead() {
        recipe("DRILL", 30, 0, "PLATE", 1, "PART", 1);
        recipe("PLATE", 30, 0, "RAW", 2);
        sell.put("DRILL", 200.0);
        buy.put("PART", 50.0);
        buy.put("PLATE", 100.0);
        buy.put("RAW", 10.0);

        assertTrue(part(best("DRILL").tree(), "PLATE").forged(), "unlocked: forge the plate");
        Route locked = new ForgePlanner(recipes, prices(), 1.0, 0, id -> !id.equals("PLATE")).best("DRILL");
        assertFalse(part(locked.tree(), "PLATE").forged(), "locked: buy the plate");
        assertEquals(50, locked.profit(), 1e-9);
    }

    @Test
    void anItemYouCantForgeHasNoRoute() {
        recipe("DRILL", 30, 0, "PART", 1);
        sell.put("DRILL", 200.0);
        buy.put("PART", 50.0);
        ForgePlanner planner = new ForgePlanner(recipes, prices(), 1.0, 0, id -> !id.equals("DRILL"));
        assertNull(planner.best("DRILL"));
        assertTrue(planner.rankAll().isEmpty());
    }

    /**
     * Buying the plate is the best rate (150 up front); forging it is cheaper in coins (70) but takes 6 hours. With 100 in
     * your purse you get the route you can afford; with 60 there is none.
     */
    @Test
    void aRouteOverYourPurseFallsBackToOneThatFits() {
        recipe("DRILL", 30, 0, "PLATE", 1, "PART", 1);
        recipe("PLATE", 6 * HOUR, 0, "RAW", 2);
        sell.put("DRILL", 200.0);
        buy.put("PART", 50.0);
        buy.put("PLATE", 100.0);
        buy.put("RAW", 10.0);
        ForgePlanner planner = new ForgePlanner(recipes, prices(), 1.0);

        Route rich = planner.best("DRILL", 1000);
        assertEquals(150, rich.cost(), 1e-9);
        assertFalse(part(rich.tree(), "PLATE").forged());

        Route tight = planner.best("DRILL", 100);
        assertEquals(70, tight.cost(), 1e-9, "only the forge-the-plate route fits");
        assertTrue(part(tight.tree(), "PLATE").forged());

        assertNull(planner.best("DRILL", 60), "not even the cheapest route fits");
        assertTrue(planner.rankAll(60).isEmpty());
        assertEquals(1, planner.rankAll(100).size());
    }

    private void assertNoLockedForge(Step s, String context) {
        if (!s.forged()) return;
        assertFalse(locked.contains(s.id()), context + ": forged " + s.id() + " which is locked");
        for (Step p : s.parts()) assertNoLockedForge(p, context);
    }

    /** Random trees with random locked recipes and random budgets, checked against every make-or-buy combination. */
    @Test
    void unlocksAndPurseMatchTryingEveryCombinationOnRandomTrees() {
        Random rnd = new Random(20261017);
        double[] durations = {30, 600, HOUR, 6 * HOUR, 10 * HOUR, 30 * HOUR};
        int fitted = 0, none = 0, fellBack = 0;
        for (int trial = 0; trial < 3000; trial++) { // few random trees have a best route dearer than the cheapest one
            randomTree(rnd, durations);
            locked.clear();
            for (String id : recipes.keySet()) if (rnd.nextDouble() < 0.25) locked.add(id);
            String context = "trial " + trial + " locked " + locked;
            double revenue = sell.get("I0") * recipes.get("I0").count();
            List<Option> options = allOptions("I0", 1, true, new HashSet<>());
            double minCost = options.stream().mapToDouble(Option::coins).min().orElse(Double.POSITIVE_INFINITY);

            ForgePlanner planner = new ForgePlanner(recipes, prices(), 1.0, 0, id -> !locked.contains(id));
            Route unlimited = planner.best("I0");
            // budgets aimed at each case: between the cheapest route and the optimum (fall back), around the cheapest, anything
            double pick = rnd.nextDouble(), budget;
            if (pick < 0.4 && unlimited != null && unlimited.cost() > minCost) {
                budget = minCost + (unlimited.cost() - minCost) * rnd.nextDouble();
            } else if (pick < 0.7 && minCost < Double.POSITIVE_INFINITY) {
                budget = minCost * (0.5 + rnd.nextDouble());
            } else {
                budget = 100 + rnd.nextInt(400_000);
            }
            double optimum = bruteForceBestRate("I0");
            if (Double.isNaN(optimum)) assertNull(unlimited, context + ": nothing profitable");
            else assertEquals(optimum, unlimited.profitPerSlotHour(), Math.abs(optimum) * 1e-9, context + ": unlimited optimum");

            Route r = planner.best("I0", budget);
            if (options.isEmpty() || minCost >= revenue || minCost > budget) {
                assertNull(r, context + ": no profitable route fits " + budget);
                none++;
                continue;
            }
            assertNotNull(r, context + ": the cheapest route fits " + budget);
            fitted++;
            assertTrue(r.cost() <= budget * (1 + 1e-12), context + ": over budget");
            assertNoLockedForge(r.tree(), context);
            double bestFitting = options.stream().filter(o -> o.coins() <= budget && o.coins() < revenue)
                    .mapToDouble(o -> (revenue - o.coins()) / (o.slotSeconds() / HOUR)).max().getAsDouble();
            assertTrue(r.profitPerSlotHour() <= bestFitting * (1 + 1e-9), context + ": better than any route that fits");
            if (unlimited.cost() <= budget) {
                assertEquals(unlimited.profitPerSlotHour(), r.profitPerSlotHour(), Math.abs(optimum) * 1e-9, context + ": optimum fits");
            } else {
                fellBack++;
            }
        }
        assertTrue(fitted > 100 && none > 20 && fellBack > 10,
                "fixture should cover all three cases, got fitted " + fitted + ", none " + none + ", fell back " + fellBack);
    }
}
