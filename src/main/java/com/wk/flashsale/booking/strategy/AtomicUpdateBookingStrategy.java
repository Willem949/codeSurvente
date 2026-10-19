package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.booking.Reservation;
import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.booking.ReservationStatus;
import com.wk.flashsale.event.EventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * UPDATE atomique conditionnel : PostgreSQL vérifie et décrémente le stock en une seule opération.
 * Les transactions concurrentes font la queue sur le verrou de la ligne, mais ce verrou
 * ne dure que le temps de la transaction (un UPDATE et un INSERT).
 */
@Component
public class AtomicUpdateBookingStrategy implements BookingStrategy {

    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;

    public AtomicUpdateBookingStrategy(EventRepository eventRepository, ReservationRepository reservationRepository) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
    }

    @Override
    @Transactional
    public BookingResult book(long eventId, String userId) {
        // 0 ligne modifiée = la condition "available_seats > 0" était fausse : plus de place
        if (eventRepository.decrementAvailableSeats(eventId) == 0) {
            return BookingResult.SOLD_OUT;
        }
        // Même transaction que la décrémentation : si l'INSERT échoue, la place est rendue (rollback)
        reservationRepository.save(new Reservation(eventId, userId, ReservationStatus.CONFIRMED));
        return BookingResult.SUCCESS;
    }
}
