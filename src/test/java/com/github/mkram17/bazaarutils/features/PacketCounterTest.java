package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketCounterTest {
    private static final long T0 = 1_700_000_000_000L; // a whole second

    @Test
    void countsPerSecondAndTheLastFullSecond() {
        PacketCounter c = new PacketCounter(60);
        for (int i = 0; i < 7; i++) c.count("ServerboundMovePlayerPacket", T0 + i * 100);
        c.count("ContainerClick [you]", T0 + 999);
        c.count("ServerboundMovePlayerPacket", T0 + 1000); // next second
        assertEquals(8, c.total(T0 / 1000));
        assertEquals(1, c.total(T0 / 1000 + 1));
        assertEquals(8, c.lastSecond(T0 + 1500), "the last full second");
    }

    @Test
    void oldSecondsLeaveTheRollingWindow() {
        PacketCounter c = new PacketCounter(60);
        for (int i = 0; i < 50; i++) c.count("A", T0);
        assertEquals(50, c.peak(T0 + 10_000, 30));
        assertEquals(0, c.peak(T0 + 31_000, 30), "older than 30 s");
        c.count("B", T0 + 60_000); // same ring slot as T0, a minute later
        assertEquals(0, c.total(T0 / 1000), "the slot was reused, the old second is gone");
        assertEquals(1, c.total(T0 / 1000 + 60));
        assertEquals(List.of(new PacketCounter.TypeCount("B", 1)), c.top(T0 + 60_000, 10, 5), "nothing left over from a minute ago");
    }

    @Test
    void peakIsTheBusiestSingleSecond() {
        PacketCounter c = new PacketCounter(60);
        for (int s = 0; s < 10; s++) for (int i = 0; i <= s; i++) c.count("A", T0 + s * 1000L);
        assertEquals(10, c.peak(T0 + 9_000, 30));
        assertEquals(10, c.peak(T0 + 9_000, 1), "the current second");
        assertEquals(9, c.peak(T0 + 9_000, 30) - 1);
    }

    @Test
    void topNTypesOverTheLastTenSeconds() {
        PacketCounter c = new PacketCounter(60);
        for (int i = 0; i < 40; i++) c.count("ServerboundMovePlayerPacket", T0 + i * 200L);
        for (int i = 0; i < 12; i++) c.count("ContainerClick [you]", T0 + i * 750L);
        for (int i = 0; i < 3; i++) c.count("ContainerClick [mod]", T0 + i * 1000L);
        c.count("ServerboundChatPacket", T0);
        c.count("ServerboundSwingPacket", T0);
        c.count("ServerboundUseItemPacket", T0);
        c.count("Old", T0 - 20_000);
        List<PacketCounter.TypeCount> top = c.top(T0 + 9_000, 10, 5);
        assertEquals(5, top.size());
        assertEquals(new PacketCounter.TypeCount("ServerboundMovePlayerPacket", 40), top.get(0));
        assertEquals(new PacketCounter.TypeCount("ContainerClick [you]", 12), top.get(1));
        assertEquals(new PacketCounter.TypeCount("ContainerClick [mod]", 3), top.get(2));
        assertTrue(top.stream().noneMatch(t -> t.type().equals("Old")), "20 s ago is outside the 10 s window");
    }

    @Test
    void theLogHasOneLinePerSecondWithEveryType() {
        PacketCounter c = new PacketCounter(60);
        c.count("A", T0);
        c.count("A", T0);
        c.count("B", T0 + 2000);
        List<String> lines = c.dump(T0 + 2000, 60);
        assertEquals(60, lines.size());
        assertTrue(lines.get(57).contains("total 2") && lines.get(57).contains("A=2"), lines.get(57));
        assertTrue(lines.get(58).contains("total 0"), lines.get(58));
        assertTrue(lines.get(59).contains("total 1") && lines.get(59).contains("B=1"), lines.get(59));
    }
}
