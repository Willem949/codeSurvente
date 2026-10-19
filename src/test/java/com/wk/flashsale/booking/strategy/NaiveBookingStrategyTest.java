package com.wk.flashsale.booking.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volontairement ROUGE : ce test prouve que la version naïve survend.
 */
class NaiveBookingStrategyTest extends AbstractBookingConcurrencyTest {

    @Autowired
    private NaiveBookingStrategy strategy;

    @Test
    void ne_doit_pas_survendre() throws Exception {
        Outcome outcome = runConcurrentBookings(strategy);

        assertThat(outcome.reservationCount())
                .as("aucune survente : au plus %d réservations", SEATS)
                .isLessThanOrEqualTo(SEATS);
        // Conversion en long pour comparer des valeurs du même type que reservationCount
        assertThat((long) outcome.availableSeats())
                .as("le compteur de places reflète les réservations")
                .isEqualTo(SEATS - outcome.reservationCount());
        assertThat((long) outcome.successCount())
                .as("chaque SUCCESS correspond à une réservation en base")
                .isEqualTo(outcome.reservationCount());
    }
}
