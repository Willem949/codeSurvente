package com.wk.flashsale.booking.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicUpdateBookingStrategyTest extends AbstractBookingConcurrencyTest {

    @Autowired
    private AtomicUpdateBookingStrategy strategy;

    @Test
    void vend_exactement_toutes_les_places_sans_survente() throws Exception {
        Outcome outcome = runConcurrentBookings(strategy);

        // Plus fort que "au plus 500" : 1 000 clients pour 500 places, tout doit partir, ni plus ni moins
        assertThat(outcome.reservationCount())
                .as("exactement %d réservations : ni survente, ni sous-vente", SEATS)
                .isEqualTo(SEATS);
        assertThat(outcome.availableSeats())
                .as("plus aucune place restante")
                .isZero();
        assertThat(outcome.successCount())
                .as("autant de SUCCESS que de réservations en base")
                .isEqualTo(SEATS);
    }
}
