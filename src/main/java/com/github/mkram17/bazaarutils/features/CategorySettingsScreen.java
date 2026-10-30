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
 * One category's own options, opened by right clicking its button on the Trading Dashboard: whether it's on, the
 * settings only that category uses, and a shortcut to show its list. Forge has its own {@link ForgeSettingsScreen}.
 */
public class CategorySettingsScreen extends Screen {
    private static final int W = 200, ROW_H = 28;

    private final Screen parent;
    private final Flippers.Flipper category;
    private final List<Runnable> savers = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<int[]> labelPos = new ArrayList<>();
    private boolean on, showList;
    private int bottom;

    public CategorySettingsScreen(Screen parent, Flippers.Flipper category) {
        super(Component.literal(category.label + " Settings"));
        this.parent = parent;
        this.category = category;
    }

    /** The flip-list mode that shows this category's rows, or -1 if it has no list of its own. */
    static int listMode(Flippers.Flipper f) {
        return switch (f) {
            case FLIPS, BOOKS -> 0;
            case DIPS -> 1;
            case FUSIONS -> 2;
            case FORGE -> 3;
            default -> -1;
        };
    }

    private int x0() {
        return width / 2 - W / 2;
    }

    private void label(int x, int y, String text) {
        labels.add(text);
        labelPos.add(new int[]{x, y});
    }

    private void number(int y, String label, double current, java.util.function.DoubleConsumer setter) {
        label(x0(), y - 10, label);
        EditBox b = new EditBox(font, x0(), y, W, 16, Component.literal(label));
        b.setValue(BaseProfit.format(current));
        addRenderableWidget(b);
        savers.add(() -> {
            try {
                setter.accept(Double.parseDouble(b.getValue().trim().replace(",", "")));
            } catch (NumberFormatException ignored) { }
        });
    }

    private Component onLabel() {
        return Component.literal(category.label + ": " + (on ? "§aON" : "§cOFF"));
    }

    private Component showLabel() {
        return Component.literal(showList ? "§aShowing this list" : "Show this list now");
    }

    /** Splits {@code text} into lines no wider than {@code max} pixels, breaking at spaces. */
    private List<String> wrap(String text, int max) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.width(next) > max) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    @Override
    protected void init() {
        savers.clear();
        labels.clear();
        labelPos.clear();
        on = Flippers.on(category);
        int mode = listMode(category);
        showList = mode >= 0 && BaseProfit.getListMode() == mode;
        int x = x0(), y = 26;
        for (String line : wrap(category.description, W)) {
            label(x, y, "§7" + line);
            y += 10;
        }
        y += 6;

        addRenderableWidget(Button.builder(onLabel(), b -> {
            on = !on;
            b.setMessage(onLabel());
        }).bounds(x, y, W, 20).build());
        y += ROW_H + 8;

        switch (category) {
            case DIPS -> {
                number(y, "Min dip vs 7-day average (%)", BaseProfit.getMinDipPercent(), BaseProfit::setMinDipPercent);
                y += ROW_H;
            }
            case FUSIONS, POTENTIAL -> {
                number(y, "Min fusion profit (0 = shared min)", BaseProfit.getFusionMin(), BaseProfit::setFusionMin);
                y += ROW_H;
            }
            default -> {
                label(x, y - 6, "§8No options of its own; the shared");
                label(x, y + 4, "§8settings on the dashboard apply.");
                y += ROW_H;
            }
        }

        if (mode >= 0) {
            addRenderableWidget(Button.builder(showLabel(), b -> {
                showList = !showList;
                b.setMessage(showLabel());
            }).bounds(x, y - 6, W, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("Switch the flip list to this category when you go back."))).build());
            y += ROW_H;
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(width / 2 - 70, y, 140, 20).build());
        bottom = y + 28;
    }

    @Override
    public void onClose() {
        for (Runnable r : savers) r.run();
        if (Flippers.on(category) != on) BaseProfit.setFlipperOn(category, on);
        int mode = listMode(category);
        if (on && showList && mode >= 0) BaseProfit.setListMode(mode);
        VersionCompat.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(x0() - 10, 8, x0() + W + 10, bottom, 0xC0101018);
        String title = category.label + " Settings";
        g.text(font, title, width / 2 - font.width(title) / 2, 14, 0xFFFFAA00, true);
        super.extractRenderState(g, mouseX, mouseY, delta);
        for (int i = 0; i < labels.size(); i++) {
            g.text(font, labels.get(i), labelPos.get(i)[0], labelPos.get(i)[1], 0xFFBBBBBB, true);
        }
    }
}
