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

    // ---- optimality: compare against trying every make-or-buy combination ----

    private record Option(double coins, double slotSeconds) { }

    /** Every way to obtain {@code qty} of {@code id}: buy it, or forge it with every combination of ways to get its parts. */
    private List<Option> allOptions(String id, double qty, boolean mustForge, Set<String> stack) {
        List<Option> out = new ArrayList<>();
        Double b = buy.get(id);
        if (!mustForge && b != null && b > 0) out.add(new Option(qty * b, 0));
        Recipe r = recipes.get(id);
        if (r == null || stack.contains(id)) return out;
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

    @Test
    void plannerMatchesTryingEveryCombinationOnRandomTrees() {
        Random rnd = new Random(20261015);
        double[] durations = {30, 600, HOUR, 6 * HOUR, 10 * HOUR, 30 * HOUR};
        int profitable = 0;
        for (int trial = 0; trial < 500; trial++) {
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
}
