package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.booking.Reservation;
import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.booking.ReservationStatus;
import com.wk.flashsale.event.Event;
import com.wk.flashsale.event.EventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verrou pessimiste : le même check-then-act que la version naïve, mais la lecture
 * pose un verrou (SELECT ... FOR UPDATE). Les transactions passent une par une sur la ligne,
 * et chacune voit le stock laissé par la précédente.
 */
@Component
public class PessimisticLockBookingStrategy implements BookingStrategy {

    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;

    public PessimisticLockBookingStrategy(EventRepository eventRepository, ReservationRepository reservationRepository) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
    }

    @Override
    @Transactional
    public BookingResult book(long eventId, String userId) {
        // Bloque ici tant qu'une autre transaction tient le verrou de la ligne
        Event event = eventRepository.findByIdForUpdate(eventId).orElseThrow();

        if (event.getAvailableSeats() > 0) {
            event.setAvailableSeats(event.getAvailableSeats() - 1);
            reservationRepository.save(new Reservation(eventId, userId, ReservationStatus.CONFIRMED));
            return BookingResult.SUCCESS;
        }
        return BookingResult.SOLD_OUT;
    }
}
