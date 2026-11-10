package io.github.geethapriya1625.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RateLimitFilterTest extends RedisTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void searchPolicyAllowsFiveThenReturns429WithHeaders() throws Exception {
        String client = "client-" + UUID.randomUUID();
        for (int i = 4; i >= 0; i--) {
            mvc.perform(get("/api/search").param("q", "x").header("X-Api-Key", client))
                    .andExpect(status().isOk())
                    .andExpect(header().string("RateLimit-Limit", "5"))
                    .andExpect(header().string("RateLimit-Remaining", String.valueOf(i)));
        }
        mvc.perform(get("/api/search").header("X-Api-Key", client))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(header().exists("RateLimit-Reset"))
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void eachClientHasItsOwnBucket() throws Exception {
        String a = "a-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/search").header("X-Api-Key", a));
        }
        mvc.perform(get("/api/search").header("X-Api-Key", a)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/search").header("X-Api-Key", "b-" + UUID.randomUUID())).andExpect(status().isOk());
    }

    @Test
    void firstMatchingPolicyWins() throws Exception {
        mvc.perform(get("/api/hello").header("X-Api-Key", "c-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit-Limit", "20"));
    }

    @Test
    void pathsWithoutAPolicyAreNotLimited() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().doesNotExist("RateLimit-Limit"));
    }
}
