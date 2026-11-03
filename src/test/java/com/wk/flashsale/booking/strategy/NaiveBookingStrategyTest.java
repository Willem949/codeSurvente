package com.wk.flashsale.booking.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preuve du bug, en VERT : le test réussit parce que la version naïve survend.
 * (Il était rouge à l'étape 1 ; inversé à l'étape 7 pour que la CI puisse passer
 * sans masquer la démonstration. S'il devient rouge, c'est que la survente n'est plus reproduite.)
 */
class NaiveBookingStrategyTest extends AbstractBookingConcurrencyTest {

    @Autowired
    private NaiveBookingStrategy strategy;

    @Test
    void la_version_naive_survend() throws Exception {
        Outcome outcome = runConcurrentBookings(strategy);

        assertThat(outcome.reservationCount())
                .as("check-then-act sans verrou : plus de réservations que de places")
                .isGreaterThan(SEATS);
        // Conversion en long pour comparer des valeurs du même type que reservationCount
        assertThat((long) outcome.availableSeats())
                .as("décrémentations perdues : le compteur ne reflète plus les réservations")
                .isNotEqualTo(SEATS - outcome.reservationCount());
    }
}
