package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonObject;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

/**
 * Every list the mod calculates, as {@link Opportunity}s for {@link OpportunityAdvisor}: BazaarFlip's flips (or dips), book
 * crafts, shard fusions and forge routes. The conversions are pure and tested; {@link #gather} reads each list's current rows
 * in game and starts a background refresh of any that are stale, never waiting on the network.
 */
public final class OpportunityFeed {
    /** One gather: every option, and how many each list gave (0 can mean that list is still loading). */
    public record Options(List<Opportunity> all, int flips, int books, int fusions, int forges) { }

    private OpportunityFeed() { }

    /** BazaarFlip rows; an item in both of its top lists counts once. dips: the rows are dips (cheap vs 7-day normal on purpose). */
    static List<Opportunity> flips(List<BazaarFlipRows.Row> rows, boolean dips, ToDoubleFunction<String> typicalBuy,
                                   UnaryOperator<String> nameOf, double orderCheckHours, MarketShare shares) {
        List<Opportunity> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BazaarFlipRows.Row r : rows) {
            if (r.id() == null || !seen.add(r.id())) continue;
            Opportunity o = Opportunity.flip(r.id(), nameOf.apply(r.id()), r.buyPrice(), r.sellPrice(), r.profit(), r.totalProfit(),
                    typicalBuy.applyAsDouble(r.id()), dips, orderCheckHours, shares);
            if (o != null) out.add(o);
        }
        return out;
    }

    static List<Opportunity> books(List<BookCraft.Craft> crafts, double orderCheckHours, MarketShare shares) {
        List<Opportunity> out = new ArrayList<>();
        for (BookCraft.Craft c : crafts) {
            Opportunity o = Opportunity.book(c, orderCheckHours, shares);
            if (o != null) out.add(o);
        }
        return out;
    }

    static List<Opportunity> fusions(List<FusionCalc.Fusion> fusions, UnaryOperator<String> nameOf, double orderCheckHours,
                                     MarketShare shares) {
        List<Opportunity> out = new ArrayList<>();
        for (FusionCalc.Fusion f : fusions) {
            Opportunity o = Opportunity.fusion(f, nameOf, orderCheckHours, shares);
            if (o != null) out.add(o);
        }
        return out;
    }

    /** supplyPerHour: how fast a bought part comes in at full volume (Bazaar insta-sells, or AH sales), NaN if unknown. */
    static List<Opportunity> forges(List<ForgeCalc.Row> rows, UnaryOperator<String> nameOf, double forgeCheckHours, MarketShare shares,
                                    ToDoubleFunction<String> supplyPerHour) {
        List<Opportunity> out = new ArrayList<>();
        for (ForgeCalc.Row r : rows) {
            Opportunity o = Opportunity.forge(r, nameOf.apply(r.route().id()), forgeCheckHours, shares, supplyPerHour);
            if (o != null) out.add(o);
        }
        return out;
    }

    // ---- in game ----

    /** Every list's current rows as options, with your market share from the last Bazaar snapshot's order book. */
    public static Options gather(AdvisorConfig cfg) {
        BookCraft.refreshIfStale();
        FusionCalc.refreshIfStale(); // also keeps the shared Bazaar snapshot (the order book) fresh
        ForgeCalc.refreshIfStale();
        JsonObject products = BazaarSnapshot.cachedProducts();
        MarketShare shares = cfg.shares(products);
        double check = cfg.orderCheckHours();
        List<Opportunity> flips = flips(BazaarFlipRows.get(), BaseProfit.isDipMode(), BaseProfit::typicalBuy, ItemNames::of, check, shares);
        List<Opportunity> books = books(BookCraft.get(), check, shares);
        List<Opportunity> fusions = fusions(FusionCalc.get(), ItemNames::of, check, shares);
        List<Opportunity> forges = forges(ForgeCalc.get(), ForgeCalc::name, BaseProfit.getForgeCheckHours(), shares,
                id -> products == null ? Double.NaN : products.has(id) ? BazaarSnapshot.fillRate(products.getAsJsonObject(id))
                        : ForgeCalc.ahSalesPerHour(id));
        List<Opportunity> all = new ArrayList<>(flips);
        all.addAll(books);
        all.addAll(fusions);
        all.addAll(forges);
        return new Options(all, flips.size(), books.size(), fusions.size(), forges.size());
    }

    /** The plan's limits: the purse as /forgesettings reads it (scoreboard purse when the purse filter is on), your slots, and the advisor's settings. */
    public static OpportunityAdvisor.Settings settings(AdvisorConfig cfg) {
        double purse = ForgeCalc.budget(BaseProfit.isForgePurseFilter(), ForgeCalc.lastPurse());
        return new OpportunityAdvisor.Settings(purse, BaseProfit.getForgeSlots(), cfg.maxActive(), cfg.afk(), cfg.combinesPerHour());
    }
}
