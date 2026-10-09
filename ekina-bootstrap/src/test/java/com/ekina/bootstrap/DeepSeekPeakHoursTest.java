package com.ekina.bootstrap;

import com.ekina.infrastructure.agent.llm.DeepSeekPeakHours;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekPeakHoursTest {

    private static Instant utc(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC);
    }

    @Test
    void weekdayPeakWindows() {
        // Tuesday 2026-09-08
        assertTrue(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 1, 0)));
        assertTrue(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 3, 59)));
        assertFalse(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 4, 0)));
        assertFalse(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 5, 59)));
        assertTrue(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 6, 0)));
        assertTrue(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 9, 59)));
        assertFalse(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 10, 0)));
        assertFalse(DeepSeekPeakHours.isPeak(utc(2026, 9, 8, 0, 59)));
    }

    @Test
    void weekendAlwaysOffPeak() {
        assertFalse(DeepSeekPeakHours.isPeak(utc(2026, 9, 12, 2, 0))); // Saturday
        assertFalse(DeepSeekPeakHours.isPeak(utc(2026, 9, 13, 8, 0))); // Sunday
    }
}
