package io.github.geethapriya1625.ratelimit;

/**
 * @param allowed      whether the request may proceed
 * @param limit        bucket capacity
 * @param remaining    whole tokens left after this request
 * @param retryAfterMs wait before the same request would be allowed (0 when allowed)
 * @param resetMs      wait until the bucket is full again
 */
public record RateLimitDecision(boolean allowed, long limit, long remaining, long retryAfterMs, long resetMs) {}
