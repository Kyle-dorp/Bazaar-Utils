package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
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
    private static final String ITEM = "uwu.ramona.bazaar.BazaarFlipMod$BazaarItem";

    private static final int WIDTH = 250, TITLE_H = 22, ROW_H = 36;

    private static int scroll;
    private static boolean dragging;
    private static int dragOffX, dragOffY;
    // last drawn bounds in screen (GUI) pixels, for scroll hit-testing
    private static volatile int boundsLeft, boundsTop, boundsRight, boundsBottom;

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
    private static List<Object> items() {
        List<Object> out = new ArrayList<>();
        try {
            Class<?> mod = Class.forName(MOD);
            for (String list : new String[]{"topItemsByPercentage", "topItemsByMoney"}) {
                List<Object> l = (List<Object>) mod.getField(list).get(null);
                if (l != null) out.addAll(l);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static double d(Object item, String field) {
        try {
            return Class.forName(ITEM).getField(field).getDouble(item);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String id(Object item) {
        try {
            return (String) Class.forName(ITEM).getField("id").get(item);
        } catch (Exception e) {
            return "?";
        }
    }

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

    private static String coins(double v) {
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

    // ---- rendering (called from the patched HudRenderer.renderOverlay) ----

    public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Font font = mc.font;
        float s = scale();

        List<Object> items = items();
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

        g.text(font, "§l§eBazaar Flips (Profit/hr)", x + 6, y + 7, textColor, true);
        if (!items.isEmpty()) {
            String range = (scroll + 1) + "-" + (scroll + visible) + " / " + items.size();
            g.text(font, "§7" + range, x + WIDTH - 6 - font.width(range), y + 7, textColor, true);
        }

        if (items.isEmpty()) {
            g.text(font, "§7No active flips found.", x + 6, y + TITLE_H + 8, textColor, true);
        } else {
            for (int i = 0; i < visible; i++) {
                int idx = scroll + i;
                if (idx >= items.size()) break;
                Object it = items.get(idx);
                int ry = y + TITLE_H + 3 + i * ROW_H;
                if (i > 0) g.fill(x + 4, ry - 2, x + WIDTH - 4, ry - 1, 0x20FFFFFF);

                String title = "§l#" + (idx + 1) + " " + name(id(it));
                while (font.width(title) > WIDTH - 90 && title.length() > 6) title = title.substring(0, title.length() - 2);
                g.text(font, title, x + 6, ry, textColor, true);

                String perHour = coins(d(it, "totalProfit")) + "/hr";
                g.text(font, "§a§l" + perHour, x + WIDTH - 6 - font.width("§l" + perHour), ry, textColor, true);

                g.text(font, "§7Buy: §r" + coins(d(it, "buyPrice")) + " §7| §aSell: §r" + coins(d(it, "sellPrice")),
                        x + 6, ry + 11, textColor, true);
                g.text(font, "§6Profit: §r" + coins(d(it, "profit")) + " §e(" + String.format("%.1f", d(it, "profitMargin")) + "%)"
                        + " §7Cost: §r" + coins(d(it, "totalBuyCost")), x + 6, ry + 22, textColor, true);
            }
            if (items.size() > visible) {
                g.text(font, "§8scroll for more", x + 6, y + height - 11, textColor, true);
            }
        }
        g.pose().popMatrix();
    }

    @RunOnInit
    public static void registerScroll() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> {
                    boolean over = mouseX >= boundsLeft && mouseX <= boundsRight && mouseY >= boundsTop && mouseY <= boundsBottom;
                    if (!over || boundsRight <= boundsLeft) return true;
                    scroll = Math.max(0, scroll - (int) Math.signum(vertical));
                    return false; // handled: do not also scroll the inventory behind it
                }));
    }
}
