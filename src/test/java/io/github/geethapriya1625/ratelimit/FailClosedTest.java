package io.github.geethapriya1625.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Redis is down and fail-open is off: the API refuses rather than run unmetered. */
@SpringBootTest(properties = {
    "spring.data.redis.port=1",  // nothing listens here
    "spring.data.redis.connect-timeout=200ms",
    "ratelimit.fail-open=false"
})
@AutoConfigureMockMvc
class FailClosedTest {

    @Autowired
    MockMvc mvc;

    @Test
    void requestsAreRefusedWith503() throws Exception {
        mvc.perform(get("/api/hello"))
                .andExpect(status().isServiceUnavailable());
    }
}
