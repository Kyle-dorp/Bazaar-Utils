package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

final class ActiveTrading {
    private record Input(int mode, double budget, double minutes, double tax, double minMarginPct, double minCoins,
                         Set<Flippers.Flipper> enabled, AdvisorConfig config) { }

    private static double bazaarFlipMinMargin() {
        try {
            return Class.forName("uwu.ramona.bazaar.config.BazaarConfig").getField("backgroundAlertThreshold").getInt(null);
        } catch (Exception e) {
            return 1;
        }
    }
    private record Result(Input input, List<ActiveRanking.Pick> picks) { }
    private static final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BazaarUtils-active-trading");
        t.setDaemon(true);
        return t;
    });
    private static final List<CachedCompute<Input, Result>> caches = java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> new CachedCompute<>(ActiveTrading::compute, worker, System::currentTimeMillis,
                    5000, new Result(null, List.of()))).toList();
    private static final String[] status = {"Active estimates loading", "Active estimates loading", "Active estimates loading"};

    private ActiveTrading() { }

    static List<ActiveRanking.Pick> get(int mode, double purse) {
        double budget = ActiveRanking.budget(purse, BaseProfit.getReservePercent());
        if (budget <= 0) {
            status[mode] = purse == 0 ? "No spendable purse" : "Purse unavailable; recommendations paused";
            return List.of();
        }
        Set<Flippers.Flipper> enabled = Flippers.feeding(mode);
        if (mode == 0) enabled.remove(Flippers.Flipper.FUSIONS);
        Input input = new Input(mode, budget, BaseProfit.getTargetMinutes(), BaseProfit.getTaxPercent(),
                bazaarFlipMinMargin(), BaseProfit.getCoins(), Set.copyOf(enabled),
                AdvisorCommand.config().withOrderCheckHours(0.05));
        CachedCompute<Input, Result> cache = caches.get(mode);
        Result result = cache.get(input);
        if (!input.equals(result.input())) {
            status[mode] = "Updating budget-aware estimates";
            return List.of();
        }
        status[mode] = cache.failure() == null ? "Alternatives, not a portfolio; quote-based estimates" : "Refresh failed; cached estimates";
        return result.picks();
    }

    static String warning(int mode) { return status[mode]; }

    private static Result compute(Input input) {
        JsonObject products;
        try {
            products = BazaarSnapshot.products();
        } catch (Exception e) {
            throw new IllegalStateException("Market refresh failed", e);
        }
        AdvisorConfig cfg = input.config();
        MarketShare shares = cfg.shares(products);
        List<Opportunity> options = new ArrayList<>();
        boolean dips = input.mode() == 1;
        if (input.mode() <= 1 && input.enabled().contains(dips ? Flippers.Flipper.DIPS : Flippers.Flipper.FLIPS)) {
            for (var entry : products.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject product = entry.getValue().getAsJsonObject();
                double bid = BazaarSnapshot.bid(product), ask = BazaarSnapshot.ask(product);
                if (!(bid > 0) || !(ask > 0)) continue;
                bid += 0.1;
                ask -= 0.1;
                double profit = ask * (1 - input.tax() / 100) - bid;
                double rate = Math.min(BazaarSnapshot.fillRate(product), BazaarSnapshot.sellRate(product));
                if (!(bid > 0) || !(profit > 0) || !(rate > 0)) continue;
                // the same gates BazaarFlip's list uses, applied to the broad scan
                if (profit / bid * 100 < Math.max(input.minMarginPct(), input.minCoins() / bid * 100)) continue;
                if (rate < BaseProfit.getMinItemsPerHour()) continue;
                String id = entry.getKey();
                if (!BaseProfit.passesAverage(id, bid, ask)) continue;
                Opportunity o = Opportunity.flip(id, ItemNames.of(id), bid, ask, profit, profit * rate,
                        BaseProfit.typicalBuy(id), dips, cfg.orderCheckHours(), shares);
                if (o != null && !Double.isFinite(BaseProfit.typicalBuy(id))) {
                    o = new Opportunity(o.kind(), o.id(), o.name(), o.unitProfit(), o.unitCost(), o.maxPerHour(),
                            o.holdHours(), o.slotHours(), o.confidence() * 0.6, java.util.List.of("price history unavailable"),
                            o.how(), o.share(), o.labour());
                }
                if (o != null) options.add(o);
            }
        }
        if (input.mode() == 0 && input.enabled().contains(Flippers.Flipper.BOOKS)) {
            BookCraft.refreshIfStale();
            options.addAll(OpportunityFeed.books(BookCraft.get(), cfg.orderCheckHours(), shares));
        }
        if (input.mode() == 2 && input.enabled().contains(Flippers.Flipper.FUSIONS)) {
            FusionCalc.refreshIfStale();
            options.addAll(OpportunityFeed.fusions(FusionCalc.getAll(), ItemNames::of, cfg.orderCheckHours(), shares));
        }
        return new Result(input, ActiveRanking.rank(options, input.budget(), input.minutes(), cfg.combinesPerHour()));
    }
}
