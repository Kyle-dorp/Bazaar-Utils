package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.Flippers.Flipper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The Mayor flipper: off means the 7-day behaviour everywhere, nothing kept and the election never asked for. */
class MayorToggleTest {
    @AfterEach
    void allOn() {
        Flippers.set(Flipper.MAYOR, true);
        MarketShift.put("TOGGLE_ITEM", null);
    }

    /** A day at 60% of the old level on both sides. */
    private static JsonArray shifted() {
        JsonArray a = new JsonArray();
        long newest = System.currentTimeMillis() / 1000 * 1000 - 4 * 3_600_000L;
        for (int i = 0; i < 82; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("sell", i < 12 ? 60 : 100);
            o.addProperty("buy", i < 12 ? 66 : 110);
            o.addProperty("buyMovingWeek", 7000);
            o.addProperty("sellMovingWeek", 7000);
            o.addProperty("timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(newest - i * 7_200_000L), ZoneOffset.UTC)));
            a.add(o);
        }
        return a;
    }

    @Test
    void mayorIsAFlipperAndOnByDefault() {
        assertTrue(Flippers.on(Flipper.MAYOR));
        assertEquals(Flipper.values().length - 1, Flipper.MAYOR.ordinal(), "last: the settings buttons go by ordinal");
    }

    @Test
    void offTheHistoryIsNeitherBuiltNorUsed() {
        MarketShift.store("TOGGLE_ITEM", shifted());
        assertNotNull(MarketShift.of("TOGGLE_ITEM"));
        Flippers.set(Flipper.MAYOR, false);
        assertNull(MarketShift.of("TOGGLE_ITEM"), "callers fall back to the 7-day rules");
        JsonObject qs = new JsonObject();
        qs.addProperty("productId", "TOGGLE_ITEM");
        qs.addProperty("buyMovingWeek", 14_000);
        qs.addProperty("sellMovingWeek", 14_000);
        assertEquals(14_000 / 168.0, MarketShift.perHour(qs, "buyMovingWeek"), 1e-9, "exactly the weekly rate");
        MarketShift.store("TOGGLE_ITEM", shifted());
        Flippers.set(Flipper.MAYOR, true);
        assertNull(MarketShift.of("TOGGLE_ITEM"), "nothing was kept while off");
    }

    @Test
    void offTheMayorWatchIsNeverAskedFor() {
        AtomicInteger asked = new AtomicInteger();
        Flippers.set(Flipper.MAYOR, false);
        for (int frame = 0; frame < 1000; frame++) {
            assertEquals("", MayorWatch.badges("JERRY_BOX_GREEN", () -> { asked.incrementAndGet(); return null; }, null));
        }
        assertEquals(0, asked.get());
    }

    @Test
    void onTheBadgesShowThePerkAndTheShift() {
        AtomicInteger downloads = new AtomicInteger();
        MayorWatch w = new MayorWatch(() -> { downloads.incrementAndGet(); return MayorPerksTest.JERRY; }, () -> null,
                System::currentTimeMillis, Runnable::run, MayorPerks.bundledMapping(), null);
        assertEquals(" §d[Jerrypocalypse]", MayorWatch.badges("JERRY_BOX_GREEN", () -> w, null));
        MarketShift.store("TOGGLE_ITEM", shifted());
        assertEquals(" §e[24h -40%]", MayorWatch.badges("TOGGLE_ITEM", () -> w, null));
        assertEquals(1, downloads.get());
    }
}
