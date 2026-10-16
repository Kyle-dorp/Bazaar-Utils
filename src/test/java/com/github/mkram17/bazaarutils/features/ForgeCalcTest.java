package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.ForgePlanner.Recipe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ForgeCalcTest {
    @Test
    void auctionFeesFollowTheListingTiersPlusClaimTax() {
        assertEquals(0.01, ForgeCalc.ahFeeFraction(500_000), 1e-12, "under 1M: listing fee only");
        assertEquals(0.02, ForgeCalc.ahFeeFraction(5_000_000), 1e-12, "1% listing + 1% claim");
        assertEquals(0.03, ForgeCalc.ahFeeFraction(50_000_000), 1e-12, "2% listing + 1% claim");
        assertEquals(0.035, ForgeCalc.ahFeeFraction(200_000_000), 1e-12, "2.5% listing + 1% claim");
    }

    /** One cheap or inflated listing moves the BIN a lot, so sales are valued at the lower of BIN and its 3-day average. */
    @Test
    void auctionSalesUseTheLowerOfBinAndItsAverage() {
        // the SX-R326 on 2026-10-15: 16.77M lowest BIN, 9.40M 3-day average
        assertEquals(9_399_500 * 0.98, ForgeCalc.ahSellValue(16_767_676.0, 9_399_500.0), 1e-6);
        assertEquals(9_000_000 * 0.98, ForgeCalc.ahSellValue(9_000_000.0, 12_000_000.0), 1e-6);
        assertEquals(9_000_000 * 0.98, ForgeCalc.ahSellValue(9_000_000.0, null), 1e-6, "no average: BIN alone");
        assertTrue(Double.isNaN(ForgeCalc.ahSellValue(null, 9_000_000.0)), "not listed: can't be sold");
    }

    @Test
    void forgedPetsArePricedUnderTheirLevelOneAuctionKey() {
        assertEquals("LVL_1_LEGENDARY_AMMONITE", ForgeCalc.ahKey("AMMONITE;4"));
        assertEquals("LVL_1_EPIC_GOBLIN", ForgeCalc.ahKey("GOBLIN;3"));
        assertEquals("MITHRIL_DRILL_2", ForgeCalc.ahKey("MITHRIL_DRILL_2"));
        assertEquals("PET_AMMONITE", ForgeCalc.coflnetTag("AMMONITE;4"));
        assertEquals("MITHRIL_DRILL_2", ForgeCalc.coflnetTag("MITHRIL_DRILL_2"));

        var prices = ForgeCalc.prices(new JsonObject(), Map.of("LVL_1_LEGENDARY_AMMONITE", 900_000.0), Map.of(), 0.0125, (i, p, a) -> true);
        assertEquals(900_000 * 0.99, prices.sell("AMMONITE;4"), 1e-6);
        assertEquals(900_000, prices.buy("AMMONITE;4"), 1e-6);
    }

    private static Map<String, Recipe> oneRecipe(String id, double seconds, String input) {
        return Map.of(id, new Recipe(id, 1, seconds, 0, Map.of(input, 1.0)));
    }

    /** A 30-second AH upgrade can't "earn" billions an hour: it earns its profit times how many actually sell. */
    @Test
    void auctionProfitPerHourIsCappedBySalesVolume() {
        var recipes = oneRecipe("UPGRADE", 30, "PART");
        Map<String, Double> lbin = Map.of("UPGRADE", 10_000_000.0, "PART", 5_000_000.0), avg = Map.of("UPGRADE", 10_000_000.0);
        JsonObject products = new JsonObject();
        var prices = ForgeCalc.prices(products, lbin, avg, 0.0125, (i, p, a) -> true);

        List<ForgeCalc.Row> rows = ForgeCalc.rank(recipes, prices, products, lbin, avg, id -> 0.25, 7, 1.0, 0, 0);
        assertEquals(1, rows.size());
        double profit = 10_000_000 * 0.97 - 5_000_000; // 10M is in the 2% listing tier, plus 1% claim tax
        assertEquals(profit, rows.get(0).route().profit(), 1e-6);
        assertEquals(profit * 0.25, rows.get(0).perHour(), 1e-6, "6 sales a day");
        assertTrue(rows.get(0).soldOnAh());
    }

    @Test
    void auctionItemsWithoutVolumeDataAreHiddenNotUncapped() {
        var recipes = oneRecipe("UPGRADE", 30, "PART");
        Map<String, Double> lbin = Map.of("UPGRADE", 10_000_000.0, "PART", 5_000_000.0);
        JsonObject products = new JsonObject();
        var prices = ForgeCalc.prices(products, lbin, Map.of(), 0.0125, (i, p, a) -> true);
        assertTrue(ForgeCalc.rank(recipes, prices, products, lbin, Map.of(), id -> Double.NaN, 7, 1.0, 0, 0).isEmpty(), "still loading");
        assertTrue(ForgeCalc.rank(recipes, prices, products, lbin, Map.of(), id -> 0, 7, 1.0, 0, 0).isEmpty(), "nobody buys it");
    }

    private static JsonObject bazaarProduct(double bid, double ask, double soldPerWeek) {
        return JsonParser.parseString("{\"sell_summary\":[{\"pricePerUnit\":" + bid + "}],\"buy_summary\":[{\"pricePerUnit\":" + ask
                + "}],\"quick_status\":{\"buyMovingWeek\":" + soldPerWeek + ",\"sellMovingWeek\":" + soldPerWeek + "}}").getAsJsonObject();
    }

    @Test
    void bazaarProfitPerHourIsCappedByWeeklySellVolume() {
        var recipes = oneRecipe("REFINED", 3600, "RAW");
        JsonObject products = new JsonObject();
        products.add("REFINED", bazaarProduct(150, 200, 168)); // the market takes 1 an hour
        products.add("RAW", bazaarProduct(100, 110, 100_000));
        var prices = ForgeCalc.prices(products, Map.of(), Map.of(), 0.0125, (i, p, a) -> true);

        List<ForgeCalc.Row> rows = ForgeCalc.rank(recipes, prices, products, Map.of(), Map.of(), id -> Double.NaN, 7, 1.0, 0, 0);
        double profit = 200 * (1 - 0.0125) - 100; // sell offer after tax, input bought with a buy order
        assertEquals(profit, rows.get(0).route().profit(), 1e-9);
        assertEquals(profit * 1, rows.get(0).perHour(), 1e-9, "7 slots could make 7 an hour, the market takes 1");
        assertFalse(rows.get(0).soldOnAh());
    }

    @Test
    void bundledRecipesLoadWithSecondsCoinsAndNames() {
        Map<String, Recipe> recipes = ForgeCalc.recipes();
        assertTrue(recipes.size() >= 100, "expected the full forge recipe list, got " + recipes.size());

        Recipe drill = recipes.get("MITHRIL_DRILL_2");
        assertEquals(30, drill.seconds(), 1e-9, "drill upgrades forge in 30 seconds");
        assertEquals(Map.of("GOLDEN_PLATE", 1.0, "MITHRIL_DRILL_1", 1.0, "MITHRIL_PLATE", 1.0), drill.inputs());

        assertEquals(6 * 3600, recipes.get("REFINED_MITHRIL").seconds(), 1e-9);
        assertEquals(50_000_000, recipes.get("DIVAN_DRILL").coins(), 1e-9, "coins are a cost, not an input item");
        assertFalse(recipes.get("DIVAN_DRILL").inputs().containsKey("SKYBLOCK_COIN"));

        assertEquals("Mithril Drill SX-R326", ForgeCalc.name("MITHRIL_DRILL_2"));
        assertEquals("Divan's Drill", ForgeCalc.name("DIVAN_DRILL"));
    }
}
