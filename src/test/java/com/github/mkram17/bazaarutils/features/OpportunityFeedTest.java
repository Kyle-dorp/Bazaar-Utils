package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.Opportunity.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpportunityFeedTest {
    private static final MarketShare FULL = MarketShare.fixed(1);

    /** BazaarFlip keeps two top lists (by percentage, by money); an item in both is one option, not two. */
    @Test
    void anItemInBothOfBazaarFlipsTopListsCountsOnce() {
        BazaarFlipRows.Row x = new BazaarFlipRows.Row("X", 100, 130, 28, 28, 28 * 50, 7_168_000);
        BazaarFlipRows.Row y = new BazaarFlipRows.Row("Y", 10, 20, 9, 90, 9 * 10, 716_800);
        BazaarFlipRows.Row losing = new BazaarFlipRows.Row("L", 100, 90, -10, -10, -100, 7_168_000);
        List<Opportunity> out = OpportunityFeed.flips(List.of(x, y, x, losing), false, id -> Double.NaN, String::toLowerCase, 1, FULL);
        assertEquals(List.of("X", "Y"), out.stream().map(Opportunity::id).toList());
        assertEquals("x", out.get(0).name());
        assertEquals(50, out.get(0).maxPerHour(), 1e-9, "items per hour from BazaarFlip's total profit over profit per item");
        assertTrue(out.stream().allMatch(o -> o.kind() == Kind.FLIP));
        assertEquals(Kind.DIP, OpportunityFeed.flips(List.of(x), true, id -> Double.NaN, id -> id, 1, FULL).get(0).kind(),
                "in dip mode BazaarFlip's rows are dips");
        // a flip's buy price 30% under its 7-day normal is suspect: the normal comes from the history for that item
        Opportunity suspect = OpportunityFeed.flips(List.of(x), false, id -> id.equals("X") ? 143 : Double.NaN, id -> id, 1, FULL).get(0);
        assertTrue(suspect.confidence() < out.get(0).confidence(), suspect.doubts().toString());
    }

    /** Each calculator's rows become options of its own kind; rows that can't be planned with are dropped. */
    @Test
    void everyListBecomesOptionsOfItsKind() {
        List<Opportunity> books = OpportunityFeed.books(List.of(
                new BookCraft.Craft("ENCHANTMENT_X_5", "X V", 1000, 100, 5000, "X I", 16, 62.5, 2100, 1000),
                new BookCraft.Craft("ENCHANTMENT_Y_5", "Y V", -5, -1, -50, "Y I", 16, 62.5, 2100, 1000)), 1, FULL);
        assertEquals(1, books.size());
        assertEquals(Kind.BOOK, books.get(0).kind());
        assertEquals(15, books.get(0).labour(), 1e-12);

        List<Opportunity> fusions = OpportunityFeed.fusions(List.of(new FusionCalc.Fusion("Combat", "A", 2, 100, "B", 3, 100, "OUT", 2,
                400, 500, 200, 2000)), id -> "n:" + id, 1, FULL);
        assertEquals(Kind.FUSION, fusions.get(0).kind());
        assertEquals("2x n:OUT", fusions.get(0).name());

        ForgeCalc.Row row = OpportunityAdvisorTest.forgeRow(3_000_000, 2_000_000, 4, 0.5, 0.5, false, Double.NaN, Double.NaN);
        List<Opportunity> forges = OpportunityFeed.forges(List.of(row), id -> "Drill " + id, 2, FULL, id -> Double.NaN);
        assertEquals(Kind.FORGE, forges.get(0).kind());
        assertEquals("Drill DRILL", forges.get(0).name());
        assertTrue(forges.get(0).passive());
    }
}
