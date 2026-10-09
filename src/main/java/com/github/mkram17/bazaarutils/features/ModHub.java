package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.config.BUConfig;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.github.mkram17.bazaarutils.utils.VersionCompat;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * /modhub: one screen that groups the features of Skyblocker, SkyHanni, Bazaar Utils and BazaarFlip by area, flags
 * features that look duplicated across mods, and has a button to open each mod's own settings. It reads the mods' config
 * files to build the lists, so it never changes any setting itself.
 */
public class ModHub extends Screen {
    private record Feature(String name, int options, boolean duplicate) { }

    private static final String SKYBLOCKER = "Skyblocker";
    private static final String SKYHANNI = "SkyHanni";

    /** area -> {Skyblocker categories, SkyHanni categories} */
    private static final Map<String, String[][]> AREAS = new LinkedHashMap<>();

    static {
        AREAS.put("Dungeons", new String[][]{{"dungeons"}, {"dungeon"}});
        AREAS.put("Slayers", new String[][]{{"slayers"}, {"slayer"}});
        AREAS.put("Mining", new String[][]{{"mining"}, {"mining"}});
        AREAS.put("Farming & Garden", new String[][]{{"farming"}, {"garden"}});
        AREAS.put("Fishing", new String[][]{{}, {"fishing"}});
        AREAS.put("Foraging", new String[][]{{"foraging"}, {"foraging"}});
        AREAS.put("Hunting", new String[][]{{"hunting"}, {"hunting"}});
        AREAS.put("Crimson Isle", new String[][]{{"crimsonIsle"}, {"crimsonIsle"}});
        AREAS.put("Rift & Other Places", new String[][]{{"otherLocations"}, {"rift"}});
        AREAS.put("Combat", new String[][]{{}, {"combat"}});
        AREAS.put("Chat", new String[][]{{"chat"}, {"chat"}});
        AREAS.put("Inventory & UI", new String[][]{{"uiAndVisuals", "helpers", "quickNav", "general"}, {"gui", "inventory", "skillProgress", "storage"}});
        AREAS.put("Events", new String[][]{{"eventNotifications"}, {"event"}});
        AREAS.put("Misc", new String[][]{{"misc"}, {"misc"}});
        AREAS.put("Bazaar", new String[][]{{}, {}});
    }

    private static final List<String> BAZAAR_UTILS = List.of(
            "Outdated / outbid order alerts", "Order status highlight", "Item bookmarks", "Custom order amounts",
            "Restrict instant sell", "Price charts", "Stash pickup key (V)", "Item search key (T)",
            "Flip tracker (/bazaarflip profit)", "Base profit, sell tax, min items/hr");
    private static final List<String> BAZAAR_FLIP = List.of(
            "Flip list (profit/hr and margin %)", "Volume and spend limits", "Background monitoring + alerts",
            "Discord webhook alerts", "Buy / sell title alerts", "Undercut alerts");

    private final Screen parent;
    private final Map<String, List<Feature>[]> data = new LinkedHashMap<>();
    private String selected = "Dungeons";
    private int scroll;

    public ModHub(Screen parent) {
        super(Component.literal("Mod Hub"));
        this.parent = parent;
        load();
    }

    // ---- data ----

    @SuppressWarnings("unchecked")
    private void load() {
        Path cfg = FabricLoader.getInstance().getConfigDir();
        JsonObject sk = read(cfg.resolve("skyblocker.json"));
        JsonObject sh = read(cfg.resolve("skyhanni").resolve("config.json"));
        for (var e : AREAS.entrySet()) {
            List<Feature>[] pair = new List[]{features(sk, e.getValue()[0]), features(sh, e.getValue()[1])};
            markDuplicates(pair);
            data.put(e.getKey(), pair);
        }
    }

    private static JsonObject read(Path file) {
        try {
            if (Files.exists(file)) return new Gson().fromJson(Files.readString(file), JsonObject.class);
        } catch (Exception ignored) { }
        return new JsonObject();
    }

    private static List<Feature> features(JsonObject root, String[] categories) {
        List<Feature> out = new ArrayList<>();
        for (String cat : categories) {
            JsonElement el = root.get(cat);
            if (el == null || !el.isJsonObject()) continue;
            for (var f : el.getAsJsonObject().entrySet()) {
                int options = f.getValue().isJsonObject() ? f.getValue().getAsJsonObject().size() : 1;
                out.add(new Feature(pretty(f.getKey()), options, false));
            }
        }
        out.sort(Comparator.comparing(Feature::name));
        return out;
    }

    private static String pretty(String key) {
        String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
        return spaced.isEmpty() ? key : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static Set<String> words(String name) {
        Set<String> s = new HashSet<>();
        for (String w : name.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.length() > 2 && !Set.of("the", "enable", "show", "display", "hide").contains(w)) s.add(w);
        }
        return s;
    }

    private static boolean similar(String a, String b) {
        Set<String> wa = words(a), wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) return false;
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);
        return inter.size() / (double) union.size() >= 0.5 || (inter.size() >= 2 && (inter.size() == wa.size() || inter.size() == wb.size()));
    }

    private static void markDuplicates(List<Feature>[] pair) {
        for (int side = 0; side < 2; side++) {
            List<Feature> mine = pair[side], theirs = pair[1 - side];
            for (int i = 0; i < mine.size(); i++) {
                Feature f = mine.get(i);
                for (Feature o : theirs) {
                    if (similar(f.name(), o.name())) {
                        mine.set(i, new Feature(f.name(), f.options(), true));
                        break;
                    }
                }
            }
        }
    }

    // ---- ui ----

    private static final int LEFT = 10, LEFT_W = 130, TOP = 40, ROW = 11;

    @Override
    protected void init() {
        int y = TOP;
        for (String area : AREAS.keySet()) {
            final String a = area;
            Button b = Button.builder(Component.literal((a.equals(selected) ? "> " : "") + a), btn -> {
                selected = a;
                scroll = 0;
                rebuild();
            }).bounds(LEFT, y, LEFT_W, 16).build();
            addRenderableWidget(b);
            y += 18;
        }
        int bx = LEFT + LEFT_W + 14, bw = (width - bx - 10 - 3 * 4) / 4;
        String[] labels = {"Skyblocker", "SkyHanni", "Bazaar Utils", "BazaarFlip"};
        for (int i = 0; i < 4; i++) {
            final int which = i;
            addRenderableWidget(Button.builder(Component.literal(labels[i]), btn -> open(which))
                    .bounds(bx + i * (bw + 4), 14, bw, 18).build());
        }
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void open(int which) {
        Minecraft mc = Minecraft.getInstance();
        if (which == 2) {
            BUConfig.openGUI();
            return;
        }
        String command = switch (which) {
            case 0 -> "skyblocker config";
            case 1 -> "sh";
            default -> "bazaarflip";
        };
        VersionCompat.setScreen(mc, null);
        mc.execute(() -> PlayerActionUtil.runCommand(command));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public void onClose() {
        VersionCompat.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xE0101018);
        g.text(font, "Mod Hub: features by area (scroll for more)", LEFT, 18, 0xFFFFAA00, true);
        super.extractRenderState(g, mouseX, mouseY, delta);

        int px = LEFT + LEFT_W + 14;
        int colW = (width - px - 10 - 8) / 2;
        int top = 56, bottom = height - 28;
        g.text(font, selected, px, 40, 0xFFFFFFFF, true);

        if (selected.equals("Bazaar")) {
            column(g, px, top, bottom, colW, "Bazaar Utils", 0xFF55FFFF, BAZAAR_UTILS.stream().map(s -> new Feature(s, 0, false)).toList());
            column(g, px + colW + 8, top, bottom, colW, "BazaarFlip", 0xFFFFFF55, BAZAAR_FLIP.stream().map(s -> new Feature(s, 0, false)).toList());
        } else {
            List<Feature>[] pair = data.get(selected);
            column(g, px, top, bottom, colW, SKYBLOCKER, 0xFF55FF55, pair[0]);
            column(g, px + colW + 8, top, bottom, colW, SKYHANNI, 0xFFFF9955, pair[1]);
        }
        g.text(font, "Red = looks like the same feature exists in the other mod. Turn one of them off.", LEFT, height - 14, 0xFFAAAAAA, true);
    }

    private void column(GuiGraphicsExtractor g, int x, int top, int bottom, int w, String title, int color, List<Feature> list) {
        g.fill(x, top - 2, x + w, bottom, 0x60000000);
        g.text(font, title + " (" + list.size() + ")", x + 4, top + 2, color, true);
        int y = top + 16 - scroll * ROW;
        if (list.isEmpty()) {
            g.text(font, "(nothing in this area)", x + 4, top + 16, 0xFF888888, true);
            return;
        }
        for (Feature f : list) {
            if (y >= top + 14 && y < bottom - ROW) {
                String text = f.name() + (f.options() > 1 ? " (" + f.options() + ")" : "");
                while (font.width(text) > w - 10 && text.length() > 4) text = text.substring(0, text.length() - 2);
                g.text(font, (f.duplicate() ? "≈ " : "  ") + text, x + 4, y, f.duplicate() ? 0xFFFF5555 : 0xFFDDDDDD, true);
            }
            y += ROW;
        }
    }

    @RunOnInit
    public static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("modhub").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    Screen current = VersionCompat.getScreen(mc);
                    mc.schedule(() -> VersionCompat.setScreen(mc, new ModHub(current)));
                    return 1;
                })));
    }
}
