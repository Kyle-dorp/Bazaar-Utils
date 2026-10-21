package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last 24 hours of one Bazaar item against its 7 days, from the Coflnet week history BaseProfit already downloads (2-hour
 * buckets, newest about 4-6 hours behind): no request of its own. Mayor perks, events and updates move a market for hours or
 * days, and a check that only knows the 7-day median calls the new level a dip or a manipulation.
 *
 * <p>Price: medians of the newest 24 hours of buckets and of the buckets before them. A median only moves once at least half
 * the buckets have, so the newest 24 hours count as a new level after about 12 hours there. A buyout or a dump moves one side
 * of the order book; a real change in supply or demand moves both, so a shift is only {@link #confirmed()} when the buy-order
 * price and the sell-offer price both moved at least {@link #SHIFT} the same way.
 *
 * <p>Volume: Hypixel only publishes 7-day moving totals. M(now) - M(24h ago) is the last 24 hours minus the 24 hours that just
 * left the window, so it never overstates the last 24 hours ({@link #perHour(double, double)}). It can understate them (a
 * busy day leaving the window), and a slump can't be proven from these totals at all, so the rate only ever goes up from the
 * weekly average, never down.
 *
 * <p>Naming: bid = top buy order (Coflnet "sell", what a buy order pays, BaseProfit's medianSell), ask = lowest sell offer
 * (Coflnet "buy", what a sell offer gets, BaseProfit's medianBuy).
 */
public final class MarketShift {
    static final long HOUR_MS = 3_600_000L;
    static final long RECENT_MS = 24 * HOUR_MS;
    /** Both sides of the book have to move this much (10%) for the newest 24 hours to count as a new level. */
    static final double SHIFT = 0.10;
    /** ...and by about as much: the sell-offer / buy-order ratio may change by at most 25%. */
    static final double SPREAD_DRIFT = 0.25;
    /** Buckets needed: 8 of the newest 12 (2-hour buckets), and a day of older ones to compare with. */
    static final int MIN_RECENT = 8, MIN_OLDER = 12;
    /** Same as BaseProfit's rule for usable history. */
    static final int MIN_PRICES = 6;

    private final double weekBid, weekAsk, recentBid, recentAsk, olderBid, olderAsk;
    private final long[] times; // ascending
    private final double[] buyWeek, sellWeek; // buyMovingWeek, sellMovingWeek at each time (NaN when missing)

    MarketShift(double weekBid, double weekAsk, double recentBid, double recentAsk, double olderBid, double olderAsk,
                long[] times, double[] buyWeek, double[] sellWeek) {
        this.weekBid = weekBid;
        this.weekAsk = weekAsk;
        this.recentBid = recentBid;
        this.recentAsk = recentAsk;
        this.olderBid = olderBid;
        this.olderAsk = olderAsk;
        this.times = times;
        this.buyWeek = buyWeek;
        this.sellWeek = sellWeek;
    }

    private record Bucket(long time, double ask, double bid, double buyWeek, double sellWeek) { }

    /** Reads a Coflnet /history/week array; null when it has too few prices (BaseProfit treats that as failed history). */
    public static MarketShift from(JsonArray history) {
        List<Bucket> buckets = new ArrayList<>();
        List<Double> asks = new ArrayList<>(), bids = new ArrayList<>(); // every price, timestamp or not, as BaseProfit counts them
        for (JsonElement e : history) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            double ask = number(o, "buy"), bid = number(o, "sell");
            if (ask > 0) asks.add(ask);
            if (bid > 0) bids.add(bid);
            long t = time(o);
            if (t != Long.MIN_VALUE) buckets.add(new Bucket(t, ask, bid, number(o, "buyMovingWeek"), number(o, "sellMovingWeek")));
        }
        if (asks.size() < MIN_PRICES || bids.size() < MIN_PRICES) return null;
        buckets.sort((a, b) -> Long.compare(a.time(), b.time()));
        long newest = buckets.isEmpty() ? 0 : buckets.get(buckets.size() - 1).time();
        List<Double> recentAsks = new ArrayList<>(), recentBids = new ArrayList<>(), olderAsks = new ArrayList<>(), olderBids = new ArrayList<>();
        for (Bucket b : buckets) {
            boolean recent = b.time() > newest - RECENT_MS;
            if (b.ask() > 0) (recent ? recentAsks : olderAsks).add(b.ask());
            if (b.bid() > 0) (recent ? recentBids : olderBids).add(b.bid());
        }
        boolean enough = recentAsks.size() >= MIN_RECENT && recentBids.size() >= MIN_RECENT
                && olderAsks.size() >= MIN_OLDER && olderBids.size() >= MIN_OLDER;
        long[] times = new long[buckets.size()];
        double[] buy = new double[buckets.size()], sell = new double[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            times[i] = buckets.get(i).time();
            buy[i] = buckets.get(i).buyWeek();
            sell[i] = buckets.get(i).sellWeek();
        }
        return new MarketShift(median(bids), median(asks),
                enough ? median(recentBids) : Double.NaN, enough ? median(recentAsks) : Double.NaN,
                enough ? median(olderBids) : Double.NaN, enough ? median(olderAsks) : Double.NaN, times, buy, sell);
    }

    /** BaseProfit's median: the upper middle value. */
    static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        return v.get(v.size() / 2);
    }

    private static double number(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Coflnet timestamps are UTC without a zone, e.g. 2026-10-17T22:00:00 or 2026-10-18T03:29:42.82. */
    private static long time(JsonObject o) {
        try {
            return LocalDateTime.parse(o.get("timestamp").getAsString()).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    // ---- price ----

    /** The 7-day median buy-order price (BaseProfit's medianSell). */
    public double weekBid() {
        return weekBid;
    }

    /** The 7-day median sell-offer price (BaseProfit's medianBuy). */
    public double weekAsk() {
        return weekAsk;
    }

    /** True when there are enough buckets to compare the newest 24 hours with the days before. */
    public boolean hasRecent() {
        return !Double.isNaN(recentBid);
    }

    /** How far the newest 24 hours' buy-order price is from the days before, as a fraction (-0.2 = 20% lower); NaN if unknown. */
    public double bidChange() {
        return hasRecent() && olderBid > 0 ? recentBid / olderBid - 1 : Double.NaN;
    }

    /** The same for the sell-offer price. */
    public double askChange() {
        return hasRecent() && olderAsk > 0 ? recentAsk / olderAsk - 1 : Double.NaN;
    }

    /**
     * Both sides of the book moved at least {@link #SHIFT} the same way, by about as much (the spread changed by under
     * {@link #SPREAD_DRIFT}), and stayed there for most of the last 24 hours. Sell offers bought out to 6x with buy orders up
     * 30% is a buyout, not a new level.
     */
    public boolean confirmed() {
        double b = bidChange(), a = askChange();
        if (Double.isNaN(b) || Double.isNaN(a)) return false;
        double spread = (1 + a) / (1 + b);
        if (!(spread <= 1 + SPREAD_DRIFT && spread >= 1 / (1 + SPREAD_DRIFT))) return false;
        return b >= SHIFT && a >= SHIFT || b <= -SHIFT && a <= -SHIFT;
    }

    /**
     * What a buy order normally pays: the lower of the 7-day and the newest-24-hour medians. A price the market has sat at for
     * most of a day is the new normal, not a dip; a market that rose is still measured against the lower 7-day level.
     */
    public double normalBid() {
        return hasRecent() ? Math.min(weekBid, recentBid) : weekBid;
    }

    /** How far below normal (see {@link #normalBid()}) a buy price is, in percent; NaN without a normal. */
    public double dipPercent(double buyPrice) {
        double n = normalBid();
        return n > 0 ? (n - buyPrice) / n * 100.0 : Double.NaN;
    }

    /**
     * Dip mode: the buy price is at least minDipPercent under normal and the sell price is believable (within tol of the 7-day
     * sell-offer median, or of the newest 24 hours' when the shift is confirmed).
     */
    public boolean isDip(double buyPrice, double sellPrice, double minDipPercent, double tol) {
        return buyPrice <= normalBid() * (1 - minDipPercent / 100.0)
                && (sellPrice <= weekAsk * (1 + tol) || confirmed() && sellPrice <= recentAsk * (1 + tol));
    }

    /**
     * The "max vs 7-day average" band for a flip: buy price (top buy order) and sell price (lowest sell offer) within tol of the
     * 7-day medians, or, once the shift is confirmed, within tol of the newest 24 hours' medians.
     */
    public boolean bandOk(double buyPrice, double sellPrice, double tol) {
        if (sellPrice <= weekAsk * (1 + tol) && buyPrice >= weekBid * (1 - tol)) return true;
        return confirmed() && sellPrice <= recentAsk * (1 + tol) && buyPrice >= recentBid * (1 - tol);
    }

    /** The band for one price (book crafts, fusions, forge parts): isAsk for a price you sell at, else one you pay. */
    public boolean priceOk(double price, boolean isAsk, double tol) {
        if (isAsk ? price <= weekAsk * (1 + tol) : price >= weekBid * (1 - tol)) return true;
        return confirmed() && (isAsk ? price <= recentAsk * (1 + tol) : price >= recentBid * (1 - tol));
    }

    // ---- volume ----

    /** A side's 7-day moving total at time t, interpolated between buckets; NaN outside the history or with a gap in it. */
    public double movingWeekAt(boolean buySide, long t) {
        double[] v = buySide ? buyWeek : sellWeek;
        if (times.length == 0 || t < times[0] || t > times[times.length - 1]) return Double.NaN;
        int i = Arrays.binarySearch(times, t);
        if (i >= 0) return v[i];
        int hi = -i - 1, lo = hi - 1;
        double a = v[lo], b = v[hi];
        if (Double.isNaN(a) || Double.isNaN(b)) return Double.NaN;
        return a + (b - a) * (t - times[lo]) / (double) (times[hi] - times[lo]);
    }

    /**
     * Items per hour on one side: the weekly average (movingWeekNow / 168), or the last 24 hours' provable rate when that is
     * higher. movingWeekNow - movingWeek24hAgo = last 24h - the day 8 days ago &lt;= last 24h, so the result never overstates
     * the last 24 hours. Without the older total it is the weekly average, as before.
     */
    public static double perHour(double movingWeekNow, double movingWeek24hAgo) {
        double weekly = movingWeekNow / 168.0;
        if (!(movingWeekNow > 0) || !(movingWeek24hAgo >= 0)) return weekly;
        return Math.max(weekly, (movingWeekNow - movingWeek24hAgo) / 24.0);
    }

    // ---- per-item cache (filled by BaseProfit when it downloads an item's week history) ----

    private static final Map<String, MarketShift> CACHE = new ConcurrentHashMap<>();

    /** The Mayor flipper: off, nothing is built or kept and every caller gets the 7-day behaviour. */
    static boolean enabled() {
        return Flippers.on(Flippers.Flipper.MAYOR);
    }

    /** Called by BaseProfit with each week history it downloads. */
    public static void store(String id, JsonArray history) {
        if (!enabled()) {
            CACHE.clear();
            return;
        }
        put(id, from(history));
    }

    public static void put(String id, MarketShift shift) {
        if (id == null) return;
        if (shift == null) CACHE.remove(id);
        else CACHE.put(id, shift);
    }

    /** The item's last downloaded history, or null if none. Never downloads. */
    public static MarketShift of(String id) {
        return id == null || !enabled() ? null : CACHE.get(id);
    }

    /**
     * A drop-in for quickStatus[field] / 168 (field is buyMovingWeek or sellMovingWeek): items per hour, raised to the last 24
     * hours' provable rate when the item's history is loaded and shows one. Map lookup and a binary search; never downloads.
     */
    public static double perHour(JsonObject quickStatus, String field) {
        double now = quickStatus == null ? 0 : number(quickStatus, field);
        if (Double.isNaN(now)) now = 0;
        MarketShift s = quickStatus != null && quickStatus.has("productId") ? of(quickStatus.get("productId").getAsString()) : null;
        if (s == null) return now / 168.0;
        return perHour(now, s.movingWeekAt(field.startsWith("buy"), System.currentTimeMillis() - RECENT_MS));
    }

    /**
     * How much faster a flip fills than the weekly volume says: the slower side's rate with the last 24 hours over the slower
     * side's weekly rate (1 = as weekly, 2.4 = profit/hr 2.4x higher); NaN without history or volume.
     */
    public double flipSpeedup(double buyMovingWeekNow, double sellMovingWeekNow, long now) {
        double weekly = Math.min(buyMovingWeekNow, sellMovingWeekNow) / 168.0;
        if (!(weekly > 0)) return Double.NaN;
        double buy = perHour(buyMovingWeekNow, movingWeekAt(true, now - RECENT_MS));
        double sell = perHour(sellMovingWeekNow, movingWeekAt(false, now - RECENT_MS));
        return Math.min(buy, sell) / weekly;
    }

    /** Short badge for a confirmed price shift ("§e[24h -22%]") or a flip filling at least 1.5x faster ("§e[24h vol x2.4]"). */
    public static String badge(String id, JsonObject quickStatus) {
        MarketShift s = of(id);
        if (s == null) return "";
        if (s.confirmed()) {
            double b = s.bidChange();
            return " §e[24h " + (b > 0 ? "+" : "") + Math.round(b * 100) + "%]";
        }
        if (quickStatus == null) return "";
        double x = s.flipSpeedup(number(quickStatus, "buyMovingWeek"), number(quickStatus, "sellMovingWeek"), System.currentTimeMillis());
        return x >= 1.5 ? " §e[24h vol x" + String.format("%.1f", x) + "]" : "";
    }
}
