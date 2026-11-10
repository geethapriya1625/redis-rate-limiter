package io.github.geethapriya1625.ratelimit;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Redis via Testcontainers, one container for the whole run.
 * Set TEST_REDIS_PORT to use a Redis already running on localhost instead.
 */
public abstract class RedisTestBase {

    private static final String EXTERNAL_PORT = System.getenv("TEST_REDIS_PORT");
    private static final GenericContainer<?> REDIS = startUnlessExternal();

    private static GenericContainer<?> startUnlessExternal() {
        if (EXTERNAL_PORT != null) {
            return null;
        }
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        if (REDIS != null) {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        } else {
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> EXTERNAL_PORT);
        }
    }
}
