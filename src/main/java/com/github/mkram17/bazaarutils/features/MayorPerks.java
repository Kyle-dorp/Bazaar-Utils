package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which mayor perks are active and which Bazaar items they move. Pure: the election JSON, the perk data file
 * (assets/bazaarutils/mayor-perks.json) and the SkyBlock calendar in, perk names and item tags out. {@link MayorWatch} keeps
 * them current in game.
 *
 * <p>Jerry (special mayor, every 24 SkyBlock years) has Perkpocalypse: all perks of another mayor, a new one every 6 hours from
 * the election end, the six rotation mayors in a random order that then repeats every 36 hours. The election API names
 * Jerry's own perks only, so the rotation mayor comes from SkyHanni or from the order already seen this term.
 */
public final class MayorPerks {
    private MayorPerks() { }

    // ---- SkyBlock calendar ----

    /** Start of SkyBlock year 1 (2019-06-11 17:55 UTC); a year is 124 hours, a month 31 days, a day 20 minutes. */
    static final long YEAR_ONE_MS = 1_560_275_700_000L;
    static final long YEAR_MS = 124 * 3_600_000L, MONTH_MS = 31 * 1_200_000L, DAY_MS = 1_200_000L;
    /** Elections end (and the new mayor takes office) on Late Spring 27th: the 3rd month, 27th day. */
    static final long ELECTION_END_OFFSET_MS = 2 * MONTH_MS + 26 * DAY_MS;

    /** When the term running at time t began (the latest election end at or before t). */
    public static long termStart(long t) {
        long first = YEAR_ONE_MS + ELECTION_END_OFFSET_MS;
        long k = Math.floorDiv(t - first, YEAR_MS);
        return first + k * YEAR_MS;
    }

    /** When the term running at time t ends. */
    public static long termEnd(long t) {
        return termStart(t) + YEAR_MS;
    }

    /** Perkpocalypse slot (0, 1, 2, ...) at time t of the term that began at termStart. */
    public static int slot(long termStart, long t, double slotHours) {
        return (int) Math.floorDiv(t - termStart, (long) (slotHours * 3_600_000L));
    }

    /** When a Perkpocalypse slot ends (the last slot of a term is cut short by the term end). */
    public static long slotEnd(long termStart, int slot, double slotHours) {
        return Math.min(termStart + (slot + 1L) * (long) (slotHours * 3_600_000L), termStart + YEAR_MS);
    }

    // ---- election API ----

    /** The parts of /v2/resources/skyblock/election this uses. perks are the mayor's own (as elected, not all of them). */
    public record Election(String mayorKey, String mayorName, List<String> perks, String ministerName, String ministerPerk) {
        public boolean isJerry() {
            return "jerry".equalsIgnoreCase(mayorKey) || "Jerry".equalsIgnoreCase(mayorName);
        }
    }

    /** Parses the election response; null when it has no mayor. */
    public static Election parseElection(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("mayor") || !root.get("mayor").isJsonObject()) return null;
            JsonObject m = root.getAsJsonObject("mayor");
            List<String> perks = new ArrayList<>();
            if (m.has("perks") && m.get("perks").isJsonArray()) {
                for (JsonElement p : m.getAsJsonArray("perks")) {
                    if (p.isJsonObject() && p.getAsJsonObject().has("name")) perks.add(p.getAsJsonObject().get("name").getAsString().trim());
                }
            }
            String ministerName = null, ministerPerk = null;
            if (m.has("minister") && m.get("minister").isJsonObject()) {
                JsonObject mi = m.getAsJsonObject("minister");
                ministerName = string(mi, "name");
                if (mi.has("perk") && mi.get("perk").isJsonObject()) ministerPerk = string(mi.getAsJsonObject("perk"), "name");
            }
            return new Election(string(m, "key"), string(m, "name"), List.copyOf(perks), ministerName, ministerPerk);
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : null;
    }

    // ---- perk data file ----

    /** One line of the data file: any of these perks active tags these items (and these whole lists, e.g. "forge"). */
    public record Group(List<String> perks, String effect, String note, List<String> items, List<String> lists) { }

    /** The data file: the Perkpocalypse rotation (mayor name to all their perks) and the item groups. */
    public record Mapping(double slotHours, Set<String> excludedPerks, Map<String, List<String>> rotation, List<Group> groups) {
        /** All perks of a rotation mayor that Jerry activates, by name (any case); empty for an unknown name. */
        public List<String> perkpocalypsePerks(String mayor) {
            if (mayor == null) return List.of();
            for (var e : rotation.entrySet()) {
                if (!e.getKey().equalsIgnoreCase(mayor.trim())) continue;
                List<String> out = new ArrayList<>();
                for (String p : e.getValue()) if (!excludedPerks.contains(key(p))) out.add(p);
                return out;
            }
            return List.of();
        }

        /** The rotation mayor's name as the data file spells it, or null if it isn't one. */
        public String rotationMayor(String name) {
            if (name == null) return null;
            for (String m : rotation.keySet()) if (m.equalsIgnoreCase(name.trim())) return m;
            return null;
        }
    }

    public static Mapping bundledMapping() {
        try (InputStream in = MayorPerks.class.getResourceAsStream("/assets/bazaarutils/mayor-perks.json")) {
            return readMapping(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new Mapping(6, Set.of(), Map.of(), List.of());
        }
    }

    public static Mapping readMapping(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        double slotHours = 6;
        Set<String> excluded = new LinkedHashSet<>();
        Map<String, List<String>> rotation = new LinkedHashMap<>();
        if (root.has("perkpocalypse")) {
            JsonObject p = root.getAsJsonObject("perkpocalypse");
            if (p.has("slotHours")) slotHours = p.get("slotHours").getAsDouble();
            for (String s : strings(p, "excludedPerks")) excluded.add(key(s));
            if (p.has("mayors")) {
                for (var e : p.getAsJsonObject("mayors").entrySet()) rotation.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
            }
        }
        List<Group> groups = new ArrayList<>();
        if (root.has("groups")) {
            for (JsonElement e : root.getAsJsonArray("groups")) {
                JsonObject g = e.getAsJsonObject();
                groups.add(new Group(strings(g, "perks"), string(g, "effect"), string(g, "note"), strings(g, "items"), strings(g, "lists")));
            }
        }
        return new Mapping(slotHours, Collections.unmodifiableSet(excluded), Collections.unmodifiableMap(rotation), List.copyOf(groups));
    }

    private static List<String> strings(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? strings(o.getAsJsonArray(key)) : List.of();
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsString().trim());
        return List.copyOf(out);
    }

    /** Perk names compare ignoring case, colour codes and surrounding spaces. */
    static String key(String perk) {
        return perk == null ? "" : perk.replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
    }

    // ---- active perks and tags ----

    /**
     * Perks active now: the mayor's, the minister's, and while Jerry is mayor all perks of the Perkpocalypse mayor (null if
     * not known). In the order shown to the player.
     */
    public static List<String> activePerks(Election e, String perkpocalypseMayor, Mapping m) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (e == null) return out;
        List<String> all = new ArrayList<>(e.perks());
        if (e.ministerPerk() != null) all.add(e.ministerPerk());
        if (e.isJerry()) all.addAll(m.perkpocalypsePerks(perkpocalypseMayor));
        for (String p : all) if (seen.add(key(p))) out.add(p);
        return out;
    }

    /** What a tag says: the active perk tied to an item or list, and its effect from the data file. */
    public record Tag(String perk, String effect, String note) {
        /** The badge drawn after a row's name, e.g. " §d[Mining Fiesta]". */
        public String badge() {
            return " §d[" + perk + "]";
        }
    }

    /** Item and list tags for these active perks: item id (or "list:forge") to the first active perk that names it. */
    public static Map<String, Tag> tags(List<String> activePerks, Mapping m) {
        Map<String, String> active = new HashMap<>();
        for (String p : activePerks) active.putIfAbsent(key(p), p);
        Map<String, Tag> out = new HashMap<>();
        for (Group g : m.groups()) {
            String hit = null;
            for (String p : g.perks()) {
                if (active.containsKey(key(p))) {
                    hit = active.get(key(p));
                    break;
                }
            }
            if (hit == null) continue;
            Tag t = new Tag(hit, g.effect(), g.note());
            for (String id : g.items()) out.putIfAbsent(id, t);
            for (String list : g.lists()) out.putIfAbsent("list:" + list, t);
        }
        return Collections.unmodifiableMap(out);
    }
}
