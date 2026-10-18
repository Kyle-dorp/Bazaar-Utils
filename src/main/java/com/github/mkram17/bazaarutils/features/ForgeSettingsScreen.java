package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.utils.VersionCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything about the Forge flip list in one place: forge slots, Quick Forge, how often you check your forges, and the two
 * optional filters, your HotM unlocks and your purse. Opened from the flip settings screen or with /forgesettings.
 */
public class ForgeSettingsScreen extends Screen {
    private static final int COL_W = 150, GAP = 14, ROW_H = 28;

    private final Screen parent;
    private final List<Runnable> savers = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<int[]> labelPos = new ArrayList<>();
    private boolean unlockFilter, purseFilter;

    public ForgeSettingsScreen(Screen parent) {
        super(Component.literal("Forge Flip Settings"));
        this.parent = parent;
    }

    /** Opens the screen from a command (chat is still closing this tick). */
    public static void open() {
        com.github.mkram17.bazaarutils.utils.Util.tickExecuteLater(1, () ->
                VersionCompat.setScreen(Minecraft.getInstance(), new ForgeSettingsScreen(null)));
    }

    private int x0() {
        return width / 2 - COL_W - GAP / 2;
    }

    private void label(int x, int y, String text) {
        labels.add(text);
        labelPos.add(new int[]{x, y});
    }

    private void number(int x, int y, String label, double current, java.util.function.DoubleConsumer setter) {
        label(x, y - 10, label);
        EditBox b = new EditBox(font, x, y, COL_W, 16, Component.literal(label));
        b.setValue(BaseProfit.format(current));
        addRenderableWidget(b);
        savers.add(() -> {
            try {
                setter.accept(Double.parseDouble(b.getValue().trim().replace(",", "")));
            } catch (NumberFormatException ignored) { }
        });
    }

    private static String onOff(String what, boolean on) {
        return what + ": " + (on ? "ON" : "OFF");
    }

    @Override
    protected void init() {
        savers.clear();
        labels.clear();
        labelPos.clear();
        unlockFilter = BaseProfit.isForgeUnlockFilter();
        purseFilter = BaseProfit.isForgePurseFilter();
        int left = x0(), right = left + COL_W + GAP, top = 52;

        // left: your forge
        number(left, top, "Forge slots (1-7)", BaseProfit.getForgeSlots(), v -> BaseProfit.setForgeSlots((int) v));
        number(left, top + ROW_H, "Quick Forge (%, 0-30)", BaseProfit.getQuickForgePercent(), BaseProfit::setQuickForgePercent);
        number(left, top + 2 * ROW_H, "Check forges every (hours)", BaseProfit.getForgeCheckHours(), BaseProfit::setForgeCheckHours);

        // right: what you can actually do
        number(right, top, "Your HotM tier (0-10)", BaseProfit.getHotmTier(), v -> BaseProfit.setHotmTier((int) v));
        addRenderableWidget(Button.builder(Component.literal(onOff("Only forges I've unlocked", unlockFilter)), b -> {
            unlockFilter = !unlockFilter;
            b.setMessage(Component.literal(onOff("Only forges I've unlocked", unlockFilter)));
        }).bounds(right, top + ROW_H - 4, COL_W, 20).build());
        addRenderableWidget(Button.builder(Component.literal(onOff("Only what my purse covers", purseFilter)), b -> {
            purseFilter = !purseFilter;
            b.setMessage(Component.literal(onOff("Only what my purse covers", purseFilter)));
        }).bounds(right, top + 2 * ROW_H - 4, COL_W, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Save and close"), b -> onClose())
                .bounds(width / 2 - 70, top + 4 * ROW_H, 140, 20).build());
    }

    @Override
    public void onClose() {
        for (Runnable r : savers) r.run();
        BaseProfit.setForgeUnlockFilter(unlockFilter);
        BaseProfit.setForgePurseFilter(purseFilter);
        VersionCompat.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int left = x0() - 10, right = x0() + 2 * COL_W + GAP + 10, top = 52;
        g.fill(left, 8, right, top + 4 * ROW_H + 28, 0xC0101018);
        g.text(font, "Forge Flip Settings", width / 2 - font.width("Forge Flip Settings") / 2, 14, 0xFFFFAA00, true);
        super.extractRenderState(g, mouseX, mouseY, delta);
        for (int i = 0; i < labels.size(); i++) {
            g.text(font, labels.get(i), labelPos.get(i)[0], labelPos.get(i)[1], 0xFFBBBBBB, true);
        }
        double p = ForgeCalc.lastPurse();
        // help lines kept within the two columns (~314px)
        String purseLine = p > 0 ? "§7Purse now: §6" + FlipHud.coins(p) + " §8- routes must cost less up front"
                : "§7Purse: §cnot read yet §8- filter skipped until it is";
        g.text(font, purseLine, x0(), top + 3 * ROW_H - 4, 0xFFFFFFFF, true);
        g.text(font, "§8Parts above your HotM tier get bought instead.", x0(), top + 3 * ROW_H + 7, 0xFFFFFFFF, true);
        g.text(font, "§8Collection needs are shown in routes, not filtered.", x0(), top + 3 * ROW_H + 16, 0xFFFFFFFF, true);
    }
}
