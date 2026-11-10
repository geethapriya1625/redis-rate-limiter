package io.github.geethapriya1625.ratelimit;

public interface RateLimiter {

    /**
     * Atomically takes {@code permits} tokens from the bucket at {@code key} if enough are
     * available. Implementations may throw if the backing store is unreachable.
     */
    RateLimitDecision tryAcquire(String key, long capacity, double refillPerSecond, int permits);
}
