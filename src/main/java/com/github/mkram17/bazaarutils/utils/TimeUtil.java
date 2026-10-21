package com.github.mkram17.bazaarutils.utils;

import java.time.*;

import com.github.mkram17.bazaarutils.BazaarUtils;
import com.github.mkram17.bazaarutils.misc.autoregistration.RunOnInit;
import lombok.Getter;

public class TimeUtil {
    @Getter
    private static ZonedDateTime modInitTime;
    public static final ZonedDateTime LAST_BAZAAR_LIMIT_RESET_TIME = ZonedDateTime.of(LocalDate.now(ZoneOffset.UTC), LocalTime.MIDNIGHT, ZoneOffset.UTC);
    @Getter
    private static ZonedDateTime nextBazaarLimitReset;
    @Getter
    private static long timeToBazaarLimitReset;

    private static void updateTime() {
        modInitTime = ZonedDateTime.now(ZoneOffset.UTC);
        nextBazaarLimitReset = modInitTime.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        timeToBazaarLimitReset = Duration.between(modInitTime, nextBazaarLimitReset).toSeconds();
    }

    @RunOnInit
    public static void startTimer() {
        updateTime();
        // on the mod's daemon scheduler: a javax.swing.Timer fires on AWT's event thread, which is not a daemon thread
        BazaarUtils.BUExecutorService.scheduleAtFixedRate(TimeUtil::updateTime, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
