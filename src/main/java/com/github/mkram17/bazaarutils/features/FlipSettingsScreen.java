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
    private final boolean advanced;
    private boolean activeRanking;
    private final List<Runnable> savers = new ArrayList<>();
    private int scaleIndex;
    private boolean listOn;
    private int listMode;
    private static final String[] MODE_LABELS = {"List: FLIPS (stable spreads)", "List: DIPS (cheap vs normal)", "List: FUSIONS (shards)",
            "List: FORGE (full routes)"};
    private Button scaleButton, listButton;
    // the flipper checkboxes, applied on save (index = Flippers.Flipper ordinal)
    private final boolean[] flipperOn = new boolean[Flippers.Flipper.values().length];

    /** BazaarFlip opens its config screen with a no-arg constructor, so this is what the patch calls. */
    public FlipSettingsScreen() {
        this(null);
    }

    public FlipSettingsScreen(Screen parent) {
        this(parent, false);
    }

    private FlipSettingsScreen(Screen parent, boolean advanced) {
        super(Component.literal(advanced ? "Advanced Flip Settings" : "Trading Dashboard"));
        this.parent = parent;
        this.advanced = advanced;
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

    private static final int COL_W = 150, GAP = 14, ROW_H = 28;

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
        activeRanking = BaseProfit.isActiveRanking();
        for (Flippers.Flipper f : Flippers.Flipper.values()) flipperOn[f.ordinal()] = Flippers.on(f);
        if (!advanced) {
            initDashboard();
            return;
        }

        int left = x0(), right = left + COL_W + GAP;
        int top = 62;

        // left column: what the list shows
        label(left, top - 32, "WHAT IT SHOWS");
        String[][] leftRows = {
                {"Min profit per item (coins)"}, {"Min items per hour"}, {"Max vs 7-day average (%)"},
                {"Max spend (coins)"}, {"Min margin (%)"}, {"Sell tax (%)"}, {"Dip mode: min dip vs 7-day (%)"}};
        for (int i = 0; i < leftRows.length; i++) label(left, top + i * ROW_H - 10, leftRows[i][0]);
        numberDouble(left, top + 0 * ROW_H, "Min profit", BaseProfit.getCoins(), BaseProfit::setCoins);
        numberDouble(left, top + 1 * ROW_H, "Min items/hr", BaseProfit.getMinItemsPerHour(), BaseProfit::setMinItemsPerHour);
        numberDouble(left, top + 2 * ROW_H, "Avg filter", BaseProfit.getAvgTolerance(), BaseProfit::setAvgTolerance);
        numberInt(left, top + 3 * ROW_H, "Max spend", "maxSpendLimit", 0);
        numberInt(left, top + 4 * ROW_H, "Min margin", "backgroundAlertThreshold", 0);
        numberDouble(left, top + 5 * ROW_H, "Tax", BaseProfit.getTaxPercent(), BaseProfit::setTaxPercent);
        numberDouble(left, top + 6 * ROW_H, "Dip", BaseProfit.getMinDipPercent(), BaseProfit::setMinDipPercent);

        // right column: how it looks
        label(right, top - 32, "HOW IT LOOKS");
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
        String[] rightRows = {"Rows visible at once", "Total rows (scroll)", "Position X", "Position Y"};
        for (int i = 0; i < rightRows.length; i++) label(right, top + (i + 2) * ROW_H - 10, rightRows[i]);
        EditBox visibleBox = box(right, top + 2 * ROW_H, "Rows visible", String.valueOf(BaseProfit.getVisibleRows()));
        savers.add(() -> {
            try {
                BaseProfit.setVisibleRows((int) Double.parseDouble(visibleBox.getValue().trim()));
            } catch (NumberFormatException ignored) { }
        });
        // the flip list is one column now: all rows live in the first list, the second stays empty
        EditBox totalBox = box(right, top + 3 * ROW_H, "Total rows", String.valueOf(
                Math.max(1, getInt("bazaarGuiTopItemsByPercentageCount") + getInt("bazaarGuiTopItemsByMoneyCount"))));
        savers.add(() -> {
            try {
                setInt("bazaarGuiTopItemsByPercentageCount", Math.max(1, (int) Double.parseDouble(totalBox.getValue().trim())));
                setInt("bazaarGuiTopItemsByMoneyCount", 0);
            } catch (NumberFormatException ignored) { }
        });
        label(right, top + 7 * ROW_H - 10, "Min fusion profit (0 = shared)");
        numberDouble(right, top + 7 * ROW_H, "Min fusion profit", BaseProfit.getFusionMin(), BaseProfit::setFusionMin);
        numberInt(right, top + 4 * ROW_H, "X", "guiX", Integer.MIN_VALUE);
        numberInt(right, top + 5 * ROW_H, "Y", "guiY", Integer.MIN_VALUE);

        listMode = BaseProfit.getListMode();
        addRenderableWidget(Button.builder(Component.literal(modeLabel()), b -> {
            listMode = (listMode + 1) % MODE_LABELS.length;
            b.setMessage(Component.literal(modeLabel()));
        }).bounds(right, top + 6 * ROW_H - 4, COL_W, 20).build());

        // forge list options (slots, Quick Forge, check-ins, HotM and purse filters) have their own screen
        addRenderableWidget(Button.builder(Component.literal("Forge settings..."), b -> {
            applyAll();
            VersionCompat.setScreen(Minecraft.getInstance(), new ForgeSettingsScreen(this));
        }).bounds(left, top + 7 * ROW_H - 4, COL_W, 20).build());

        // flippers: one compact row of checkboxes; off = not computed or fetched at all, not in the list, the cycle key or the advisor
        int flipY = top + 8 * ROW_H + 4;
        label(left, flipY - 11, "FLIPPERS §8(green on, crossed out off; hover for details)");
        Flippers.Flipper[] all = Flippers.Flipper.values();
        int bw = (2 * COL_W + GAP - (all.length - 1) * 2) / all.length;
        for (Flippers.Flipper f : all) {
            flipperOn[f.ordinal()] = Flippers.on(f);
            Button cb = Button.builder(flipperLabel(f), b -> {
                flipperOn[f.ordinal()] = !flipperOn[f.ordinal()];
                b.setMessage(flipperLabel(f));
            }).bounds(left + f.ordinal() * (bw + 2), flipY, bw, 16).tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal(f.description + "\nOff: not computed or fetched, left out of the list, the cycle key and the advisor."))).build();
            addRenderableWidget(cb);
        }

        addRenderableWidget(Button.builder(Component.literal("Save and close"), b -> onClose())
                .bounds(width / 2 - 70, flipY + 22, 140, 20).build());
    }

    private void initDashboard() {
        int left = x0(), right = left + COL_W + GAP, top = 50;
        int step = 24;

        // left: the filters the ranking applies
        label(left, 34, "TRADING");
        String[][] leftLabels = {{"Purse reserve %"}, {"Trade window (min)"},
                {"Min profit/item (0=off)"}, {"Min items/hour (0=off)"}, {"Max vs 7-day avg (%)"}, {"Sell tax (%)"}};
        for (int i = 0; i < leftLabels.length; i++) label(left, top + i * step - 9, leftLabels[i][0]);
        numberDouble(left, top + 0 * step, "Reserve", BaseProfit.getReservePercent(), BaseProfit::setReservePercent);
        numberDouble(left, top + 1 * step, "Window", BaseProfit.getTargetMinutes(), BaseProfit::setTargetMinutes);
        numberDouble(left, top + 2 * step, "Min profit", BaseProfit.getCoins(), BaseProfit::setCoins);
        numberDouble(left, top + 3 * step, "Min items/hr", BaseProfit.getMinItemsPerHour(), BaseProfit::setMinItemsPerHour);
        numberDouble(left, top + 4 * step, "Avg filter", BaseProfit.getAvgTolerance(), BaseProfit::setAvgTolerance);
        numberDouble(left, top + 5 * step, "Tax", BaseProfit.getTaxPercent(), BaseProfit::setTaxPercent);
        double purse = MaxBuyOrder.readPurse(Minecraft.getInstance());
        label(left, top + 6 * step - 6, Double.isFinite(purse) && purse >= 0
                ? "Spendable: " + FlipHud.coins(ActiveRanking.budget(purse, BaseProfit.getReservePercent()))
                : "Purse unavailable");

        // right: ranking mode, list, and the remaining filters
        label(right, 34, "LIST & FILTERS");
        addRenderableWidget(Button.builder(Component.literal("Rank: " + (activeRanking ? "ACTIVE" : "LEGACY")), b -> {
            activeRanking = !activeRanking;
            b.setMessage(Component.literal("Rank: " + (activeRanking ? "ACTIVE" : "LEGACY")));
        }).bounds(right, top - 10, COL_W, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("ACTIVE: sized batches ranked by estimated net profit. LEGACY: BazaarFlip's own list."))).build());
        listMode = BaseProfit.getListMode();
        addRenderableWidget(Button.builder(Component.literal(modeLabel()), b -> {
            listMode = nextListMode();
            b.setMessage(Component.literal(modeLabel()));
        }).bounds(right, top + step - 10, COL_W, 18).build());
        label(right, top + 2 * step - 9, "Min margin (%)");
        numberInt(right, top + 2 * step, "Min margin", "backgroundAlertThreshold", 0);
        label(right, top + 3 * step - 9, "Max spend (coins)");
        numberInt(right, top + 3 * step, "Max spend", "maxSpendLimit", 0);
        label(right, top + 4 * step - 9, "Min dip vs 7-day (%)");
        numberDouble(right, top + 4 * step, "Dip", BaseProfit.getMinDipPercent(), BaseProfit::setMinDipPercent);
        label(right, top + 5 * step - 9, "Min fusion profit (0=shared)");
        numberDouble(right, top + 5 * step, "Fusion min", BaseProfit.getFusionMin(), BaseProfit::setFusionMin);

        int y = top + 7 * step + 4;
        Flippers.Flipper[] all = Flippers.Flipper.values();
        int bw = (2 * COL_W + GAP - (all.length - 1) * 2) / all.length;
        label(left, y - 12, "ENABLED METHODS §8(green on; hover for details)");
        for (int i = 0; i < all.length; i++) {
            Flippers.Flipper f = all[i];
            addRenderableWidget(Button.builder(flipperLabel(f), b -> {
                flipperOn[f.ordinal()] = !flipperOn[f.ordinal()];
                b.setMessage(flipperLabel(f));
            }).bounds(left + i * (bw + 2), y, bw, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(f.description))).build());
        }
        int btnY = y + 22;
        addRenderableWidget(Button.builder(Component.literal("Forge settings..."), b -> {
            applyAll();
            VersionCompat.setScreen(Minecraft.getInstance(), new ForgeSettingsScreen(this));
        }).bounds(left, btnY, COL_W, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Advanced / appearance..."), b -> {
            applyAll();
            VersionCompat.setScreen(Minecraft.getInstance(), new FlipSettingsScreen(this, true));
        }).bounds(right, btnY, COL_W, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save and close"), b -> onClose())
                .bounds(width / 2 - 70, btnY + 24, 140, 20).build());
    }

    private boolean modeAvailable(int mode) {
        return switch (mode) {
            case 0 -> flipperOn[Flippers.Flipper.FLIPS.ordinal()] || flipperOn[Flippers.Flipper.BOOKS.ordinal()];
            case 1 -> flipperOn[Flippers.Flipper.DIPS.ordinal()];
            case 2 -> flipperOn[Flippers.Flipper.FUSIONS.ordinal()];
            case 3 -> flipperOn[Flippers.Flipper.FORGE.ordinal()];
            default -> false;
        };
    }

    private int nextListMode() {
        for (int i = 1; i <= MODE_LABELS.length; i++) {
            int mode = (listMode + i) % MODE_LABELS.length;
            if (modeAvailable(mode)) return mode;
        }
        return listMode;
    }

    private String modeLabel() {
        return listMode == 0 && activeRanking ? "List: ACTIVE FLIPS (no fusions)" : MODE_LABELS[listMode];
    }

    private Component flipperLabel(Flippers.Flipper f) {
        return Component.literal((flipperOn[f.ordinal()] ? "§a" : "§8§m") + f.label);
    }

    /** Saves everything typed or toggled on this screen. */
    private void applyAll() {
        for (Runnable r : savers) r.run();
        setInt("guiScale", scaleIndex);
        setBool("enableMonitoring", listOn);
        BaseProfit.setActiveRanking(activeRanking);
        for (Flippers.Flipper f : Flippers.Flipper.values()) {
            if (Flippers.on(f) != flipperOn[f.ordinal()]) BaseProfit.setFlipperOn(f, flipperOn[f.ordinal()]);
        }
        if (!modeAvailable(listMode)) listMode = nextListMode(); // the chosen list was turned off
        BaseProfit.setListMode(listMode);
        saveConfig();
    }

    @Override
    public void onClose() {
        applyAll();
        VersionCompat.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int left = x0() - 10, right = x0() + 2 * COL_W + GAP + 10;
        g.fill(left, 8, right, height - 8, 0xC0101018);
        String title = advanced ? "Advanced Flip Settings" : "Trading Dashboard";
        g.text(font, title, width / 2 - font.width(title) / 2, 14, 0xFFFFAA00, true);
        super.extractRenderState(g, mouseX, mouseY, delta);
        for (int i = 0; i < labels.size(); i++) {
            boolean header = labels.get(i)[0].equals("WHAT IT SHOWS") || labels.get(i)[0].equals("HOW IT LOOKS")
                    || labels.get(i)[0].startsWith("FLIPPERS");
            g.text(font, labels.get(i)[0], labelPos.get(i)[0], labelPos.get(i)[1],
                    header ? 0xFF55FFFF : 0xFFBBBBBB, true);
        }
        drawProfitPanel(g, left, right);
    }

    /** The flip tracker's profit summary in a panel beside the settings (right side if there's room, else left). */
    private void drawProfitPanel(GuiGraphicsExtractor g, int settingsLeft, int settingsRight) {
        int w = 148;
        int px = settingsRight + 6;
        if (px + w > width - 4 && settingsLeft - 6 - w >= 4) px = settingsLeft - 6 - w;
        if (px < 4 || px + w > width - 4) return;
        List<String> lines = FlipTracker.summaryLines();
        int h = 12 + lines.size() * 11;
        g.fill(px, 8, px + w, 8 + h, 0xC0101018);
        int y = 14;
        for (String line : lines) {
            String text = line;
            if (font.width(text) > w - 10) {
                while (text.length() > 4 && font.width(text + "..") > w - 10) text = text.substring(0, text.length() - 1);
                text += "..";
            }
            g.text(font, text, px + 5, y, 0xFFFFFFFF, true);
            y += 11;
        }
    }
}
