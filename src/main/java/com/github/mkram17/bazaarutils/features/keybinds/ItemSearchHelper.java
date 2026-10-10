package com.github.mkram17.bazaarutils.features.keybinds;

import com.github.mkram17.bazaarutils.BazaarUtils;
import com.github.mkram17.bazaarutils.data.BazaarData;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.mixin.AccessorAbstractContainerScreen;
import com.github.mkram17.bazaarutils.utils.GUIUtils;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.github.mkram17.bazaarutils.utils.VersionCompat;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Hotkey that runs /bz or /ah for the item under the cursor (or the held item when no screen is open).
 */
public class ItemSearchHelper {
    public static final KeyMapping.Category CATEGORY = StashHelper.CATEGORY;
    private static final KeyMapping searchKey = new KeyMapping("Search Item (Bazaar or Auction House)", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, CATEGORY);

    @RunOnInit
    public static void initializeKeybinds() {
        KeyMappingHelper.registerKeyMapping(searchKey);

        // No screen open: use the held item.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (searchKey.consumeClick()) {
                if (VersionCompat.getScreen(client) == null && client.player != null) search(client.player.getMainHandItem());
            }
        });

        // Screen open: use the hovered slot.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, keyEvent) -> {
                if (!searchKey.matches(keyEvent)) return;
                Slot slot = ((AccessorAbstractContainerScreen) containerScreen).getHoveredSlot();
                if (slot == null || !slot.hasItem()) return;
                if (search(slot.getItem())) GUIUtils.closeHandledScreen();
            });
        });
    }

    /** Runs /bz if the item is a Bazaar product, otherwise /ah. */
    private static boolean search(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CompoundTag tag = customData(stack);

        String name = enchantBookName(tag);
        if (name == null) name = cleanName(stack.getHoverName().getString(), tag.getString("modifier").orElse(""));
        if (name.isBlank()) return false;

        String command = BazaarData.findProductIdOptional(name).isPresent() ? "bz" : "ahs";
        PlayerActionUtil.runCommand(command + " " + name);
        return true;
    }

    private static CompoundTag customData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** "Sharpness VI" for a book holding {sharpness: 6}; null if the stack isn't a single-enchant book. */
    private static String enchantBookName(CompoundTag tag) {
        CompoundTag enchants = tag.getCompound("enchantments").orElse(null);
        if (enchants == null || !"ENCHANTED_BOOK".equals(tag.getString("id").orElse(""))) return null;
        for (String key : enchants.keySet()) {
            int level = enchants.getInt(key).orElse(1);
            String title = titleCase(key);
            String withLevel = title + " " + roman(level);
            // Bazaar lists some ultimates without the "Ultimate" prefix (e.g. "Last Stand III")
            String stripped = title.startsWith("Ultimate ") ? title.substring(9) + " " + roman(level) : null;
            if (BazaarData.findProductIdOptional(withLevel).isPresent()) return withLevel;
            if (stripped != null && BazaarData.findProductIdOptional(stripped).isPresent()) return stripped;
            return stripped != null ? stripped : withLevel;
        }
        return null;
    }

    private static String titleCase(String key) {
        StringBuilder sb = new StringBuilder();
        for (String word : key.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String roman(int n) {
        int[] vals = {10, 9, 5, 4, 1};
        String[] syms = {"X", "IX", "V", "IV", "I"};
        if (n <= 0 || n >= 40) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i]; }
        }
        return sb.toString();
    }

    /** Removes formatting codes, reforge, pet levels, upgrade stars and similar decoration from a Skyblock item name. */
    static String cleanName(String raw, String reforge) {
        String name = raw.replaceAll("§.", "")
                .replaceAll("\\[Lvl \\d+\\]\\s*", "")
                .replaceAll("[✪➊➋➌➍➎✦⚚♲]", "")
                .replaceAll("\\s+x\\d+$", "")
                .replaceAll("\\s+", " ")
                .trim()
                // Bazaar order screens prefix items with "BUY " / "SELL "
                .replaceFirst("^(BUY|SELL)\\s+", "");
        if (!reforge.isEmpty()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            String prefix = reforge.toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " ";
            if (lower.startsWith(prefix)) name = name.substring(prefix.length());
        }
        return name;
    }
}
