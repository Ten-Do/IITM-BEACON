package com.iitm.beacon.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RateLimiterService rateLimiterService = new RateLimiterService(clock);

    @Test
    void allowsExactlyCapacityCallsWithinWindow() {
        Duration window = Duration.ofMinutes(1);

        assertThat(rateLimiterService.tryConsume("limiter", "key", 3, window)).isTrue();
        assertThat(rateLimiterService.tryConsume("limiter", "key", 3, window)).isTrue();
        assertThat(rateLimiterService.tryConsume("limiter", "key", 3, window)).isTrue();
    }

    @Test
    void rejectsTheCapacityPlusOnethCall() {
        Duration window = Duration.ofMinutes(1);

        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiterService.tryConsume("limiter", "key", 3, window)).isTrue();
        }

        assertThat(rateLimiterService.tryConsume("limiter", "key", 3, window)).isFalse();
    }

    @Test
    void replenishesAfterWindowElapses() {
        Duration window = Duration.ofMinutes(1);

        assertThat(rateLimiterService.tryConsume("limiter", "key", 1, window)).isTrue();
        assertThat(rateLimiterService.tryConsume("limiter", "key", 1, window)).isFalse();

        clock.advanceBy(Duration.ofMinutes(1).plusSeconds(1));

        assertThat(rateLimiterService.tryConsume("limiter", "key", 1, window)).isTrue();
    }

    @Test
    void differentKeysUnderSameLimiterDoNotInterfere() {
        Duration window = Duration.ofMinutes(1);

        assertThat(rateLimiterService.tryConsume("limiter", "key-a", 1, window)).isTrue();
        assertThat(rateLimiterService.tryConsume("limiter", "key-b", 1, window)).isTrue();
        assertThat(rateLimiterService.tryConsume("limiter", "key-a", 1, window)).isFalse();
        assertThat(rateLimiterService.tryConsume("limiter", "key-b", 1, window)).isFalse();
    }

    @Test
    void differentLimiterNamesWithSameKeyDoNotInterfere() {
        Duration window = Duration.ofMinutes(1);

        assertThat(rateLimiterService.tryConsume("limiter-a", "key", 1, window)).isTrue();
        assertThat(rateLimiterService.tryConsume("limiter-b", "key", 1, window)).isTrue();
        assertThat(rateLimiterService.tryConsume("limiter-a", "key", 1, window)).isFalse();
        assertThat(rateLimiterService.tryConsume("limiter-b", "key", 1, window)).isFalse();
    }

    @Test
    void concurrentCallsWithCapacityOneAllowExactlyOneSuccess() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (rateLimiterService.tryConsume("concurrent", "shared-key", 1, Duration.ofMinutes(1))) {
                    successes.incrementAndGet();
                }
            });
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);
    }
}
