package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.BazaarUtils;
import com.github.mkram17.bazaarutils.events.SlotClickEvent;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fuse-click limiter in the Fusion Machine / Fusion Box: blocks (never sends) fuse clicks that come too fast, so fusing by hand
 * doesn't get you kicked for "Sending packets too fast!". A fuse click is any click on a menu item in the "Confirm Fusion"
 * screen, or on an item named like a fusion (e.g. "Repeat Previous Fusion") in any Fusion screen. Shard picking and your own
 * inventory are never limited. /flipfuselimit [on|off|<ms> <per 30s>]; on by default, 1,500 ms apart and at most 10 per 30 s.
 */
public final class FuseLimiterHooks {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarutils-fuselimit.json");
    static final long DEFAULT_INTERVAL_MS = 1500;
    static final int DEFAULT_PER_WINDOW = 10;
    private static final FuseLimiter limiter = new FuseLimiter(DEFAULT_INTERVAL_MS, DEFAULT_PER_WINDOW, 30_000);
    private static volatile boolean enabled = true;
    private static volatile long blockedAt;
    private static volatile String blockedText = "";

    private FuseLimiterHooks() { }

    @EventHandler
    public static void onSlotClick(SlotClickEvent e) {
        if (!enabled || e.isCancelled()) return;
        Slot slot = e.slot;
        boolean inMenu = !(slot.container instanceof Inventory);
        if (!FuseLimiter.isFuseClick(e.handledScreen.getTitle().getString(), slot.getItem().getHoverName().getString(), inMenu, slot.getItem().isEmpty())) return;
        FuseLimiter.Verdict v = limiter.click(System.currentTimeMillis());
        if (v.allowed()) return;
        e.setCancelled(true); // the click never leaves the game
        blockedAt = System.currentTimeMillis();
        blockedText = "§cSlow down §7(" + v.reason() + ") - fuse again in §f" + String.format("%.1f", v.waitMs() / 1000.0) + "s";
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
                limiter.setLimits(o.has("intervalMs") ? o.get("intervalMs").getAsLong() : DEFAULT_INTERVAL_MS,
                        o.has("perWindow") ? o.get("perWindow").getAsInt() : DEFAULT_PER_WINDOW);
            }
        } catch (Exception ignored) { }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("intervalMs", limiter.minIntervalMs());
            o.addProperty("perWindow", limiter.maxPerWindow());
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception ignored) { }
    }

    private static String status() {
        return "Fuse limiter " + (enabled ? "ON" : "OFF") + ": at least " + limiter.minIntervalMs() + " ms between fuse clicks, at most "
                + (limiter.maxPerWindow() == 0 ? "unlimited" : limiter.maxPerWindow()) + " per 30 s. /flipfuselimit on|off|<ms> <per 30s>";
    }

    @RunOnInit
    public static void register() {
        load();
        BazaarUtils.EVENT_BUS.subscribe(FuseLimiterHooks.class);
        // the "slow down" line, top centre of the Fusion screen, for 1.5 s after a blocked click (no chat spam)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            if (!screen.getTitle().getString().replaceAll("§.", "").contains("Fusion")) return;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                if (System.currentTimeMillis() - blockedAt > 1500) return;
                var font = Minecraft.getInstance().font;
                g.text(font, blockedText, s.width / 2 - font.width(blockedText) / 2, 6, 0xFFFFFFFF, true);
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("flipfuselimit")
                .executes(ctx -> { PlayerActionUtil.notifyAll(status()); return 1; })
                .then(ClientCommands.literal("on").executes(ctx -> { enabled = true; save(); PlayerActionUtil.notifyAll(status()); return 1; }))
                .then(ClientCommands.literal("off").executes(ctx -> { enabled = false; save(); PlayerActionUtil.notifyAll(status()); return 1; }))
                .then(ClientCommands.argument("ms", IntegerArgumentType.integer(0, 60_000))
                        .then(ClientCommands.argument("per30s", IntegerArgumentType.integer(0, 1000)).executes(ctx -> {
                            limiter.setLimits(IntegerArgumentType.getInteger(ctx, "ms"), IntegerArgumentType.getInteger(ctx, "per30s"));
                            enabled = true;
                            save();
                            PlayerActionUtil.notifyAll(status());
                            return 1;
                        })))));
    }
}
