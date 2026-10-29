package com.github.mkram17.bazaarutils.features;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * BazaarFlip's current flip (or dip) rows, read by reflection since BazaarFlip is not a compile dependency. One place for
 * every reader: the flip list and the opportunity advisor. Rows come from its two top lists (by percentage, by money) in that
 * order, so an item can appear twice; empty when BazaarFlip isn't loaded or hasn't checked the Bazaar yet.
 *
 * <p>totalProfit is BazaarFlip's "total profit" as patched by {@link BaseProfit#profitPerHour}: profit per item after tax times
 * the slower side of the 7-day volume per hour.
 */
public final class BazaarFlipRows {
    private static final String MOD = "uwu.ramona.bazaar.BazaarFlipMod";
    private static final String ITEM = "uwu.ramona.bazaar.BazaarFlipMod$BazaarItem";
    private static final String[] LISTS = {"topItemsByPercentage", "topItemsByMoney"};

    public record Row(String id, double buyPrice, double sellPrice, double profit, double profitMargin, double totalProfit,
                      double totalBuyCost) { }

    private record Fields(Field[] lists, Field id, Field buyPrice, Field sellPrice, Field profit, Field profitMargin,
                          Field totalProfit, Field totalBuyCost) { }

    private static volatile Fields fields;
    private static volatile boolean unavailable;

    private BazaarFlipRows() { }

    private static Fields fields() {
        Fields f = fields;
        if (f != null || unavailable) return f;
        try {
            Class<?> mod = Class.forName(MOD), item = Class.forName(ITEM);
            Field[] lists = new Field[LISTS.length];
            for (int i = 0; i < LISTS.length; i++) lists[i] = mod.getField(LISTS[i]);
            f = new Fields(lists, item.getField("id"), item.getField("buyPrice"), item.getField("sellPrice"), item.getField("profit"),
                    item.getField("profitMargin"), item.getField("totalProfit"), item.getField("totalBuyCost"));
            fields = f;
        } catch (ReflectiveOperationException | LinkageError e) {
            unavailable = true; // BazaarFlip not installed, or a version without these fields
        }
        return f;
    }

    public static String warning() {
        return fields() == null ? "BazaarFlip is missing or incompatible; flip results unavailable" : "";
    }

    /** The rows BazaarFlip shows right now (flips or dips, whichever mode it's in). */
    public static List<Row> get() {
        Fields f = fields();
        List<Row> out = new ArrayList<>();
        if (f == null) return out;
        for (Field list : f.lists()) {
            try {
                Object l = list.get(null);
                if (!(l instanceof List<?> items)) continue;
                for (Object it : new ArrayList<>(items)) { // BazaarFlip replaces its lists from a background thread
                    if (it == null) continue;
                    out.add(new Row((String) f.id().get(it), d(f.buyPrice(), it), d(f.sellPrice(), it), d(f.profit(), it),
                            d(f.profitMargin(), it), d(f.totalProfit(), it), d(f.totalBuyCost(), it)));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return out;
    }

    private static double d(Field field, Object item) throws IllegalAccessException {
        return field.getDouble(item);
    }
}
