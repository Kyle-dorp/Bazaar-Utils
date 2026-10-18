package com.github.mkram17.bazaarutils.features;

import java.util.*;

/**
 * Picks the most profitable way to forge an item when every part can either be bought or forged yourself. Pure logic (no
 * Minecraft or network access) so it can be unit-tested; {@link ForgeCalc} feeds it live recipes and prices.
 *
 * <p>Forge slots are the scarce resource, so routes are compared by profit per slot-hour. Forging a sub-part yourself saves
 * coins but ties up a slot, and is only worth it when the saving per slot-hour beats what the rest of the route earns. That
 * makes "best route" a ratio problem (maximise profit / slot time over all make-or-buy choices), solved exactly with
 * Dinkelbach iteration: for a rate lambda (coins per slot-second) each part costs min(buy, (coins + lambda * forge time + parts)
 * / count), which splits cleanly down the tree; the route that minimises that cost has some actual rate, and repeating with
 * lambda set to that rate climbs to the optimum in a few steps. The item itself is always forged (that is the flip). Ties
 * between forging and buying a part go to buying: same money, less slot time.
 */
public final class ForgePlanner {
    /** One forge recipe: forging once takes {@code seconds}, costs {@code coins} plus the inputs, and makes {@code count}. */
    public record Recipe(String id, int count, double seconds, double coins, Map<String, Double> inputs) { }

    public interface Prices {
        /** Coins to buy one unit, NaN if it cannot be bought. */
        double buy(String id);

        /** Coins received for selling one unit after fees, NaN if it cannot be sold. */
        double sell(String id);
    }

    /**
     * One part of a route. {@code quantity} is how many the parent needs; a forged part runs {@code forges} forges of
     * {@code secondsPerForge} each. {@code coins}, {@code slotSeconds} and {@code chainSeconds} cover this part and
     * everything under it: coins spent (purchases plus forge coin costs), total forge-slot time, and the longest chain of
     * forges that must run one after another.
     */
    public record Step(String id, double quantity, boolean forged, double forges, double secondsPerForge, double coins,
                       double slotSeconds, double chainSeconds, List<Step> parts) { }

    /** The chosen route for forging {@code id} once. */
    public record Route(String id, Step tree, double revenue, double cost, double profit, double slotSeconds,
                        double chainSeconds, int forgeSteps, double profitPerSlotHour) {
        /** Hours until the item is done with {@code slots} forge slots: the longest chain, or total time spread over the slots. */
        public double hoursWith(int slots) {
            return Math.max(chainSeconds, slotSeconds / Math.max(1, slots)) / 3600.0;
        }
    }

    private static final int MAX_ITERATIONS = 100;

    private final Map<String, Recipe> recipes;
    private final Prices prices;
    private final double timeFactor;
    private final double checkInSeconds;
    private final java.util.function.Predicate<String> canForge;

    /** timeFactor scales every forge duration, e.g. 0.7 with 30% Quick Forge. */
    public ForgePlanner(Map<String, Recipe> recipes, Prices prices, double timeFactor) {
        this(recipes, prices, timeFactor, 0);
    }

    /**
     * checkInSeconds is how often you come back to collect and restart forges (0 = the moment they finish). A forge holds its
     * slot until it is collected, so a 30-second forge started before logging off for 4 hours costs 4 slot-hours.
     */
    public ForgePlanner(Map<String, Recipe> recipes, Prices prices, double timeFactor, double checkInSeconds) {
        this(recipes, prices, timeFactor, checkInSeconds, id -> true);
    }

    /**
     * canForge says which recipes you have unlocked (e.g. your HotM tier). A part you can't forge is bought instead, so the
     * route adapts; an item you can't forge at all has no route.
     */
    public ForgePlanner(Map<String, Recipe> recipes, Prices prices, double timeFactor, double checkInSeconds,
                        java.util.function.Predicate<String> canForge) {
        this.recipes = recipes;
        this.prices = prices;
        this.timeFactor = timeFactor;
        this.checkInSeconds = Math.max(0, checkInSeconds);
        this.canForge = canForge;
    }

    /** How long one forge of this recipe holds a slot: its (Quick Forge) duration, or until your next check-in if that's later. */
    private double slotSeconds(Recipe r) {
        return Math.max(r.seconds() * timeFactor, checkInSeconds);
    }

    /** Every recipe that can be forged at a profit, best profit per slot-hour first. */
    public List<Route> rankAll() {
        return rankAll(Double.POSITIVE_INFINITY);
    }

    /** Every recipe with a profitable route costing at most {@code maxCost} up front, best profit per slot-hour first. */
    public List<Route> rankAll(double maxCost) {
        List<Route> out = new ArrayList<>();
        for (String id : recipes.keySet()) {
            Route r = best(id, maxCost);
            if (r != null) out.add(r);
        }
        out.sort((a, b) -> Double.compare(b.profitPerSlotHour(), a.profitPerSlotHour()));
        return out;
    }

    /** The route with the highest profit per slot-hour for forging {@code id}, or null if it can't be forged at a profit. */
    public Route best(String id) {
        return best(id, Double.POSITIVE_INFINITY);
    }

    /**
     * Like {@link #best(String)}, limited to routes whose up-front cost (parts bought plus forge coin fees) is at most
     * {@code maxCost}, e.g. your purse. The first Dinkelbach step is the cheapest route in coins, so null means not even that
     * one fits. Otherwise this is the best-rate route that fits among the steps taken, which is the true optimum whenever
     * the optimum itself fits.
     */
    public Route best(String id, double maxCost) {
        Recipe root = recipes.get(id);
        if (root == null || !canForge.test(id)) return null;
        double sell = prices.sell(id);
        if (!(sell > 0)) return null;
        double revenue = sell * root.count();

        double lambda = 0;
        Route bestRoute = null, affordable = null;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            Route candidate = route(root, revenue, lambda);
            if (candidate == null || !(candidate.profit() > 0)) break;
            double rate = candidate.profit() / candidate.slotSeconds();
            if (bestRoute != null && rate <= lambda * (1 + 1e-12)) {
                // no route beats the current rate: optimal. An equally good route that uses less slot time wins the tie.
                if (rate >= lambda * (1 - 1e-12) && candidate.slotSeconds() < bestRoute.slotSeconds()) bestRoute = candidate;
                affordable = better(affordable, candidate, maxCost);
                break;
            }
            bestRoute = candidate;
            affordable = better(affordable, candidate, maxCost);
            lambda = rate;
        }
        return maxCost == Double.POSITIVE_INFINITY ? bestRoute : affordable;
    }

    /** The better of two routes that fit the budget: higher rate, then less slot time. */
    private static Route better(Route current, Route candidate, double maxCost) {
        if (candidate.cost() > maxCost) return current;
        if (current == null) return candidate;
        double a = current.profitPerSlotHour(), b = candidate.profitPerSlotHour();
        if (b > a * (1 + 1e-12)) return candidate;
        if (b >= a * (1 - 1e-12) && candidate.slotSeconds() < current.slotSeconds()) return candidate;
        return current;
    }

    /**
     * How many times an hour a route can really run and what stops it running more: {@link #SLOTS} (your forge slots),
     * {@link #MARKET} (how many the market buys) or the id of a bought part you can't get faster. {@code limitPerHour} is
     * that limit's units per hour (NaN for slots).
     */
    public record Throughput(double runsPerHour, double profitPerHour, String limit, double limitPerHour) {
        public static final String SLOTS = "slots", MARKET = "market";

        /** The bought part whose supply caps the route, or null if slots or the market do. */
        public String limitingInput() {
            return SLOTS.equals(limit) || MARKET.equals(limit) ? null : limit;
        }
    }

    /** Units of each bought (not forged) part one run uses, summed over every place it appears in the tree. */
    public static Map<String, Double> boughtInputs(Step tree) {
        Map<String, Double> out = new LinkedHashMap<>();
        addBought(tree, out);
        return out;
    }

    private static void addBought(Step s, Map<String, Double> out) {
        if (!s.forged()) {
            out.merge(s.id(), s.quantity(), Double::sum);
            return;
        }
        for (Step p : s.parts()) addBought(p, out);
    }

    /** One line of the forge queue: {@code forges} forges of {@code id}, {@code secondsPerForge} each, startable at {@code stage}. */
    public record QueueItem(String id, double forges, double secondsPerForge, int stage) { }

    /**
     * Every forge a route needs, grouped by item. Stage 1 can start right away (all its parts are bought), stage 2 needs
     * stage-1 results, and so on up to the item itself. Sorted by stage, then longest forge first so slow ones start early.
     */
    public static List<QueueItem> forgeQueue(Step tree) {
        Map<String, double[]> byId = new LinkedHashMap<>(); // id -> {forges, secondsPerForge, stage}
        stage(tree, byId);
        List<QueueItem> out = new ArrayList<>();
        for (var e : byId.entrySet()) {
            double[] v = e.getValue();
            out.add(new QueueItem(e.getKey(), v[0], v[1], (int) v[2]));
        }
        out.sort(Comparator.comparingInt(QueueItem::stage)
                .thenComparing(Comparator.comparingDouble(QueueItem::secondsPerForge).reversed()));
        return out;
    }

    /** Records this forged step in the queue and returns its stage (0 for a bought part). */
    private static int stage(Step s, Map<String, double[]> byId) {
        if (!s.forged()) return 0;
        int stage = 1;
        for (Step p : s.parts()) stage = Math.max(stage, stage(p, byId) + 1);
        double[] v = byId.computeIfAbsent(s.id(), k -> new double[]{0, s.secondsPerForge(), 0});
        v[0] += s.forges();
        v[2] = Math.max(v[2], stage);
        return stage;
    }

    /**
     * Runs per hour: the least of what your slots can forge, what the market buys (marketUnitsPerHour / units per run) and,
     * for every bought part, how fast you can get it (supplyPerHour / units per run). NaN means unknown: no cap from that
     * source. Ties go to slots, then the market, so a cap is only blamed on supply when supply is strictly tighter.
     */
    public static Throughput throughput(Route route, Recipe recipe, int slots, double marketUnitsPerHour,
                                        java.util.function.ToDoubleFunction<String> supplyPerHour) {
        double runs = Math.max(1, slots) * 3600.0 / route.slotSeconds();
        String limit = Throughput.SLOTS;
        double limitPerHour = Double.NaN;
        if (marketUnitsPerHour >= 0 && marketUnitsPerHour / recipe.count() < runs) {
            runs = marketUnitsPerHour / recipe.count();
            limit = Throughput.MARKET;
            limitPerHour = marketUnitsPerHour;
        }
        for (var e : boughtInputs(route.tree()).entrySet()) {
            double supply = supplyPerHour.applyAsDouble(e.getKey());
            if (supply >= 0 && supply / e.getValue() < runs) {
                runs = supply / e.getValue();
                limit = e.getKey();
                limitPerHour = supply;
            }
        }
        return new Throughput(runs, route.profit() * runs, limit, limitPerHour);
    }

    /** Profit per hour running this route in every slot, capped by how many the market buys per hour (NaN = no cap known). */
    public static double profitPerHour(Route route, Recipe recipe, int slots, double marketUnitsPerHour) {
        return throughput(route, recipe, slots, marketUnitsPerHour, id -> Double.NaN).profitPerHour();
    }

    // ---- route at a given rate ----

    /** The cheapest route at rate lambda (coins + lambda * slot time), with the root forced to be forged. Null if impossible. */
    private Route route(Recipe root, double revenue, double lambda) {
        Map<String, Double> memo = new HashMap<>();
        Set<String> stack = new HashSet<>();
        Step tree = forgedStep(root, 1, lambda, memo, stack);
        if (tree == null) return null;
        double profit = revenue - tree.coins();
        return new Route(root.id(), tree, revenue, tree.coins(), profit, tree.slotSeconds(), tree.chainSeconds(),
                countForged(tree), profit / (tree.slotSeconds() / 3600.0));
    }

    private Step forgedStep(Recipe r, double quantity, double lambda, Map<String, Double> memo, Set<String> stack) {
        double forges = quantity / r.count();
        double seconds = slotSeconds(r);
        stack.add(r.id());
        List<Step> parts = new ArrayList<>();
        double coins = forges * r.coins(), slot = forges * seconds, chain = 0;
        for (var e : r.inputs().entrySet()) {
            Step part = step(e.getKey(), e.getValue() * forges, lambda, memo, stack);
            if (part == null) {
                stack.remove(r.id());
                return null;
            }
            parts.add(part);
            coins += part.coins();
            slot += part.slotSeconds();
            chain = Math.max(chain, part.chainSeconds());
        }
        stack.remove(r.id());
        return new Step(r.id(), quantity, true, forges, seconds, coins, slot, seconds + chain, parts);
    }

    private Step step(String id, double quantity, double lambda, Map<String, Double> memo, Set<String> stack) {
        double buy = buyPrice(id);
        double make = makeUnitCost(id, lambda, memo, stack);
        if (make < buy) return forgedStep(recipes.get(id), quantity, lambda, memo, stack);
        if (buy == Double.POSITIVE_INFINITY) return null;
        return new Step(id, quantity, false, 0, 0, quantity * buy, 0, 0, List.of());
    }

    /** min(buy, make) per unit at rate lambda. */
    private double unitCost(String id, double lambda, Map<String, Double> memo, Set<String> stack) {
        return Math.min(buyPrice(id), makeUnitCost(id, lambda, memo, stack));
    }

    /** Cost per unit of forging it yourself at rate lambda (infinite if there's no recipe or it would loop back on itself). */
    private double makeUnitCost(String id, double lambda, Map<String, Double> memo, Set<String> stack) {
        Recipe r = recipes.get(id);
        if (r == null || stack.contains(id) || !canForge.test(id)) return Double.POSITIVE_INFINITY;
        Double cached = memo.get(id);
        if (cached != null) return cached;
        stack.add(id);
        double cost = r.coins() + lambda * slotSeconds(r);
        for (var e : r.inputs().entrySet()) cost += e.getValue() * unitCost(e.getKey(), lambda, memo, stack);
        stack.remove(id);
        double perUnit = cost / r.count();
        memo.put(id, perUnit);
        return perUnit;
    }

    private double buyPrice(String id) {
        double p = prices.buy(id);
        return p > 0 ? p : Double.POSITIVE_INFINITY;
    }

    private static int countForged(Step s) {
        if (!s.forged()) return 0;
        int n = 1;
        for (Step p : s.parts()) n += countForged(p);
        return n;
    }
}
