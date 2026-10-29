package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BazaarSnapshotTest {
    @Test
    void cachedReadsDoNotWaitForTheNetworkRefreshLock() throws Exception {
        CompletableFuture<Void> read;
        synchronized (BazaarSnapshot.class) {
            read = CompletableFuture.runAsync(BazaarSnapshot::cachedProducts);
            assertDoesNotThrow(() -> read.get(1, TimeUnit.SECONDS));
        }
        read.get(1, TimeUnit.SECONDS);
    }
}
