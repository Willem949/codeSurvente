package com.wk.flashsale.booking.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckConstraintBookingStrategyTest extends AbstractBookingConcurrencyTest {

    @Autowired
    private CheckConstraintBookingStrategy strategy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void vend_exactement_toutes_les_places_sans_survente() throws Exception {
        assertSoldExactlyAllSeats(runConcurrentBookings(strategy));
    }

    @Test
    void la_base_refuse_un_stock_negatif_meme_sans_passer_par_le_code() {
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE event SET available_seats = -1 WHERE id = ?", EVENT_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
