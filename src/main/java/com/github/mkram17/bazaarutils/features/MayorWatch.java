package com.github.mkram17.bazaarutils.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps the active mayor perks and their item tags current without ever blocking the caller: {@link #tag(String)} is a map
 * lookup on the last snapshot, safe to call every frame. At most one refresh runs at a time, on the executor, and it only
 * downloads the election (about 4 KB) every {@link #ELECTION_TTL_MS}, a little after a term ends, or
 * {@link #FAILED_RETRY_MS} after a failure. The cheap part (Jerry's Perkpocalypse slot, SkyHanni's reading) runs every
 * {@link #CHECK_MS}.
 *
 * <p>Perkpocalypse mayor: SkyHanni's reading for the current slot when it has one (it reads the Calendar after you open it),
 * else the mayor already seen in the same place of the 36-hour cycle this term, else the order you set with /flipmayor.
 * What it learns is kept in orderFile, so it survives a restart within the term; a new term forgets it.
 */
public final class MayorWatch {
    static final long ELECTION_TTL_MS = 10 * 60_000L;
    static final long FAILED_RETRY_MS = 2 * 60_000L;
    static final long CHECK_MS = 30_000L;
    /** The election API is cached for up to 5 minutes, so the first fetch after a term ends waits a minute past it. */
    static final long AFTER_TERM_MS = 60_000L;

    /** SkyHanni's Perkpocalypse mayor and when it expires (epoch ms). */
    public record JerryReading(String mayor, long until) { }

    /**
     * What is active. perkpocalypseMayor is null unless Jerry is mayor and the rotation mayor is known; source says how it is
     * known ("SkyHanni", "this term's rotation", "set by you").
     */
    public record State(MayorPerks.Election election, String perkpocalypseMayor, String source, long perkpocalypseUntil,
                        int slot, List<String> perks, Map<String, MayorPerks.Tag> tags) { }

    private static final State EMPTY = new State(null, null, null, 0, -1, List.of(), Map.of());

    private final Callable<String> fetchElection;
    private final Supplier<JerryReading> skyHanni;
    private final LongSupplier clock;
    private final Executor executor;
    private final MayorPerks.Mapping mapping;
    private final Path orderFile;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger fetches = new AtomicInteger();
    private volatile long nextCheck = Long.MIN_VALUE;
    private volatile State state = EMPTY;

    // guarded by this
    private MayorPerks.Election election;
    private long electionDue = Long.MIN_VALUE;
    private long orderTerm = Long.MIN_VALUE;
    private final String[] order, orderSource;

    public MayorWatch(Callable<String> fetchElection, Supplier<JerryReading> skyHanni, LongSupplier clock, Executor executor,
                      MayorPerks.Mapping mapping, Path orderFile) {
        this.fetchElection = fetchElection;
        this.skyHanni = skyHanni;
        this.clock = clock;
        this.executor = executor;
        this.mapping = mapping;
        this.orderFile = orderFile;
        int cycle = mapping.rotation().size();
        this.order = new String[cycle];
        this.orderSource = new String[cycle];
        load();
    }

    // ---- the per-frame side ----

    /** The active perk tied to this Bazaar item, or null. Never blocks; starts a refresh in the background when one is due. */
    public MayorPerks.Tag tag(String itemId) {
        refreshIfStale();
        return itemId == null ? null : state.tags().get(itemId);
    }

    /** The active perk tied to a whole list (e.g. "forge"), or null. */
    public MayorPerks.Tag listTag(String list) {
        refreshIfStale();
        return state.tags().get("list:" + list);
    }

    public State state() {
        refreshIfStale();
        return state;
    }

    /** Election downloads so far (for tests and /flipmayor). */
    public int fetches() {
        return fetches.get();
    }

    public void refreshIfStale() {
        long now = clock.getAsLong();
        if (now < nextCheck) return;
        if (!running.compareAndSet(false, true)) return;
        nextCheck = now + CHECK_MS;
        try {
            executor.execute(() -> {
                try {
                    refresh();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
        }
    }

    // ---- the background side ----

    synchronized void refresh() {
        long now = clock.getAsLong();
        if (now >= electionDue) { // Long.MIN_VALUE at first; a failure waits FAILED_RETRY_MS even with nothing loaded
            try {
                fetches.incrementAndGet();
                MayorPerks.Election e = MayorPerks.parseElection(fetchElection.call());
                if (e != null) election = e;
                electionDue = e == null ? now + FAILED_RETRY_MS
                        : Math.min(now + ELECTION_TTL_MS, MayorPerks.termEnd(now) + AFTER_TERM_MS);
            } catch (Exception ex) {
                electionDue = now + FAILED_RETRY_MS;
            }
        }
        rebuild(now);
    }

    private void rebuild(long now) {
        String mayor = null, source = null;
        long until = 0;
        int slot = -1;
        if (election != null && election.isJerry() && order.length > 0) {
            long start = MayorPerks.termStart(now);
            double hours = mapping.slotHours();
            slot = MayorPerks.slot(start, now, hours);
            if (orderTerm != start) {
                Arrays.fill(order, null);
                Arrays.fill(orderSource, null);
                orderTerm = start;
            }
            JerryReading r = read();
            if (r != null && r.until() > now) {
                String name = mapping.rotationMayor(r.mayor());
                // a reading belongs to the slot its expiry ends; only the current slot's is used
                if (name != null && MayorPerks.slot(start, r.until() - 1, hours) == slot) {
                    learn(slot % order.length, name, "SkyHanni");
                    source = "SkyHanni";
                }
            }
            mayor = order[slot % order.length];
            if (mayor != null) {
                if (source == null) source = orderSource[slot % order.length];
                until = MayorPerks.slotEnd(start, slot, hours);
            }
        }
        List<String> perks = MayorPerks.activePerks(election, mayor, mapping);
        state = new State(election, mayor, mayor == null ? null : source, until, slot, List.copyOf(perks), MayorPerks.tags(perks, mapping));
    }

    private JerryReading read() {
        try {
            return skyHanni == null ? null : skyHanni.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private void learn(int index, String mayor, String source) {
        if (mayor.equals(order[index]) && source.equals("SkyHanni")) return; // already known: no write every 30 seconds
        // the same mayor can't hold two places of one cycle: a new sighting moves it
        for (int i = 0; i < order.length; i++) {
            if (i != index && mayor.equals(order[i])) {
                order[i] = null;
                orderSource[i] = null;
            }
        }
        order[index] = mayor;
        orderSource[index] = source.equals("SkyHanni") ? "this term's rotation" : source;
        save();
    }

    // ---- set by the player ----

    /**
     * Sets this term's rotation from the Calendar or the wiki: one rotation mayor per slot from the election end (slot A
     * first). Returns an error message, or null when set.
     */
    public synchronized String setOrder(List<String> mayors) {
        if (mayors.size() != order.length) return "Give all " + order.length + " mayors, slot A first: " + String.join(" ", mapping.rotation().keySet());
        String[] names = new String[order.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = mapping.rotationMayor(mayors.get(i));
            if (names[i] == null) return "Not a Perkpocalypse mayor: " + mayors.get(i) + " (one of " + String.join(", ", mapping.rotation().keySet()) + ")";
            for (int j = 0; j < i; j++) if (names[j].equals(names[i])) return names[i] + " appears twice";
        }
        long now = clock.getAsLong();
        orderTerm = MayorPerks.termStart(now);
        for (int i = 0; i < names.length; i++) {
            order[i] = names[i];
            orderSource[i] = "set by you";
        }
        save();
        rebuild(now);
        return null;
    }

    /** Sets the mayor for the slot running now (what the Calendar shows). Returns an error message, or null when set. */
    public synchronized String setCurrent(String mayor) {
        String name = mapping.rotationMayor(mayor);
        if (name == null) return "Not a Perkpocalypse mayor: " + mayor + " (one of " + String.join(", ", mapping.rotation().keySet()) + ")";
        long now = clock.getAsLong();
        long start = MayorPerks.termStart(now);
        if (orderTerm != start) {
            Arrays.fill(order, null);
            Arrays.fill(orderSource, null);
            orderTerm = start;
        }
        learn(MayorPerks.slot(start, now, mapping.slotHours()) % order.length, name, "set by you");
        rebuild(now);
        return null;
    }

    public synchronized void forget() {
        Arrays.fill(order, null);
        Arrays.fill(orderSource, null);
        save();
        rebuild(clock.getAsLong());
    }

    // ---- this term's rotation, kept across restarts ----

    private void load() {
        if (orderFile == null || !Files.exists(orderFile)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(orderFile)).getAsJsonObject();
            orderTerm = o.get("termStart").getAsLong();
            JsonArray names = o.getAsJsonArray("order"), sources = o.getAsJsonArray("source");
            for (int i = 0; i < order.length && i < names.size(); i++) {
                order[i] = names.get(i).isJsonNull() ? null : mapping.rotationMayor(names.get(i).getAsString());
                orderSource[i] = order[i] == null || sources == null || i >= sources.size() || sources.get(i).isJsonNull()
                        ? null : sources.get(i).getAsString();
            }
        } catch (Exception e) {
            Arrays.fill(order, null);
            Arrays.fill(orderSource, null);
            orderTerm = Long.MIN_VALUE;
        }
    }

    private void save() {
        if (orderFile == null) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("termStart", orderTerm);
            JsonArray names = new JsonArray(), sources = new JsonArray();
            for (int i = 0; i < order.length; i++) {
                names.add(order[i]);
                sources.add(orderSource[i]);
            }
            o.add("order", names);
            o.add("source", sources);
            Files.writeString(orderFile, new Gson().toJson(o));
        } catch (Exception ignored) { }
    }

    /**
     * A list row's title badges: the active perk that moves the item, then a confirmed 24-hour shift or faster fills. With the
     * Mayor flipper off: "" without asking for the watch (no election download, nothing built).
     */
    public static String badges(String id, Supplier<MayorWatch> watch, JsonObject products) {
        if (!MarketShift.enabled() || id == null) return "";
        MayorPerks.Tag tag = watch.get().tag(id);
        JsonObject product = products != null && products.has(id) && products.get(id).isJsonObject() ? products.getAsJsonObject(id) : null;
        JsonObject quickStatus = product != null && product.has("quick_status") ? product.getAsJsonObject("quick_status") : null;
        return (tag == null ? "" : tag.badge()) + MarketShift.badge(id, quickStatus);
    }

    /** This term's known rotation, slot A first (null where not seen yet). */
    public synchronized List<String> order() {
        return new ArrayList<>(Arrays.asList(order));
    }
}
