package com.wk.flashsale.booking.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PessimisticLockBookingStrategyTest extends AbstractBookingConcurrencyTest {

    @Autowired
    private PessimisticLockBookingStrategy strategy;

    @Test
    void vend_exactement_toutes_les_places_sans_survente() throws Exception {
        assertSoldExactlyAllSeats(runConcurrentBookings(strategy));
    }
}
