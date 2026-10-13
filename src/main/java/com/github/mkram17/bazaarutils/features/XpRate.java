package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.BazaarUtils;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session skill XP per hour: total XP gained this session divided by the time you were active. SkyHanni's XP/h only looks
 * at the last 30 seconds, so a burst (a sea creature kill) reads as 1.5M/h and then drops to N/A; this spreads it over the
 * session instead. Time only stops counting after a long gap with no XP (default 5 minutes), so waiting for a bite doesn't
 * pause it. XP comes from the "+12.3 Fishing (413,280/700,000)" action bar. That message repeats for a few seconds, so
 * the gain is taken from the change in the current XP number, not from counting messages.
 */
public class XpRate {
    // same shapes SkyHanni's SkillApi matches (its REGEX-TESTs: "+207.2 Hunting (5,183,244/0)", "+1.1 Mining (48.39%)")
    private static final Pattern WITH_TOTAL = Pattern.compile("\\+([\\d.,]+) ([A-Za-z]+(?: [A-Za-z]+)?) \\(([\\d.,]+)/([\\d.,]+[kmbKMB]?)\\)");
    private static final Pattern WITH_PERCENT = Pattern.compile("\\+([\\d.,]+) ([A-Za-z]+(?: [A-Za-z]+)?) \\(([\\d.,]+)%\\)");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarutils-xprate.json");

    private static final class Session {
        double gained;
        long activeMs;
        long lastGainAt;
        double lastCurrent = -1;
        String lastPercentKey = "";
    }

    private static final Map<String, Session> sessions = new LinkedHashMap<>();
    private static String lastSkill;

    private static boolean shown = true;
    private static int pauseMinutes = 5;
    private static int posX = 5, posY = 80;

    // ---- settings ----

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("shown")) shown = o.get("shown").getAsBoolean();
                if (o.has("pauseMinutes")) pauseMinutes = Math.max(1, o.get("pauseMinutes").getAsInt());
                if (o.has("x")) posX = o.get("x").getAsInt();
                if (o.has("y")) posY = o.get("y").getAsInt();
            }
        } catch (Exception ignored) { }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("shown", shown);
            o.addProperty("pauseMinutes", pauseMinutes);
            o.addProperty("x", posX);
            o.addProperty("y", posY);
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception ignored) { }
    }

    // ---- tracking ----

    private static double num(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    static synchronized void onActionBar(String text, long now) {
        Matcher m = WITH_TOTAL.matcher(text);
        if (m.find()) {
            String skill = m.group(2);
            double gained = num(m.group(1)), current = num(m.group(3));
            Session s = sessions.computeIfAbsent(skill, k -> new Session());
            double delta;
            if (s.lastCurrent < 0) delta = gained;               // first message seen for this skill
            else if (current > s.lastCurrent) delta = current - s.lastCurrent;
            else if (current < s.lastCurrent) delta = gained;    // level up: the counter restarted
            else delta = 0;                                      // the same message repeated
            s.lastCurrent = current;
            if (delta > 0) addGain(skill, s, delta, now);
            return;
        }
        m = WITH_PERCENT.matcher(text);
        if (m.find()) {
            String skill = m.group(2);
            Session s = sessions.computeIfAbsent(skill, k -> new Session());
            String key = m.group(1) + "|" + m.group(3);
            if (key.equals(s.lastPercentKey)) return;            // the same message repeated
            s.lastPercentKey = key;
            addGain(skill, s, num(m.group(1)), now);
        }
    }

    private static void addGain(String skill, Session s, double amount, long now) {
        if (s.lastGainAt > 0) {
            long gap = now - s.lastGainAt;
            if (gap <= pauseMinutes * 60_000L) s.activeMs += gap; // a longer gap means you stopped: not counted
        }
        s.lastGainAt = now;
        s.gained += amount;
        lastSkill = skill;
    }

    /** XP per hour over the session, counting the time since the last gain while it's still within the pause window. */
    static synchronized double perHour(String skill, long now) {
        Session s = sessions.get(skill);
        if (s == null || s.lastGainAt == 0) return 0;
        long since = now - s.lastGainAt;
        long active = s.activeMs + (since <= pauseMinutes * 60_000L ? since : 0);
        if (active < 60_000) return Double.NaN;                   // under a minute is too short to mean anything
        return s.gained * 3_600_000.0 / active;
    }

    private static String fmt(double v) {
        if (v >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (v >= 1_000) return String.format("%.1fK", v / 1_000);
        return String.format("%.0f", v);
    }

    private static String time(long ms) {
        long sec = ms / 1000, h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%dh %dm", h, m) : String.format("%dm %ds", m, s);
    }

    // ---- display ----

    @RunOnInit
    public static void register() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) return;
            try {
                onActionBar(message.getString().replaceAll("§.", ""), System.currentTimeMillis());
            } catch (Exception ignored) { }
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BazaarUtils.MODID, "xp_rate"), (g, delta) -> {
            String skill = lastSkill;
            if (!shown || skill == null) return;
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            long now = System.currentTimeMillis();
            Session s;
            double rate;
            synchronized (XpRate.class) {
                s = sessions.get(skill);
                rate = perHour(skill, now);
            }
            if (s == null) return;
            long since = now - s.lastGainAt;
            boolean paused = since > pauseMinutes * 60_000L;
            long active = s.activeMs + (paused ? 0 : since);
            String rateText = Double.isNaN(rate) ? "§7measuring..." : "§e" + fmt(rate) + " XP/h";
            g.text(font, "§b" + skill + " (session): " + rateText + (paused ? " §c(PAUSED)" : ""), posX, posY, 0xFFFFFFFF, true);
            g.text(font, "§7+" + fmt(s.gained) + " XP in " + time(active), posX, posY + 10, 0xFFFFFFFF, true);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("xprate")
                        .executes(ctx -> {
                            shown = !shown;
                            save();
                            PlayerActionUtil.notifyAll("Session XP/h display " + (shown ? "shown" : "hidden") + ".");
                            return 1;
                        })
                        .then(ClientCommands.literal("reset").executes(ctx -> {
                            synchronized (XpRate.class) {
                                sessions.clear();
                                lastSkill = null;
                            }
                            PlayerActionUtil.notifyAll("Session XP/h reset.");
                            return 1;
                        }))
                        .then(ClientCommands.literal("pause").then(ClientCommands.argument("minutes", IntegerArgumentType.integer(1, 120))
                                .executes(ctx -> {
                                    pauseMinutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                    save();
                                    PlayerActionUtil.notifyAll("Session pauses after " + pauseMinutes + " min with no XP.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("pos")
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 4000))
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 4000)).executes(ctx -> {
                                            posX = IntegerArgumentType.getInteger(ctx, "x");
                                            posY = IntegerArgumentType.getInteger(ctx, "y");
                                            save();
                                            return 1;
                                        }))))));
    }
}
