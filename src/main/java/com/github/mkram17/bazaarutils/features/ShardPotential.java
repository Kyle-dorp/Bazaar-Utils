package com.github.mkram17.bazaarutils.features;

import com.google.gson.JsonObject;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Data for the potential-fusions panel in the Fusion Box: the shards actually in your Hunting Box (SkyHanni keeps the counts
 * per profile), planned by {@link FusionPotential} on a background thread. The render thread only reads SkyHanni's counts
 * (at most once a second) and the last finished plan; a new plan is computed when your shards or the Bazaar prices change,
 * or every 30 seconds.
 */
final class ShardPotential {
    enum Source { OK, NO_SKYHANNI, NOT_SEEN_YET }

    private record Key(Map<String, Integer> held, int pricesId) { }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BazaarFlip-shard-potential");
        t.setDaemon(true);
        return t;
    });
    private static final CachedCompute<Key, FusionPotential.Plan> plan =
            new CachedCompute<>(ShardPotential::compute, worker, System::currentTimeMillis, 30_000, FusionPotential.EMPTY);

    private static volatile Source source = Source.NOT_SEEN_YET;
    private static Map<String, Integer> held = Map.of();
    private static long heldAt;
    private static Method profileSpecific, attributeShards, amountInBox;
    private static Object storage;
    private static boolean unavailable;

    private ShardPotential() { }

    /** The latest plan for the shards in your Hunting Box. Render thread only. */
    static FusionPotential.Plan get() {
        Map<String, Integer> h = heldShards();
        JsonObject products = BazaarSnapshot.cachedProducts();
        if (h.isEmpty() || products == null) return FusionPotential.EMPTY;
        return plan.get(new Key(h, System.identityHashCode(products)));
    }

    static Source source() {
        return source;
    }

    /** True until the Bazaar prices have been fetched once (the fusion list fetches them when the Fusion Box opens). */
    static boolean pricesLoading() {
        return BazaarSnapshot.cachedProducts() == null;
    }

    private static FusionPotential.Plan compute(Key k) {
        return FusionPotential.plan(k.held(), FusionCalc.recipesUsing(k.held().keySet()),
                FusionCalc.cachedPrices(BaseProfit.getTaxPercent() / 100.0));
    }

    /**
     * SkyHanni's Hunting Box counts for the profile you're on (shard id -> amount, only shards you have), read by reflection
     * since SkyHanni isn't a compile dependency. Re-read at most once a second; on the client thread, where SkyHanni updates them.
     */
    private static Map<String, Integer> heldShards() {
        long now = System.currentTimeMillis();
        if (now - heldAt < 1000) return held;
        heldAt = now;
        if (unavailable) return held;
        try {
            if (profileSpecific == null) {
                Class<?> data = Class.forName("at.hannibal2.skyhanni.data.ProfileStorageData");
                storage = data.getField("INSTANCE").get(null);
                profileSpecific = data.getMethod("getProfileSpecific");
            }
            Object profile = profileSpecific.invoke(storage);
            if (profile == null) {
                source = Source.NOT_SEEN_YET;
                return held = Map.of();
            }
            if (attributeShards == null) attributeShards = profile.getClass().getMethod("getAttributeShards");
            Map<?, ?> shards = (Map<?, ?>) attributeShards.invoke(profile);
            Map<String, Integer> out = new HashMap<>();
            if (shards != null) {
                for (var e : shards.entrySet()) {
                    if (e.getValue() == null) continue;
                    if (amountInBox == null) amountInBox = e.getValue().getClass().getMethod("getAmountInBox");
                    int n = (Integer) amountInBox.invoke(e.getValue());
                    if (n > 0) out.put(String.valueOf(e.getKey()), n);
                }
            }
            source = out.isEmpty() ? Source.NOT_SEEN_YET : Source.OK;
            return held = Map.copyOf(out);
        } catch (ClassNotFoundException e) {
            unavailable = true;
            source = Source.NO_SKYHANNI;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            unavailable = true; // a SkyHanni version without this data
            source = Source.NO_SKYHANNI;
        }
        return held = Map.of();
    }
}
