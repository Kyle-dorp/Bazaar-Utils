package com.github.mkram17.bazaarutils.features;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Bazaar product id -> the name shown in game (ENCHANTMENT_ULTIMATE_REITERATE_4 is "Duplex IV"), read from the product list
 * Bazaar Utils already keeps in its config folder. Falls back to a tidied-up version of the id for anything missing.
 */
public class ItemNames {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarutils").resolve("bazaar-resources.json");
    private static volatile Map<String, String> names;

    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>();
        try {
            if (Files.exists(FILE)) {
                JsonObject root = new Gson().fromJson(Files.readString(FILE), JsonObject.class);
                // newer files are a flat id -> name map; older ones nest it under "bazaarConversions"
                JsonObject source = root.has("bazaarConversions") && root.get("bazaarConversions").isJsonObject()
                        ? root.getAsJsonObject("bazaarConversions") : root;
                for (var e : source.entrySet()) {
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) map.put(e.getKey(), v.getAsString());
                }
            }
        } catch (Exception ignored) { }
        return map;
    }

    public static String of(String id) {
        if (names == null) names = load();
        String n = names.get(id);
        return n != null ? n : fallback(id);
    }

    static String fallback(String id) {
        // ENCHANTMENT_SHARPNESS_6 -> "Sharpness VI", ENCHANTMENT_ULTIMATE_JERRY_1 -> "Ultimate Jerry I", others just title-cased
        String s = id;
        boolean enchant = s.startsWith("ENCHANTMENT_");
        if (enchant) s = s.substring("ENCHANTMENT_".length());
        String tier = "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*)_(\\d+)$").matcher(s);
        if (enchant && m.matches()) {
            s = m.group(1);
            tier = " " + roman(Integer.parseInt(m.group(2)));
        }
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb + tier;
    }

    public static String roman(int n) {
        if (n <= 0 || n >= 40) return String.valueOf(n);
        int[] vals = {10, 9, 5, 4, 1};
        String[] syms = {"X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            while (n >= vals[i]) {
                sb.append(syms[i]);
                n -= vals[i];
            }
        }
        return sb.toString();
    }
}
