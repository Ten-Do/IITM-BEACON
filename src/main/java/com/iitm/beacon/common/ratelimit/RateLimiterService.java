package com.iitm.beacon.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Generic, in-memory, per-key rate limiter backed by Bucket4j buckets cached
 * in a Caffeine cache. Used for both admin and visitor OTP request
 * throttling (per-email and per-IP), keyed by an arbitrary limiter name so
 * unrelated limiters never share state even for the same key value.
 */
@Component
public class RateLimiterService {

    private final ClockTimeMeter timeMeter;
    private final Cache<String, Bucket> buckets;

    public RateLimiterService(Clock clock) {
        this.timeMeter = new ClockTimeMeter(clock);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    public boolean tryConsume(String limiterName, String key, int capacity, Duration window) {
        String cacheKey = limiterName + "|" + key;
        Bucket bucket = buckets.get(cacheKey, k -> Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
                .build());
        return bucket.tryConsume(1);
    }
}
