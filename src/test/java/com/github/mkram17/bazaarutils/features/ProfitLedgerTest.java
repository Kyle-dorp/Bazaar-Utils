package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.ProfitLedger.Entry;
import com.github.mkram17.bazaarutils.features.ProfitLedger.Mode;
import com.github.mkram17.bazaarutils.features.ProfitLedger.Realized;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProfitLedgerTest {
    // ---- parsing ----

    @Test
    void bazaarAndAuctionTradesAreReadFromChat() {
        Entry b = ProfitLedger.parseTrade("[Bazaar] Bought 64x Enchanted Coal for 95,610 coins!", 1);
        assertTrue(b.buy);
        assertEquals("Enchanted Coal", b.item);
        assertEquals(64, b.qty);
        assertEquals(95_610, b.coins);
        assertNull(b.source, "Bazaar trades have no source tag, like everything saved before");

        Entry claim = ProfitLedger.parseTrade("[Bazaar] Claimed 2,785,121 coins from selling 42x Praying Mantis Shard at 66,312 each!", 2);
        assertFalse(claim.buy);
        assertEquals(42, claim.qty);
        assertEquals(2_785_121, claim.coins);

        Entry ahBuy = ProfitLedger.parseTrade("You purchased Hotspot Ring for 680,000 coins!", 3);
        assertTrue(ahBuy.buy);
        assertEquals("Hotspot Ring", ahBuy.item);
        assertEquals(680_000, ahBuy.coins);
        assertEquals("AH", ahBuy.source);

        Entry ahSell = ProfitLedger.parseTrade("You collected 1,458,270 coins from selling Minion Storage X-pender to [MVP+] PlaymasterXD in an auction!", 4);
        assertFalse(ahSell.buy);
        assertEquals("Minion Storage X-pender", ahSell.item);
        assertEquals(1_458_270, ahSell.coins);
        assertEquals("AH", ahSell.source);
    }

    @Test
    void npcShopPurchasesAndOtherChatAreNotTrades() {
        assertNull(ProfitLedger.parseTrade("You purchased Wool", 1), "NPC shop line has no price");
        assertNull(ProfitLedger.parseTrade("Claiming BIN auction...", 1));
        assertNull(ProfitLedger.parseTrade("BIN Auction started for God Potion!", 1));
    }

    @Test
    void namesMatchIgnoringIconsStarsAndCase() {
        assertEquals(ProfitLedger.norm(" Rough Topaz Gemstone"), ProfitLedger.norm("Rough Topaz Gemstone"));
        assertEquals(ProfitLedger.norm("Precise Juju Shortbow ✪✪✪✪✪"), ProfitLedger.norm("precise juju shortbow"));
        assertEquals("divan's drill", ProfitLedger.norm("Divan's Drill"));
        assertEquals("titanium drill dr-x455", ProfitLedger.norm("§6Titanium Drill DR-X455"));
    }

    // ---- replay ----

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, ForgePlanner.Recipe> recipes = new HashMap<>();
    private long clock;

    private Entry add(Entry e) {
        e.time = ++clock;
        entries.add(e);
        return e;
    }

    private Entry buy(String item, double qty, double coins) {
        return add(ProfitLedger.parseTrade("[Bazaar] Bought " + (long) qty + "x " + item + " for " + (long) coins + " coins!", 0));
    }

    private Entry sell(String item, double qty, double coins) {
        return add(ProfitLedger.parseTrade("[Bazaar] Sold " + (long) qty + "x " + item + " for " + (long) coins + " coins!", 0));
    }

    private Entry fusion(String output, double qty, String in1, double in1Qty, String in2, double in2Qty) {
        Entry e = new Entry();
        e.item = output;
        e.qty = qty;
        e.fusion = true;
        e.in1 = in1;
        e.in1Qty = in1Qty;
        e.in2 = in2;
        e.in2Qty = in2Qty;
        return add(e);
    }

    private void recipe(String id, double coins, Object... inputs) {
        Map<String, Double> in = new LinkedHashMap<>();
        for (int i = 0; i < inputs.length; i += 2) in.put((String) inputs[i], ((Number) inputs[i + 1]).doubleValue());
        recipes.put(id, new ForgePlanner.Recipe(id, 1, 3600, coins, in));
    }

    private final ProfitLedger.Forges forges = new ProfitLedger.Forges() {
        public String outputIdFor(String soldName) {
            return recipes.containsKey(soldName) ? soldName : null;
        }

        public ForgePlanner.Recipe recipe(String id) {
            return recipes.get(id);
        }

        public String name(String id) {
            return id;
        }
    };

    private List<Realized> realized() {
        return ProfitLedger.replay(entries, forges).realized();
    }

    private double profit(Mode mode) {
        return ProfitLedger.total(realized(), 0, mode)[0];
    }

    @Test
    void aBazaarFlipIsBookedToBazaarFlips() {
        buy("Enchanted Coal", 10, 1000);
        sell("Enchanted Coal", 10, 1500);
        assertEquals(500, profit(Mode.BAZAAR), 1e-9);
        assertEquals(500, profit(null), 1e-9);
        assertEquals(0, profit(Mode.FORGE), 1e-9);
    }

    @Test
    void anAuctionFlipIsBookedToAhFlips() {
        add(ProfitLedger.parseTrade("You purchased Hotspot Ring for 680,000 coins!", 0));
        add(ProfitLedger.parseTrade("You collected 800,000 coins from selling Hotspot Ring to [VIP] Someone in an auction!", 0));
        assertEquals(120_000, profit(Mode.AH), 1e-9);
        assertEquals(0, profit(Mode.BAZAAR), 1e-9);
    }

    @Test
    void entriesSavedBeforeAuctionTrackingCountAsBazaar() {
        Entry old = new Entry(); // as Gson loads an old save: no source field
        old.item = "Thunder Shard";
        old.buy = true;
        old.qty = 5;
        old.coins = 500;
        add(old);
        sell("Thunder Shard", 5, 700);
        assertEquals(200, profit(Mode.BAZAAR), 1e-9);
    }

    /** 6 shards bought for 600 and 4 fused from inputs costing 800, all 10 sold for 2,000: 60% / 40% of the sale. */
    @Test
    void aMixedStackSplitsItsProfitByWhereEachShardCameFrom() {
        buy("Hideoncave Shard", 6, 600);
        buy("Hideonleaf Shard", 5, 500);
        buy("Flitter Shard", 5, 300);
        fusion("Hideoncave Shard", 4, "Hideonleaf Shard", 5, "Flitter Shard", 5);
        sell("Hideoncave Shard", 10, 2000);

        assertEquals(1200 - 600, profit(Mode.BAZAAR), 1e-9);
        assertEquals(800 - 800, profit(Mode.FUSION), 1e-9);
        double[] all = ProfitLedger.total(realized(), 0, null);
        assertEquals(600, all[0], 1e-9);
        assertEquals(1, all[1], "one sale, counted once overall");
        assertEquals(1, ProfitLedger.total(realized(), 0, Mode.FUSION)[1], "and once in each mode it drew on");
    }

    /** Selling 5 of that 10 takes 3 bought (60%) and 2 fused (40%), not the bought ones first. */
    @Test
    void aPartialSaleOfAMixedStackTakesFromEachSourceInProportion() {
        buy("Hideoncave Shard", 6, 600);
        buy("Hideonleaf Shard", 5, 500);
        buy("Flitter Shard", 5, 300);
        fusion("Hideoncave Shard", 4, "Hideonleaf Shard", 5, "Flitter Shard", 5);
        sell("Hideoncave Shard", 5, 1000);

        assertEquals(600 - 300, profit(Mode.BAZAAR), 1e-9, "3 bought at 100 sold for 200 each");
        assertEquals(400 - 400, profit(Mode.FUSION), 1e-9, "2 fused at 200 sold for 200 each");
        ProfitLedger.Holding left = ProfitLedger.replay(entries, forges).holding("Hideoncave Shard");
        assertEquals(5, left.qty(), 1e-9);
        assertEquals(3, left.lots.get(Mode.BAZAAR)[0], 1e-9);
        assertEquals(2, left.lots.get(Mode.FUSION)[0], 1e-9);
    }

    @Test
    void aForgeItemSoldFromTrackedPartsIsForgeProfit() {
        recipe("PLATE", 100, "RAW", 2);
        buy("RAW", 4, 40);
        sell("PLATE", 2, 500);
        Realized r = realized().get(realized().size() - 1);
        assertEquals(Mode.FORGE, r.mode());
        assertEquals(500 - 40 - 2 * 100, r.profit(), 1e-9, "parts plus the forge's coin fee per run");
        assertFalse(r.estimated());
    }

    /** RAW was bought, GEM wasn't (mined): GEM is valued at its price when the plate sold, so the profit is an estimate. */
    @Test
    void partsYouDidntBuyAreValuedAtTheirPriceAndMarkedEstimated() {
        recipe("PLATE", 0, "RAW", 2, "GEM", 1);
        buy("RAW", 4, 40);
        Entry sale = sell("PLATE", 2, 500);
        sale.forgePrices = Map.of("GEM", 50.0);
        Realized r = realized().get(realized().size() - 1);
        assertEquals(500 - 40 - 2 * 50, r.profit(), 1e-9);
        assertTrue(r.estimated());
        assertEquals(1, ProfitLedger.total(realized(), 0, Mode.FORGE)[2], "estimated flag carried into the totals");
    }

    @Test
    void forgePartsThatAreThemselvesForgedAreBuiltFromTheirOwnParts() {
        recipe("DRILL", 0, "PLATE", 1, "PART", 1);
        recipe("PLATE", 0, "RAW", 2);
        buy("RAW", 2, 30);
        buy("PART", 1, 70);
        sell("DRILL", 1, 1000);
        assertEquals(1000 - 30 - 70, profit(Mode.FORGE), 1e-9);
    }

    @Test
    void aForgeWithAnUnpricedPartIsNotCountedAndLeavesYourStockAlone() {
        recipe("DRILL", 0, "RAW", 2, "GEM", 1);
        buy("RAW", 2, 20);
        sell("DRILL", 1, 1000); // GEM: not held, not forgeable, no price -> can't tell what the drill cost
        sell("RAW", 2, 50);     // the RAW was never used up, so this is still a 30 coin Bazaar flip
        assertEquals(0, profit(Mode.FORGE), 1e-9);
        assertEquals(0, ProfitLedger.total(realized(), 0, Mode.FORGE)[1]);
        assertEquals(30, profit(Mode.BAZAAR), 1e-9);
    }

    @Test
    void forgeItemsYouBoughtAreFlipsNotForges() {
        recipe("PLATE", 0, "RAW", 2);
        buy("PLATE", 1, 300);
        sell("PLATE", 1, 400);
        assertEquals(100, profit(Mode.BAZAAR), 1e-9);
        assertEquals(0, profit(Mode.FORGE), 1e-9);
    }

    @Test
    void sellingWhatYouNeverBoughtIsNotCounted() {
        sell("Hard Stone", 640, 9000);
        assertTrue(realized().isEmpty());
    }

    @Test
    void totalsOnlyCountSalesSinceTheGivenTime() {
        buy("Coal", 10, 100);
        sell("Coal", 5, 100);  // time 2
        sell("Coal", 5, 150);  // time 3
        assertEquals(50 + 100, ProfitLedger.total(realized(), 0, null)[0], 1e-9);
        assertEquals(100, ProfitLedger.total(realized(), 3, null)[0], 1e-9);
    }
}
