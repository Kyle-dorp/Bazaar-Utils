package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MayorPerksTest {
    /** The election API on 2026-10-21 with Jerry in office (trimmed): his own perks only, no minister. */
    static final String JERRY = """
            {"success":true,"lastUpdated":1792573000000,"mayor":{"key":"jerry","name":"Jerry","perks":[
              {"name":"Perkpocalypse","description":"Activates all perks of another mayor every 18 SkyBlock days (6 hours)."},
              {"name":"Statspocalypse","description":"Increases all §cCombat §7and §dMiscellaneous §7stats by §a10%§7."},
              {"name":"Jerrypocalypse","description":"Reveal hidden Jerries from logging, farming, mining and killing mobs."}],
              "election":{"year":520,"candidates":[]}}}""";

    /** The election API on 2026-10-18 (trimmed): Diaz with minister Finnegan. */
    static final String DIAZ = """
            {"success":true,"mayor":{"key":"economist","name":"Diaz","perks":[
              {"name":"Stock Exchange","description":"..."},{"name":"Volume Trading","description":"..."}],
              "minister":{"key":"farming","name":"Finnegan","perk":{"name":"Blooming Business","description":"..."}},
              "election":{"year":519,"candidates":[]}},"current":{"year":520,"candidates":[]}}""";

    static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    // ---- calendar: the wiki's Year 521 Perkpocalypse table ----

    @Test
    void jerrysTermMatchesTheWiki() {
        long start = utc("2026-10-20T15:15:00Z"); // "Late Spring 27th, Year 521 ... Tue Oct 20 2026 15:15:00 GMT"
        assertEquals(start, MayorPerks.termStart(utc("2026-10-21T09:02:00Z")));
        assertEquals(start, MayorPerks.termStart(start));
        assertEquals(utc("2026-10-15T11:15:00Z"), MayorPerks.termStart(start - 1), "Diaz's term");
        assertEquals(utc("2026-10-25T19:15:00Z"), MayorPerks.termEnd(start));
    }

    @Test
    void perkpocalypseSlotsAreSixHoursFromTheElectionEnd() {
        long start = utc("2026-10-20T15:15:00Z");
        assertEquals(0, MayorPerks.slot(start, start, 6));
        assertEquals(0, MayorPerks.slot(start, utc("2026-10-20T21:14:59Z"), 6));
        assertEquals(1, MayorPerks.slot(start, utc("2026-10-20T21:15:00Z"), 6));
        assertEquals(2, MayorPerks.slot(start, utc("2026-10-21T09:02:00Z"), 6), "slot C");
        assertEquals(utc("2026-10-21T09:15:00Z"), MayorPerks.slotEnd(start, 2, 6));
        // 21 windows; the last runs 15:15 to 19:15 only
        int last = MayorPerks.slot(start, MayorPerks.termEnd(start) - 1, 6);
        assertEquals(20, last);
        assertEquals(2, last % 6, "the last window is slot C again");
        assertEquals(utc("2026-10-25T19:15:00Z"), MayorPerks.slotEnd(start, last, 6));
    }

    // ---- election API ----

    @Test
    void readsJerryWithoutAMinister() {
        MayorPerks.Election e = MayorPerks.parseElection(JERRY);
        assertTrue(e.isJerry());
        assertEquals(List.of("Perkpocalypse", "Statspocalypse", "Jerrypocalypse"), e.perks());
        assertNull(e.ministerName());
    }

    @Test
    void readsMayorAndMinister() {
        MayorPerks.Election e = MayorPerks.parseElection(DIAZ);
        assertFalse(e.isJerry());
        assertEquals("Diaz", e.mayorName());
        assertEquals("Finnegan", e.ministerName());
        assertEquals("Blooming Business", e.ministerPerk());
    }

    @Test
    void brokenResponsesAreNoElection() {
        assertNull(MayorPerks.parseElection("{\"success\":false}"));
        assertNull(MayorPerks.parseElection("<html>502</html>"));
    }

    // ---- the data file ----

    @Test
    void bundledDataFileIsComplete() {
        MayorPerks.Mapping m = MayorPerks.bundledMapping();
        assertEquals(6, m.slotHours());
        assertEquals(List.of("Aatrox", "Cole", "Diana", "Finnegan", "Marina", "Paul"), List.copyOf(m.rotation().keySet()));
        assertTrue(m.excludedPerks().contains("grand feast"));
        assertFalse(m.groups().isEmpty());
        Pattern id = Pattern.compile("[A-Z0-9_]+");
        for (MayorPerks.Group g : m.groups()) {
            assertFalse(g.perks().isEmpty(), g.toString());
            assertFalse(g.items().isEmpty() && g.lists().isEmpty(), g.toString());
            assertNotNull(g.note(), g.toString());
            for (String i : g.items()) assertTrue(id.matcher(i).matches(), "not a Bazaar id: " + i);
        }
        // every perk a rotation mayor brings that the file tags is spelled the way the rotation spells it
        for (var e : m.rotation().entrySet()) assertFalse(m.perkpocalypsePerks(e.getKey()).isEmpty(), e.getKey());
    }

    @Test
    void jerryActivatesAllOfTheRotationMayorsPerksButGrandFeast() {
        MayorPerks.Mapping m = MayorPerks.bundledMapping();
        MayorPerks.Election jerry = MayorPerks.parseElection(JERRY);
        List<String> perks = MayorPerks.activePerks(jerry, "Finnegan", m);
        assertTrue(perks.containsAll(List.of("Perkpocalypse", "Jerrypocalypse", "Pest Eradicator", "GOATed", "Blooming Business")));
        assertFalse(perks.contains("Grand Feast"));
        assertEquals(List.of("Perkpocalypse", "Statspocalypse", "Jerrypocalypse"), MayorPerks.activePerks(jerry, null, m),
                "rotation mayor unknown: Jerry's own perks");
        assertTrue(MayorPerks.activePerks(MayorPerks.parseElection(DIAZ), "Cole", m).stream().noneMatch(p -> p.equals("Mining Fiesta")),
                "only Jerry brings a rotation mayor");
    }

    @Test
    void tagsTheItemsOfActivePerks() {
        MayorPerks.Mapping m = MayorPerks.bundledMapping();
        Map<String, MayorPerks.Tag> t = MayorPerks.tags(MayorPerks.activePerks(MayorPerks.parseElection(JERRY), "Cole", m), m);
        assertEquals("Mining Fiesta", t.get("REFINED_MINERAL").perk());
        assertEquals("Jerrypocalypse", t.get("JERRY_BOX_GOLDEN").perk());
        assertEquals("Molten Forge", t.get("list:forge").perk());
        assertNull(t.get("GRIFFIN_FEATHER"), "Diana isn't up");
        assertEquals(" §d[Mining Fiesta]", t.get("GLOSSY_GEMSTONE").badge());

        Map<String, MayorPerks.Tag> diaz = MayorPerks.tags(MayorPerks.activePerks(MayorPerks.parseElection(DIAZ), null, m), m);
        assertEquals("Stock Exchange", diaz.get("STOCK_OF_STONKS").perk());
        assertEquals("Blooming Business", diaz.get("FINE_FLOUR").perk(), "the minister's perk counts");
        assertNull(diaz.get("JERRY_BOX_GREEN"));
    }

    @Test
    void perkNamesMatchIgnoringCaseColoursAndSpaces() {
        MayorPerks.Mapping m = MayorPerks.readMapping(new StringReader("""
                {"groups":[{"perks":["SLASHED Pricing","Pathfinder"],"effect":"supply","note":"n","items":["NULL_SPHERE"]}]}"""));
        assertEquals("§6Pathfinder ", MayorPerks.tags(List.of("§6Pathfinder "), m).get("NULL_SPHERE").perk());
        assertNotNull(MayorPerks.tags(List.of("slashed pricing"), m).get("NULL_SPHERE"));
        assertTrue(MayorPerks.tags(List.of("Path finder"), m).isEmpty());
        assertTrue(MayorPerks.tags(List.of(), m).isEmpty());
    }
}
