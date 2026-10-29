package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MenuSearchTest {
    @Test
    void closesTheOldMenuBeforeSendingTheNewSearch() {
        MenuSearch search = new MenuSearch(750);
        List<String> events = new ArrayList<>();
        assertTrue(search.run(0, "bz Gazer Shard", () -> events.add("close"), events::add));
        assertEquals(List.of("close", "bz Gazer Shard"), events);
    }

    @Test
    void rapidRepeatsNeitherCloseNorSendAgain() {
        MenuSearch search = new MenuSearch(750);
        List<String> events = new ArrayList<>();
        assertTrue(search.run(0, "bz Gazer Shard", () -> events.add("close"), events::add));
        for (long now = 1; now < 750; now += 10) {
            assertFalse(search.run(now, "bz Gazer Shard", () -> events.add("close"), events::add));
        }
        assertEquals(2, events.size());
        assertTrue(search.run(750, "bz Gazer Shard", () -> events.add("close"), events::add));
        assertEquals(4, events.size());
    }

    @Test
    void failedCloseDoesNotSendOrConsumeTheCooldown() {
        MenuSearch search = new MenuSearch(750);
        List<String> events = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> search.run(0, "bz Gazer Shard", () -> {
            throw new IllegalStateException();
        }, events::add));
        assertTrue(events.isEmpty());
        assertTrue(search.run(0, "bz Gazer Shard", () -> {}, events::add));
    }
}
