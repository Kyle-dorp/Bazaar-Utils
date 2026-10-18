package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FusionCalcTest {
    /** The potential panel only looks at recipes you could do: both input shards are ones you hold. Uses the bundled recipes. */
    @Test
    void recipesUsingReturnsOnlyRecipesWhoseInputsYouHold() {
        Set<String> held = Set.of("SHARD_SOLSNATCHER", "SHARD_THUNDER", "SHARD_MINER_ZOMBIE");
        List<FusionPotential.Recipe> recipes = FusionCalc.recipesUsing(held);
        assertFalse(recipes.isEmpty(), "the bundled recipes include fusions of these shards");
        for (FusionPotential.Recipe r : recipes) {
            assertTrue(held.contains(r.left()) && held.contains(r.right()), "both inputs held: " + r);
        }
        assertEquals(recipes.size(), new HashSet<>(recipes).size(), "each recipe once, even when both inputs are held");
        assertTrue(recipes.stream().anyMatch(r -> r.left().equals(r.right())), "same-shard recipes (Miner Zombie + Miner Zombie) included");
    }

    @Test
    void holdingNoShardsMeansNoRecipes() {
        assertTrue(FusionCalc.recipesUsing(Set.of()).isEmpty());
        assertTrue(FusionCalc.recipesUsing(Set.of("NOT_A_SHARD")).isEmpty());
    }
}
