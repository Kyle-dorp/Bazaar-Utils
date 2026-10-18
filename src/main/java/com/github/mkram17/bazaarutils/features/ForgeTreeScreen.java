package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.features.ForgePlanner.QueueItem;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Route;
import com.github.mkram17.bazaarutils.features.ForgePlanner.Step;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.*;

/**
 * A forge flip's whole route drawn as a tree: every part, whether it is forged (with how long) or bought (at what price),
 * joined by lines to what it goes into. Beside it, the same route as two to-do lists: the forges to queue, grouped and in the
 * order they can start, and everything to buy. Opened by clicking a row of the forge list, or with /forgetree [rank].
 */
public class ForgeTreeScreen extends Screen {
    private static final int PAD = 8, HEADER_H = 52, NODE_H = 18, INDENT = 14, SIDE_W = 200, LINE_H = 11;
    private static final int BG = 0xD0101018, BORDER = 0xFF3A3A4A, CONNECTOR = 0xFF6A6A7A;
    private static final int FORGE_COLOR = 0xFF55FF55, BUY_COLOR = 0xFFFFFF55;

    private record Node(Step step, int depth, int parent) { }

    private final ForgeCalc.Row row;
    private final int slots;
    private final double checkHours;
    private final List<Node> nodes = new ArrayList<>();
    private final List<QueueItem> queue;
    private final List<Map.Entry<String, Double>> shopping;
    private final Map<String, Double> unitPrice = new HashMap<>();
    private int scroll;

    /**
     * Opens the tree for a row on the next tick (a click or a chat command is still being handled now). An open Hypixel menu
     * is closed properly first so the server knows it's gone, then the tree takes its place.
     */
    public static void open(ForgeCalc.Row row) {
        com.github.mkram17.bazaarutils.utils.Util.tickExecuteLater(1, () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && com.github.mkram17.bazaarutils.utils.VersionCompat.getScreen(mc)
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                mc.player.closeContainer();
            }
            com.github.mkram17.bazaarutils.utils.VersionCompat.setScreen(mc, new ForgeTreeScreen(row));
        });
    }

    public ForgeTreeScreen(ForgeCalc.Row row) {
        super(Component.literal("Forge route"));
        this.row = row;
        this.slots = BaseProfit.getForgeSlots();
        this.checkHours = BaseProfit.getForgeCheckHours();
        flatten(row.route().tree(), 0, -1);
        this.queue = ForgePlanner.forgeQueue(row.route().tree());
        this.shopping = new ArrayList<>(ForgePlanner.boughtInputs(row.route().tree()).entrySet());
        shopping.sort((a, b) -> Double.compare(b.getValue() * unitPrice.getOrDefault(b.getKey(), 0.0),
                a.getValue() * unitPrice.getOrDefault(a.getKey(), 0.0)));
    }

    private void flatten(Step s, int depth, int parent) {
        int index = nodes.size();
        nodes.add(new Node(s, depth, parent));
        if (!s.forged()) unitPrice.putIfAbsent(s.id(), s.coins() / s.quantity());
        for (Step p : s.parts()) flatten(p, depth + 1, index);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- layout ----

    private boolean twoColumns() {
        return width >= 520;
    }

    private int contentTop() {
        return PAD + HEADER_H;
    }

    private int contentBottom() {
        return height - PAD - 4;
    }

    private int treeWidth() {
        return twoColumns() ? width - 2 * PAD - SIDE_W - 12 : width - 2 * PAD - 8;
    }

    private int sideHeight() {
        int stages = queue.isEmpty() ? 0 : queue.get(queue.size() - 1).stage();
        return (2 + queue.size() + stages + 1 + shopping.size() + 2) * LINE_H + 10;
    }

    private int contentHeight() {
        int tree = nodes.size() * NODE_H + 4;
        return twoColumns() ? Math.max(tree, sideHeight()) : tree + 8 + sideHeight();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, contentHeight() - (contentBottom() - contentTop()));
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY) * NODE_H * 2));
        return true;
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int left = PAD, right = width - PAD;
        g.fill(left, PAD, right, height - PAD, BG);
        g.outline(left, PAD, right - left, height - 2 * PAD, BORDER);
        drawHeader(g, left + 6, right - 6);
        g.fill(left + 4, contentTop() - 3, right - 4, contentTop() - 2, 0x30FFFFFF);

        g.enableScissor(left + 1, contentTop(), right - 1, contentBottom());
        int y0 = contentTop() + 2 - scroll;
        drawTree(g, left + 6, y0);
        if (twoColumns()) {
            int sideX = right - SIDE_W - 4;
            g.fill(sideX - 6, contentTop() - scroll, sideX - 5, contentTop() + contentHeight() - scroll, 0x30FFFFFF);
            drawLists(g, sideX, y0, SIDE_W - 4);
        } else {
            drawLists(g, left + 6, y0 + nodes.size() * NODE_H + 8, treeWidth());
        }
        g.disableScissor();
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawHeader(GuiGraphicsExtractor g, int x, int right) {
        Route r = row.route();
        String perHour = "§a§l" + FlipHud.coins(row.perHour()) + "/hr";
        g.text(font, perHour, right - font.width(perHour), PAD + 6, 0xFFFFFFFF, true);
        g.text(font, fit(font, "§6§l" + ForgeCalc.name(r.id()) + " §7- forge route", right - x - font.width(perHour) - 8),
                x, PAD + 6, 0xFFFFFFFF, true);
        String sold = row.soldOnAh() ? " §8(AH, after fees)" : " §8(Bazaar, after tax)";
        g.text(font, fit(font, "§aSell §f" + FlipHud.coins(r.revenue()) + sold + " §7- Cost §f" + FlipHud.coins(r.cost())
                + " §7= §6Profit " + FlipHud.coins(r.profit()) + "   §e" + FlipHud.coins(row.perDay()) + "/day", right - x), x, PAD + 18, 0xFFFFFFFF, true);
        g.text(font, fit(font, "§7Limited by §f" + ForgeCalc.limitText(row, slots, true) + " §7- §f"
                + ForgeCalc.qty(Math.round(row.throughput().runsPerHour() * 24 * 10) / 10.0) + " §7runs/day   §f"
                + FlipHud.coins(r.profitPerSlotHour()) + "§7/hr per slot", right - x), x, PAD + 29, 0xFFFFFFFF, true);
        g.text(font, fit(font, "§7Slot time §f" + ForgeCalc.duration(r.slotSeconds()) + "§7 over §f" + r.forgeSteps()
                + "§7 forges, done in ~§f" + ForgeCalc.duration(r.hoursWith(slots) * 3600) + "§7 with " + slots + " slots"
                + (checkHours > 0 ? ", collecting every " + ForgeCalc.duration(checkHours * 3600) : "")
                + "   §8scroll · esc to close", right - x), x, PAD + 40, 0xFFFFFFFF, true);
    }

    private void drawTree(GuiGraphicsExtractor g, int x0, int y0) {
        int maxW = treeWidth();
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            int x = x0 + n.depth() * INDENT, y = y0 + i * NODE_H;
            if (n.parent() >= 0) {
                Node p = nodes.get(n.parent());
                int px = x0 + p.depth() * INDENT + 7, py = y0 + n.parent() * NODE_H;
                g.fill(px, py + 16, px + 1, y + 9, CONNECTOR);   // down from the parent's icon
                g.fill(px, y + 8, x - 1, y + 9, CONNECTOR);      // across to this part
            }
            Step s = n.step();
            ItemStack icon = icon(s.id());
            if (icon.isEmpty()) g.fill(x + 3, y + 4, x + 12, y + 13, s.forged() ? FORGE_COLOR : BUY_COLOR);
            else g.item(icon, x, y);
            String text = s.forged()
                    ? "§aForge §f" + ForgeCalc.qty(s.quantity()) + "x " + ForgeCalc.name(s.id()) + "  §7" + ForgeCalc.duration(s.secondsPerForge())
                    + (s.forges() > 1 ? " §8x" + ForgeCalc.qty(s.forges()) : "")
                    + (ForgeCalc.requirement(s.id()).hotm() > 0 ? " §3HotM " + ForgeCalc.requirement(s.id()).hotm() : "")
                    : "§eBuy §f" + ForgeCalc.qty(s.quantity()) + "x " + ForgeCalc.name(s.id()) + " §8@ " + FlipHud.coins(s.coins() / s.quantity())
                    + " §7= §f" + FlipHud.coins(s.coins());
            g.text(font, fit(font, text, maxW - (x - x0) - 20), x + 19, y + 5, 0xFFFFFFFF, true);
        }
    }

    private void drawLists(GuiGraphicsExtractor g, int x, int y, int w) {
        g.text(font, "§b§lForges to queue", x, y + 2, 0xFFFFFFFF, true);
        y += LINE_H + 4;
        int stage = 0;
        for (QueueItem q : queue) {
            if (q.stage() != stage) {
                stage = q.stage();
                g.text(font, "§7Stage " + stage + (stage == 1 ? " - start now" : " - after stage " + (stage - 1)), x, y, 0xFFFFFFFF, true);
                y += LINE_H;
            }
            g.text(font, fit(font, " §a" + ForgeCalc.qty(q.forges()) + "x §f" + ForgeCalc.name(q.id()) + " §8"
                    + ForgeCalc.duration(q.secondsPerForge()), w), x, y, 0xFFFFFFFF, true);
            y += LINE_H;
        }
        y += 6;
        g.text(font, "§e§lShopping list", x, y, 0xFFFFFFFF, true);
        y += LINE_H + 2;
        double total = 0;
        for (var e : shopping) {
            double cost = e.getValue() * unitPrice.getOrDefault(e.getKey(), 0.0);
            total += cost;
            String price = FlipHud.coins(cost);
            g.text(font, fit(font, "§f" + ForgeCalc.qty(e.getValue()) + "x " + ForgeCalc.name(e.getKey()), w - font.width(price) - 6),
                    x, y, 0xFFFFFFFF, true);
            g.text(font, "§7" + price, x + w - font.width(price), y, 0xFFFFFFFF, true);
            y += LINE_H;
        }
        String sum = FlipHud.coins(total + coinCosts(row.route().tree()));
        g.text(font, "§7Total incl. forge coins", x, y + 2, 0xFFFFFFFF, true);
        g.text(font, "§6" + sum, x + w - font.width(sum), y + 2, 0xFFFFFFFF, true);
    }

    /** Coins paid to the forge itself (the Divan Drill's 50M, travel scrolls...), on top of the parts. */
    private static double coinCosts(Step s) {
        if (!s.forged()) return 0;
        double partCoins = 0;
        for (Step p : s.parts()) partCoins += p.coins();
        double own = s.coins() - partCoins;
        double sum = own;
        for (Step p : s.parts()) sum += coinCosts(p);
        return sum;
    }

    private static String fit(Font font, String text, int max) {
        if (max <= 8 || font.width(text) <= max) return text;
        while (text.length() > 4 && font.width(text + "..") > max) text = text.substring(0, text.length() - 1);
        return text + "..";
    }

    // ---- item icons from Skyblocker's item repo (by reflection: its API differs between versions) ----

    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static Method getItemStack, getStackOrEmpty;
    private static boolean iconsUnavailable;

    /** Skyblocker's item for a NEU id, or EMPTY. 6.6 returns an ItemStack; 6.10 a FlexibleItemStack (getStackOrEmpty). */
    static ItemStack icon(String id) {
        ItemStack cached = ICONS.get(id);
        if (cached != null) return cached;
        if (iconsUnavailable) return ItemStack.EMPTY;
        try {
            if (getItemStack == null) {
                if (!FabricLoader.getInstance().isModLoaded("skyblocker")) {
                    iconsUnavailable = true;
                    return ItemStack.EMPTY;
                }
                getItemStack = Class.forName("de.hysky.skyblocker.skyblock.itemlist.ItemRepository").getMethod("getItemStack", String.class);
            }
            Object result = getItemStack.invoke(null, id);
            ItemStack stack = ItemStack.EMPTY;
            if (result instanceof ItemStack s) {
                stack = s;
            } else if (result != null) {
                if (getStackOrEmpty == null) getStackOrEmpty = result.getClass().getMethod("getStackOrEmpty");
                if (getStackOrEmpty.invoke(result) instanceof ItemStack s) stack = s;
            }
            if (!stack.isEmpty()) ICONS.put(id, stack); // an empty result may just mean the repo is still loading
            return stack;
        } catch (Throwable t) {
            iconsUnavailable = true; // missing or changed API: draw the coloured squares instead
            return ItemStack.EMPTY;
        }
    }
}
