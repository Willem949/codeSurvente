package com.wk.flashsale;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trouvé par le tir Gatling du parcours complet : un Redis débordé faisait répondre 500,
 * et le client ne savait pas qu'il pouvait simplement réessayer.
 */
class ServiceUnavailableHandlerTest {

    @RestController
    static class FailingController {

        @GetMapping("/redis-timeout")
        String timeout() {
            throw new QueryTimeoutException("Redis command timed out");
        }

        @GetMapping("/redis-down")
        String down() {
            throw new RedisConnectionFailureException("Unable to connect to Redis");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ServiceUnavailableHandler())
            .build();

    @Test
    void un_redis_trop_lent_donne_503_avec_retry_after() throws Exception {
        mockMvc.perform(get("/redis-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void un_redis_injoignable_donne_503() throws Exception {
        mockMvc.perform(get("/redis-down")).andExpect(status().isServiceUnavailable());
    }
}
