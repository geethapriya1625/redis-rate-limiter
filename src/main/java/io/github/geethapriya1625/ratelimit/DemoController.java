package io.github.geethapriya1625.ratelimit;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints to put the limiter in front of. */
@RestController
public class DemoController {

    @GetMapping("/api/hello")
    public Map<String, Object> hello() {
        return Map.of("message", "hello", "at", Instant.now().toString());
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q) {
        return Map.of("query", q, "results", 0);
    }
}
