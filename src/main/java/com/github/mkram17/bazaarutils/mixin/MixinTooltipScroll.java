package com.github.mkram17.bazaarutils.mixin;

import com.github.mkram17.bazaarutils.features.TooltipScroll;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Every tooltip is drawn through GuiGraphicsExtractor.tooltip(...), which asks a positioner where to put it (and that
 * positioner keeps it on screen, which is why long tooltips get cut off). This wraps the positioner so the tooltip is moved
 * by the scroll offset after it has been placed, letting you scroll it past the screen edge to read the rest.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class MixinTooltipScroll {

    @Inject(method = "tooltip", at = @At("HEAD"))
    private void bazaarutils$trackTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y,
                                         ClientTooltipPositioner positioner, Identifier style, CallbackInfo ci) {
        TooltipScroll.onTooltipDrawn(lines);
    }

    @ModifyVariable(method = "tooltip", at = @At("HEAD"), argsOnly = true)
    private ClientTooltipPositioner bazaarutils$scrollPositioner(ClientTooltipPositioner original) {
        if (TooltipScroll.offsetX() == 0 && TooltipScroll.offsetY() == 0) return original;
        return (screenWidth, screenHeight, mouseX, mouseY, width, height) -> {
            Vector2ic placed = original.positionTooltip(screenWidth, screenHeight, mouseX, mouseY, width, height);
            return new Vector2i(placed.x() + TooltipScroll.offsetX(), placed.y() + TooltipScroll.offsetY());
        };
    }
}
