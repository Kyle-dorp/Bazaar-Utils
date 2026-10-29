package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/** One shared copy of the public Bazaar data for the book and fusion calculators, refetched at most every 30 seconds. */
public class BazaarSnapshot {
    private static final long MAX_AGE_MS = 30_000;
    private static volatile JsonObject products;
    private static volatile long fetchedAt;
    private static volatile String refreshError;

    /** The last fetched products without fetching (null if never fetched): for code that must not wait on the network. */
    public static JsonObject cachedProducts() {
        return products;
    }

    public static synchronized JsonObject products() throws Exception {
        long now = System.currentTimeMillis();
        if (products == null || now - fetchedAt > MAX_AGE_MS) {
            HttpURLConnection c = (HttpURLConnection) URI.create("https://api.hypixel.net/skyblock/bazaar").toURL().openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                JsonObject response = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject updated = response.getAsJsonObject("products");
                if (updated == null || (response.has("success") && !response.get("success").getAsBoolean())) {
                    throw new java.io.IOException("Bazaar response did not contain successful market data");
                }
                fetchedAt = System.currentTimeMillis();
                products = updated;
                refreshError = null;
            } catch (Exception e) {
                refreshError = e.getClass().getSimpleName();
                throw e;
            } finally {
                c.disconnect();
            }
        }
        return products;
    }

    public static String warning() {
        if (products == null) return refreshError == null ? "Market prices loading" : "Market refresh failed: " + refreshError;
        long age = Math.max(0, System.currentTimeMillis() - fetchedAt);
        if (refreshError != null) return "Market refresh failed; cached prices " + age / 1000 + "s old";
        return age > 90_000 ? "Stale market prices: " + age / 1000 + "s old" : "";
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
            return MarketShift.perHour(product.getAsJsonObject("quick_status"), "sellMovingWeek"); // weekly / 168, or a provable 24h surge
        } catch (Exception e) {
            return 0;
        }
    }

    /** Items per hour bought instantly from sell offers (how fast your sell offers fill), from the 7-day volume. */
    public static double sellRate(JsonObject product) {
        try {
            return MarketShift.perHour(product.getAsJsonObject("quick_status"), "buyMovingWeek"); // weekly / 168, or a provable 24h surge
        } catch (Exception e) {
            return 0;
        }
    }
}
