package io.github.geethapriya1625.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Policies are checked in order; the first whose path pattern matches the request applies.
 * Put specific patterns (e.g. /api/search/**) before broad ones (/api/**).
 */
@Validated
@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("rl:") String keyPrefix,
        /** Allow traffic through if Redis is unreachable (true) or answer 503 (false). */
        @DefaultValue("true") boolean failOpen,
        /** Header identifying the client; falls back to the remote address when absent. */
        @DefaultValue("X-Api-Key") String clientHeader,
        @Valid List<Policy> policies) {

    public RateLimitProperties {
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    public record Policy(
            @NotBlank String name,
            @NotBlank String pathPattern,
            @Min(1) long capacity,
            @DecimalMin(value = "0.0", inclusive = false) double refillPerSecond,
            @DefaultValue("1") @Min(1) int cost) {}
}
