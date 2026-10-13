package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/** One shared copy of the public Bazaar data for the book and fusion calculators, refetched at most every 30 seconds. */
public class BazaarSnapshot {
    private static final long MAX_AGE_MS = 30_000;
    private static JsonObject products;
    private static long fetchedAt;

    public static synchronized JsonObject products() throws Exception {
        long now = System.currentTimeMillis();
        if (products == null || now - fetchedAt > MAX_AGE_MS) {
            HttpURLConnection c = (HttpURLConnection) URI.create("https://api.hypixel.net/skyblock/bazaar").toURL().openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                products = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject().getAsJsonObject("products");
            }
            fetchedAt = now;
        }
        return products;
    }

    /** Top buy order (what you pay with a buy order), 0 if none. */
    public static double bid(JsonObject product) {
        var a = product.getAsJsonArray("sell_summary");
        return a == null || a.isEmpty() ? 0 : a.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
    }

    /** Lowest sell offer (what you sell at with a sell offer), 0 if none. */
    public static double ask(JsonObject product) {
        var a = product.getAsJsonArray("buy_summary");
        return a == null || a.isEmpty() ? 0 : a.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
    }

    /** Items per hour sold instantly into buy orders (how fast your buy orders fill), from the 7-day volume. */
    public static double fillRate(JsonObject product) {
        try {
            return product.getAsJsonObject("quick_status").get("sellMovingWeek").getAsDouble() / 168.0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Items per hour bought instantly from sell offers (how fast your sell offers fill), from the 7-day volume. */
    public static double sellRate(JsonObject product) {
        try {
            return product.getAsJsonObject("quick_status").get("buyMovingWeek").getAsDouble() / 168.0;
        } catch (Exception e) {
            return 0;
        }
    }
}
