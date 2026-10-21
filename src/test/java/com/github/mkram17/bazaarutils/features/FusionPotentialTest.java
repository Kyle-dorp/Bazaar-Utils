package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.FusionPotential.Recipe;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FusionPotentialTest {
    private final Map<String, Integer> held = new HashMap<>();
    private final Map<String, Double> input = new HashMap<>(), output = new HashMap<>();
    private final List<Recipe> recipes = new ArrayList<>();
    private int priceCalls;

    private final FusionPotential.Prices prices = new FusionPotential.Prices() {
        public double input(String id) {
            priceCalls++;
            return input.getOrDefault(id, Double.NaN);
        }

        public double output(String id) {
            priceCalls++;
            return output.getOrDefault(id, Double.NaN);
        }
    };

    private FusionPotential.Plan plan() {
        return FusionPotential.plan(held, recipes, prices);
    }

    /** 12 Hideonleaf + 10 Flitter: two fusions of 5 + 5, each making 2 Hideoncave. Profit over what the inputs are worth. */
    @Test
    void fusesWhatYouHold() {
        held.put("HIDEONLEAF", 12);
        held.put("FLITTER", 10);
        input.put("HIDEONLEAF", 100.0);
        input.put("FLITTER", 60.0);
        output.put("HIDEONCAVE", 900.0);
        recipes.add(new Recipe("HIDEONLEAF", 5, "FLITTER", 5, "HIDEONCAVE", 2));

        FusionPotential.Plan p = plan();
        assertEquals(1, p.fusions().size());
        assertEquals(2, p.fusions().get(0).times());
        double each = 2 * 900 - (5 * 100 + 5 * 60);
        assertEquals(each, p.fusions().get(0).profitEach(), 1e-9);
        assertEquals(2 * each, p.total(), 1e-9);
        assertEquals(20, p.shardsUsed());
        assertEquals(22, p.shardsHeld());
    }

    /** Both recipes want the 10 Bramble you have: the better one gets them, the other isn't listed with the same shards. */
    @Test
    void aShardTwoRecipesWantIsOnlyUsedOnce() {
        held.put("BRAMBLE", 10);
        held.put("CHUCKWALLA", 10);
        held.put("BLUEBIRD", 10);
        for (String s : List.of("BRAMBLE", "CHUCKWALLA", "BLUEBIRD")) input.put(s, 100.0);
        output.put("GOOD", 3000.0);
        output.put("OKAY", 2000.0);
        recipes.add(new Recipe("BRAMBLE", 5, "BLUEBIRD", 5, "OKAY", 1));
        recipes.add(new Recipe("BRAMBLE", 5, "CHUCKWALLA", 5, "GOOD", 1));

        FusionPotential.Plan p = plan();
        assertEquals(1, p.fusions().size(), "no Bramble left for the second recipe");
        assertEquals("GOOD", p.fusions().get(0).recipe().output());
        assertEquals(2 * (3000 - 1000), p.total(), 1e-9);
    }

    @Test
    void leftoversGoToTheNextBestRecipe() {
        held.put("BRAMBLE", 15);
        held.put("CHUCKWALLA", 10);
        held.put("BLUEBIRD", 10);
        for (String s : List.of("BRAMBLE", "CHUCKWALLA", "BLUEBIRD")) input.put(s, 100.0);
        output.put("GOOD", 3000.0);
        output.put("OKAY", 2000.0);
        recipes.add(new Recipe("BRAMBLE", 5, "CHUCKWALLA", 5, "GOOD", 1));
        recipes.add(new Recipe("BRAMBLE", 5, "BLUEBIRD", 5, "OKAY", 1));

        FusionPotential.Plan p = plan();
        assertEquals(2, p.fusions().size());
        assertEquals(2, p.fusions().get(0).times());
        assertEquals(1, p.fusions().get(1).times(), "5 Bramble left: one Okay fusion");
    }

    /** Listed by total profit: one huge fusion beats three small ones even if the small ones are worth more each. */
    @Test
    void theListIsRankedByTotalProfit() {
        held.put("A", 30);
        held.put("B", 30);
        held.put("C", 2);
        held.put("D", 2);
        for (String s : List.of("A", "B", "C", "D")) input.put(s, 10.0);
        output.put("MANY", 70.0);  // 5 A + 5 B (100) -> 2 MANY (140): 40 each, 6 fusions = 240
        output.put("RARE", 200.0); // 2 C + 2 D (40) -> 1 RARE (200): 160 each, 1 fusion = 160
        recipes.add(new Recipe("A", 5, "B", 5, "MANY", 2));
        recipes.add(new Recipe("C", 2, "D", 2, "RARE", 1));
        FusionPotential.Plan p = plan();
        assertEquals("MANY", p.fusions().get(0).recipe().output(), "240 in total beats 160");
        assertEquals("RARE", p.fusions().get(1).recipe().output());
    }

    @Test
    void aRecipeUsingTwoOfTheSameShardTakesBothFromOneStack() {
        held.put("THUNDER", 9);
        input.put("THUNDER", 100.0);
        output.put("STORM", 1000.0);
        recipes.add(new Recipe("THUNDER", 2, "THUNDER", 2, "STORM", 1));
        FusionPotential.Plan p = plan();
        assertEquals(2, p.fusions().get(0).times(), "9 Thunder makes two fusions of 4");
        assertEquals(2 * (1000 - 400), p.total(), 1e-9);
        assertEquals(8, p.shardsUsed());
    }

    @Test
    void lossesAndUnpricedShardsAreLeftOut() {
        held.put("BRAMBLE", 10);
        held.put("CHUCKWALLA", 10);
        input.put("BRAMBLE", 100.0);
        input.put("CHUCKWALLA", 100.0);
        output.put("CHEAP", 500.0); // 1,000 of shards in
        recipes.add(new Recipe("BRAMBLE", 5, "CHUCKWALLA", 5, "CHEAP", 1));
        recipes.add(new Recipe("BRAMBLE", 5, "CHUCKWALLA", 5, "UNKNOWN", 1));
        assertTrue(plan().fusions().isEmpty());
        assertEquals(0, plan().total());
    }

    @Test
    void holdingNothingPlansNothing() {
        output.put("GOOD", 3000.0);
        recipes.add(new Recipe("BRAMBLE", 5, "CHUCKWALLA", 5, "GOOD", 1));
        assertSame(0, plan().fusions().size());
        assertEquals(0, priceCalls, "no prices asked for recipes you can't do");
    }

    /** Random holdings and recipes: across the whole plan no shard is ever used more than you have. */
    @Test
    void noShardIsUsedMoreThanYouHaveAcrossTheWholePlan() {
        Random rnd = new Random(20261017);
        String[] shards = {"S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7"};
        for (int trial = 0; trial < 1000; trial++) {
            held.clear();
            input.clear();
            output.clear();
            recipes.clear();
            for (String s : shards) {
                if (rnd.nextDouble() < 0.7) held.put(s, rnd.nextInt(40));
                input.put(s, 10.0 + rnd.nextInt(100));
                output.put(s, 10.0 + rnd.nextInt(2000));
            }
            int n = 3 + rnd.nextInt(30);
            for (int i = 0; i < n; i++) {
                String l = shards[rnd.nextInt(shards.length)], r = rnd.nextDouble() < 0.2 ? l : shards[rnd.nextInt(shards.length)];
                recipes.add(new Recipe(l, 1 + rnd.nextInt(6), r, 1 + rnd.nextInt(6), shards[rnd.nextInt(shards.length)], 1 + rnd.nextInt(3)));
            }
            FusionPotential.Plan p = plan();
            Map<String, Long> used = new HashMap<>();
            for (FusionPotential.Fusion f : p.fusions()) {
                assertTrue(f.times() >= 1 && f.profit() > 0, "trial " + trial + ": only real, profitable lines");
                used.merge(f.recipe().left(), (long) f.recipe().leftCount() * f.times(), Long::sum);
                used.merge(f.recipe().right(), (long) f.recipe().rightCount() * f.times(), Long::sum);
            }
            for (var e : used.entrySet()) {
                assertTrue(e.getValue() <= held.getOrDefault(e.getKey(), 0),
                        "trial " + trial + ": used " + e.getValue() + " " + e.getKey() + " but hold " + held.getOrDefault(e.getKey(), 0));
            }
            assertEquals(used.values().stream().mapToLong(Long::longValue).sum(), p.shardsUsed(), "trial " + trial);
        }
    }

    /**
     * The panel froze the game: 44,051 price lookups over 128,695 recipes. Prices are now asked for at most once per shard, and
     * only for recipes you can actually do, so adding 10,000 recipes you can't do changes nothing.
     */
    @Test
    void priceLookupsGrowWithWhatYouHoldNotWithTheRecipeList() {
        held.put("BRAMBLE", 50);
        held.put("CHUCKWALLA", 50);
        input.put("BRAMBLE", 100.0);
        input.put("CHUCKWALLA", 100.0);
        for (int i = 0; i < 20; i++) {
            output.put("OUT" + i, 1500.0 + i);
            recipes.add(new Recipe("BRAMBLE", 5, "CHUCKWALLA", 5, "OUT" + i, 1));
        }
        plan();
        int small = priceCalls;
        assertTrue(small <= 2 + 20, "each shard priced once: got " + small);

        for (int i = 0; i < 10_000; i++) recipes.add(new Recipe("UNHELD" + i, 5, "CHUCKWALLA", 5, "OUT" + (i % 20), 1));
        priceCalls = 0;
        plan();
        assertEquals(small, priceCalls, "10,000 more recipes you can't do cost no extra price lookups");
    }
    /** The fusion min profit: a fusion below it is left out (its shards stay free for others), one at or above it is kept. */
    @Test
    void fusionsBelowTheMinProfitAreLeftOut() {
        held.put("CHEAP", 10);
        held.put("RICH", 10);
        input.put("CHEAP", 100.0);
        input.put("RICH", 100.0);
        output.put("SMALL", 400.0); // 2 CHEAP -> SMALL: +200 each
        output.put("BIG", 1300.0); // 2 RICH -> BIG: +1,100 each
        recipes.add(new Recipe("CHEAP", 1, "CHEAP", 1, "SMALL", 1));
        recipes.add(new Recipe("RICH", 1, "RICH", 1, "BIG", 1));

        assertEquals(2, plan().fusions().size(), "no min: both");
        FusionPotential.Plan p = FusionPotential.plan(held, recipes, prices, 1000);
        assertEquals(1, p.fusions().size());
        assertEquals("BIG", p.fusions().get(0).recipe().output(), "the +1,100 fusion is kept");
        assertEquals(5 * 1100, p.total(), 1e-9);
        assertEquals(10, p.shardsUsed(), "the CHEAP shards aren't counted as used");
        assertEquals(1, FusionPotential.plan(held, recipes, prices, 1100).fusions().size(), "exactly at the min is kept");
        assertEquals(0, FusionPotential.plan(held, recipes, prices, 1100.01).fusions().size());
    }

    @Test
    void zeroFusionMinFallsBackToTheSharedMin() {
        assertEquals(10_000, FusionPotential.minProfit(0, 10_000), "unset: the shared min profit per item");
        assertEquals(250_000, FusionPotential.minProfit(250_000, 10_000), "set: the fusion min, even above the shared one");
        assertEquals(5_000, FusionPotential.minProfit(5_000, 10_000), "set: the fusion min, even below the shared one");
        assertFalse(FusionPotential.worthIt(9_999, FusionPotential.minProfit(0, 10_000)));
        assertTrue(FusionPotential.worthIt(10_000, FusionPotential.minProfit(0, 10_000)));
        assertFalse(FusionPotential.worthIt(-5, 0), "a losing fusion is never worth it, whatever the min");
        assertFalse(FusionPotential.worthIt(0, 0));
    }
}
