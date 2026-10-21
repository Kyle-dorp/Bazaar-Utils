package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.Flippers.Flipper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlippersTest {
    @AfterEach
    void allOn() {
        Flippers.load(List.of());
        FlipPause.set(false, null);
    }

    @Test
    void everythingIsOnByDefault() {
        for (Flipper f : Flipper.values()) assertTrue(Flippers.on(f), f.name());
        assertEquals(EnumSet.of(Flipper.FLIPS, Flipper.BOOKS), Flippers.feeding(0));
        for (int mode = 0; mode < 4; mode++) assertTrue(Flippers.modeOn(mode));
    }

    @Test
    void aFlipperThatsOffContributesNoRowsAndComputesNothing() {
        AtomicInteger computed = new AtomicInteger();
        Flippers.set(Flipper.FUSIONS, false);
        assertEquals(List.of(), Flippers.only(Flipper.FUSIONS, () -> { computed.incrementAndGet(); return List.of("row"); }));
        assertEquals(0, computed.get(), "nothing computed for a flipper that's off");
        assertEquals(List.of("row"), Flippers.only(Flipper.FORGE, () -> { computed.incrementAndGet(); return List.of("row"); }));
        assertEquals(1, computed.get());
        assertEquals(Set.of(), Flippers.feeding(2), "the fusions list has nothing to show");
        assertFalse(Flippers.modeOn(2));
    }

    @Test
    void theFlipsListKeepsBookCraftsWhenBazaarFlipsAreOff() {
        Flippers.set(Flipper.FLIPS, false);
        assertEquals(EnumSet.of(Flipper.BOOKS), Flippers.feeding(0));
        assertTrue(Flippers.modeOn(0));
        Flippers.set(Flipper.BOOKS, false);
        assertFalse(Flippers.modeOn(0));
    }

    @Test
    void theCycleKeySkipsListsThatAreOff() {
        Flippers.set(Flipper.DIPS, false);
        Flippers.set(Flipper.FUSIONS, false);
        assertEquals(3, Flippers.nextMode(0, 4), "flips -> forge, skipping dips and fusions");
        assertEquals(0, Flippers.nextMode(3, 4), "wraps around");
        for (Flipper f : Flipper.values()) Flippers.set(f, false);
        assertEquals(2, Flippers.nextMode(2, 4), "nothing on: stays put");
    }

    @Test
    void choicesSurviveSaveAndLoad() {
        Flippers.set(Flipper.FORGE, false);
        Flippers.set(Flipper.ADVISOR, false);
        List<String> saved = Flippers.offNames();
        Flippers.load(List.of());
        assertTrue(Flippers.on(Flipper.FORGE));
        Flippers.load(saved);
        assertFalse(Flippers.on(Flipper.FORGE));
        assertFalse(Flippers.on(Flipper.ADVISOR));
        assertTrue(Flippers.on(Flipper.FLIPS));
        Flippers.load(List.of("NOT_A_FLIPPER"));
        for (Flipper f : Flipper.values()) assertTrue(Flippers.on(f), "unknown names are ignored");
    }

    @Test
    void calculatorsThatAreOffStartNoRefresh() throws Exception {
        Flippers.set(Flipper.FUSIONS, false);
        Flippers.set(Flipper.FORGE, false);
        for (Class<?> c : List.of(FusionCalc.class, ForgeCalc.class)) {
            Field last = c.getDeclaredField("lastRefresh"), running = c.getDeclaredField("running");
            last.setAccessible(true);
            running.setAccessible(true);
            last.setLong(null, 0); // long overdue: would refresh at once if on
            c.getMethod("refreshIfStale").invoke(null);
            assertEquals(0, last.getLong(null), c.getSimpleName() + " started a refresh while turned off");
            assertFalse(running.getBoolean(null), c.getSimpleName());
        }
    }

    @Test
    void theFusionMachinePanelReadsNothingWhenOff() throws Exception {
        Flippers.set(Flipper.POTENTIAL, false);
        Field heldAt = ShardPotential.class.getDeclaredField("heldAt");
        heldAt.setAccessible(true);
        heldAt.setLong(null, 0);
        assertSame(FusionPotential.EMPTY, ShardPotential.get());
        assertEquals(0, heldAt.getLong(null), "read SkyHanni's shard counts while the panel is off");
    }

    @Test
    void bazaarFlipChecksStopOnlyWhenBothFlipsAndDipsAreOff() throws Exception {
        assertEquals(0, checksIn150ms(Flipper.FLIPS, Flipper.DIPS), "both off: no Bazaar checks");
        Flippers.load(List.of());
        assertTrue(checksIn150ms(Flipper.FLIPS) > 0, "dips still need them");
    }

    private static int checksIn150ms(Flipper... off) throws InterruptedException {
        for (Flipper f : off) Flippers.set(f, false);
        ScheduledExecutorService scheduler = FlipPause.pausableScheduler(1, Thread::new);
        try {
            AtomicInteger checks = new AtomicInteger();
            scheduler.scheduleAtFixedRate(checks::incrementAndGet, 0, 5, TimeUnit.MILLISECONDS);
            Thread.sleep(150);
            return checks.get();
        } finally {
            scheduler.shutdownNow();
        }
    }
}
