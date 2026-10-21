package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

class MarketShiftTest {
    private static final long H = 3_600_000L;
    private static final DateTimeFormatter COFLNET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * A Coflnet week history: 82 two-hour buckets, newest first (as Coflnet sends them), the newest {@code lagHours} before
     * {@code now}. Bucket i counts back from the newest (i = 0 is newest). bid = Coflnet "sell", ask = Coflnet "buy".
     */
    private static JsonArray history(long now, double lagHours, IntToDoubleFunction bid, IntToDoubleFunction ask,
                                     IntToDoubleFunction buyWeek, IntToDoubleFunction sellWeek) {
        JsonArray a = new JsonArray();
        long newest = now - (long) (lagHours * H);
        for (int i = 0; i < 82; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("buy", ask.applyAsDouble(i));
            o.addProperty("sell", bid.applyAsDouble(i));
            o.addProperty("buyMovingWeek", buyWeek.applyAsDouble(i));
            o.addProperty("sellMovingWeek", sellWeek.applyAsDouble(i));
            o.addProperty("timestamp", COFLNET.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(newest - i * 2 * H), ZoneOffset.UTC)));
            a.add(o);
        }
        return a;
    }

    private static JsonArray prices(IntToDoubleFunction bid, IntToDoubleFunction ask) {
        return history(System.currentTimeMillis(), 4, bid, ask, i -> 7000, i -> 7000);
    }

    /** Bid and ask both at {@code factor} times normal for the newest {@code buckets} buckets, normal (100 / 110) before. */
    private static MarketShift moved(int buckets, double bidFactor, double askFactor) {
        return MarketShift.from(prices(i -> i < buckets ? 100 * bidFactor : 100, i -> i < buckets ? 110 * askFactor : 110));
    }

    // ---- the 7-day medians are BaseProfit's, so nothing changes without a shift ----

    @Test
    void weekMediansMatchBaseProfitsUpperMedianOverEveryPrice() {
        Random r = new Random(7);
        double[] bids = new double[82], asks = new double[82];
        for (int i = 0; i < 82; i++) {
            bids[i] = 50 + r.nextInt(100);
            asks[i] = 60 + r.nextInt(100);
        }
        JsonArray a = prices(i -> bids[i], i -> asks[i]);
        // an entry without a timestamp still counts toward the 7-day medians, as in BaseProfit
        JsonObject noTime = new JsonObject();
        noTime.addProperty("buy", 1000);
        noTime.addProperty("sell", 1000);
        a.add(noTime);
        List<Double> b = new ArrayList<>(), k = new ArrayList<>();
        for (double v : bids) b.add(v);
        for (double v : asks) k.add(v);
        b.add(1000.0);
        k.add(1000.0);
        b.sort(null);
        k.sort(null);
        MarketShift s = MarketShift.from(a);
        assertEquals(b.get(b.size() / 2), s.weekBid());
        assertEquals(k.get(k.size() / 2), s.weekAsk());
    }

    @Test
    void tooFewPricesIsNoHistory() {
        JsonArray a = new JsonArray();
        for (int i = 0; i < 5; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("buy", 10);
            o.addProperty("sell", 9);
            o.addProperty("timestamp", "2026-10-17T22:00:00");
            a.add(o);
        }
        assertNull(MarketShift.from(a), "BaseProfit needs 6 prices a side");
    }

    @Test
    void withoutAConfirmedShiftTheBandIsExactlyTheOldSevenDayBand() {
        Random r = new Random(3);
        for (int n = 0; n < 200; n++) {
            double[] bids = new double[82], asks = new double[82];
            for (int i = 0; i < 82; i++) {
                bids[i] = 90 + r.nextInt(20);
                asks[i] = 100 + r.nextInt(20);
            }
            MarketShift s = MarketShift.from(prices(i -> bids[i], i -> asks[i]));
            if (s.confirmed()) continue;
            double tol = 0.2, buy = 60 + r.nextInt(80), sell = 70 + r.nextInt(90);
            boolean old = sell <= s.weekAsk() * (1 + tol) && buy >= s.weekBid() * (1 - tol);
            assertEquals(old, s.bandOk(buy, sell, tol));
            assertEquals(sell <= s.weekAsk() * (1 + tol), s.priceOk(sell, true, tol));
            assertEquals(buy >= s.weekBid() * (1 - tol), s.priceOk(buy, false, tol));
        }
    }

    // ---- price ----

    @Test
    void aDayAtANewLevelOnBothSidesIsAConfirmedShift() {
        MarketShift down = moved(12, 0.7, 0.7);
        assertTrue(down.confirmed());
        assertEquals(-0.3, down.bidChange(), 1e-9);
        assertTrue(moved(12, 1.3, 1.25).confirmed());
        assertFalse(moved(12, 0.95, 0.95).confirmed(), "5% is noise, not a shift");
        assertFalse(moved(12, 0.85, 0.95).confirmed(), "buy orders -15% but sell offers only -5%");
        assertFalse(moved(12, 0.95, 0.85).confirmed(), "and the other way round");
    }

    @Test
    void oneSideMovingIsABuyoutOrADumpNotAShift() {
        assertFalse(moved(12, 1.0, 1.6).confirmed(), "sell offers up alone: a buyout");
        assertFalse(moved(12, 0.5, 1.0).confirmed(), "buy orders down alone: a dump");
        assertFalse(moved(12, 0.7, 1.3).confirmed(), "opposite ways: the spread blew open");
        // Shark Fin on 2026-10-21: sell offers +542%, buy orders +32% over the day
        assertFalse(moved(12, 1.32, 6.42).confirmed(), "a buyout that dragged buy orders up a little");
        assertTrue(moved(12, 0.79, 0.72).confirmed(), "Fine Flour the same day: -21% / -28%");
    }

    @Test
    void aMoveOnlyCountsOnceItHeldForHalfTheDay() {
        assertFalse(moved(5, 0.6, 0.6).confirmed(), "10 hours of 24: the median hasn't moved");
        assertTrue(moved(7, 0.6, 0.6).confirmed(), "14 hours of 24");
    }

    @Test
    void theNewLowerNormalIsNotADipButAFurtherDropIs() {
        MarketShift s = moved(12, 0.6, 0.6); // buy orders 60, sell offers 66 for the last day; 100 / 110 before
        assertEquals(60, s.normalBid(), 1e-9);
        assertFalse(s.isDip(60, 66, 30, 0.2), "the price it has sat at all day");
        assertEquals(0, s.dipPercent(60), 1e-9);
        assertTrue(s.isDip(40, 66, 30, 0.2), "a third below the new normal");
        // the old rule called the new normal a 40% dip
        assertTrue(60 <= s.weekBid() * (1 - 0.30));
    }

    @Test
    void aRiseKeepsTheLowerSevenDayNormalForDips() {
        MarketShift s = moved(12, 1.5, 1.5);
        assertEquals(s.weekBid(), s.normalBid(), 1e-9);
    }

    @Test
    void aFreshDropIsStillADip() {
        MarketShift s = moved(2, 0.5, 1.0); // newest 4 hours only
        assertTrue(s.isDip(50, 110, 30, 0.2));
    }

    @Test
    void aOneSidedLevelThatHeldAllDayIsNotADipEither() {
        // buy orders sat at 60 all day while sell offers stayed: not confirmed, but still the price it has traded at
        MarketShift s = moved(12, 0.6, 1.0);
        assertFalse(s.confirmed());
        assertFalse(s.isDip(60, 110, 30, 0.2));
    }

    @Test
    void aConfirmedShiftPassesTheManipulationBandAtItsNewLevel() {
        MarketShift down = moved(12, 0.6, 0.6);
        assertFalse(60 >= down.weekBid() * 0.8, "outside the old 20% band");
        assertTrue(down.bandOk(60, 66, 0.2));
        assertTrue(down.priceOk(60, false, 0.2));
        assertTrue(down.priceOk(66, true, 0.2));
        MarketShift up = moved(12, 1.5, 1.5);
        assertTrue(up.bandOk(150, 165, 0.2));
        assertTrue(up.priceOk(165, true, 0.2));
        // the old level still passes too
        assertTrue(up.bandOk(100, 110, 0.2));
    }

    @Test
    void aBuyoutStillFailsTheBand() {
        MarketShift s = moved(12, 1.0, 1.6);
        assertFalse(s.bandOk(100, 176, 0.2));
        assertFalse(s.priceOk(176, true, 0.2));
        assertFalse(s.isDip(60, 176, 30, 0.2), "a dip with an unbelievable sell price");
    }

    @Test
    void tooLittleHistoryForADayComparisonFallsBackToSevenDays() {
        JsonArray a = new JsonArray();
        for (int i = 0; i < 10; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("buy", i < 6 ? 66 : 110);
            o.addProperty("sell", i < 6 ? 60 : 100);
            o.addProperty("timestamp", LocalDateTime.of(2026, 10, 17, 22, 0).minusHours(2L * i).toString());
            a.add(o);
        }
        MarketShift s = MarketShift.from(a);
        assertFalse(s.hasRecent());
        assertFalse(s.confirmed());
        assertEquals(s.weekBid(), s.normalBid());
    }

    @Test
    void readsCoflnetTimestampsAsUtc() {
        long now = Instant.parse("2026-10-18T03:30:00Z").toEpochMilli();
        JsonArray a = history(now, 5.5, i -> 1, i -> 1, i -> 1000 - i, i -> 1000 - i);
        assertEquals("2026-10-17T22:00:00", a.get(0).getAsJsonObject().get("timestamp").getAsString());
        JsonObject live = a.get(0).getAsJsonObject().deepCopy(); // the hour endpoint's style, with fractions of a second
        live.addProperty("timestamp", "2026-10-17T22:30:00.82");
        live.addProperty("buyMovingWeek", 2000);
        a.add(live);
        MarketShift s = MarketShift.from(a);
        assertEquals(1000, s.movingWeekAt(true, Instant.parse("2026-10-17T22:00:00Z").toEpochMilli()), 1e-9);
        assertEquals(999.5, s.movingWeekAt(true, Instant.parse("2026-10-17T21:00:00Z").toEpochMilli()), 1e-9);
        assertEquals(2000, s.movingWeekAt(true, Instant.parse("2026-10-17T22:30:00.820Z").toEpochMilli()), 1e-9);
    }

    // ---- volume ----

    @Test
    void movingWeekIsInterpolatedAndUnknownOutsideTheHistory() {
        long now = System.currentTimeMillis() / 1000 * 1000; // Coflnet timestamps are whole seconds
        MarketShift s = MarketShift.from(history(now, 4, i -> 1, i -> 1, i -> 1000 - 10 * i, i -> 50));
        long newest = now - 4 * H;
        assertEquals(1000, s.movingWeekAt(true, newest), 1e-9);
        assertEquals(995, s.movingWeekAt(true, newest - H), 1e-9);
        assertEquals(50, s.movingWeekAt(false, newest - 7 * H), 1e-9);
        assertTrue(Double.isNaN(s.movingWeekAt(true, newest + 1)), "after the newest bucket");
        assertTrue(Double.isNaN(s.movingWeekAt(true, newest - 200 * H)), "before the oldest");
    }

    @Test
    void steadyVolumeKeepsTheWeeklyRate() {
        assertEquals(7000 / 168.0, MarketShift.perHour(7000, 7000), 1e-9);
    }

    @Test
    void aProvableSurgeRaisesTheRate() {
        // the 7-day total rose 3000 in a day: the last day sold at least 3000 more than the day that left the window
        assertEquals(3000 / 24.0, MarketShift.perHour(10_000, 7000), 1e-9);
    }

    @Test
    void aFallingTotalNeverLowersTheRate() {
        // can't tell a slump from a busy day leaving the window
        assertEquals(4000 / 168.0, MarketShift.perHour(4000, 7000), 1e-9);
    }

    @Test
    void withoutTheOlderTotalItIsTheWeeklyRate() {
        assertEquals(7000 / 168.0, MarketShift.perHour(7000, Double.NaN), 1e-9);
        assertEquals(0, MarketShift.perHour(0, 5000), 1e-9);
    }

    @Test
    void theRateNeverOverstatesTheLastDay() {
        Random r = new Random(11);
        for (int n = 0; n < 2000; n++) {
            double[] day = new double[8]; // day[0] = last 24 hours, day[7] = the day that just left the window
            for (int i = 0; i < 8; i++) day[i] = r.nextInt(4) == 0 ? r.nextInt(100_000) : r.nextInt(5000);
            double now = 0, ago = 0;
            for (int i = 0; i < 7; i++) now += day[i];
            for (int i = 1; i < 8; i++) ago += day[i];
            double rate = MarketShift.perHour(now, ago);
            assertTrue(rate <= Math.max(now / 168.0, day[0] / 24.0) + 1e-9, "overstated at " + n);
            assertTrue(rate >= now / 168.0 - 1e-9);
        }
    }

    @Test
    void perHourFromAQuickStatusUsesTheItemsHistoryAndSide() {
        long now = System.currentTimeMillis();
        // 7-day totals 24 hours ago: 7000 instant buys, 1000 instant sells
        MarketShift.put("TEST_SURGE", MarketShift.from(history(now, 4, i -> 100, i -> 110, i -> 7000, i -> 1000)));
        JsonObject qs = new JsonObject();
        qs.addProperty("productId", "TEST_SURGE");
        qs.addProperty("buyMovingWeek", 10_000);
        qs.addProperty("sellMovingWeek", 7000);
        assertEquals(3000 / 24.0, MarketShift.perHour(qs, "buyMovingWeek"), 1e-6, "buys: 3000 more than a week ago");
        assertEquals(6000 / 24.0, MarketShift.perHour(qs, "sellMovingWeek"), 1e-6, "sells: 6000 more");
        qs.addProperty("productId", "TEST_NO_HISTORY");
        assertEquals(10_000 / 168.0, MarketShift.perHour(qs, "buyMovingWeek"), 1e-9, "no history: exactly the weekly rate");
        assertEquals(0, MarketShift.perHour(null, "buyMovingWeek"));
        MarketShift.put("TEST_SURGE", null);
        assertNull(MarketShift.of("TEST_SURGE"));
    }

    @Test
    void flipSpeedupIsTheSlowerSidesGain() {
        long now = System.currentTimeMillis();
        MarketShift s = MarketShift.from(history(now, 4, i -> 100, i -> 110, i -> 7000, i -> 1400));
        // sell side (slower) 1400 -> 2800: weekly 2800/168, provable 1400/24 = 3.5x
        assertEquals(1400 / 24.0 / (2800 / 168.0), s.flipSpeedup(10_000, 2800, now), 1e-6);
        assertEquals(1.0, s.flipSpeedup(7000, 1400, now), 1e-9);
    }

    @Test
    void badges() {
        MarketShift.put("TEST_BADGE", moved(12, 0.7, 0.7));
        assertEquals(" §e[24h -30%]", MarketShift.badge("TEST_BADGE", null));
        long now = System.currentTimeMillis();
        MarketShift.put("TEST_BADGE", MarketShift.from(history(now, 4, i -> 100, i -> 110, i -> 7000, i -> 7000)));
        JsonObject qs = new JsonObject();
        qs.addProperty("buyMovingWeek", 14_000);
        qs.addProperty("sellMovingWeek", 14_000);
        assertEquals(" §e[24h vol x3.5]", MarketShift.badge("TEST_BADGE", qs)); // 7000/24 over 14000/168
        qs.addProperty("buyMovingWeek", 7000);
        assertEquals("", MarketShift.badge("TEST_BADGE", qs));
        assertEquals("", MarketShift.badge("TEST_NONE", qs));
        MarketShift.put("TEST_BADGE", null);
    }
}
