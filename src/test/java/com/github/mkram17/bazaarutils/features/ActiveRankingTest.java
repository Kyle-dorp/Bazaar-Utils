package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ActiveRankingTest {
    private Opportunity option(String id, double profit, double cost, double rate) {
        return new Opportunity(Opportunity.Kind.FLIP, id, id, profit, cost, rate, 0.01, 0, 1, List.of(), "buy then sell", 0.1, 0);
    }

    @Test
    void ranksBatchProfitNotUnitProfitOrWholeMarketProfit() {
        var picks = ActiveRanking.rank(List.of(option("slow", 10000, 100000, 4),
                option("liquid", 100, 1000, 10000)), 1000000, 30, 150);
        assertEquals("liquid", picks.getFirst().opportunity().id());
        assertEquals(250, picks.getFirst().quantity());
        assertEquals(25000, picks.getFirst().estimatedProfit());
    }

    @Test
    void reserveAndUnknownPurseFailClosed() {
        assertEquals(900, ActiveRanking.budget(1000, 10));
        assertEquals(0, ActiveRanking.budget(Double.NaN, 10));
        assertEquals(0, ActiveRanking.budget(-1, 10));
        assertEquals(0, ActiveRanking.budget(Double.POSITIVE_INFINITY, 10));
        assertEquals(0, ActiveRanking.budget(1000, Double.NaN));
        assertTrue(ActiveRanking.rank(List.of(option("x", 10, 100, 100)), 0, 30, 150).isEmpty());
    }

    @Test
    void bucketIgnoresSmallPurseChanges() {
        assertEquals(13_000_000, ActiveRanking.bucket(13_540_864), 1e-6);
        assertEquals(ActiveRanking.bucket(13_540_864), ActiveRanking.bucket(13_541_000));
        assertEquals(950, ActiveRanking.bucket(957), 1e-9);
        assertEquals(0, ActiveRanking.bucket(Double.NaN));
        assertEquals(0, ActiveRanking.bucket(0));
    }

    @Test
    void budgetsEachAlternativeAtQuarterOfSpendablePurse() {
        var pick = ActiveRanking.rank(List.of(option("x", 10, 100, 10000)), 10000, 30, 150).getFirst();
        assertEquals(25, pick.quantity());
        assertEquals(2500, pick.cost());
    }

    @Test
    void requiresWholeUnitsToCompleteBothSidesWithinTarget() {
        assertTrue(ActiveRanking.rank(List.of(option("slow", 100000, 1, 1)), 1000000, 30, 150).isEmpty());
        var pick = ActiveRanking.rank(List.of(option("ok", 10, 1, 16)), 1000000, 30, 150).getFirst();
        assertEquals(4, pick.quantity());
        assertEquals(0.5, pick.hours(), 1e-9);
    }

    @Test
    void confidenceAndManualWorkReduceRanking() {
        var book = new Opportunity(Opportunity.Kind.BOOK, "book", "Book", 100, 1, 10000,
                0.01, 0, 0.5, List.of(), "combine", 0.1, 15);
        var pick = ActiveRanking.rank(List.of(book), 1000000, 30, 150).getFirst();
        assertEquals(4, pick.quantity());
        assertEquals(200, pick.estimatedProfit());
        assertTrue(pick.hours() <= 0.5);
    }

    @Test
    void keepsOnlyBestBatchForEachOutputAndMethod() {
        var picks = ActiveRanking.rank(List.of(option("same", 10, 100, 100), option("same", 20, 100, 100)),
                1000000, 30, 150);
        assertEquals(1, picks.size());
        assertEquals(500, picks.getFirst().estimatedProfit());
    }

    @Test
    void respectsOrderQuantityCapAndConfidence() {
        var pick = ActiveRanking.rank(List.of(option("x", 1, 1, 10000000)), 10000000, 30, 150).getFirst();
        assertEquals(71680, pick.quantity());
        var uncertain = new Opportunity(Opportunity.Kind.FLIP, "risk", "Risk", 100, 1, 100,
                0.01, 0, 0.1, List.of("uncertain"), "buy", 0.1, 0);
        assertEquals(250, ActiveRanking.rank(List.of(uncertain), 1000000, 30, 150).getFirst().estimatedProfit());
    }

    @Test
    void rejectsInvalidInputsAndExcludesForge() {
        var forge = new Opportunity(Opportunity.Kind.FORGE, "f", "Forge", 100, 1, 1000,
                1, 1, 1, List.of(), "forge");
        assertTrue(ActiveRanking.rank(List.of(forge, option("nan", Double.NaN, 1, 100),
                option("bad", 10, 0, 100)), 1000, 30, 150).isEmpty());
        assertTrue(ActiveRanking.rank(List.of(option("x", 10, 1, 100)), Double.NaN, 30, 150).isEmpty());
    }
}
