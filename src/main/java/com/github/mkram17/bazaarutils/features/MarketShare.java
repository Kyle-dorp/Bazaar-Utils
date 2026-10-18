package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * How much of a market's volume your own orders capture. The lists rank every option by the item's whole 7-day volume, but
 * other players run orders on the same items and only the order on top fills. The public Bazaar data lists the top 30 price
 * levels on each side with how many orders sit at each, so the orders priced near the best one are the players you're
 * fighting: with {@code n} of them, you're on top about {@code 1 / (n + 1)} of the time. That share is kept between a
 * minimum and a maximum (the player's own estimate of 5-10%), and an item the order book doesn't cover (the auction house,
 * no snapshot yet) gets the middle of the two.
 *
 * <p>Pure: reads a products object from the Bazaar API, no network.
 */
public final class MarketShare {
    /** Orders priced within this fraction of the best one count as fighting for the same fills (live, 2026-10-17: a 0.1% band counts nearly the same). */
    static final double NEAR_TOP = 0.01;

    private final JsonObject products; // null: no order book, every market gets the base share
    private final double min, max;

    private MarketShare(JsonObject products, double min, double max) {
        this.products = products;
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
    }

    /** The same share of every market: 1 plans with the lists' full volume. */
    public static MarketShare fixed(double share) {
        return new MarketShare(null, share, share);
    }

    /** Shares from the order book in a Bazaar API products object, kept within [min, max] (fractions, not percent). */
    public static MarketShare fromOrderBook(JsonObject products, double min, double max) {
        return new MarketShare(products, min, max);
    }

    /** The share for a market the order book can't tell about: the middle of the range. */
    public double base() {
        return (min + max) / 2;
    }

    /** Your buy order's share of the instant sells into buy orders for this item (Hypixel lists buy orders as sell_summary). */
    public double buying(String id) {
        return side(id, "sell_summary");
    }

    /** Your sell offer's share of the instant buys from sell offers for this item (listed as buy_summary). */
    public double selling(String id) {
        return side(id, "buy_summary");
    }

    private double side(String id, String summary) {
        if (products == null || id == null || !products.has(id) || !products.get(id).isJsonObject()) return base();
        JsonElement levels = products.getAsJsonObject(id).get(summary);
        if (levels == null || !levels.isJsonArray()) return base();
        return against(competitors(levels.getAsJsonArray()));
    }

    /** Your share against this many competing orders: on top about 1 in (n + 1) of the time, within [min, max]. */
    double against(int competitors) {
        return Math.max(min, Math.min(max, 1.0 / (1 + Math.max(0, competitors))));
    }

    /** Orders priced within {@link #NEAR_TOP} of the best price on this side (the list is best price first). */
    static int competitors(JsonArray levels) {
        if (levels.isEmpty()) return 0;
        double top = price(levels.get(0));
        int n = 0;
        for (JsonElement e : levels) {
            if (!(Math.abs(price(e) - top) <= NEAR_TOP * top)) continue;
            JsonElement orders = e.getAsJsonObject().get("orders");
            n += orders == null ? 1 : orders.getAsInt();
        }
        return n;
    }

    private static double price(JsonElement level) {
        JsonElement p = level.getAsJsonObject().get("pricePerUnit");
        return p == null ? Double.NaN : p.getAsDouble();
    }
}
