package com.github.mkram17.bazaarutils.features;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Searching the flip list within its current mode. Matching is a loose "contains" on in-game names (case, item icons and
 * stars ignored), and always runs over the full computed list, so the best matches show up even if they'd be cut by the
 * row limit. Pure logic, no Minecraft classes, so it can be unit tested.
 *
 * <ul>
 *   <li>Flips, dips, book crafts: rows whose name matches.</li>
 *   <li>Fusions: every profitable recipe that makes the shard you typed, then every one that uses it as an input.</li>
 *   <li>Forge: the item's own best route if it's forgeable, plus every forge recipe that takes it as a direct part (what you
 *   could forge it into), with that recipe's route and profit.</li>
 * </ul>
 */
final class FlipSearch {
    private FlipSearch() { }

    /** True if {@code name} contains {@code query}, ignoring case, icons and stars. A blank query matches everything. */
    static boolean matches(String query, String name) {
        String q = ProfitLedger.norm(query);
        return q.isEmpty() || ProfitLedger.norm(name).contains(q);
    }

    static boolean blank(String query) {
        return ProfitLedger.norm(query).isEmpty();
    }

    /** The first {@code cap} rows that pass {@code keep}, in order: filtering happens before the cap, never after. */
    static <T> List<T> top(List<T> all, Predicate<T> keep, int cap) {
        List<T> out = new ArrayList<>();
        for (T t : all) {
            if (out.size() >= cap) break;
            if (keep.test(t)) out.add(t);
        }
        return out;
    }

    // ---- fusions ----

    enum Relation { MAKES, USES }

    record FusionHit(FusionCalc.Fusion fusion, Relation relation) { }

    /**
     * Every recipe in {@code all} whose output matches (MAKES), then every one with a matching input (USES), each group best
     * profit per hour first. {@code name} turns a shard id into its in-game name.
     */
    static List<FusionHit> fusions(String query, List<FusionCalc.Fusion> all, Function<String, String> name) {
        return new FusionIndex(all, name).search(query);
    }

    /**
     * Every profitable fusion, ready to search: shard names are normalized once when the list is built (on the fusion worker,
     * after each Bazaar refresh), so a search is one pass over three int arrays plus a "contains" per distinct shard, and the
     * last result is kept so a redraw with the same query costs nothing. The list is sorted best profit per hour first once,
     * here; both result groups keep that order, so nothing is sorted at search time.
     */
    static final class FusionIndex {
        static final FusionIndex EMPTY = new FusionIndex(List.of(), id -> id);

        private final List<FusionCalc.Fusion> all;
        private final String[] names; // normalized in-game name per shard number
        private final int[] out, left, right; // shard numbers per fusion
        private String lastQuery;
        private List<FusionHit> lastHits;

        FusionIndex(List<FusionCalc.Fusion> all, Function<String, String> name) {
            List<FusionCalc.Fusion> sorted = new ArrayList<>(all);
            sorted.sort((a, b) -> Double.compare(b.perHour(), a.perHour()));
            this.all = List.copyOf(sorted);
            java.util.Map<String, Integer> number = new java.util.HashMap<>();
            List<String> names = new ArrayList<>();
            Function<String, Integer> num = id -> number.computeIfAbsent(id, k -> {
                names.add(ProfitLedger.norm(name.apply(k)));
                return names.size() - 1;
            });
            int n = this.all.size();
            out = new int[n];
            left = new int[n];
            right = new int[n];
            for (int i = 0; i < n; i++) {
                FusionCalc.Fusion f = this.all.get(i);
                out[i] = num.apply(f.output());
                left[i] = num.apply(f.left());
                right[i] = num.apply(f.right());
            }
            this.names = names.toArray(String[]::new);
        }

        int size() {
            return all.size();
        }

        synchronized List<FusionHit> search(String query) {
            String q = ProfitLedger.norm(query);
            if (q.equals(lastQuery)) return lastHits;
            boolean[] hit = new boolean[names.length];
            for (int k = 0; k < names.length; k++) hit[k] = names[k].contains(q);
            List<FusionHit> makes = new ArrayList<>(), uses = new ArrayList<>();
            for (int i = 0; i < out.length; i++) {
                if (hit[out[i]]) makes.add(new FusionHit(all.get(i), Relation.MAKES));
                else if (hit[left[i]] || hit[right[i]]) uses.add(new FusionHit(all.get(i), Relation.USES));
            }
            makes.addAll(uses);
            lastQuery = q;
            lastHits = java.util.Collections.unmodifiableList(makes);
            return lastHits;
        }
    }

    // ---- forge ----

    /** A forge row found by a search: {@code usedPart} is the matching part it consumes, or null if the item itself matched. */
    record ForgeHit(ForgeCalc.Row row, String usedPart) { }

    /**
     * Rows for items whose name matches, then rows whose recipe takes a matching part directly ("used in"), each group in
     * the order given (best first). {@code name} turns an item id into its in-game name.
     */
    static List<ForgeHit> forge(String query, List<ForgeCalc.Row> rows, Function<String, String> name) {
        List<ForgeHit> self = new ArrayList<>(), usedIn = new ArrayList<>();
        for (ForgeCalc.Row row : rows) {
            if (matches(query, name.apply(row.route().id()))) {
                self.add(new ForgeHit(row, null));
                continue;
            }
            for (String part : row.recipe().inputs().keySet()) {
                if (matches(query, name.apply(part))) {
                    usedIn.add(new ForgeHit(row, part));
                    break;
                }
            }
        }
        self.addAll(usedIn);
        return self;
    }
}
