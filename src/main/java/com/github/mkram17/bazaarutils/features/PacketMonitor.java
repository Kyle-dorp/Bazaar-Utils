package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.BazaarUtils;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Outgoing packet counter (/flippackets): every packet the game sends to the server is counted by type (the mixin on
 * Connection.send calls {@link #onSend}); it never blocks or changes a packet. Container clicks are split into "[you]" (sent
 * while the game is handling your own mouse button or key press) and "[mod]" (sent any other time, i.e. a mod clicking on its
 * own). /flippackets toggles a small overlay; /flippackets log writes the last 60 s to config/bazaarutils-packets.log.
 */
public final class PacketMonitor {
    private static final PacketCounter counter = new PacketCounter(60);
    private static final Path LOG = FabricLoader.getInstance().getConfigDir().resolve("bazaarutils-packets.log");
    private static volatile boolean shown;
    // how deep the render thread is inside the game's own mouse-button / key-press handling (only touched on that thread)
    private static int userInput;

    private PacketMonitor() { }

    public static void userInputStart() {
        userInput = 1;
    }

    public static void userInputEnd() {
        userInput = 0;
    }

    /** Called for every outgoing packet, on whatever thread sends it. Counting only. */
    public static void onSend(Packet<?> packet) {
        try {
            String type = packet instanceof ServerboundContainerClickPacket
                    ? (userInput > 0 && Minecraft.getInstance().isSameThread() ? "ContainerClick [you]" : "ContainerClick [mod]")
                    : packet.getClass().getSimpleName();
            counter.count(type, System.currentTimeMillis());
        } catch (Throwable ignored) { } // diagnostics must never break sending
    }

    private static List<String> overlayLines() {
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        lines.add("§ePackets out §f" + counter.lastSecond(now) + "/s §7(peak 30s: §f" + counter.peak(now, 30) + "§7)");
        for (PacketCounter.TypeCount t : counter.top(now, 10, 5)) {
            String name = t.type().startsWith("Serverbound") ? t.type().substring("Serverbound".length()) : t.type();
            lines.add("§7" + name.replace("Packet", "") + " §f" + t.count() + " §8/10s");
        }
        return lines;
    }

    private static void draw(GuiGraphicsExtractor g) {
        if (!shown) return;
        Font font = Minecraft.getInstance().font;
        List<String> lines = overlayLines();
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        g.fill(2, 2, 8 + w, 6 + lines.size() * 10, 0xA0000000);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), 5, 5 + i * 10, 0xFFFFFFFF, true);
    }

    @RunOnInit
    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BazaarUtils.MODID, "packet_counter"), (g, delta) -> {
            if (com.github.mkram17.bazaarutils.utils.VersionCompat.getScreen(Minecraft.getInstance()) == null) draw(g);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> draw(g)));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("flippackets")
                .executes(ctx -> {
                    shown = !shown;
                    PlayerActionUtil.notifyAll("Packet counter " + (shown ? "shown" : "hidden") + ". /flippackets log saves the last 60 s.");
                    return 1;
                })
                .then(ClientCommands.literal("log").executes(ctx -> {
                    long now = System.currentTimeMillis();
                    List<String> lines = new ArrayList<>();
                    lines.add("=== " + java.time.LocalDateTime.now() + "  outgoing packets per second, last 60 s ===");
                    lines.addAll(counter.dump(now, 60));
                    lines.add("");
                    try {
                        Files.write(LOG, lines, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        PlayerActionUtil.notifyAll("Saved the last 60 s of packets to config/bazaarutils-packets.log");
                    } catch (Exception e) {
                        PlayerActionUtil.notifyAll("Couldn't write the packet log: " + e.getMessage());
                    }
                    return 1;
                }))));
    }
}
