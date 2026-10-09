package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.utils.VersionCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact replacement for BazaarFlip's settings screen (BazaarFlip is patched to open this one). Everything here is about
 * the in-game flip list: what it shows and how it looks. Chat/webhook alert options are not exposed.
 */
public class FlipSettingsScreen extends Screen {
    private static final String CONFIG = "uwu.ramona.bazaar.config.BazaarConfig";
    private static final String[] SCALE = {"", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x"};

    private final Screen parent;
    private final List<Runnable> savers = new ArrayList<>();
    private int scaleIndex;
    private boolean listOn;
    private Button scaleButton, listButton;

    /** BazaarFlip opens its config screen with a no-arg constructor, so this is what the patch calls. */
    public FlipSettingsScreen() {
        this(null);
    }

    public FlipSettingsScreen(Screen parent) {
        super(Component.literal("Flip List Settings"));
        this.parent = parent;
    }

    // ---- BazaarFlip config access (reflection: BazaarFlip is not a compile dependency) ----

    private static Field field(String name) throws Exception {
        return Class.forName(CONFIG).getField(name);
    }

    private static int getInt(String name) {
        try {
            return field(name).getInt(null);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void setInt(String name, int value) {
        try {
            field(name).setInt(null, value);
        } catch (Exception ignored) { }
    }

    private static boolean getBool(String name) {
        try {
            return field(name).getBoolean(null);
        } catch (Exception e) {
            return false;
        }
    }

    private static void setBool(String name, boolean value) {
        try {
            field(name).setBoolean(null, value);
        } catch (Exception ignored) { }
    }

    private static void saveConfig() {
        try {
            Class.forName(CONFIG).getMethod("save").invoke(null);
        } catch (Exception ignored) { }
    }

    // ---- ui ----

    private static final int COL_W = 150, GAP = 14, ROW_H = 30;

    private int x0() {
        return width / 2 - COL_W - GAP / 2;
    }

    private EditBox box(int x, int y, String label, String value) {
        EditBox b = new EditBox(font, x, y, COL_W, 16, Component.literal(label));
        b.setValue(value);
        addRenderableWidget(b);
        return b;
    }

    private void numberInt(int x, int y, String label, String key, int min) {
        EditBox b = box(x, y, label, String.valueOf(getInt(key)));
        savers.add(() -> {
            try {
                setInt(key, Math.max(min, (int) Double.parseDouble(b.getValue().trim().replace(",", ""))));
            } catch (NumberFormatException ignored) { }
        });
    }

    private void numberDouble(int x, int y, String label, double current, java.util.function.DoubleConsumer setter) {
        EditBox b = box(x, y, label, BaseProfit.format(current));
        savers.add(() -> {
            try {
                setter.accept(Double.parseDouble(b.getValue().trim().replace(",", "")));
            } catch (NumberFormatException ignored) { }
        });
    }

    private final List<String[]> labels = new ArrayList<>();
    private final List<int[]> labelPos = new ArrayList<>();

    private void label(int x, int y, String text) {
        labels.add(new String[]{text});
        labelPos.add(new int[]{x, y});
    }

    @Override
    protected void init() {
        savers.clear();
        labels.clear();
        labelPos.clear();
        scaleIndex = Math.max(1, Math.min(5, getInt("guiScale")));
        listOn = getBool("enableMonitoring");

        int left = x0(), right = left + COL_W + GAP;
        int top = 44;

        // left column: what the list shows
        label(left, top - 22, "WHAT IT SHOWS");
        String[][] leftRows = {
                {"Min profit per item (coins)"}, {"Min items per hour"}, {"Max price vs 7-day average (%)"},
                {"Max spend (coins)"}, {"Min margin (%)"}, {"Sell tax (%)"}};
        for (int i = 0; i < leftRows.length; i++) label(left, top + i * ROW_H - 10, leftRows[i][0]);
        numberDouble(left, top + 0 * ROW_H, "Min profit", BaseProfit.getCoins(), BaseProfit::setCoins);
        numberDouble(left, top + 1 * ROW_H, "Min items/hr", BaseProfit.getMinItemsPerHour(), BaseProfit::setMinItemsPerHour);
        numberDouble(left, top + 2 * ROW_H, "Avg filter", BaseProfit.getAvgTolerance(), BaseProfit::setAvgTolerance);
        numberInt(left, top + 3 * ROW_H, "Max spend", "maxSpendLimit", 0);
        numberInt(left, top + 4 * ROW_H, "Min margin", "backgroundAlertThreshold", 0);
        numberDouble(left, top + 5 * ROW_H, "Tax", BaseProfit.getTaxPercent(), BaseProfit::setTaxPercent);

        // right column: how it looks
        label(right, top - 22, "HOW IT LOOKS");
        listButton = Button.builder(Component.literal("Flip list: " + (listOn ? "ON" : "OFF")), b -> {
            listOn = !listOn;
            b.setMessage(Component.literal("Flip list: " + (listOn ? "ON" : "OFF")));
        }).bounds(right, top - 10, COL_W, 18).build();
        addRenderableWidget(listButton);
        scaleButton = Button.builder(Component.literal("Size: " + SCALE[scaleIndex]), b -> {
            scaleIndex = scaleIndex % 5 + 1;
            b.setMessage(Component.literal("Size: " + SCALE[scaleIndex]));
        }).bounds(right, top - 10 + 1 * ROW_H, COL_W, 18).build();
        addRenderableWidget(scaleButton);
        String[] rightRows = {"Rows in profit/hr list", "Rows in margin % list", "Position X", "Position Y"};
        for (int i = 0; i < rightRows.length; i++) label(right, top + (i + 2) * ROW_H - 10, rightRows[i]);
        numberInt(right, top + 2 * ROW_H, "Rows A", "bazaarGuiTopItemsByPercentageCount", 1);
        numberInt(right, top + 3 * ROW_H, "Rows B", "bazaarGuiTopItemsByMoneyCount", 1);
        numberInt(right, top + 4 * ROW_H, "X", "guiX", Integer.MIN_VALUE);
        numberInt(right, top + 5 * ROW_H, "Y", "guiY", Integer.MIN_VALUE);

        addRenderableWidget(Button.builder(Component.literal("Save and close"), b -> onClose())
                .bounds(width / 2 - 70, top + 6 * ROW_H + 4, 140, 20).build());
    }

    @Override
    public void onClose() {
        for (Runnable r : savers) r.run();
        setInt("guiScale", scaleIndex);
        setBool("enableMonitoring", listOn);
        saveConfig();
        VersionCompat.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int left = x0() - 10, right = x0() + 2 * COL_W + GAP + 10;
        g.fill(left, 8, right, height - 8, 0xC0101018);
        g.text(font, "Flip List Settings", width / 2 - font.width("Flip List Settings") / 2, 14, 0xFFFFAA00, true);
        super.extractRenderState(g, mouseX, mouseY, delta);
        for (int i = 0; i < labels.size(); i++) {
            boolean header = labels.get(i)[0].equals("WHAT IT SHOWS") || labels.get(i)[0].equals("HOW IT LOOKS");
            g.text(font, labels.get(i)[0], labelPos.get(i)[0], labelPos.get(i)[1] + (header ? 14 : 0),
                    header ? 0xFF55FFFF : 0xFFBBBBBB, true);
        }
    }
}
