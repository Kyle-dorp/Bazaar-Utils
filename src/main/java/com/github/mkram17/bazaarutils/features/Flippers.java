package com.github.mkram17.bazaarutils.features;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which flippers are on (the checkboxes in /bazaarflip). A flipper that's off computes and fetches nothing, contributes no rows,
 * is skipped by the list-mode cycle and left out of the advisor. All on by default; saved with the other settings by
 * {@link BaseProfit}. Pure logic (no Minecraft classes) so it can be unit tested.
 */
public final class Flippers {
    public enum Flipper {
        FLIPS("Flips", "Bazaar flips (BazaarFlip's stable spreads)"),
        DIPS("Dips", "Dips: items cheap vs their 7-day normal"),
        BOOKS("Books", "Book crafts (in the flips list)"),
        FUSIONS("Fusions", "Shard fusions list"),
        POTENTIAL("Panel", "Fusion Machine panel: what you could fuse from your Hunting Box"),
        FORGE("Forge", "Forge routes list"),
        ADVISOR("Advise", "/advisor plan"),
        MAYOR("Mayor", "Mayor events: 24h shift checks (dips, the 7-day filter, profit/hr) and perk badges; off = 7-day numbers only");

        public final String label, description;

        Flipper(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    private static volatile Set<Flipper> off = EnumSet.noneOf(Flipper.class);

    private Flippers() { }

    public static boolean on(Flipper f) {
        return !off.contains(f);
    }

    public static synchronized void set(Flipper f, boolean on) {
        Set<Flipper> next = off.isEmpty() ? EnumSet.noneOf(Flipper.class) : EnumSet.copyOf(off);
        if (on) next.remove(f);
        else next.add(f);
        off = next;
    }

    /** The names of the flippers that are off, for saving. */
    public static List<String> offNames() {
        return off.stream().map(Enum::name).toList();
    }

    /** Restores saved choices: everything on except the names given (unknown names are ignored). */
    public static synchronized void load(Collection<String> offNames) {
        Set<Flipper> next = EnumSet.noneOf(Flipper.class);
        for (String n : offNames) {
            for (Flipper f : Flipper.values()) if (f.name().equals(n)) next.add(f);
        }
        off = next;
    }

    /** The flippers whose rows a list mode shows (0 flips + book crafts, 1 dips, 2 fusions, 3 forge), only those that are on. */
    static Set<Flipper> feeding(int mode) {
        Set<Flipper> s = switch (mode) {
            case 0 -> EnumSet.of(Flipper.FLIPS, Flipper.BOOKS);
            case 1 -> EnumSet.of(Flipper.DIPS);
            case 2 -> EnumSet.of(Flipper.FUSIONS);
            case 3 -> EnumSet.of(Flipper.FORGE);
            default -> EnumSet.noneOf(Flipper.class);
        };
        s.removeIf(f -> !on(f));
        return s;
    }

    static boolean modeOn(int mode) {
        return !feeding(mode).isEmpty();
    }

    /** The next list mode after {@code current} that has something on (wrapping), or {@code current} if none has. */
    static int nextMode(int current, int modes) {
        for (int i = 1; i <= modes; i++) {
            int m = Math.floorMod(current + i, modes);
            if (modeOn(m)) return m;
        }
        return current;
    }

    /** BazaarFlip's Bazaar checks feed only the flips and dips lists. */
    static boolean bazaarChecksOn() {
        return on(Flipper.FLIPS) || on(Flipper.DIPS);
    }

    /** {@code rows} computed only when {@code f} is on; empty (and nothing computed) when it's off. */
    static <T> List<T> only(Flipper f, Supplier<List<T>> rows) {
        return on(f) ? rows.get() : List.of();
    }
}
