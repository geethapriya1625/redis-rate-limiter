package io.github.geethapriya1625.ratelimit;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * One round trip per decision. The whole read-refill-take-write cycle runs inside Redis as a
 * single Lua script, so concurrent requests on any number of app instances can never both
 * spend the last token. Spring sends EVALSHA and falls back to EVAL if Redis lost the script.
 */
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitDecision tryAcquire(String key, long capacity, double refillPerSecond, int permits) {
        if (permits > capacity) {
            return new RateLimitDecision(false, capacity, 0, Long.MAX_VALUE, 0);
        }
        @SuppressWarnings("unchecked")
        List<Long> r = redis.execute(SCRIPT, List.of(key),
                Long.toString(capacity), Double.toString(refillPerSecond), Integer.toString(permits));
        if (r == null || r.size() != 4) {
            throw new IllegalStateException("unexpected rate limit script reply: " + r);
        }
        return new RateLimitDecision(r.get(0) == 1L, capacity, r.get(1), r.get(2), r.get(3));
    }
}
