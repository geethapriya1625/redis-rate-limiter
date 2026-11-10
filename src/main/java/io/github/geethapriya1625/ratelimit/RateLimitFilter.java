package io.github.geethapriya1625.ratelimit;

import io.github.geethapriya1625.ratelimit.RateLimitProperties.Policy;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Applies the first matching policy to each request and reports state with the IETF
 * RateLimit header fields (draft-ietf-httpapi-ratelimit-headers): RateLimit-Limit,
 * RateLimit-Remaining, RateLimit-Reset (seconds), plus Retry-After on 429.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private record CompiledPolicy(Policy policy, PathPattern pattern) {}

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final List<CompiledPolicy> policies;
    private final MeterRegistry meters;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties props, MeterRegistry meters) {
        this.limiter = limiter;
        this.props = props;
        this.meters = meters;
        this.policies = props.policies().stream()
                .map(p -> new CompiledPolicy(p, PathPatternParser.defaultInstance.parse(p.pathPattern())))
                .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled() || match(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Policy policy = match(request).policy();
        String key = props.keyPrefix() + policy.name() + ":" + clientId(request);

        RateLimitDecision decision;
        try {
            decision = limiter.tryAcquire(key, policy.capacity(), policy.refillPerSecond(), policy.cost());
        } catch (RuntimeException e) {
            meters.counter("ratelimit.errors", "policy", policy.name()).increment();
            if (props.failOpen()) {
                log.warn("rate limiter unavailable, failing open for {}: {}", policy.name(), e.toString());
                chain.doFilter(request, response);
            } else {
                problem(response, HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter unavailable",
                        "try again shortly");
            }
            return;
        }

        meters.counter("ratelimit.decisions", "policy", policy.name(),
                "outcome", decision.allowed() ? "allowed" : "limited").increment();
        response.setHeader("RateLimit-Limit", Long.toString(decision.limit()));
        response.setHeader("RateLimit-Remaining", Long.toString(decision.remaining()));
        response.setHeader("RateLimit-Reset", Long.toString(ceilSeconds(decision.resetMs())));

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, ceilSeconds(decision.retryAfterMs()));
        response.setHeader("Retry-After", Long.toString(retryAfter));
        problem(response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "rate limit for '" + policy.name() + "' exceeded; retry in " + retryAfter + "s");
    }

    private CompiledPolicy match(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        for (CompiledPolicy p : policies) {
            if (p.pattern().matches(path)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Uses the configured client header when present, otherwise the socket address. We do not
     * read X-Forwarded-For here: behind a proxy, configure the server's forwarded-header
     * handling so getRemoteAddr() is already the real client, or anyone can spoof the header.
     */
    private String clientId(HttpServletRequest request) {
        String header = request.getHeader(props.clientHeader());
        if (header != null && !header.isBlank()) {
            return "k:" + header.strip();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static long ceilSeconds(long ms) {
        return Math.max(0, (ms + 999) / 1000);
    }

    private static void problem(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
