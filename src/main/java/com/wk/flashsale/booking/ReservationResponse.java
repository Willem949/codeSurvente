package com.wk.flashsale.booking;

import java.time.Instant;

public record ReservationResponse(Long id, Long eventId, String userId, ReservationStatus status, Instant expiresAt) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getEventId(), reservation.getUserId(),
                reservation.getStatus(), reservation.getExpiresAt());
    }
}
