package com.wk.flashsale.event;

import com.wk.flashsale.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class EventIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void flyway_cree_le_concert_avec_500_places() {
        Event event = eventRepository.findById(1L).orElseThrow();

        assertThat(event.getName()).isEqualTo("Concert");
        assertThat(event.getTotalSeats()).isEqualTo(500);
        assertThat(event.getAvailableSeats()).isEqualTo(500);
    }

    @Test
    void get_event_renvoie_les_places_restantes() throws Exception {
        mockMvc.perform(get("/events/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Concert"))
                .andExpect(jsonPath("$.availableSeats").value(500));
    }

    @Test
    void get_event_inconnu_renvoie_404() throws Exception {
        mockMvc.perform(get("/events/999"))
                .andExpect(status().isNotFound());
    }
}
