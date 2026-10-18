package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketShareTest {
    private static JsonArray levels(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    /**
     * The players you fight are the orders priced near the best one, counted by order, not by price level. Live 2026-10-17:
     * Rejuvenate V's sell offers held 19 orders within 1% of the best (0.1-coin undercuts), Magma Cream Distillate's buy
     * orders 3.
     */
    @Test
    void competitorsAreTheOrdersPricedNearTheBest() {
        JsonArray book = levels("[{\"pricePerUnit\": 100.0, \"orders\": 2}, {\"pricePerUnit\": 99.95, \"orders\": 3},"
                + " {\"pricePerUnit\": 99.5, \"orders\": 1}, {\"pricePerUnit\": 98.0, \"orders\": 5}]");
        assertEquals(6, MarketShare.competitors(book), "2 + 3 + 1 within 1% of 100; the 5 at 98 aren't fighting");
        JsonArray offers = levels("[{\"pricePerUnit\": 361257.9, \"orders\": 1}, {\"pricePerUnit\": 361258.2, \"orders\": 1},"
                + " {\"pricePerUnit\": 362000.0, \"orders\": 4}, {\"pricePerUnit\": 380000.0, \"orders\": 9}]");
        assertEquals(6, MarketShare.competitors(offers), "sell offers rise from the best: within 1% above it");
        assertEquals(0, MarketShare.competitors(new JsonArray()));
    }

    /** With n orders fighting you're on top about 1 in n + 1 of the time, kept within the range. */
    @Test
    void shareIsOneInNPlusOneWithinTheRange() {
        MarketShare s = MarketShare.fromOrderBook(new JsonObject(), 0.05, 0.10);
        assertEquals(0.10, s.against(0), 1e-12, "nobody else: the top of the range, not all of the market");
        assertEquals(0.10, s.against(9), 1e-12);
        assertEquals(1 / 13.0, s.against(12), 1e-12);
        assertEquals(0.05, s.against(19), 1e-12);
        assertEquals(0.05, s.against(300), 1e-12);
        assertEquals(s.against(0), MarketShare.fromOrderBook(null, 0.10, 0.05).against(0), 1e-12, "min and max either way round");
    }

    /** Hypixel lists buy orders under sell_summary and sell offers under buy_summary. */
    @Test
    void buyOrdersAreSellSummaryAndSellOffersBuySummary() {
        JsonObject p = new JsonObject();
        p.add("sell_summary", levels("[{\"pricePerUnit\": 100.0, \"orders\": 3}]"));
        p.add("buy_summary", levels("[{\"pricePerUnit\": 110.0, \"orders\": 9}]"));
        JsonObject products = new JsonObject();
        products.add("X", p);
        MarketShare s = MarketShare.fromOrderBook(products, 0.01, 0.5);
        assertEquals(0.25, s.buying("X"), 1e-12, "your buy order against 3 others");
        assertEquals(0.10, s.selling("X"), 1e-12, "your sell offer against 9 others");
    }

    /** The auction house, an item not in the snapshot, or no snapshot yet: the middle of the range, 7.5% for 5-10%. */
    @Test
    void marketsTheOrderBookDoesntCoverGetTheMiddle() {
        MarketShare s = MarketShare.fromOrderBook(new JsonObject(), 0.05, 0.10);
        assertEquals(0.075, s.base(), 1e-12);
        assertEquals(0.075, s.buying("NOT_ON_THE_BAZAAR"), 1e-12);
        assertEquals(0.075, s.selling(null), 1e-12);
        assertEquals(0.075, MarketShare.fromOrderBook(null, 0.05, 0.10).buying("X"), 1e-12);
        MarketShare fixed = MarketShare.fixed(0.3);
        assertEquals(0.3, fixed.buying("X"), 1e-12);
        assertEquals(0.3, fixed.base(), 1e-12);
    }
}
