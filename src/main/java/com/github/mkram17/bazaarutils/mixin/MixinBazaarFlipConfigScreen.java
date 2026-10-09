package com.github.mkram17.bazaarutils.mixin;

import com.github.mkram17.bazaarutils.features.BaseProfit;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds "Min Profit/item" and "Min items/hr" boxes under the Buy/Sell title buttons of BazaarFlip's config screen. */
@IfModLoaded("bazaarflip")
@Mixin(targets = "uwu.ramona.bazaar.config.BazaarConfigScreen")
public abstract class MixinBazaarFlipConfigScreen extends Screen {
    @Shadow private EditBox webhookUrlField;

    @Unique private EditBox bazaarutils$baseProfitField;
    @Unique private EditBox bazaarutils$minRateField;

    protected MixinBazaarFlipConfigScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void bazaarutils$addFields(CallbackInfo ci) {
        int x = webhookUrlField.getX();
        int y = webhookUrlField.getY() + 60;
        bazaarutils$baseProfitField = new EditBox(this.font, x, y, 88, 16, Component.literal("Min Profit/item"));
        bazaarutils$baseProfitField.setValue(BaseProfit.format(BaseProfit.getCoins()));
        this.addRenderableWidget(bazaarutils$baseProfitField);

        bazaarutils$minRateField = new EditBox(this.font, x + 92, y, 88, 16, Component.literal("Min items/hr"));
        bazaarutils$minRateField.setValue(BaseProfit.format(BaseProfit.getMinItemsPerHour()));
        this.addRenderableWidget(bazaarutils$minRateField);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void bazaarutils$drawFields(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (bazaarutils$baseProfitField == null) return;
        g.text(this.font, "Min Profit/item", bazaarutils$baseProfitField.getX(), bazaarutils$baseProfitField.getY() - 10, -5197648, true);
        g.text(this.font, "Min items/hr", bazaarutils$minRateField.getX(), bazaarutils$minRateField.getY() - 10, -5197648, true);
        bazaarutils$baseProfitField.extractRenderState(g, mouseX, mouseY, delta);
        bazaarutils$minRateField.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void bazaarutils$saveFields(CallbackInfo ci) {
        if (bazaarutils$baseProfitField == null) return;
        try {
            BaseProfit.setCoins(Double.parseDouble(bazaarutils$baseProfitField.getValue().trim()));
        } catch (NumberFormatException ignored) { }
        try {
            BaseProfit.setMinItemsPerHour(Double.parseDouble(bazaarutils$minRateField.getValue().trim()));
        } catch (NumberFormatException ignored) { }
    }
}
