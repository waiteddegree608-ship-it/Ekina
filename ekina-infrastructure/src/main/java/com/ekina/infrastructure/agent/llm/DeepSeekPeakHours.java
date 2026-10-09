package com.ekina.infrastructure.agent.llm;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Official DeepSeek API peak windows (api-docs.deepseek.com Models &amp; Pricing):
 * Mon–Fri 01:00–04:00 and 06:00–10:00 UTC. All other hours (including weekends) are off-peak
 * at half price. Beijing = UTC+8 → weekdays 09:00–12:00 and 14:00–18:00.
 */
public final class DeepSeekPeakHours {

    private DeepSeekPeakHours() {
    }

    public static boolean isPeakNow() {
        return isPeak(Instant.now());
    }

    public static boolean isPeak(Instant instant) {
        if (instant == null) {
            return false;
        }
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        DayOfWeek day = z.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        int sec = z.toLocalTime().toSecondOfDay();
        int peak1Start = 1 * 3600;
        int peak1End = 4 * 3600;
        int peak2Start = 6 * 3600;
        int peak2End = 10 * 3600;
        return (sec >= peak1Start && sec < peak1End) || (sec >= peak2Start && sec < peak2End);
    }

    public static String describeNow() {
        return describe(Instant.now());
    }

    public static String describe(Instant instant) {
        boolean peak = isPeak(instant);
        ZonedDateTime bj = instant.atZone(ZoneOffset.ofHours(8));
        return (peak ? "peak" : "off-peak")
                + " UTC=" + instant.atZone(ZoneOffset.UTC).toLocalTime().withNano(0)
                + " BJ=" + bj.toLocalTime().withNano(0)
                + " " + bj.getDayOfWeek();
    }
}
