package com.wk.flashsale.booking;

import com.wk.flashsale.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
// Sans elle, Spring Boot coupe l'export des métriques pendant les tests : /actuator/prometheus répondrait 404
@AutoConfigureMetrics
class ReservationApiTest extends PostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetConcert(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        resetConcert(jdbcTemplate);
    }

    private static MockHttpServletRequestBuilder hold(String userId, String key) {
        return post("/events/1/reservations")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}");
    }

    @Test
    void premier_appel_201_puis_rejeu_200_avec_la_meme_reservation() throws Exception {
        String body = mockMvc.perform(hold("alice", "k1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(hold("alice", "k1"))
                .andExpect(status().isOk())
                .andExpect(content().json(body));
    }

    @Test
    void sans_idempotency_key_la_requete_est_refusee() throws Exception {
        mockMvc.perform(post("/events/1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void complet_409_et_cle_reutilisee_422() throws Exception {
        jdbcTemplate.update("UPDATE event SET available_seats = 0 WHERE id = 1");
        mockMvc.perform(hold("alice", "k1")).andExpect(status().isConflict());

        jdbcTemplate.update("UPDATE event SET available_seats = 1 WHERE id = 1");
        mockMvc.perform(hold("alice", "k2")).andExpect(status().isCreated());
        mockMvc.perform(hold("bob", "k2")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void confirmation_204_puis_410_si_expiree() throws Exception {
        mockMvc.perform(hold("alice", "k1")).andExpect(status().isCreated());
        mockMvc.perform(hold("bob", "k2")).andExpect(status().isCreated());
        long aliceId = jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE user_id = 'alice'", Long.class);
        long bobId = jdbcTemplate.queryForObject("SELECT id FROM reservation WHERE user_id = 'bob'", Long.class);
        jdbcTemplate.update("UPDATE reservation SET expires_at = now() - interval '1 second' WHERE id = ?", bobId);

        mockMvc.perform(post("/reservations/{id}/confirm", aliceId)).andExpect(status().isNoContent());
        mockMvc.perform(post("/reservations/{id}/confirm", bobId)).andExpect(status().isGone());
        mockMvc.perform(post("/reservations/{id}/confirm", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void sans_redis_la_file_d_attente_n_existe_pas() throws Exception {
        mockMvc.perform(post("/events/1/queue")).andExpect(status().isNotFound());
    }

    @Test
    void les_metriques_sont_exposees_pour_prometheus() throws Exception {
        mockMvc.perform(hold("alice", "k1")).andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("flashsale_holds_total{outcome=\"CREATED\"")));
    }
}
