package com.github.mkram17.bazaarutils.features;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The accounting behind {@link FlipTracker}, kept free of Minecraft classes so it can be unit tested: it parses trade
 * messages, replays every buy, sale, fusion and forge in order, and splits realized profit by where the sold item came from.
 *
 * <p>Every item you hold is a set of lots by source: bought on the Bazaar, bought on the auction house, fused, or forged.
 * A sale takes from those lots in proportion to how much each holds (average cost, as before), so selling 10 shards of
 * which 4 were fused and 6 bought books 40% of the profit to Fusions and 60% to Bazaar flips. Fusions and forges turn the
 * cost of their inputs into the cost of the output, so their profit shows up when the output sells.
 *
 * <p>Hypixel sends no chat message when you forge, so a forge is recognised when you sell a forge item you don't hold: it
 * is built from its recipe at that moment, using parts you hold (tracked purchases) first, forging parts that are
 * themselves forge items the same way, and valuing anything else (mined, from sacks) at its price when the item sold.
 * That last part makes the profit an estimate, and it is marked as one.
 */
final class ProfitLedger {
    enum Mode {
        BAZAAR("Bazaar flips"), AH("AH flips"), FUSION("Fusions"), FORGE("Forge");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** One tracked event. Field names match what FlipTracker has always saved, so old history still loads. */
    static final class Entry {
        long time;
        String item;
        boolean buy;
        double qty;
        double coins; // total paid (buy) or total received after tax/fees (sell)
        String source; // "AH" for auction house trades; null for the Bazaar (and everything saved before AH tracking)
        // a fusion: `item` x `qty` came out of the Fusion Machine, made from these inputs (null if they couldn't be matched)
        boolean fusion;
        String in1;
        double in1Qty;
        String in2;
        double in2Qty;
        // a sale of a forge item: unit prices of its parts when it sold, for parts you didn't buy (id -> coins)
        Map<String, Double> forgePrices;
    }

    /** Profit from (part of) one sale; a sale drawing on several sources gives one per source, the first marked. */
    record Realized(long time, String item, double profit, Mode mode, boolean estimated, boolean firstOfSale) { }

    /** Forge recipes and names (ForgeCalc in game, a fake in tests). */
    interface Forges {
        /** The forge item a sold item is (ignoring reforges, stars and icons), or null. */
        String outputIdFor(String soldName);

        ForgePlanner.Recipe recipe(String id);

        String name(String id);
    }

    static final Forges NO_FORGES = new Forges() {
        public String outputIdFor(String soldName) {
            return null;
        }

        public ForgePlanner.Recipe recipe(String id) {
            return null;
        }

        public String name(String id) {
            return id;
        }
    };

    // ---- parsing ----

    private static final Pattern BUY_CLAIM = Pattern.compile("^\\[Bazaar\\] Claimed ([\\d,]+)x (.+) worth [\\d,.]+ coins bought for ([\\d,.]+) each!");
    private static final Pattern SELL_CLAIM = Pattern.compile("^\\[Bazaar\\] Claimed ([\\d,.]+) coins from selling ([\\d,]+)x (.+) at [\\d,.]+ each!");
    private static final Pattern INSTA_SELL = Pattern.compile("^\\[Bazaar\\] Sold ([\\d,]+)x (.+) for ([\\d,.]+) coins!");
    private static final Pattern INSTA_BUY = Pattern.compile("^\\[Bazaar\\] Bought ([\\d,]+)x (.+) for ([\\d,.]+) coins!");
    // "You purchased Hotspot Ring for 680,000 coins!" (BIN) / "You collected 1,458,270 coins from selling X to [MVP+] Y in an auction!"
    private static final Pattern AH_BUY = Pattern.compile("^You purchased (.+) for ([\\d,]+) coins!");
    private static final Pattern AH_SELL = Pattern.compile("^You collected ([\\d,]+) coins from selling (.+) to .+ in an auction!");

    private static double num(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    private static Entry trade(long time, String item, boolean buy, double qty, double coins, String source) {
        Entry e = new Entry();
        e.time = time;
        e.item = item.trim();
        e.buy = buy;
        e.qty = qty;
        e.coins = coins;
        e.source = source;
        return e;
    }

    /** A Bazaar or auction house trade from one chat line (colour codes already stripped), or null. */
    static Entry parseTrade(String msg, long time) {
        Matcher m;
        if ((m = BUY_CLAIM.matcher(msg)).find()) {
            double qty = num(m.group(1));
            return trade(time, m.group(2), true, qty, qty * num(m.group(3)), null);
        }
        if ((m = SELL_CLAIM.matcher(msg)).find()) return trade(time, m.group(3), false, num(m.group(2)), num(m.group(1)), null);
        if ((m = INSTA_SELL.matcher(msg)).find()) return trade(time, m.group(2), false, num(m.group(1)), num(m.group(3)), null);
        if ((m = INSTA_BUY.matcher(msg)).find()) return trade(time, m.group(2), true, num(m.group(1)), num(m.group(3)), null);
        if ((m = AH_BUY.matcher(msg)).find()) return trade(time, m.group(1), true, 1, num(m.group(2)), "AH");
        if ((m = AH_SELL.matcher(msg)).find()) return trade(time, m.group(2), false, 1, num(m.group(1)), "AH");
        return null;
    }

    /** Names compared loosely: case, stars, item icons and extra spaces don't matter (" Rough Topaz Gemstone"). */
    static String norm(String name) {
        if (name == null) return "";
        String s = name.toLowerCase(Locale.ROOT).replaceAll("§.", "").replaceAll("[^a-z0-9'\\- ]", " ");
        return s.trim().replaceAll("\\s+", " ");
    }

    // ---- holdings ----

    /** What you hold of one item, split by where it came from: per source {quantity, cost, estimated part of the cost}. */
    static final class Holding {
        final EnumMap<Mode, double[]> lots = new EnumMap<>(Mode.class);

        double qty() {
            double q = 0;
            for (double[] l : lots.values()) q += l[0];
            return q;
        }

        double cost() {
            double c = 0;
            for (double[] l : lots.values()) c += l[1];
            return c;
        }

        void add(Mode source, double qty, double cost, double estimated) {
            double[] l = lots.computeIfAbsent(source, k -> new double[3]);
            l[0] += qty;
            l[1] += cost;
            l[2] += estimated;
        }

        /** Takes {@code qty} out in proportion to each source's share; returns what came from each {qty, cost, estimated}. */
        EnumMap<Mode, double[]> take(double qty) {
            EnumMap<Mode, double[]> out = new EnumMap<>(Mode.class);
            double total = qty();
            if (total <= 0) return out;
            for (var e : lots.entrySet()) {
                double[] l = e.getValue();
                if (l[0] <= 0) continue;
                double share = l[0] / total * qty; // this source's part of the amount taken
                double frac = share / l[0];
                double[] taken = {share, l[1] * frac, l[2] * frac};
                l[0] -= taken[0];
                l[1] -= taken[1];
                l[2] -= taken[2];
                out.put(e.getKey(), taken);
            }
            return out;
        }

        Holding copy() {
            Holding h = new Holding();
            for (var e : lots.entrySet()) h.lots.put(e.getKey(), e.getValue().clone());
            return h;
        }
    }

    record Replay(Map<String, Holding> stock, Map<String, Long> lastBuys, List<Realized> realized) {
        Holding holding(String item) {
            return stock.get(norm(item));
        }

        double qty(String item) {
            Holding h = holding(item);
            return h == null ? 0 : h.qty();
        }

        long lastBuy(String item) {
            return lastBuys.getOrDefault(norm(item), 0L);
        }
    }

    private static final double EPS = 1e-9;

    /** Replays every entry in time order; each sale books its profit to the sources of what it sold. */
    static Replay replay(List<Entry> entries, Forges forges) {
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparingLong(e -> e.time));
        Map<String, Holding> stock = new HashMap<>();
        Map<String, Long> lastBuys = new HashMap<>();
        List<Realized> out = new ArrayList<>();
        for (Entry e : copy) {
            String key = norm(e.item);
            if (e.fusion) {
                fuse(e, key, stock);
                continue;
            }
            Holding h = stock.computeIfAbsent(key, k -> new Holding());
            if (e.buy) {
                lastBuys.put(key, e.time);
                h.add("AH".equals(e.source) ? Mode.AH : Mode.BAZAAR, e.qty, e.coins, 0);
                continue;
            }
            if (h.qty() < e.qty - EPS) forge(e, key, e.qty - h.qty(), stock, forges);
            double held = h.qty();
            if (held <= EPS) continue; // nothing to sell from: no cost basis, not counted
            double sold = Math.min(e.qty, held);
            double proceeds = e.coins * (sold / e.qty);
            boolean first = true;
            for (var t : h.take(sold).entrySet()) {
                double[] taken = t.getValue();
                double profit = proceeds * (taken[0] / sold) - taken[1];
                out.add(new Realized(e.time, e.item, profit, t.getKey(), taken[2] > EPS, first));
                first = false;
            }
        }
        return new Replay(stock, lastBuys, out);
    }

    /** A fusion uses up its inputs at their cost; the output is held as fused, carrying that cost. */
    private static void fuse(Entry e, String key, Map<String, Holding> stock) {
        if (e.in1 == null) return; // inputs unknown: the output has no cost basis
        Holding a = stock.get(norm(e.in1)), b = stock.get(norm(e.in2));
        boolean same = norm(e.in1).equals(norm(e.in2));
        double needA = same ? e.in1Qty + e.in2Qty : e.in1Qty;
        if (a == null || a.qty() < needA - EPS || (!same && (b == null || b.qty() < e.in2Qty - EPS))) return;
        double cost = 0, estimated = 0;
        for (double[] t : a.take(needA).values()) {
            cost += t[1];
            estimated += t[2];
        }
        if (!same) {
            for (double[] t : b.take(e.in2Qty).values()) {
                cost += t[1];
                estimated += t[2];
            }
        }
        stock.computeIfAbsent(key, k -> new Holding()).add(Mode.FUSION, e.qty, cost, estimated);
    }

    /** Sells more of a forge item than you hold: forge the rest now from parts, or leave everything as it was. */
    private static void forge(Entry sale, String key, double missing, Map<String, Holding> stock, Forges forges) {
        String id = forges.outputIdFor(sale.item);
        if (id == null) return;
        Map<String, Holding> trial = new HashMap<>();
        for (var e : stock.entrySet()) trial.put(e.getKey(), e.getValue().copy());
        double[] made = make(id, missing, trial, forges, sale.forgePrices, new HashSet<>());
        if (made == null) return; // a part with no cost and no price: can't tell what it cost, so don't count it
        for (var e : trial.entrySet()) {
            Holding h = stock.computeIfAbsent(e.getKey(), k -> new Holding());
            h.lots.clear();
            h.lots.putAll(e.getValue().lots);
        }
        stock.get(key).add(Mode.FORGE, missing, made[0], made[1]);
    }

    /**
     * Cost of forging {@code qty} of {@code id} {cost, estimated part}: its coin fee plus each part, taken from what you hold,
     * else forged the same way if it is a forge item, else valued at its price when the item sold. Null if a part has none.
     */
    private static double[] make(String id, double qty, Map<String, Holding> stock, Forges forges, Map<String, Double> prices,
                                 Set<String> stack) {
        ForgePlanner.Recipe r = forges.recipe(id);
        if (r == null || !stack.add(id)) return null;
        double runs = qty / r.count();
        double cost = runs * r.coins(), estimated = 0;
        for (var in : r.inputs().entrySet()) {
            double need = in.getValue() * runs;
            Holding h = stock.get(norm(forges.name(in.getKey())));
            double have = h == null ? 0 : h.qty();
            double take = Math.min(have, need);
            if (take > EPS) {
                for (double[] t : h.take(take).values()) {
                    cost += t[1];
                    estimated += t[2];
                }
            }
            double shortBy = need - take;
            if (shortBy <= EPS) continue;
            double[] sub = make(in.getKey(), shortBy, stock, forges, prices, stack);
            if (sub == null) {
                Double p = prices == null ? null : prices.get(in.getKey());
                if (p == null || !(p > 0)) {
                    stack.remove(id);
                    return null;
                }
                sub = new double[]{shortBy * p, shortBy * p};
            }
            cost += sub[0];
            estimated += sub[1];
        }
        stack.remove(id);
        return new double[]{cost, estimated};
    }

    // ---- totals ----

    /**
     * Profit since {@code since} for one mode (null = all modes): {total, number of sales, 1 if any of it is estimated}. A
     * sale counts once overall, and once in every mode it drew on.
     */
    static double[] total(List<Realized> realized, long since, Mode mode) {
        double total = 0, count = 0, estimated = 0;
        for (Realized r : realized) {
            if (r.time() < since || (mode != null && r.mode() != mode)) continue;
            total += r.profit();
            if (mode != null || r.firstOfSale()) count++;
            if (r.estimated()) estimated = 1;
        }
        return new double[]{total, count, estimated};
    }
}
