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

/** Adds a "Base Profit" box under the Buy/Sell title buttons of BazaarFlip's config screen. */
@IfModLoaded("bazaarflip")
@Mixin(targets = "uwu.ramona.bazaar.config.BazaarConfigScreen")
public abstract class MixinBazaarFlipConfigScreen extends Screen {
    @Shadow private EditBox webhookUrlField;

    @Unique private EditBox bazaarutils$baseProfitField;

    protected MixinBazaarFlipConfigScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void bazaarutils$addBaseProfit(CallbackInfo ci) {
        bazaarutils$baseProfitField = new EditBox(this.font, webhookUrlField.getX(), webhookUrlField.getY() + 60,
                180, 16, Component.literal("Base Profit"));
        bazaarutils$baseProfitField.setValue(BaseProfit.format(BaseProfit.getCoins()));
        this.addRenderableWidget(bazaarutils$baseProfitField);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void bazaarutils$drawBaseProfit(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (bazaarutils$baseProfitField == null) return;
        g.text(this.font, "Base Profit (coins/item, 0 = off)", bazaarutils$baseProfitField.getX(),
                bazaarutils$baseProfitField.getY() - 10, -5197648, true);
        bazaarutils$baseProfitField.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void bazaarutils$saveBaseProfit(CallbackInfo ci) {
        if (bazaarutils$baseProfitField == null) return;
        try {
            BaseProfit.setCoins(Double.parseDouble(bazaarutils$baseProfitField.getValue().trim()));
        } catch (NumberFormatException ignored) { }
    }
}
