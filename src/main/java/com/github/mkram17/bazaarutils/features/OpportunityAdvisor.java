package com.github.mkram17.bazaarutils.features;

import java.util.*;

/**
 * Turns every money-making option the mod calculates into one plan for your purse and forge slots: which routes to fill the
 * slots with, which Bazaar flips, crafts and fusions to put the rest of the purse into, and why. Pure logic (no Minecraft or
 * network access); each calculator's rows become {@link Opportunity}s first. The scoring model is written up in
 * docs/opportunity-advisor.md.
 *
 * <p>The plan maximises expected profit per hour. Three things limit it: the purse (each option ties up coins for as long as
 * they take to come back, by Little's law), the forge slots (whole slots, one route each) and how many Bazaar items you run at
 * once. Forges are passive: they run while you're away and alongside your flipping, so they only compete with the Bazaar for
 * coins. The forge side is searched exhaustively over whole-slot assignments of a short list of routes (the best per slot and
 * the best per coin); for each, the coins left go to the Bazaar options. Without a limit on how many Bazaar items you run,
 * that is the classic fractional knapsack (best return per coin first) and exact. With the limit it tries a few dozen
 * shortlists, from "most profit per hour" to "most profit per coin", and keeps the best.
 */
public final class OpportunityAdvisor {
    /**
     * purse: coins you can put to work; +infinity means no limit (ForgeCalc.budget gives that when the purse filter is off
     * or the purse is unknown). maxActive: how many Bazaar items you'll run at once (each needs a buy order and a sell
     * offer). afk: plan for time away, so only passive options (forges) count.
     */
    public record Settings(double purse, int forgeSlots, int maxActive, boolean afk) {
        public Settings {
            if (Double.isNaN(purse)) purse = Double.POSITIVE_INFINITY;
            purse = Math.max(0, purse);
            forgeSlots = Math.max(0, forgeSlots);
            maxActive = Math.max(0, maxActive);
        }

        public boolean purseLimited() {
            return purse != Double.POSITIVE_INFINITY;
        }
    }

    /** One part of the plan: units (or forge runs) per hour, forge slots it fills, coins it ties up, expected profit per hour. */
    public record Pick(Opportunity opportunity, double unitsPerHour, int slots, double capital, double profitPerHour, String why) { }

    /** A strong option the plan leaves out, and why. */
    public record Skip(Opportunity opportunity, String why) { }

    /**
     * The plan: forge picks, then Bazaar picks, the totals, plain-text summary lines, the best options left out, and how many
     * forge slot assignments were scored (the search's cost).
     */
    public record Plan(List<Pick> forges, List<Pick> active, double profitPerHour, double passivePerHour, double capitalUsed,
                       int slotsUsed, Settings settings, List<String> summary, List<Skip> skipped, int assignmentsTried) { }

    /** Forge routes searched from each angle (best per slot, best per coin); the union is at most twice this. */
    static final int FORGE_POOL_EACH = 5;
    /** Capital prices tried when choosing which Bazaar options share a limited number of picks. */
    static final int MAX_CAPITAL_PRICES = 32;
    /** Best options left out that the plan explains. */
    static final int SKIPS_SHOWN = 5;

    private static final Comparator<Opportunity> BY_RETURN_PER_COIN = Comparator
            .comparingDouble(Opportunity::returnPerCoinHour).reversed()
            .thenComparing(Comparator.comparingDouble(Opportunity::perHourAtLimit).reversed())
            .thenComparing(Opportunity::id);

    private OpportunityAdvisor() { }

    public static Plan plan(List<Opportunity> options, Settings s) {
        List<Opportunity> valid = new ArrayList<>(), forges = new ArrayList<>(), active = new ArrayList<>();
        for (Opportunity o : options) {
            if (o == null || !(o.expectedProfit() > 0) || !(o.maxPerHour() > 0) || !(o.capitalPerRate() > 0)) continue;
            if (o.passive() && !(o.slotHours() > 0)) continue;
            valid.add(o);
            if (o.passive()) {
                if (s.forgeSlots() > 0) forges.add(o);
            } else if (!s.afk()) {
                active.add(o);
            }
        }

        ActiveChooser chooser = new ActiveChooser(active, s.maxActive());
        List<Opportunity> pool = forgePool(forges, s.forgeSlots());
        Search search = new Search(pool, s, chooser);
        search.run(0, s.forgeSlots(), 0, 0);

        List<Pick> forgePicks = new ArrayList<>();
        double forgeCapital = 0, passive = 0;
        int slotsUsed = 0;
        for (int i = 0; i < pool.size(); i++) {
            int n = search.bestCounts[i];
            if (n == 0) continue;
            Opportunity o = pool.get(i);
            double rate = forgeRate(o, n), capital = forgeCapital(o, n), profit = rate * o.expectedProfit();
            forgePicks.add(new Pick(o, rate, n, capital, profit, forgeWhy(o, n, rate, capital, profit)));
            forgeCapital += capital;
            passive += profit;
            slotsUsed += n;
        }
        forgePicks.sort(Comparator.comparingDouble(Pick::profitPerHour).reversed());

        List<Pick> activePicks = new ArrayList<>();
        double activeProfit = chooser.fill(search.bestSet, s.purse() - forgeCapital, activePicks);
        double activeCapital = activePicks.stream().mapToDouble(Pick::capital).sum();

        Plan draft = new Plan(forgePicks, activePicks, passive + activeProfit, passive, forgeCapital + activeCapital, slotsUsed,
                s, List.of(), List.of(), search.tried);
        return new Plan(forgePicks, activePicks, draft.profitPerHour(), passive, draft.capitalUsed(), slotsUsed, s,
                summary(draft, forges, active), skips(draft, valid), search.tried);
    }

    // ---- forge side ----

    /** Runs per hour with n slots on this route: every slot busy, unless the market or part supply allows fewer. */
    static double forgeRate(Opportunity o, int n) {
        return Math.min(n / o.slotHours(), o.maxPerHour());
    }

    /** Coins n slots of this route keep busy: Little's law, and never less than one run. */
    static double forgeCapital(Opportunity o, int n) {
        return Math.max(o.unitCost(), forgeRate(o, n) * o.capitalPerRate());
    }

    /** Slots worth giving a route: past the market's limit another slot would only sit idle. */
    static int usefulSlots(Opportunity o, int slots) {
        double busy = o.maxPerHour() * o.slotHours();
        return (int) Math.max(1, Math.min(slots, Math.ceil(busy - 1e-9)));
    }

    /** What one slot earns running only this route, capped by its market. */
    private static double firstSlotValue(Opportunity o) {
        return o.expectedProfit() * forgeRate(o, 1);
    }

    /** The routes worth searching: the best per slot and the best per coin, best per slot first. */
    static List<Opportunity> forgePool(List<Opportunity> forges, int slots) {
        if (slots <= 0 || forges.isEmpty()) return List.of();
        Comparator<Opportunity> bySlot = Comparator.comparingDouble(OpportunityAdvisor::firstSlotValue).reversed()
                .thenComparing(Opportunity::id);
        LinkedHashSet<Opportunity> pool = new LinkedHashSet<>();
        forges.stream().sorted(bySlot).limit(FORGE_POOL_EACH).forEach(pool::add);
        forges.stream().sorted(BY_RETURN_PER_COIN).limit(FORGE_POOL_EACH).forEach(pool::add);
        List<Opportunity> out = new ArrayList<>(pool);
        out.sort(bySlot);
        return out;
    }

    /** Every whole-slot assignment of the pool that fits the purse, each topped up with the best Bazaar picks for the coins left. */
    private static final class Search {
        final List<Opportunity> pool;
        final Settings s;
        final ActiveChooser chooser;
        final int[] counts, bestCounts;
        double best = Double.NEGATIVE_INFINITY;
        List<Opportunity> bestSet = List.of();
        int tried;

        Search(List<Opportunity> pool, Settings s, ActiveChooser chooser) {
            this.pool = pool;
            this.s = s;
            this.chooser = chooser;
            counts = new int[pool.size()];
            bestCounts = new int[pool.size()];
        }

        void run(int index, int slotsLeft, double capital, double profit) {
            if (index == pool.size()) {
                tried++;
                ActiveChooser.Best a = chooser.best(s.purse() - capital);
                double total = profit + a.profit();
                if (total > best * (1 + 1e-12) + 1e-9) {
                    best = total;
                    bestSet = a.set();
                    System.arraycopy(counts, 0, bestCounts, 0, counts.length);
                }
                return;
            }
            Opportunity o = pool.get(index);
            for (int n = Math.min(slotsLeft, usefulSlots(o, s.forgeSlots())); n >= 0; n--) {
                double c = capital, p = profit;
                if (n > 0) {
                    c += forgeCapital(o, n);
                    if (c > s.purse()) continue; // fewer slots of it may still fit
                    p += forgeRate(o, n) * o.expectedProfit();
                }
                counts[index] = n;
                run(index + 1, slotsLeft - n, c, p);
            }
            counts[index] = 0;
        }
    }

    // ---- Bazaar side ----

    /**
     * Picks which Bazaar options get the coins. For a set of options the best use of a budget is to fill them in order of
     * return per coin, each up to its market's limit. Which set: with no cap on how many, every option (exact). With a cap of
     * k, the sets tried are the top k by (profit - price * capital) per hour for a range of capital prices: price 0 is "most
     * profit per hour", high prices favour profit per coin. The sets don't depend on the budget, so they're built once.
     */
    static final class ActiveChooser {
        record Best(List<Opportunity> set, double profit) { }

        final List<List<Opportunity>> sets = new ArrayList<>();
        /** How many capital prices were tried: each one sorts every option, so this is the chooser's cost. */
        int pricesTried;

        ActiveChooser(List<Opportunity> active, int k) {
            if (active.isEmpty() || k <= 0) return;
            double[] returns = active.stream().mapToDouble(Opportunity::returnPerCoinHour).sorted().distinct().toArray();
            List<Double> prices = new ArrayList<>();
            prices.add(0.0);
            int step = Math.max(1, (int) Math.ceil(returns.length / (double) (MAX_CAPITAL_PRICES - 1)));
            for (int i = 0; i < returns.length; i += step) prices.add(returns[i]);
            pricesTried = prices.size();
            for (double price : prices) {
                List<Opportunity> set = active.stream().filter(o -> net(o, price) > 0)
                        .sorted(Comparator.comparingDouble((Opportunity o) -> net(o, price)).reversed().thenComparing(Opportunity::id))
                        .limit(k).sorted(BY_RETURN_PER_COIN).toList();
                if (!set.isEmpty() && !sets.contains(set)) sets.add(set);
            }
        }

        private static double net(Opportunity o, double price) {
            return (o.expectedProfit() - price * o.capitalPerRate()) * o.maxPerHour();
        }

        Best best(double budget) {
            Best best = new Best(List.of(), 0);
            for (List<Opportunity> set : sets) {
                double p = fill(set, budget, null);
                if (p > best.profit() * (1 + 1e-12) + 1e-9) best = new Best(set, p);
            }
            return best;
        }

        /** Fills the set best return per coin first, each up to its market's limit; adds the picks to out if given. */
        double fill(List<Opportunity> set, double budget, List<Pick> out) {
            double left = budget, profit = 0;
            for (Opportunity o : set) {
                if (!(left >= o.unitCost())) continue; // can't afford one unit; a cheaper one further down still might
                double rate = Math.min(o.maxPerHour(), left / o.capitalPerRate());
                double capital = Math.max(o.unitCost(), rate * o.capitalPerRate());
                left -= capital;
                profit += rate * o.expectedProfit();
                if (out != null) out.add(new Pick(o, rate, 0, capital, rate * o.expectedProfit(), activeWhy(o, rate, capital)));
            }
            return profit;
        }
    }

    // ---- explanations ----

    private static String forgeWhy(Opportunity o, int n, double rate, double capital, double profit) {
        StringBuilder sb = new StringBuilder();
        sb.append(n).append(n == 1 ? " slot: " : " slots: ").append(o.how()).append(". ")
                .append(coins(o.unitProfit())).append("/run x ").append(perDay(rate)).append(" runs/day = ")
                .append(coins(rate * o.unitProfit())).append("/hr. Ties up ").append(coins(capital)).append(" (")
                .append(hours(o.holdHours())).append(" from buying parts to sale). Keeps running while you're AFK.");
        if (rate < n / o.slotHours() * (1 - 1e-9)) {
            sb.append(" Its market takes only ~").append(perDay(o.maxPerHour())).append(" a day, so more slots wouldn't add anything.");
        }
        appendDoubts(sb, o, profit);
        return sb.toString();
    }

    private static String activeWhy(Opportunity o, double rate, double capital) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.how()).append(". ").append(coins(o.unitProfit())).append(" each x ").append(perDay(rate)).append("/day = ")
                .append(coins(rate * o.unitProfit())).append("/hr on ").append(coins(capital)).append(" of your purse (back in ~")
                .append(hours(o.holdHours())).append("), ").append(coins(o.returnPerCoinHour() * 1e6)).append("/hr per 1M tied up.");
        sb.append(rate >= o.maxPerHour() * (1 - 1e-9) ? " That's all its market trades." : " More would need more purse.");
        appendDoubts(sb, o, rate * o.expectedProfit());
        return sb.toString();
    }

    /** Why the plan counts less than the listed profit, if it does. */
    private static void appendDoubts(StringBuilder sb, Opportunity o, double plannedPerHour) {
        if (o.doubts().isEmpty()) return;
        sb.append(" Planned at ").append(String.format("%.0f", o.confidence() * 100)).append("% (").append(coins(plannedPerHour))
                .append("/hr): ").append(String.join("; ", o.doubts())).append('.');
    }

    private static List<String> summary(Plan p, List<Opportunity> forges, List<Opportunity> active) {
        Settings s = p.settings();
        List<String> lines = new ArrayList<>();
        boolean slotsFull = p.slotsUsed() >= s.forgeSlots();
        double activeProfit = p.profitPerHour() - p.passivePerHour();
        if (s.forgeSlots() > 0) {
            if (p.forges().isEmpty()) {
                lines.add(forges.isEmpty() ? "Forge: nothing profitable to forge right now."
                        : "Forge: leave the slots empty, no route fits your purse.");
            } else {
                List<String> parts = new ArrayList<>();
                for (Pick f : p.forges()) parts.add(f.slots() + "x " + f.opportunity().name());
                String line = "Fill " + p.slotsUsed() + (p.slotsUsed() == 1 ? " forge slot" : " forge slots") + " with "
                        + join(parts) + ": " + coins(p.passivePerHour()) + "/hr, running while you're AFK.";
                if (!slotsFull) {
                    int free = s.forgeSlots() - p.slotsUsed();
                    line += " " + free + (free == 1 ? " slot stays" : " slots stay") + " empty: "
                            + (s.purseLimited() && purseNearlyUsed(p) ? "your purse can't cover more runs."
                            : "their markets can't take more.");
                }
                lines.add(line);
            }
        }
        if (s.afk()) {
            lines.add("AFK: Bazaar flips, crafts and fusions are left out, they need you online.");
        } else if (!p.active().isEmpty()) {
            double capital = p.active().stream().mapToDouble(Pick::capital).sum();
            List<String> names = new ArrayList<>();
            for (Pick a : p.active()) names.add(a.opportunity().name());
            lines.add("Put " + coins(capital) + " into " + join(names) + ": " + coins(activeProfit) + "/hr while you're online.");
        } else if (s.maxActive() == 0) {
            lines.add("Bazaar: off (0 items at once).");
        } else if (!active.isEmpty()) {
            lines.add("Bazaar: nothing affordable with the coins left.");
        }
        if (s.purseLimited()) {
            double left = s.purse() - p.capitalUsed();
            if (left > s.purse() * 0.01) {
                lines.add(coins(left) + " of your " + coins(s.purse()) + " purse stays free: " + leftoverReason(p, active) + ".");
            }
        } else {
            lines.add("Purse not limiting (purse filter off or purse unknown): every pick runs at its market's limit.");
        }
        lines.add("Expected " + coins(p.profitPerHour()) + "/hr: " + coins(p.passivePerHour()) + " passive + "
                + coins(activeProfit) + " active.");
        return lines;
    }

    /** Why coins are left over: the item limit, full slots, every pick at its market's limit, or too little for one more unit. */
    private static String leftoverReason(Plan p, List<Opportunity> active) {
        Settings s = p.settings();
        boolean slotsFull = p.slotsUsed() >= s.forgeSlots();
        if (!s.afk() && s.maxActive() > 0 && p.active().size() >= s.maxActive()) {
            return "you're already running " + s.maxActive() + " Bazaar items";
        }
        if (slotsFull && (s.afk() || active.isEmpty() || s.maxActive() == 0)) {
            return "every forge slot is busy" + (s.afk() ? " and the Bazaar waits until you're back" : "");
        }
        boolean activeCapped = p.active().stream().allMatch(OpportunityAdvisor::atMarketLimit);
        boolean forgeCapped = p.forges().stream().allMatch(OpportunityAdvisor::atMarketLimit);
        if (activeCapped && (slotsFull || forgeCapped)) {
            return slotsFull && !forgeCapped ? "every forge slot is busy and the Bazaar picks' markets can't take more"
                    : "the picks' markets can't take more";
        }
        return "what's left is less than one more run or unit of anything worth it";
    }

    private static boolean atMarketLimit(Pick x) {
        return x.unitsPerHour() >= x.opportunity().maxPerHour() * (1 - 1e-9);
    }

    private static boolean purseNearlyUsed(Plan p) {
        return p.capitalUsed() >= p.settings().purse() * 0.9;
    }

    /** The options the lists rank highest that the plan leaves out, each with the reason. */
    private static List<Skip> skips(Plan p, List<Opportunity> valid) {
        Set<Opportunity> picked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Pick x : p.forges()) picked.add(x.opportunity());
        for (Pick x : p.active()) picked.add(x.opportunity());
        Settings s = p.settings();
        double lowestPickedReturn = p.active().stream().mapToDouble(x -> x.opportunity().returnPerCoinHour()).min().orElse(0);
        double lowestReturnOfAll = Math.min(lowestPickedReturn == 0 ? Double.POSITIVE_INFINITY : lowestPickedReturn,
                p.forges().stream().mapToDouble(x -> x.opportunity().returnPerCoinHour()).min().orElse(Double.POSITIVE_INFINITY));
        List<Skip> out = new ArrayList<>();
        for (Opportunity o : valid.stream().sorted(Comparator.comparingDouble(Opportunity::perHourAtLimit).reversed()).toList()) {
            if (out.size() >= SKIPS_SHOWN) break;
            if (picked.contains(o)) continue;
            String why;
            if (o.passive()) {
                if (s.forgeSlots() == 0) why = "no forge slots";
                else if (o.unitCost() > s.purse()) why = "one run costs " + coins(o.unitCost()) + ", more than your purse";
                else if (s.purseLimited() && o.returnPerCoinHour() < lowestReturnOfAll) {
                    // a slot of it would earn well, but the coins it ties up earn more in the picks
                    why = "a slot ties up " + coins(forgeCapital(o, 1)) + " for " + coins(firstSlotValue(o)) + "/hr: "
                            + coins(o.returnPerCoinHour() * 1e6) + "/hr per 1M, less than your picks earn";
                } else if (p.slotsUsed() >= s.forgeSlots() && !p.forges().isEmpty()) {
                    why = "your slots earn more on " + p.forges().get(0).opportunity().name();
                } else why = "ties up too much purse for what it earns (" + coins(o.returnPerCoinHour() * 1e6) + "/hr per 1M)";
            } else if (s.afk()) {
                why = "needs you online to keep its orders on top";
            } else if (p.active().size() >= s.maxActive() && o.returnPerCoinHour() >= lowestPickedReturn) {
                why = "you're already running " + s.maxActive() + " Bazaar items that earn more together";
            } else if (o.unitCost() > s.purse()) {
                why = "one costs " + coins(o.unitCost()) + ", more than your purse";
            } else {
                why = "earns " + coins(o.returnPerCoinHour() * 1e6) + "/hr per 1M tied up; your purse does better in the picks";
            }
            out.add(new Skip(o, o.kind().label + " " + o.name() + " (" + coins(o.perHourAtLimit()) + "/hr at its market's limit): " + why));
        }
        return out;
    }

    // ---- formatting ----

    /** Same format as the flip list (FlipHud.coins): 1.23M, 45.60K. */
    static String coins(double v) {
        if (!Double.isFinite(v)) return v > 0 ? "unlimited" : "?";
        double a = Math.abs(v);
        String s;
        if (a >= 1_000_000_000) s = String.format("%.2fB", a / 1_000_000_000);
        else if (a >= 1_000_000) s = String.format("%.2fM", a / 1_000_000);
        else if (a >= 1_000) s = String.format("%.2fK", a / 1_000);
        else s = String.format("%.1f", a);
        return (v < 0 ? "-" : "") + s;
    }

    static String hours(double h) {
        if (h < 1) return String.format("%.0fm", h * 60);
        return h == Math.floor(h) ? String.format("%.0fh", h) : String.format("%.1fh", h);
    }

    static String perDay(double perHour) {
        double d = perHour * 24;
        return d >= 10 ? String.format("%.0f", d) : String.format("%.1f", d);
    }

    private static String join(List<String> parts) {
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
