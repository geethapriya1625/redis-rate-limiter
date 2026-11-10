package io.github.geethapriya1625.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class RedisTokenBucketRateLimiterTest extends RedisTestBase {

    @Autowired
    RateLimiter limiter;

    @Autowired
    StringRedisTemplate redis;

    private static String key() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    void allowsABurstUpToCapacityThenLimits() {
        String key = key();
        List<Boolean> outcomes = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            outcomes.add(limiter.tryAcquire(key, 5, 10, 1).allowed());
        }
        assertThat(outcomes).containsExactly(true, true, true, true, true, false, false);

        RateLimitDecision denied = limiter.tryAcquire(key, 5, 10, 1);
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMs()).isBetween(1L, 100L);  // 10 tokens/s -> next token within 100 ms
    }

    @Test
    void refillsAtTheConfiguredRate() throws Exception {
        String key = key();
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(key, 5, 2, 1);
        }
        assertThat(limiter.tryAcquire(key, 5, 2, 1).allowed()).isFalse();

        // 2 tokens/s: after 700 ms at least 1.4 tokens are back. The upper bound leaves
        // headroom for slow CI machines instead of asserting an exact count.
        Thread.sleep(700);
        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire(key, 5, 2, 1).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isBetween(1, 2);
    }

    @Test
    void costCanSpendSeveralTokensAndOversizedRequestsAreRefused() {
        String key = key();
        assertThat(limiter.tryAcquire(key, 10, 1, 7).remaining()).isEqualTo(3);
        assertThat(limiter.tryAcquire(key, 10, 1, 4).allowed()).isFalse();
        assertThat(limiter.tryAcquire(key, 10, 1, 11).allowed()).isFalse();
    }

    @Test
    void bucketsAreIndependentAndExpireWhenIdle() {
        String a = key();
        String b = key();
        limiter.tryAcquire(a, 1, 1, 1);
        assertThat(limiter.tryAcquire(a, 1, 1, 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire(b, 1, 1, 1).allowed()).isTrue();

        Long ttl = redis.getExpire(a, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(2_100L);
    }

    @Test
    void concurrentCallersNeverOverspendTheBucket() throws Exception {
        String key = key();
        int threads = 32;
        int callsEach = 20;
        long capacity = 50;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int allowed = 0;
                    for (int i = 0; i < callsEach; i++) {
                        // near-zero refill so the race is purely about the starting tokens
                        if (limiter.tryAcquire(key, capacity, 0.001, 1).allowed()) {
                            allowed++;
                        }
                    }
                    return allowed;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get();
            }
            assertThat(total).isEqualTo(capacity);
        } finally {
            pool.shutdownNow();
        }
    }
}
