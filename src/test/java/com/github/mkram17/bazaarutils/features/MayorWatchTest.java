package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.mkram17.bazaarutils.features.MayorPerksTest.utc;
import static org.junit.jupiter.api.Assertions.*;

class MayorWatchTest {
    private static final long MIN = 60_000L, H = 3_600_000L;
    private static final long TERM = utc("2026-10-20T15:15:00Z"); // Jerry's term, slot A from here

    private final AtomicLong clock = new AtomicLong(TERM + 17 * H + 47 * MIN); // 2026-10-21 09:02 UTC, slot C (index 2)
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicReference<String> election = new AtomicReference<>(MayorPerksTest.JERRY);
    private final AtomicReference<MayorWatch.JerryReading> skyHanni = new AtomicReference<>();

    private MayorWatch watch(Path file) {
        return new MayorWatch(() -> {
            downloads.incrementAndGet();
            String e = election.get();
            if (e == null) throw new java.io.IOException("offline");
            return e;
        }, skyHanni::get, clock::get, Runnable::run, MayorPerks.bundledMapping(), file);
    }

    private MayorWatch watch() {
        return watch(null);
    }

    // ---- cost: the per-frame lookups never download ----

    @Test
    void framesOfLookupsDownloadTheElectionOncePerTenMinutes() {
        MayorWatch w = watch();
        for (int frame = 0; frame < 10_000; frame++) {
            w.tag("REFINED_MINERAL");
            clock.addAndGet(50); // 20 fps for 500 seconds
        }
        assertEquals(1, downloads.get());
        assertEquals(1, w.fetches());
        clock.addAndGet(2 * MIN);
        w.tag("REFINED_MINERAL");
        assertEquals(2, downloads.get(), "due again after 10 minutes");
    }

    @Test
    void aFailedDownloadIsRetriedAfterTwoMinutesNotEveryFrame() {
        election.set(null);
        MayorWatch w = watch();
        for (int i = 0; i < 1000; i++) {
            w.tag("X");
            clock.addAndGet(100);
        }
        assertEquals(1, downloads.get(), "100 seconds of frames");
        clock.addAndGet(30_000);
        election.set(MayorPerksTest.JERRY);
        w.tag("X");
        assertEquals(2, downloads.get());
        assertTrue(w.state().election().isJerry());
    }

    @Test
    void onlyOneRefreshRunsAtATime() {
        List<Runnable> queued = new ArrayList<>();
        MayorWatch w = new MayorWatch(() -> { downloads.incrementAndGet(); return MayorPerksTest.JERRY; }, () -> null, clock::get,
                queued::add, MayorPerks.bundledMapping(), null);
        for (int i = 0; i < 100; i++) {
            w.tag("X");
            clock.addAndGet(CHECK_MS_PLUS);
        }
        assertEquals(1, queued.size(), "still running: no second refresh queued");
        queued.get(0).run();
        assertEquals(1, downloads.get());
        w.tag("X");
        assertEquals(2, queued.size());
    }

    private static final long CHECK_MS_PLUS = MayorWatch.CHECK_MS + 1;

    @Test
    void aNewTermIsDownloadedAMinuteAfterItStarts() {
        clock.set(TERM - 3 * MIN);
        election.set(MayorPerksTest.DIAZ);
        MayorWatch w = watch();
        w.tag("X");
        assertEquals("Diaz", w.state().election().mayorName());
        election.set(MayorPerksTest.JERRY);
        clock.set(TERM + MIN + MayorWatch.CHECK_MS);
        w.tag("X");
        assertEquals(2, downloads.get(), "before the 10 minutes were up");
        assertTrue(w.state().election().isJerry());
    }

    // ---- Jerry's Perkpocalypse mayor ----

    @Test
    void skyHannisReadingTagsTheRotationMayorsItems() {
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 18 * H)); // slot C ends 09:15
        MayorWatch w = watch();
        assertEquals("Mining Fiesta", w.tag("REFINED_MINERAL").perk());
        assertEquals("Jerrypocalypse", w.tag("JERRY_BOX_BLUE").perk());
        assertEquals("Cole", w.state().perkpocalypseMayor());
        assertEquals("SkyHanni", w.state().source());
        assertEquals(TERM + 18 * H, w.state().perkpocalypseUntil());
    }

    @Test
    void anExpiredOrUnknownReadingTagsNothingExtra() {
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 12 * H)); // slot B's, already over
        MayorWatch w = watch();
        assertNull(w.tag("REFINED_MINERAL"));
        assertNull(w.state().perkpocalypseMayor());
        assertEquals("Jerrypocalypse", w.tag("JERRY_BOX_GREEN").perk(), "Jerry's own perks still count");
        skyHanni.set(new MayorWatch.JerryReading("Unknown", TERM + 24 * H));
        clock.addAndGet(MayorWatch.CHECK_MS);
        assertNull(w.tag("REFINED_MINERAL"));
    }

    @Test
    void aReadingForAnotherSlotIsNotThisSlots() {
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 30 * H)); // claims to run until slot E ends
        MayorWatch w = watch();
        assertNull(w.tag("REFINED_MINERAL"));
        assertTrue(w.order().stream().allMatch(java.util.Objects::isNull), "nothing learned");
    }

    @Test
    void theRotationRepeatsEvery36Hours() {
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 18 * H));
        MayorWatch w = watch();
        w.tag("X");
        skyHanni.set(null); // you stop opening the Calendar
        clock.addAndGet(6 * H);
        clock.addAndGet(MayorWatch.CHECK_MS);
        assertNull(w.tag("REFINED_MINERAL"), "slot D: not seen yet");
        clock.set(TERM + 36 * H + 12 * H + MIN); // slot C of the second cycle
        assertEquals("Mining Fiesta", w.tag("REFINED_MINERAL").perk());
        assertEquals("this term's rotation", w.state().source());
        assertEquals(TERM + 54 * H, w.state().perkpocalypseUntil());
    }

    @Test
    void theRotationIsForgottenNextTerm() {
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 18 * H));
        MayorWatch w = watch();
        w.tag("X");
        skyHanni.set(null);
        clock.set(TERM + MayorPerks.YEAR_MS + 12 * H + MIN); // slot C of a later Jerry term
        assertNull(w.tag("REFINED_MINERAL"));
    }

    @Test
    void aMayorSeenInANewPlaceMovesThere() {
        MayorWatch w = watch();
        assertNull(w.setCurrent("Cole"));
        clock.addAndGet(6 * H);
        assertNull(w.setCurrent("cole"));
        List<String> order = w.order();
        assertNull(order.get(2));
        assertEquals("Cole", order.get(3));
    }

    @Test
    void settingTheWholeOrderFromTheWiki() {
        MayorWatch w = watch();
        assertNotNull(w.setOrder(List.of("Cole", "Diana")), "all six");
        assertNotNull(w.setOrder(List.of("Cole", "Diana", "Paul", "Aatrox", "Marina", "Foxy")), "Foxy isn't in the rotation");
        assertNotNull(w.setOrder(List.of("Cole", "Diana", "Paul", "Aatrox", "Marina", "Cole")), "twice");
        assertNull(w.setOrder(List.of("Diana", "Paul", "Cole", "Aatrox", "Marina", "Finnegan")));
        assertEquals("Mining Fiesta", w.tag("REFINED_MINERAL").perk(), "slot C is Cole");
        assertEquals("set by you", w.state().source());
        clock.set(TERM + 36 * H + MIN);
        assertEquals("Mythological Ritual", w.tag("GRIFFIN_FEATHER").perk(), "slot A of the second cycle is Diana");
    }

    @Test
    void skyHanniOverridesAWrongEntry() {
        MayorWatch w = watch();
        assertNull(w.setOrder(List.of("Diana", "Paul", "Aatrox", "Cole", "Marina", "Finnegan")));
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 18 * H));
        clock.addAndGet(MayorWatch.CHECK_MS);
        w.tag("X");
        List<String> order = w.order();
        assertEquals("Cole", order.get(2));
        assertNull(order.get(3), "Cole can't be in two places");
    }

    @Test
    void theRotationSurvivesARestartWithinTheTerm(@TempDir Path dir) {
        Path file = dir.resolve("perkpocalypse.json");
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 18 * H));
        watch(file).tag("X");
        skyHanni.set(null);
        MayorWatch restarted = watch(file);
        assertEquals("Mining Fiesta", restarted.tag("REFINED_MINERAL").perk());
        assertEquals("this term's rotation", restarted.state().source());
    }

    @Test
    void aBrokenSkyHanniIsIgnored() {
        MayorWatch w = new MayorWatch(() -> MayorPerksTest.JERRY, () -> { throw new NoSuchMethodError("renamed"); }, clock::get,
                Runnable::run, MayorPerks.bundledMapping(), null);
        assertEquals("Jerrypocalypse", w.tag("JERRY_BOX_PURPLE").perk());
        assertNull(w.tag("REFINED_MINERAL"));
    }

    @Test
    void notJerryNoRotation() {
        election.set(MayorPerksTest.DIAZ);
        skyHanni.set(new MayorWatch.JerryReading("Cole", TERM + 18 * H)); // a stale reading from Jerry's term
        MayorWatch w = watch();
        assertNull(w.tag("REFINED_MINERAL"));
        assertEquals("Stock Exchange", w.tag("STOCK_OF_STONKS").perk());
        assertNull(w.state().perkpocalypseMayor());
    }
}
