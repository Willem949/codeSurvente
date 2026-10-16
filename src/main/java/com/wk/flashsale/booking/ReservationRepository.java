package com.wk.flashsale.booking;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countByEventId(Long eventId);
}
