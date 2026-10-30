package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.FlipSearch.ForgeHit;
import com.github.mkram17.bazaarutils.features.FlipSearch.FusionHit;
import com.github.mkram17.bazaarutils.features.FlipSearch.Relation;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Recipe;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FlipSearchTest {
    @Test
    void searchIgnoresCaseIconsAndStars() {
        assertTrue(FlipSearch.matches("drill", "Titanium Drill DR-X455"));
        assertTrue(FlipSearch.matches("DR-X455", "Fleet Titanium Drill DR-X455 ✪✪"));
        assertTrue(FlipSearch.matches("topaz", " Rough Topaz Gemstone"));
        assertFalse(FlipSearch.matches("ruby", "Rough Topaz Gemstone"));
        assertTrue(FlipSearch.matches("", "anything"), "a blank search shows everything");
        assertTrue(FlipSearch.matches("  ", "anything"));
    }

    /** The match at position 50 is found even though only 7 rows are shown: filter first, then cap. */
    @Test
    void matchesBeyondTheRowLimitAreFound() {
        List<String> rows = new ArrayList<>(IntStream.range(0, 60).mapToObj(i -> "Item " + i).toList());
        rows.set(50, "Hideoncave Shard");
        assertEquals(List.of("Hideoncave Shard"), FlipSearch.top(rows, r -> FlipSearch.matches("hideon", r), 7));
        assertEquals(7, FlipSearch.top(rows, r -> true, 7).size(), "no search: the usual first 7");
        assertEquals("Item 0", FlipSearch.top(rows, r -> true, 7).get(0));
    }

    // ---- fusions ----

    private static FusionCalc.Fusion fusion(String left, String right, String output, double perHour) {
        return new FusionCalc.Fusion("NORMAL", left, 5, 100, right, 5, 100, output, 1, 2000, 1000, 1000, perHour);
    }

    private static final Function<String, String> SHARD_NAME = id -> switch (id) {
        case "SHARD_HIDEONCAVE" -> "Hideoncave Shard";
        case "SHARD_HIDEONLEAF" -> "Hideonleaf Shard";
        case "SHARD_FLITTER" -> "Flitter Shard";
        case "SHARD_BRAMBLE" -> "Bramble Shard";
        default -> id;
    };

    /** Searching a shard shows what makes it first, then what it can be fused into; each group best first. */
    @Test
    void aShardShowsTheFusionsThatMakeItThenTheOnesThatUseIt() {
        List<FusionCalc.Fusion> all = List.of(
                fusion("SHARD_BRAMBLE", "SHARD_FLITTER", "SHARD_HIDEONLEAF", 900),     // uses Flitter
                fusion("SHARD_HIDEONLEAF", "SHARD_BRAMBLE", "SHARD_FLITTER", 300),     // makes Flitter
                fusion("SHARD_HIDEONLEAF", "SHARD_FLITTER", "SHARD_HIDEONCAVE", 2000), // uses Flitter
                fusion("SHARD_BRAMBLE", "SHARD_HIDEONLEAF", "SHARD_FLITTER", 500),     // makes Flitter
                fusion("SHARD_BRAMBLE", "SHARD_HIDEONLEAF", "SHARD_HIDEONCAVE", 5000)); // not Flitter at all

        List<FusionHit> hits = FlipSearch.fusions("flitter", all, SHARD_NAME);
        assertEquals(4, hits.size());
        assertEquals(List.of(Relation.MAKES, Relation.MAKES, Relation.USES, Relation.USES), hits.stream().map(FusionHit::relation).toList());
        assertEquals(List.of(500.0, 300.0, 2000.0, 900.0), hits.stream().map(h -> h.fusion().perHour()).toList());
    }

    @Test
    void aFusionThatBothMakesAndUsesTheShardIsListedOnceAsMakes() {
        List<FusionHit> hits = FlipSearch.fusions("hideon", List.of(fusion("SHARD_HIDEONLEAF", "SHARD_FLITTER", "SHARD_HIDEONCAVE", 1)), SHARD_NAME);
        assertEquals(1, hits.size());
        assertEquals(Relation.MAKES, hits.get(0).relation());
    }

    @Test
    void repeatedFusionIngredientsAreLookedUpOnlyOncePerSearch() {
        Map<String, Integer> calls = new HashMap<>();
        List<FusionCalc.Fusion> all = IntStream.range(0, 1000)
                .mapToObj(i -> fusion("SHARD_BRAMBLE", "SHARD_FLITTER", "SHARD_HIDEONCAVE", i)).toList();
        assertEquals(1000, FlipSearch.fusions("flitter", all, id -> {
            calls.merge(id, 1, Integer::sum);
            return SHARD_NAME.apply(id);
        }).size());
        assertEquals(Map.of("SHARD_BRAMBLE", 1, "SHARD_FLITTER", 1, "SHARD_HIDEONCAVE", 1), calls);
    }

    /** The index looks names up once when it's built, never at search time, and a repeated search reuses its result. */
    @Test
    void theFusionIndexNormalizesNamesOnceAndCachesTheLastSearch() {
        Map<String, Integer> calls = new HashMap<>();
        List<FusionCalc.Fusion> all = IntStream.range(0, 5000)
                .mapToObj(i -> fusion("SHARD_BRAMBLE", i % 2 == 0 ? "SHARD_FLITTER" : "SHARD_HIDEONLEAF", "SHARD_HIDEONCAVE", i)).toList();
        FlipSearch.FusionIndex index = new FlipSearch.FusionIndex(all, id -> {
            calls.merge(id, 1, Integer::sum);
            return SHARD_NAME.apply(id);
        });
        Map<String, Integer> afterBuild = Map.copyOf(calls);
        List<FusionHit> flitter = index.search("Flitter");
        assertEquals(2500, flitter.size(), "every match, not cut to the row limit");
        assertEquals(4998.0, flitter.get(0).fusion().perHour(), "best profit per hour first");
        assertSame(flitter, index.search("  flitter "), "same search after normalizing: the cached result");
        assertEquals(5000, index.search("hideoncave").size());
        assertEquals(afterBuild, calls, "no name lookups while searching");
        assertTrue(index.search("umber").isEmpty());
        assertEquals(0, FlipSearch.FusionIndex.EMPTY.search("flitter").size());
    }

    // ---- forge ----

    private final Map<String, Recipe> recipes = new LinkedHashMap<>();
    private final Map<String, Double> lbin = new HashMap<>();

    private void recipe(String id, double sell, Object... inputs) {
        Map<String, Double> in = new LinkedHashMap<>();
        for (int i = 0; i < inputs.length; i += 2) in.put((String) inputs[i], ((Number) inputs[i + 1]).doubleValue());
        recipes.put(id, new Recipe(id, 1, 3600, 0, in));
        lbin.put(id, sell);
    }

    private List<ForgeCalc.Row> forgeRows() {
        JsonObject products = new JsonObject();
        var prices = ForgeCalc.prices(products, lbin, Map.of(), 0.0125, (i, p, a) -> true);
        return ForgeCalc.rank(recipes, prices, products, lbin, Map.of(), id -> 100, 7, 1.0, 0, 0);
    }

    private static final Function<String, String> ITEM_NAME = id -> switch (id) {
        case "DRILL_2" -> "Mithril Drill SX-R326";
        case "DRILL_1" -> "Mithril Drill SX-R226";
        case "PLATE" -> "Golden Plate";
        case "ENGINE" -> "Drill Motor";
        case "DIAMOND" -> "Refined Diamond";
        case "GEM" -> "Glacite Jewel";
        default -> id;
    };

    /** "drill" finds both drills' own routes; "golden plate" finds the plate itself and everything forged from it. */
    @Test
    void anItemShowsItsOwnRouteThenWhatItCanBeForgedInto() {
        lbin.put("GEM", 1_000.0);
        lbin.put("DIAMOND", 50_000.0);
        recipe("PLATE", 300_000, "DIAMOND", 1, "GEM", 5);
        recipe("ENGINE", 3_000_000, "PLATE", 1);
        recipe("DRILL_1", 6_000_000, "ENGINE", 1);
        recipe("DRILL_2", 16_000_000, "DRILL_1", 1, "PLATE", 1);
        List<ForgeCalc.Row> rows = forgeRows();
        assertEquals(4, rows.size(), "fixture: all four are profitable");

        List<ForgeHit> drills = FlipSearch.forge("drill", rows, ITEM_NAME);
        assertTrue(drills.stream().anyMatch(h -> h.row().route().id().equals("DRILL_2") && h.usedPart() == null));
        assertTrue(drills.stream().anyMatch(h -> h.row().route().id().equals("DRILL_1") && h.usedPart() == null));

        List<ForgeHit> plate = FlipSearch.forge("golden plate", rows, ITEM_NAME);
        assertEquals("PLATE", plate.get(0).row().route().id(), "its own route comes first");
        assertNull(plate.get(0).usedPart());
        Set<String> into = new HashSet<>();
        for (ForgeHit h : plate.subList(1, plate.size())) {
            assertEquals("PLATE", h.usedPart());
            into.add(h.row().route().id());
        }
        assertEquals(Set.of("ENGINE", "DRILL_2"), into, "everything that takes a Golden Plate directly");
    }

    /** A part that isn't forged itself (Glacite Jewel) still shows what it can be forged into. */
    @Test
    void aPartYouCantForgeStillShowsWhatItsUsedIn() {
        lbin.put("GEM", 1_000.0);
        lbin.put("DIAMOND", 50_000.0);
        recipe("PLATE", 300_000, "DIAMOND", 1, "GEM", 5);
        List<ForgeHit> hits = FlipSearch.forge("glacite", forgeRows(), ITEM_NAME);
        assertEquals(1, hits.size());
        assertEquals("PLATE", hits.get(0).row().route().id());
        assertEquals("GEM", hits.get(0).usedPart());
    }

    @Test
    void aSearchThatMatchesNothingFindsNothing() {
        lbin.put("GEM", 1_000.0);
        lbin.put("DIAMOND", 50_000.0);
        recipe("PLATE", 300_000, "DIAMOND", 1, "GEM", 5);
        assertTrue(FlipSearch.forge("umber", forgeRows(), ITEM_NAME).isEmpty());
        assertTrue(FlipSearch.fusions("umber", List.of(fusion("SHARD_BRAMBLE", "SHARD_FLITTER", "SHARD_HIDEONLEAF", 1)), SHARD_NAME).isEmpty());
    }
}
