package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Scrollable tooltips: while a tooltip is showing, the mouse wheel moves it up/down and shift + wheel moves it sideways, so
 * tooltips taller or wider than the screen can be read. The offset resets when you hover a different item.
 * Drawing is in MixinTooltipScroll.
 */
public class TooltipScroll {
    private static final int STEP = 12;
    private static volatile int offsetX, offsetY;
    private static volatile long lastDrawn;
    private static volatile int lastTooltip;

    public static int offsetX() {
        return offsetX;
    }

    public static int offsetY() {
        return offsetY;
    }

    /** Called for every tooltip drawn; a different tooltip than last frame starts back at no offset. */
    public static void onTooltipDrawn(List<ClientTooltipComponent> lines) {
        long now = System.currentTimeMillis();
        // tooltip lists are rebuilt every frame, so compare by size and how recently one was drawn instead of identity
        int key = lines.size();
        if (key != lastTooltip || now - lastDrawn > 250) {
            offsetX = 0;
            offsetY = 0;
        }
        lastTooltip = key;
        lastDrawn = now;
    }

    @RunOnInit
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> {
                    if (System.currentTimeMillis() - lastDrawn > 100) return true; // no tooltip on screen: normal scrolling
                    long window = Minecraft.getInstance().getWindow().handle();
                    boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                    int dir = (int) Math.signum(vertical != 0 ? vertical : horizontal);
                    if (shift || (vertical == 0 && horizontal != 0)) offsetX += dir * STEP;
                    else offsetY += dir * STEP;
                    return false; // the tooltip used the scroll
                }));
    }
}
