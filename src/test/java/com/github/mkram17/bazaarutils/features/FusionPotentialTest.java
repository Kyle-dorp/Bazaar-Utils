package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.FusionCalc.NamedRecipe;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FusionPotentialTest {
    private final Map<String, double[]> held = new HashMap<>();
    private final Map<String, Double> value = new HashMap<>();
    private final List<NamedRecipe> recipes = new ArrayList<>();

    private void hold(String shard, double qty, double cost) {
        held.put(shard, new double[]{qty, cost});
    }

    private FusionPotential.Plan plan() {
        return FusionPotential.plan(held, recipes, name -> value.getOrDefault(name, Double.NaN));
    }

    /** 12 Hideonleaf (100 each) + 10 Flitter (60 each): 2 fusions of 5 + 5, each making 2 Hideoncave worth 900. */
    @Test
    void fusesWhatYourShardsAllow() {
        hold("Hideonleaf Shard", 12, 1200);
        hold("Flitter Shard", 10, 600);
        recipes.add(new NamedRecipe("Hideonleaf Shard", 5, "Flitter Shard", 5, "Hideoncave Shard", 2));
        value.put("Hideoncave Shard", 900.0);

        FusionPotential.Plan p = plan();
        assertEquals(1, p.fusions().size());
        assertEquals(2, p.fusions().get(0).times());
        double perFusion = 2 * 900 - (5 * 100 + 5 * 60);
        assertEquals(2 * perFusion, p.total(), 1e-9);
    }

    /** Both recipes want the 10 Bramble you hold: the better one gets them, the other doesn't count them again. */
    @Test
    void aShardTwoRecipesWantIsOnlyUsedOnce() {
        hold("Bramble Shard", 10, 1000);
        hold("Chuckwalla Shard", 10, 1000);
        hold("Bluebird Shard", 10, 1000);
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Chuckwalla Shard", 5, "Good Shard", 1));
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Bluebird Shard", 5, "Okay Shard", 1));
        value.put("Good Shard", 3000.0);
        value.put("Okay Shard", 2000.0);

        FusionPotential.Plan p = plan();
        assertEquals(1, p.fusions().size(), "no Bramble left for the second recipe");
        assertEquals("Good Shard", p.fusions().get(0).recipe().output());
        assertEquals(2 * (3000 - 1000), p.total(), 1e-9);
    }

    @Test
    void theLeftoversGoToTheNextBestRecipe() {
        hold("Bramble Shard", 15, 1500);
        hold("Chuckwalla Shard", 10, 1000);
        hold("Bluebird Shard", 10, 1000);
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Chuckwalla Shard", 5, "Good Shard", 1));
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Bluebird Shard", 5, "Okay Shard", 1));
        value.put("Good Shard", 3000.0);
        value.put("Okay Shard", 2000.0);

        FusionPotential.Plan p = plan();
        assertEquals(2, p.fusions().size());
        assertEquals(2, p.fusions().get(0).times());
        assertEquals(1, p.fusions().get(1).times(), "5 Bramble left: one Okay fusion");
        assertEquals(2 * 2000 + 1 * 1000, p.total(), 1e-9);
    }

    @Test
    void aRecipeUsingTwoOfTheSameShardTakesBothFromOneStack() {
        hold("Thunder Shard", 9, 900);
        recipes.add(new NamedRecipe("Thunder Shard", 2, "Thunder Shard", 2, "Storm Shard", 1));
        value.put("Storm Shard", 1000.0);
        FusionPotential.Plan p = plan();
        assertEquals(2, p.fusions().get(0).times(), "9 Thunder makes two fusions of 4");
        assertEquals(2 * (1000 - 400), p.total(), 1e-9);
    }

    @Test
    void lossesAndUnpricedOutputsAreLeftOut() {
        hold("Bramble Shard", 10, 1000);
        hold("Chuckwalla Shard", 10, 1000);
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Chuckwalla Shard", 5, "Cheap Shard", 1));
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Chuckwalla Shard", 5, "Unknown Shard", 1));
        value.put("Cheap Shard", 500.0); // costs 1,000 of shards
        assertTrue(plan().fusions().isEmpty());
        assertEquals(0, plan().total());
    }

    @Test
    void heldNamesWithIconsStillMatchTheRecipes() {
        hold(" Bramble Shard", 5, 500);
        hold("CHUCKWALLA SHARD", 5, 500);
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Chuckwalla Shard", 5, "Good Shard", 1));
        value.put("Good Shard", 3000.0);
        assertEquals(2000, plan().total(), 1e-9);
    }

    @Test
    void holdingNothingPlansNothing() {
        recipes.add(new NamedRecipe("Bramble Shard", 5, "Chuckwalla Shard", 5, "Good Shard", 1));
        value.put("Good Shard", 3000.0);
        assertEquals(0, plan().total());
        assertTrue(plan().fusions().isEmpty());
    }
}
