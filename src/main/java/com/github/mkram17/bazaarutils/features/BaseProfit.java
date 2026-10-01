package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimum profit (in coins) per item for BazaarFlip's flip list. BazaarFlip is patched to call {@link #minMargin(double)}
 * in place of its fixed % margin floor, so the floor becomes whatever % each item needs to earn this many coins.
 * Set with /flipprofit <coins>; 0 turns it off and BazaarFlip's own % setting applies again.
 */
public class BaseProfit {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarflip-baseprofit.json");
    private static volatile double coins = load();

    private static double load() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                return o.get("minProfitPerItem").getAsDouble();
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("minProfitPerItem", coins);
            o.addProperty("sellTaxPercent", taxPercent);
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception ignored) { }
    }

    private static volatile double taxPercent = loadTax();

    private static double loadTax() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                if (o.has("sellTaxPercent")) return o.get("sellTaxPercent").getAsDouble();
            }
        } catch (Exception ignored) { }
        return 1.25;
    }

    /** Called by the patched BazaarFlip on every sell price: what you actually receive after the Bazaar sell tax. */
    public static double afterTax(double sellPrice) {
        return sellPrice * (1.0 - taxPercent / 100.0);
    }

    private static final double FULL_INVENTORY = 71680.0;
    private static java.lang.reflect.Field maxSpendField;

    /**
     * Called by the patched BazaarFlip in place of its fixed 71680-item quantity. With a base profit set, expensive items
     * are sized to the Max Spend Limit instead of being rejected for not affording a full inventory.
     */
    public static double qty(double buyPrice) {
        if (coins <= 0 || buyPrice <= 0) return FULL_INVENTORY;
        try {
            if (maxSpendField == null) {
                maxSpendField = Class.forName("uwu.ramona.bazaar.config.BazaarConfig").getField("maxSpendLimit");
            }
            double affordable = Math.floor(maxSpendField.getInt(null) / buyPrice);
            if (affordable < 1) return FULL_INVENTORY; // can't afford even one: stays rejected
            return Math.min(FULL_INVENTORY, affordable);
        } catch (Exception e) {
            return FULL_INVENTORY;
        }
    }

    /** Called by the patched BazaarFlip in place of its fixed min-margin setting. */
    public static double minMargin(double buyPrice, double configuredMinMargin) {
        if (coins <= 0 || buyPrice <= 0) return configuredMinMargin;
        return coins / buyPrice * 100.0;
    }

    @RunOnInit
    public static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("fliptax")
                    .executes(ctx -> {
                        PlayerActionUtil.notifyAll("Bazaar sell tax for flips: " + format(taxPercent) + "%. Use /fliptax <percent> to change it.");
                        return 1;
                    })
                    .then(ClientCommands.argument("percent", DoubleArgumentType.doubleArg(0, 100))
                            .executes(ctx -> {
                                taxPercent = DoubleArgumentType.getDouble(ctx, "percent");
                                save();
                                PlayerActionUtil.notifyAll("Bazaar sell tax set to " + format(taxPercent) + "%.");
                                return 1;
                            })));
            dispatcher.register(ClientCommands.literal("flipprofit")
                        .executes(ctx -> {
                            PlayerActionUtil.notifyAll(coins <= 0
                                    ? "Base profit is off. Use /flipprofit <coins per item>."
                                    : "Base profit: " + format(coins) + " coins per item.");
                            return 1;
                        })
                        .then(ClientCommands.argument("coins", DoubleArgumentType.doubleArg(0))
                                .executes(ctx -> {
                                    coins = DoubleArgumentType.getDouble(ctx, "coins");
                                    save();
                                    PlayerActionUtil.notifyAll(coins <= 0
                                            ? "Base profit turned off."
                                            : "Base profit set to " + format(coins) + " coins per item.");
                                    return 1;
                                })));
        });
    }

    public static double getCoins() {
        return coins;
    }

    public static void setCoins(double value) {
        coins = Math.max(0, value);
        save();
    }

    public static String format(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }
}
