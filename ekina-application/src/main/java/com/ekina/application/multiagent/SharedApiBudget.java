package com.ekina.application.multiagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared API budget for Chief / Coder / Claw using the same OpenCode Zen key.
 * Does not replace provider-side rate limits — only local concurrency + spacing.
 */
@Component
public class SharedApiBudget {

    private final Semaphore slots;
    private final long minIntervalMs;
    private final AtomicLong lastAcquireAt = new AtomicLong(0);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger totalAcquired = new AtomicInteger(0);

    public SharedApiBudget(
            @Value("${ekina.multiagent.api-max-concurrent:2}") int maxConcurrent,
            @Value("${ekina.multiagent.api-min-interval-ms:800}") long minIntervalMs
    ) {
        this.slots = new Semaphore(Math.max(1, maxConcurrent), true);
        this.minIntervalMs = Math.max(0, minIntervalMs);
    }

    public boolean tryAcquire(long timeoutMs) {
        try {
            if (!slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            long now = System.currentTimeMillis();
            long last = lastAcquireAt.get();
            long wait = minIntervalMs - (now - last);
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastAcquireAt.set(System.currentTimeMillis());
            inFlight.incrementAndGet();
            totalAcquired.incrementAndGet();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        inFlight.updateAndGet(v -> Math.max(0, v - 1));
        slots.release();
    }

    public int getInFlight() {
        return inFlight.get();
    }

    public int getTotalAcquired() {
        return totalAcquired.get();
    }

    public int getAvailablePermits() {
        return slots.availablePermits();
    }
}
