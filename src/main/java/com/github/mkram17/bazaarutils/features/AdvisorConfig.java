package com.github.mkram17.bazaarutils.features;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The opportunity advisor's own settings, saved as config/bazaarutils-advisor.json. Pure (reads and writes a path you give
 * it), so the defaults and limits are testable; {@link AdvisorCommand} keeps the live copy in game.
 *
 * <ul>
 *   <li>orderCheckHours: how often you tend your Bazaar orders while playing, 0-48h. A trade completes no sooner.</li>
 *   <li>maxActive: Bazaar items you run at once, 0-20 (each is a buy order and a sell offer).</li>
 *   <li>afk: plan for time away, so only forges count.</li>
 *   <li>shareMinPercent / shareMaxPercent: the range your share of a market's volume is kept in. The order book picks the
 *       point in it (more orders fighting near the top, smaller share); where there's no order book (the auction house) it's
 *       the middle.</li>
 *   <li>combinesPerHour: anvil combines you do in an hour for book crafts (a tier V from tier I books is 15).</li>
 * </ul>
 */
public record AdvisorConfig(double orderCheckHours, int maxActive, boolean afk, double shareMinPercent, double shareMaxPercent,
                            double combinesPerHour) {
    static final double DEFAULT_CHECK_HOURS = 1, DEFAULT_SHARE_MIN = 5, DEFAULT_SHARE_MAX = 10, DEFAULT_COMBINES = 150;
    static final int DEFAULT_MAX_ACTIVE = 7, MAX_ACTIVE = 20;
    public static final AdvisorConfig DEFAULTS = new AdvisorConfig(DEFAULT_CHECK_HOURS, DEFAULT_MAX_ACTIVE, false,
            DEFAULT_SHARE_MIN, DEFAULT_SHARE_MAX, DEFAULT_COMBINES);

    public AdvisorConfig {
        orderCheckHours = clamp(orderCheckHours, 0, 48, DEFAULT_CHECK_HOURS);
        maxActive = Math.max(0, Math.min(MAX_ACTIVE, maxActive));
        shareMinPercent = clamp(shareMinPercent, 0.1, 100, DEFAULT_SHARE_MIN);
        shareMaxPercent = clamp(shareMaxPercent, 0.1, 100, DEFAULT_SHARE_MAX);
        if (shareMinPercent > shareMaxPercent) {
            double t = shareMinPercent;
            shareMinPercent = shareMaxPercent;
            shareMaxPercent = t;
        }
        combinesPerHour = clamp(combinesPerHour, 0, 100_000, DEFAULT_COMBINES);
    }

    private static double clamp(double v, double lo, double hi, double fallback) {
        return Double.isNaN(v) ? fallback : Math.max(lo, Math.min(hi, v));
    }

    /** Your share of each market, from the order book in this Bazaar products object (null: the middle of the range everywhere). */
    public MarketShare shares(JsonObject products) {
        return MarketShare.fromOrderBook(products, shareMinPercent / 100, shareMaxPercent / 100);
    }

    public AdvisorConfig withOrderCheckHours(double v) {
        return new AdvisorConfig(v, maxActive, afk, shareMinPercent, shareMaxPercent, combinesPerHour);
    }

    public AdvisorConfig withMaxActive(int v) {
        return new AdvisorConfig(orderCheckHours, v, afk, shareMinPercent, shareMaxPercent, combinesPerHour);
    }

    public AdvisorConfig withAfk(boolean v) {
        return new AdvisorConfig(orderCheckHours, maxActive, v, shareMinPercent, shareMaxPercent, combinesPerHour);
    }

    public AdvisorConfig withShare(double minPercent, double maxPercent) {
        return new AdvisorConfig(orderCheckHours, maxActive, afk, minPercent, maxPercent, combinesPerHour);
    }

    public AdvisorConfig withCombinesPerHour(double v) {
        return new AdvisorConfig(orderCheckHours, maxActive, afk, shareMinPercent, shareMaxPercent, v);
    }

    /** The saved settings; any missing or unreadable value is its default, a missing or broken file is all defaults. */
    public static AdvisorConfig read(Path file) {
        try {
            if (!Files.exists(file)) return DEFAULTS;
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return new AdvisorConfig(num(o, "orderCheckHours", DEFAULT_CHECK_HOURS), (int) num(o, "maxActive", DEFAULT_MAX_ACTIVE),
                    o.has("afk") && o.get("afk").isJsonPrimitive() && o.get("afk").getAsBoolean(),
                    num(o, "shareMinPercent", DEFAULT_SHARE_MIN), num(o, "shareMaxPercent", DEFAULT_SHARE_MAX),
                    num(o, "combinesPerHour", DEFAULT_COMBINES));
        } catch (Exception e) {
            return DEFAULTS;
        }
    }

    private static double num(JsonObject o, String key, double fallback) {
        try {
            JsonElement e = o.get(key);
            return e == null ? fallback : e.getAsDouble();
        } catch (Exception ex) {
            return fallback;
        }
    }

    public void write(Path file) throws java.io.IOException {
        JsonObject o = new JsonObject();
        o.addProperty("orderCheckHours", orderCheckHours);
        o.addProperty("maxActive", maxActive);
        o.addProperty("afk", afk);
        o.addProperty("shareMinPercent", shareMinPercent);
        o.addProperty("shareMaxPercent", shareMaxPercent);
        o.addProperty("combinesPerHour", combinesPerHour);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(o));
    }
}
