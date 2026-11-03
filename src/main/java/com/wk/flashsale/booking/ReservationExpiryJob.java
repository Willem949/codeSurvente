package com.wk.flashsale.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tourne sur chaque instance : pas besoin d'élire un "chef", la requête SKIP LOCKED
 * empêche deux instances de rendre deux fois la même place.
 */
@Component
class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationService reservationService;

    ReservationExpiryJob(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelayString = "${flashsale.hold.expiry-check-interval:PT5S}")
    void releaseExpiredHolds() {
        int released = reservationService.releaseExpiredHolds();
        if (released > 0) {
            log.info("{} place(s) remise(s) en vente après expiration", released);
        }
    }
}
