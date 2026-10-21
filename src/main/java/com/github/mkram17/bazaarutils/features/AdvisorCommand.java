package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * /advisor: the opportunity advisor's plan for your purse and forge slots, in chat, and its settings.
 *
 * <pre>
 * /advisor                       the plan: summary lines and each pick
 * /advisor settings              the settings below
 * /advisor checkhours &lt;hours&gt;     how often you tend your Bazaar orders (default 1)
 * /advisor maxactive &lt;items&gt;     Bazaar items you run at once (default 7)
 * /advisor afk                   toggle planning for time away (forges only)
 * /advisor share &lt;min%&gt; &lt;max%&gt;   range of a market's volume you capture (default 5-10%)
 * /advisor combines &lt;per hour&gt;   anvil combines you do in an hour for book crafts (default 150)
 * </pre>
 */
public final class AdvisorCommand {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bazaarutils-advisor.json");
    private static volatile AdvisorConfig config = AdvisorConfig.read(FILE);

    private AdvisorCommand() { }

    public static AdvisorConfig config() {
        return config;
    }

    private static void set(AdvisorConfig c) {
        config = c;
        try {
            c.write(FILE);
        } catch (Exception ignored) { }
        showSettings();
    }

    private static void showSettings() {
        AdvisorConfig c = config;
        PlayerActionUtil.notifyAll("Advisor: orders checked every " + OpportunityAdvisor.hours(c.orderCheckHours()) + ", up to "
                + c.maxActive() + " Bazaar items, " + (c.afk() ? "AFK (forges only)" : "online") + ", market share "
                + OpportunityAdvisor.percent(c.shareMinPercent() / 100) + "-" + OpportunityAdvisor.percent(c.shareMaxPercent() / 100)
                + " (from the order book), " + OpportunityAdvisor.amount(c.combinesPerHour()) + " anvil combines/hr.");
    }

    /** Reads every list on the client thread (the purse is on the scoreboard), plans off it, prints back on it. */
    private static void showPlan() {
        if (!Flippers.on(Flippers.Flipper.ADVISOR)) {
            PlayerActionUtil.notifyAll("The advisor is turned off in /bazaarflip (Flippers: Advisor).");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        AdvisorConfig c = config;
        OpportunityFeed.Options options = OpportunityFeed.gather(c);
        OpportunityAdvisor.Settings settings = OpportunityFeed.settings(c);
        Thread t = new Thread(() -> {
            OpportunityAdvisor.Plan plan = OpportunityAdvisor.plan(options.all(), settings);
            mc.execute(() -> print(plan, options));
        }, "BazaarUtils-advisor");
        t.setDaemon(true);
        t.start();
    }

    private static void print(OpportunityAdvisor.Plan plan, OpportunityFeed.Options options) {
        for (String line : plan.summary()) PlayerActionUtil.notifyAll(line);
        for (OpportunityAdvisor.Pick p : plan.forges()) {
            PlayerActionUtil.notifyAll(" " + p.slots() + "x " + p.opportunity().name() + ": " + OpportunityAdvisor.coins(p.profitPerHour())
                    + "/hr, ties up " + OpportunityAdvisor.coins(p.capital()));
        }
        for (OpportunityAdvisor.Pick p : plan.active()) {
            PlayerActionUtil.notifyAll(" " + p.opportunity().kind().label + " " + p.opportunity().name() + ": "
                    + OpportunityAdvisor.coins(p.profitPerHour()) + "/hr on " + OpportunityAdvisor.coins(p.capital()) + ", "
                    + OpportunityAdvisor.perDay(p.unitsPerHour()) + "/day");
        }
        String counts = "From " + options.flips() + (BaseProfit.isDipMode() ? " dips, " : " flips, ") + options.books()
                + " book crafts, " + options.fusions() + " fusions and " + options.forges() + " forge routes.";
        boolean loading = options.flips() == 0 || options.books() == 0 || options.fusions() == 0 || options.forges() == 0;
        PlayerActionUtil.notifyAll(counts + (loading ? " Lists at 0 may still be loading: run /advisor again shortly." : ""));
    }

    @RunOnInit
    public static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("advisor")
                .executes(ctx -> { showPlan(); return 1; })
                .then(ClientCommands.literal("settings").executes(ctx -> { showSettings(); return 1; }))
                .then(ClientCommands.literal("checkhours").then(ClientCommands.argument("hours", DoubleArgumentType.doubleArg(0, 48))
                        .executes(ctx -> { set(config.withOrderCheckHours(DoubleArgumentType.getDouble(ctx, "hours"))); return 1; })))
                .then(ClientCommands.literal("maxactive").then(ClientCommands.argument("items", IntegerArgumentType.integer(0, AdvisorConfig.MAX_ACTIVE))
                        .executes(ctx -> { set(config.withMaxActive(IntegerArgumentType.getInteger(ctx, "items"))); return 1; })))
                .then(ClientCommands.literal("afk").executes(ctx -> { set(config.withAfk(!config.afk())); return 1; }))
                .then(ClientCommands.literal("share").then(ClientCommands.argument("min", DoubleArgumentType.doubleArg(0.1, 100))
                        .then(ClientCommands.argument("max", DoubleArgumentType.doubleArg(0.1, 100)).executes(ctx -> {
                            set(config.withShare(DoubleArgumentType.getDouble(ctx, "min"), DoubleArgumentType.getDouble(ctx, "max")));
                            return 1;
                        }))))
                .then(ClientCommands.literal("combines").then(ClientCommands.argument("perHour", DoubleArgumentType.doubleArg(0, 100_000))
                        .executes(ctx -> { set(config.withCombinesPerHour(DoubleArgumentType.getDouble(ctx, "perHour"))); return 1; })))));
    }
}
