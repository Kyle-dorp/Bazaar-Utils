package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen flip list: one column, ranked by profit per hour, a fixed number of rows visible and the mouse wheel
 * scrolls through the rest. BazaarFlip's HudRenderer is patched to call {@link #render} instead of drawing its own
 * two-column layout. Everything is read from BazaarFlip by reflection since BazaarFlip is not a compile dependency.
 */
public class FlipHud {
    private static final String CONFIG = "uwu.ramona.bazaar.config.BazaarConfig";
    private static final String MOD = "uwu.ramona.bazaar.BazaarFlipMod";

    private static final int WIDTH = FlipHudLayout.WIDTH, TITLE_H = FlipHudLayout.TITLE_H, ROW_H = FlipHudLayout.ROW_H;

    private static int scroll;
    private static boolean dragging;
    private static int dragOffX, dragOffY;
    // last drawn bounds in screen (GUI) pixels, for scroll hit-testing
    private static volatile int boundsLeft, boundsTop, boundsRight, boundsBottom;
    // what the last frame drew, so a click maps back to the forge row that was under the cursor
    private static volatile List<ForgeCalc.Row> forgeRowsShown = List.of();
    private static volatile int lastMode = -1, lastPanelX, lastPanelY, lastVisible, lastScroll, lastCount;
    private static volatile float lastScale = 1;
    private static volatile long lastRenderMs;

    // ---- reflection helpers ----

    private static int cfgInt(String name) {
        try {
            return Class.forName(CONFIG).getField(name).getInt(null);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void cfgSetInt(String name, int value) {
        try {
            Class.forName(CONFIG).getField(name).setInt(null, value);
        } catch (Exception ignored) { }
    }

    private static int cfgColor(String method, int fallback) {
        try {
            Method m = Class.forName(CONFIG).getMethod(method);
            return (Integer) m.invoke(null);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void cfgSave() {
        try {
            Class.forName(CONFIG).getMethod("save").invoke(null);
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    private static float scale() {
        return switch (cfgInt("guiScale")) {
            case 1 -> 0.5f;
            case 2 -> 0.75f;
            case 4 -> 1.25f;
            case 5 -> 1.5f;
            default -> 1.0f;
        };
    }

    // ---- formatting ----

    static String coins(double v) {
        double a = Math.abs(v);
        String s;
        if (a >= 1_000_000_000) s = String.format("%.2fB", a / 1_000_000_000);
        else if (a >= 1_000_000) s = String.format("%.2fM", a / 1_000_000);
        else if (a >= 1_000) s = String.format("%.2fK", a / 1_000);
        else s = String.format("%.1f", a);
        return (v < 0 ? "-" : "") + s;
    }

    private static String name(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    /** "Howling Spirit Shard" -> "Howling Spirit": every input is a shard, so the word only costs space. */
    private static String shardName(String id) {
        String n = ItemNames.of(id);
        return n.endsWith(" Shard") ? n.substring(0, n.length() - 6) : n;
    }

    /** Cuts a line so it never runs past the panel edge. */
    private static String fit(Font font, String text, int max) {
        if (font.width(text) <= max) return text;
        while (text.length() > 4 && font.width(text + "..") > max) text = text.substring(0, text.length() - 1);
        return text + "..";
    }

    private record Row(String title, double perHour, String line2, String line3, String name) { }

    // ---- search within the current mode ----

    private static final String[] MODE_NOUN = {"flips", "dips", "fusions", "forge items"};
    private static final String[] queries = {"", "", "", ""};

    /** The search typed for a list mode ("" = none). */
    public static String query(int mode) {
        return queries[mode];
    }

    public static void setQuery(int mode, String text) {
        String q = text == null ? "" : text.trim();
        if (!q.equals(queries[mode])) scroll = 0;
        queries[mode] = q;
    }

    private static int cap() {
        return Math.max(1, cfgInt("bazaarGuiTopItemsByPercentageCount") + cfgInt("bazaarGuiTopItemsByMoneyCount"));
    }

    // the rows a list showed (and, in forge mode, which forge row each one is), so a pause can keep them exactly as drawn
    private record Shown(List<Row> rows, List<ForgeCalc.Row> forge) { }

    /** The list for a mode: live, or frozen as last drawn while {@link FlipPause paused} (a new search re-filters once). */
    private static List<Row> rows(int mode) {
        Shown shown = FlipPause.hold("list" + mode, queries[mode], () -> {
            List<Row> live = liveRows(mode);
            return new Shown(live, mode == 3 ? forgeRowsShown : null);
        });
        if (shown.forge() != null) forgeRowsShown = shown.forge();
        return shown.rows();
    }

    /**
     * Title badges for an item (never on Row.name, which search matches): the active mayor perk that moves it and a confirmed
     * 24-hour shift or faster fills. Map lookups on cached data, safe every frame; nothing with the Mayor flipper off.
     */
    private static String badges(String id) {
        return MayorWatch.badges(id, MayorCommand::watch, BazaarSnapshot.cachedProducts());
    }

    /** BazaarFlip's flips (or dips) plus book crafting flips, in one list ranked by profit per hour. */
    private static List<Row> liveRows(int mode) {
        List<Row> out = new ArrayList<>();
        java.util.Set<Flippers.Flipper> on = Flippers.feeding(mode); // flippers turned off in /bazaarflip compute nothing
        if (on.isEmpty()) {
            if (mode == 3) forgeRowsShown = List.of();
            return out;
        }
        if (mode == 3) {
            ForgeCalc.refreshIfStale();
            int slots = BaseProfit.getForgeSlots();
            List<ForgeCalc.Row> forge = ForgeCalc.get();
            String q = queries[3];
            List<FlipSearch.ForgeHit> hits = FlipSearch.blank(q)
                    ? forge.stream().map(f -> new FlipSearch.ForgeHit(f, null)).toList()
                    : FlipSearch.forge(q, forge, ForgeCalc::name);
            hits = hits.subList(0, Math.min(hits.size(), cap()));
            List<ForgeCalc.Row> shown = new ArrayList<>();
            for (FlipSearch.ForgeHit hit : hits) {
                ForgeCalc.Row f = hit.row();
                shown.add(f);
                ForgePlanner.Route r = f.route();
                String uses = hit.usedPart() == null ? "" : " §3[uses " + ForgeCalc.name(hit.usedPart()) + "]";
                out.add(new Row(ForgeCalc.name(r.id()) + (f.soldOnAh() ? " §6[forge, AH]" : " §6[forge]") + uses, f.perHour(),
                        "§7" + r.forgeSteps() + (r.forgeSteps() == 1 ? " forge, §r" : " forges, §r") + ForgeCalc.duration(r.slotSeconds())
                                + "§7 slot time, done in ~§r" + ForgeCalc.duration(r.hoursWith(slots) * 3600)
                                + "§7, limit: §r" + ForgeCalc.limitText(f, slots, false),
                        "§aSell §r" + coins(r.revenue()) + " §7- Cost §r" + coins(r.cost()) + " §7= §6" + coins(r.profit())
                                + " §e" + coins(f.perDay()) + "/day", ForgeCalc.name(r.id())));
            }
            forgeRowsShown = shown; // same order as the rows drawn, so a click opens the tree of the row it hit
            return out;
        }
        if (mode == 2) {
            FusionCalc.refreshIfStale();
            String q = queries[2];
            List<FlipSearch.FusionHit> hits = FlipSearch.blank(q)
                    ? FusionCalc.get().stream().map(f -> new FlipSearch.FusionHit(f, null)).toList()
                    : FlipSearch.fusions(q, FusionCalc.getAll(), ItemNames::of);
            for (FlipSearch.FusionHit hit : hits.subList(0, Math.min(hits.size(), cap()))) {
                FusionCalc.Fusion f = hit.fusion();
                String tag = hit.relation() == null ? "§7" : hit.relation() == FlipSearch.Relation.MAKES ? "§b[makes] §7" : "§3[uses] §7";
                out.add(new Row(f.outputCount() + "x " + ItemNames.of(f.output()) + " §d[" + f.type().toLowerCase() + "]" + badges(f.output()), f.perHour(),
                        tag + f.leftCount() + "x " + shardName(f.left()) + " + " + f.rightCount() + "x " + shardName(f.right()),
                        // whole-fusion totals: everything you sell (after tax) minus everything you buy
                        "§aSell §r" + coins(f.cost() + f.profit()) + " §7- Cost §r" + coins(f.cost())
                                + " §7= §6" + coins(f.profit()) + " §e(" + String.format("%.0f", f.profit() / f.cost() * 100) + "%)",
                        ItemNames.of(f.output())));
            }
            return out;
        }
        boolean dips = mode == 1;
        for (BazaarFlipRows.Row it : on.contains(dips ? Flippers.Flipper.DIPS : Flippers.Flipper.FLIPS) ? BazaarFlipRows.get() : List.<BazaarFlipRows.Row>of()) {
            String id = it.id();
            String third = "§6Profit: §r" + coins(it.profit()) + " §e(" + String.format("%.1f", it.profitMargin()) + "%)"
                    + " §7Cost: §r" + coins(it.totalBuyCost());
            if (dips) {
                double dip = BaseProfit.dipPercent(id, it.buyPrice());
                if (!Double.isNaN(dip)) {
                    third = "§bDip: -" + String.format("%.0f", dip) + "% §7(usually §r" + coins(BaseProfit.typicalBuy(id))
                            + "§7) §6+" + coins(it.profit());
                }
            }
            out.add(new Row(ItemNames.of(id) + badges(id), it.totalProfit(),
                    "§7Buy: §r" + coins(it.buyPrice()) + " §7| §aSell: §r" + coins(it.sellPrice()), third, ItemNames.of(id)));
        }
        if (!dips && on.contains(Flippers.Flipper.BOOKS)) {
            BookCraft.refreshIfStale();
            for (BookCraft.Craft c : BookCraft.get()) {
                out.add(new Row(c.title() + " §d[craft]" + badges(c.id()), c.perHour(),
                        "§7Buy §r" + c.units() + "x " + c.fromName() + " §7@ §r" + coins(c.bid()) + " §7| §aSell: §r" + coins(c.ask()),
                        "§6Profit: §r" + coins(c.profit()) + " §e(" + String.format("%.1f", c.profitMargin()) + "%) §7Cost: §r" + coins(c.cost()),
                        c.title() + " " + c.fromName()));
            }
        }
        out.sort((a, b) -> Double.compare(b.perHour(), a.perHour()));
        String q = queries[mode];
        return FlipSearch.top(out, r -> FlipSearch.matches(q, r.name()), cap());
    }

    // ---- rendering (called from the patched HudRenderer.renderOverlay) ----

    public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int mode = BaseProfit.getListMode();
        if (!Flippers.modeOn(mode)) {
            int next = Flippers.nextMode(mode, MODE_NAMES.length);
            if (next == mode) return; // every list is turned off
            BaseProfit.setListMode(next);
            mode = next;
        }
        render(g, mouseX, mouseY, mode);
    }

    /** Draws the list in a given mode (0 flips, 1 dips, 2 fusions, 3 forge), regardless of the saved mode. */
    public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY, int mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Font font = mc.font;
        float s = scale();

        List<Row> items = rows(mode);
        int visible = Math.max(1, Math.min(BaseProfit.getVisibleRows(), Math.max(1, items.size())));
        int maxScroll = Math.max(0, items.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int footer = items.size() > visible ? 12 : 4;
        int height = TITLE_H + (items.isEmpty() ? 26 : visible * ROW_H) + footer;

        // drag by the title bar
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().handle(), 0) == 1;
        int guiX = cfgInt("guiX"), guiY = cfgInt("guiY");
        if (down) {
            if (!dragging) {
                boolean onTitle = mouseX >= guiX && mouseX <= guiX + WIDTH * s && mouseY >= guiY && mouseY <= guiY + TITLE_H * s;
                if (onTitle) {
                    dragging = true;
                    dragOffX = mouseX - guiX;
                    dragOffY = mouseY - guiY;
                }
            } else {
                guiX = mouseX - dragOffX;
                guiY = mouseY - dragOffY;
                cfgSetInt("guiX", guiX);
                cfgSetInt("guiY", guiY);
                cfgSave();
            }
        } else {
            dragging = false;
        }

        int x = Math.round(guiX / s), y = Math.round(guiY / s);
        lastMode = mode;
        lastPanelX = x;
        lastPanelY = y;
        lastScale = s;
        lastVisible = visible;
        lastScroll = scroll;
        lastCount = items.size();
        lastRenderMs = System.currentTimeMillis();
        boundsLeft = guiX;
        boundsTop = guiY;
        boundsRight = guiX + Math.round(WIDTH * s);
        boundsBottom = guiY + Math.round(height * s);

        int textColor = cfgColor("getTextColor", 0xFFFFFFFF);
        int bg = cfgColor("getBackgroundColor", 0xB0101018);
        int border = 0xFF3A3A4A;

        g.pose().pushMatrix();
        g.pose().scale(s, s);

        g.fill(x, y, x + WIDTH, y + height, bg);
        g.fill(x, y, x + WIDTH, y + 1, border);
        g.fill(x, y + height - 1, x + WIDTH, y + height, border);
        g.fill(x, y, x + 1, y + height, border);
        g.fill(x + WIDTH - 1, y, x + WIDTH, y + height, border);
        g.fill(x, y + TITLE_H, x + WIDTH, y + TITLE_H + 1, 0x30FFFFFF);

        String heading = switch (mode) {
            case 1 -> "§l§bBazaar Dips (Profit/hr)";
            case 2 -> "§l§dShard Fusions (Profit/hr)";
            case 3 -> ForgeCalc.filterTag().isEmpty() ? "§l§6Forge Flips (Profit/hr)" : "§l§6Forge Flips§r" + ForgeCalc.filterTag();
            default -> "§l§eBazaar Flips (Profit/hr)";
        };
        String q = queries[mode];
        if (!q.isEmpty()) heading += " §r§7\"" + q + "\"";
        if (FlipPause.paused()) heading = "§c§lPAUSED §r" + heading;
        String range = items.isEmpty() ? "" : (scroll + 1) + "-" + (scroll + visible) + " / " + items.size();
        g.text(font, fit(font, heading, WIDTH - 16 - font.width(range)), x + 6, y + 7, textColor, true);
        if (!items.isEmpty()) {
            g.text(font, "§7" + range, x + WIDTH - 6 - font.width(range), y + 7, textColor, true);
        }

        if (items.isEmpty()) {
            g.text(font, !Flippers.modeOn(mode) ? "§7Turned off in /bazaarflip (Flippers)."
                    : q.isEmpty() ? "§7No active flips found." : fit(font, "§7No " + MODE_NOUN[mode] + " matching \"" + q + "\".", WIDTH - 12),
                    x + 6, y + TITLE_H + 8, textColor, true);
        } else {
            for (int i = 0; i < visible; i++) {
                int idx = scroll + i;
                if (idx >= items.size()) break;
                Row row = items.get(idx);
                int ry = FlipHudLayout.rowTextY(y, i);
                if (i > 0) g.fill(x + 4, ry - 2, x + WIDTH - 4, ry - 1, 0x20FFFFFF);

                String perHour = coins(row.perHour()) + "/hr";
                int perHourW = font.width("§l" + perHour);
                String title = "§l#" + (idx + 1) + " " + row.title();
                if (font.width(title) > WIDTH - 18 - perHourW) {
                    while (font.width(title + "..") > WIDTH - 18 - perHourW && title.length() > 6) title = title.substring(0, title.length() - 1);
                    title += "..";
                }
                g.text(font, title, x + 6, ry, textColor, true);
                g.text(font, "§a§l" + perHour, x + WIDTH - 6 - perHourW, ry, textColor, true);
                g.text(font, fit(font, row.line2(), WIDTH - 12), x + 6, ry + 11, textColor, true);
                g.text(font, fit(font, row.line3(), WIDTH - 12), x + 6, ry + 22, textColor, true);
            }
            if (items.size() > visible) {
                g.text(font, "§8scroll for more", x + 6, y + height - 11, textColor, true);
            }
        }
        g.pose().popMatrix();
    }

    @RunOnInit
    public static void registerFusionBox() {
        // the Fusion Box menu (Fusion Machine in Galatea, or the Hunting Box fuse button) shows the fusion list too
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
            if (!screen.getTitle().getString().replaceAll("§.", "").contains("Fusion")) return;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                boolean list = Flippers.on(Flippers.Flipper.FUSIONS), panel = Flippers.on(Flippers.Flipper.POTENTIAL);
                if (!list && !panel) return;
                render(g, mouseX, mouseY, 2); // a one-line "turned off" box when only the panel is on: the panel sits under it
                if (panel) renderFusionPotential(g);
            });
        });
    }

    private static String shardLabel(String name) {
        return name.endsWith(" Shard") ? name.substring(0, name.length() - 6) : name;
    }

    private static final int POTENTIAL_ROWS = 15;
    private record Potential(FusionPotential.Plan plan, ShardPotential.Source source, boolean pricesLoading) { }
    private static int potentialScroll;
    // last drawn bounds of the potential panel in screen pixels, for scrolling it
    private static volatile int potLeft, potTop, potRight, potBottom;

    /**
     * Under the fusion list in the Fusion Box: the fusions you could do with the shards actually in your Hunting Box (SkyHanni's
     * counts), each shard used once across the whole list, most profit first. Up to 15 rows, scroll for more. The plan is
     * computed in the background ({@link ShardPotential}); this only draws it.
     */
    static void renderFusionPotential(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || boundsRight <= boundsLeft) return;
        Font font = mc.font;
        float s = scale();
        Potential pot = FlipPause.hold("potential", null,
                () -> new Potential(ShardPotential.get(), ShardPotential.source(), ShardPotential.pricesLoading()));
        FusionPotential.Plan plan = pot.plan();
        List<String> head = new ArrayList<>(), rows = new ArrayList<>();
        head.add((FlipPause.paused() ? "§c§lPAUSED §r" : "") + "§l§dFuse your Hunting Box §r§8(SkyHanni counts)");
        switch (pot.source()) {
            case NO_SKYHANNI -> head.add("§7Needs SkyHanni to read your Hunting Box.");
            case NOT_SEEN_YET -> head.add("§7Open your Hunting Box once so SkyHanni can count it.");
            default -> {
                if (pot.pricesLoading()) head.add("§7Loading prices...");
                else if (plan.fusions().isEmpty()) head.add("§7Nothing profitable to fuse from what you have.");
            }
        }
        for (FusionPotential.Fusion f : plan.fusions()) {
            FusionPotential.Recipe r = f.recipe();
            rows.add("§a+" + coins(f.profit()) + " §7" + f.times() + "x §f" + shardLabel(ItemNames.of(r.output())) + " §8("
                    + r.leftCount() * f.times() + " " + shardLabel(ItemNames.of(r.left())) + " + "
                    + r.rightCount() * f.times() + " " + shardLabel(ItemNames.of(r.right())) + ")");
        }
        int x = Math.round(boundsLeft / s), y = Math.round((boundsBottom + 4) / s);
        int screenRows = (int) ((mc.getWindow().getGuiScaledHeight() / s - y - 8 - (head.size() + 2) * 11) / 11);
        int visible = Math.max(0, Math.min(POTENTIAL_ROWS, Math.min(rows.size(), screenRows)));
        potentialScroll = Math.max(0, Math.min(potentialScroll, rows.size() - visible));
        List<String> lines = new ArrayList<>(head);
        for (int i = 0; i < visible; i++) lines.add(rows.get(potentialScroll + i));
        if (!rows.isEmpty()) {
            lines.add("§7Total §6+" + coins(plan.total()) + " §8- uses " + plan.shardsUsed() + " of your " + plan.shardsHeld() + " shards");
            if (rows.size() > visible) lines.add("§8" + (potentialScroll + 1) + "-" + (potentialScroll + visible) + " of " + rows.size() + ", scroll for more");
        }
        int height = 8 + lines.size() * 11;
        potLeft = boundsLeft;
        potTop = boundsBottom + 4;
        potRight = boundsRight;
        potBottom = potTop + Math.round(height * s);
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        g.fill(x, y, x + WIDTH, y + height, cfgColor("getBackgroundColor", 0xB0101018));
        g.fill(x, y, x + WIDTH, y + 1, 0xFF3A3A4A);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, fit(font, lines.get(i), WIDTH - 12), x + 6, y + 5 + i * 11, 0xFFFFFFFF, true);
        }
        g.pose().popMatrix();
    }

    @RunOnInit
    public static void registerForgeMenu() {
        // the Dwarven Forge menu ("The Forge") shows the forge flip list while you pick what to start
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
            if (!screen.getTitle().getString().replaceAll("§.", "").contains("The Forge")) return;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                if (Flippers.on(Flippers.Flipper.FORGE)) render(g, mouseX, mouseY, 3);
            });
        });
    }

    @RunOnInit
    public static void registerForgeRowClick() {
        // clicking a forge row opens its route tree: only where the panel was drawn just now, never over an open container
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                    if (click.button() != 0 || lastMode != 3 || System.currentTimeMillis() - lastRenderMs > 250) return true;
                    if (s instanceof com.github.mkram17.bazaarutils.mixin.AccessorAbstractContainerScreen c
                            && FlipHudLayout.inside(click.x(), click.y(), c.getLeftPos(), c.getTopPos(), c.getImageWidth(), c.getImageHeight())) {
                        return true;
                    }
                    List<ForgeCalc.Row> shown = forgeRowsShown;
                    int index = FlipHudLayout.rowAt(click.x(), click.y(), lastPanelX, lastPanelY, lastScale, lastScroll, lastVisible,
                            Math.min(lastCount, shown.size()));
                    if (index < 0) return true;
                    ForgeTreeScreen.open(shown.get(index));
                    return false; // handled: don't also click whatever is behind the panel
                }));
    }

    /**
     * A search field above the list (below it if there's no room) in every inventory-style screen while the list is on
     * screen. While it has focus it takes every key, so typing E or Y doesn't close the inventory or switch modes; Esc or a
     * click elsewhere leaves it.
     */
    @RunOnInit
    public static void registerSearchBox() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
            net.minecraft.client.gui.components.EditBox box = new net.minecraft.client.gui.components.EditBox(
                    client.font, 0, 0, 120, 14, net.minecraft.network.chat.Component.literal("Search"));
            box.setMaxLength(40);
            box.visible = false;
            int[] boxMode = {-1};
            box.setResponder(text -> {
                if (boxMode[0] >= 0) setQuery(boxMode[0], text);
            });
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(box);
            ScreenEvents.beforeExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                boolean shown = System.currentTimeMillis() - lastRenderMs < 500 && boundsRight > boundsLeft;
                box.visible = shown;
                if (!shown) {
                    box.setFocused(false);
                    return;
                }
                if (boxMode[0] != lastMode) { // the list switched mode: show that mode's search
                    boxMode[0] = -1;
                    box.setValue(queries[lastMode]);
                    box.setHint(net.minecraft.network.chat.Component.literal("Search " + MODE_NOUN[lastMode] + "..."));
                    boxMode[0] = lastMode;
                }
                box.setWidth(boundsRight - boundsLeft);
                box.setX(boundsLeft);
                box.setY(boundsTop >= 16 ? boundsTop - 15 : boundsBottom + 2);
            });
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> {
                if (!box.visible || !box.isFocused()) return true;
                if (key.key() == GLFW.GLFW_KEY_ESCAPE) {
                    box.setFocused(false);
                    s.setFocused(null);
                } else {
                    box.keyPressed(key);
                }
                return false; // the field has it: not the inventory key, the mode key or anything else
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (box.isFocused() && !box.isMouseOver(click.x(), click.y())) box.setFocused(false);
                return true;
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("flipsearch")
                        .executes(ctx -> {
                            int mode = BaseProfit.getListMode();
                            setQuery(mode, "");
                            PlayerActionUtil.notifyAll("Search cleared (" + MODE_NOUN[mode] + ").");
                            return 1;
                        })
                        .then(ClientCommands.argument("text", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    int mode = BaseProfit.getListMode();
                                    setQuery(mode, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "text"));
                                    PlayerActionUtil.notifyAll("Searching " + MODE_NOUN[mode] + " for \"" + queries[mode]
                                            + "\". /flipsearch on its own clears it.");
                                    return 1;
                                }))));
    }

    @RunOnInit
    public static void registerScroll() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> {
                    if (potRight > potLeft && mouseX >= potLeft && mouseX <= potRight && mouseY >= potTop && mouseY <= potBottom) {
                        potentialScroll = Math.max(0, potentialScroll - (int) Math.signum(vertical));
                        return false; // scrolls the potential-fusions list, not the inventory
                    }
                    boolean over = mouseX >= boundsLeft && mouseX <= boundsRight && mouseY >= boundsTop && mouseY <= boundsBottom;
                    if (!over || boundsRight <= boundsLeft) return true;
                    scroll = Math.max(0, scroll - (int) Math.signum(vertical));
                    return false; // handled: do not also scroll the inventory behind it
                }));
    }

    // ---- flips / dips mode toggle ----

    private static final KeyMapping modeKey = new KeyMapping("Cycle list: Flips / Dips / Fusions / Forge", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y, com.github.mkram17.bazaarutils.features.keybinds.ItemSearchHelper.CATEGORY);

    private static final KeyMapping pauseKey = new KeyMapping("Pause flip list (freeze while fusing)", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U, com.github.mkram17.bazaarutils.features.keybinds.ItemSearchHelper.CATEGORY);

    /** Pauses (or resumes) every list, panel and calculator; resuming refreshes once, right away. */
    public static void setPaused(boolean pause) {
        if (!FlipPause.set(pause, FlipHud::refreshAfterPause)) {
            PlayerActionUtil.notifyAll(pause ? "Flip list is already paused." : "Flip list isn't paused.");
            return;
        }
        PlayerActionUtil.notifyAll(pause
                ? "§c§lFlip list PAUSED§r - nothing updates or re-sorts until you unpause (" + pauseKey.getTranslatedKeyMessage().getString() + " or /flippause)."
                : "§a§lFlip list resumed§r - refreshing now.");
    }

    // BazaarFlip's checks were skipped while paused: run one now; the calculators refresh on the next frame if they're due
    private static void refreshAfterPause() {
        Thread t = new Thread(() -> {
            try {
                Class.forName(MOD).getMethod("performBazaarCheck").invoke(null);
            } catch (Exception ignored) { }
        }, "BazaarFlip-unpause-refresh");
        t.setDaemon(true);
        t.start();
    }

    private static final String[] MODE_NAMES = {"FLIPS (stable spreads)", "DIPS (cheap vs 7-day normal)", "FUSIONS (attribute shards)",
            "FORGE (cheapest route per slot-hour)"};

    /**
     * Switches the list mode (null = next in the cycle Flips -> Dips -> Fusions -> Forge) and refreshes it right away instead of
     * waiting for the next poll.
     */
    public static void toggleMode(Integer forceMode) {
        if (forceMode != null && !Flippers.modeOn(forceMode)) {
            PlayerActionUtil.notifyAll("That list is turned off in /bazaarflip (Flippers).");
            return;
        }
        int current = BaseProfit.getListMode();
        int mode = forceMode != null ? forceMode : Flippers.nextMode(current, MODE_NAMES.length); // skips lists that are off
        if (forceMode == null && !Flippers.modeOn(mode)) {
            PlayerActionUtil.notifyAll("Every list is turned off in /bazaarflip (Flippers).");
            return;
        }
        BaseProfit.setListMode(mode);
        scroll = 0;
        PlayerActionUtil.notifyAll("List mode: " + MODE_NAMES[mode]);
        Thread t = new Thread(() -> {
            if (FlipPause.paused()) return; // the new mode shows what's already loaded; nothing refreshes until unpaused
            try {
                if (mode == 2) {
                    FusionCalc.refreshIfStale();
                    return;
                }
                if (mode == 3) {
                    ForgeCalc.refreshIfStale();
                    return;
                }
                Class<?> mod = Class.forName(MOD);
                mod.getMethod("performBazaarCheck").invoke(null);
                Thread.sleep(8000); // dip mode needs the 7-day history, which loads in the background on the first pass
                mod.getMethod("performBazaarCheck").invoke(null);
            } catch (Exception ignored) { }
        }, "BazaarFlip-mode-refresh");
        t.setDaemon(true);
        t.start();
    }

    @RunOnInit
    public static void registerModeToggle() {
        KeyMappingHelper.registerKeyMapping(modeKey);
        KeyMappingHelper.registerKeyMapping(pauseKey);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (modeKey.consumeClick()) {
                if (client.player != null && com.github.mkram17.bazaarutils.utils.VersionCompat.getScreen(client) == null) toggleMode(null);
            }
            while (pauseKey.consumeClick()) {
                if (client.player != null && com.github.mkram17.bazaarutils.utils.VersionCompat.getScreen(client) == null) setPaused(!FlipPause.paused());
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenKeyboardEvents.afterKeyPress(screen).register((s, keyEvent) -> {
                    if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
                    if (modeKey.matches(keyEvent)) toggleMode(null);
                    if (pauseKey.matches(keyEvent)) setPaused(!FlipPause.paused()); // works inside the Fusion Machine
                }));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("flippause")
                        .executes(ctx -> { setPaused(!FlipPause.paused()); return 1; })
                        .then(ClientCommands.literal("on").executes(ctx -> { setPaused(true); return 1; }))
                        .then(ClientCommands.literal("off").executes(ctx -> { setPaused(false); return 1; }))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("flipmode")
                        .executes(ctx -> { toggleMode(null); return 1; })
                        .then(ClientCommands.literal("flips").executes(ctx -> { toggleMode(0); return 1; }))
                        .then(ClientCommands.literal("dips").executes(ctx -> { toggleMode(1); return 1; }))
                        .then(ClientCommands.literal("fusions").executes(ctx -> { toggleMode(2); return 1; }))
                        .then(ClientCommands.literal("forge").executes(ctx -> { toggleMode(3); return 1; }))));
    }
}
