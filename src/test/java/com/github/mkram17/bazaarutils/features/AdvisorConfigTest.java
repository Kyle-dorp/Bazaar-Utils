package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AdvisorConfigTest {
    @TempDir
    Path dir;

    /** The player's answers on 2026-10-17: a 5-10% share depending on competition (7.5% without an order book), 150 combines an hour. */
    @Test
    void defaultsAreThePlayersOwnEstimates() {
        AdvisorConfig d = AdvisorConfig.read(dir.resolve("missing.json"));
        assertEquals(AdvisorConfig.DEFAULTS, d);
        assertEquals(1, d.orderCheckHours());
        assertEquals(7, d.maxActive());
        assertFalse(d.afk());
        assertEquals(5, d.shareMinPercent());
        assertEquals(10, d.shareMaxPercent());
        assertEquals(150, d.combinesPerHour());
        assertEquals(0.075, d.shares(null).base(), 1e-12);
    }

    @Test
    void settingsRoundTripAndBadValuesFallBack() throws Exception {
        Path f = dir.resolve("bazaarutils-advisor.json");
        AdvisorConfig c = AdvisorConfig.DEFAULTS.withOrderCheckHours(3).withMaxActive(4).withAfk(true).withShare(12, 6)
                .withCombinesPerHour(90);
        assertEquals(6, c.shareMinPercent(), "min and max either way round");
        assertEquals(12, c.shareMaxPercent());
        c.write(f);
        assertEquals(c, AdvisorConfig.read(f));

        Files.writeString(f, "{\"maxActive\": 99, \"orderCheckHours\": \"soon\", \"combinesPerHour\": -5}");
        AdvisorConfig r = AdvisorConfig.read(f);
        assertEquals(AdvisorConfig.MAX_ACTIVE, r.maxActive());
        assertEquals(1, r.orderCheckHours(), "unreadable: the default");
        assertEquals(0, r.combinesPerHour());
        assertEquals(5, r.shareMinPercent(), "missing: the default");

        Files.writeString(f, "not json");
        assertEquals(AdvisorConfig.DEFAULTS, AdvisorConfig.read(f));
    }
}
